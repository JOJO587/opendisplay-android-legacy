package org.opendisplay.legacy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.UUID;

/**
 * 接收端主服务：监听 TCP 9000，接受 Mac 连入，跑协议，喂解码器。
 *
 * 规范要点落实：
 *  - 接收端是监听方，发送端主动连（section 1）—— 所以 USB 场景可以用
 *    adb reverse tcp:9000 tcp:9000 把这条路也走通
 *  - 连接建立后第一件事发 hello（section 6.1）
 *  - 每 2 秒发 ping，否则 5 秒静默会被判定链路死亡（section 8.2）
 *  - 新连入的连接直接顶掉旧连接（section 1）
 *  - 收到不认识的控制消息一律忽略（section 6）
 */
public class ReceiverService extends Service {

    private static final String TAG = "ODService";
    private static final int NOTIF_ID = 9001;
    private static final String CHANNEL_ID = "opendisplay_rx";

    public static final String ACTION_START = "org.opendisplay.legacy.START";
    public static final String ACTION_STOP = "org.opendisplay.legacy.STOP";

    /**
     * 是否开启 mDNS 广播（WiFi 发现）。
     *
     * ADB 模式下必须关掉：Mac 端的 Bonjour 浏览器若同时看到
     *   (a) 设备自己广播的服务 → 解析出设备真实 IP → 走 WiFi
     *   (b) Mac 本地代理注册的服务 → 指向 127.0.0.1 → 走 USB
     * 它可能挑 (a)，那 USB 链路就白建了。关掉设备端广播，
     * 让 Mac 只能看到 (b)，才能强制走 USB。
     */
    public static final String EXTRA_WIFI_DISCOVERY = "wifi_discovery";

    private ServerSocket serverSocket;
    private volatile Socket current;
    private Thread acceptThread, readThread, pingThread;
    private volatile boolean alive = false;

    /** 供 UI 层（触控转发）使用的当前连接句柄 */
    private static volatile Socket controlSocket;

    private NsdAdvertiser nsd;
    private PowerManager.WakeLock wakeLock;
    private String stableId;
    private volatile boolean wifiDiscovery = false; // 默认 ADB 优先

    /** 由 Activity 在 surface 就绪时注入 */
    private static H264Decoder decoder;
    private static StatusCallback statusCb;

    public interface StatusCallback {
        void onStatus(String text);

        void onSurfaceNeeded();
    }

    public static void attach(H264Decoder d, StatusCallback cb) {
        decoder = d;
        statusCb = cb;
    }

    public static void detach() {
        decoder = null;
        statusCb = null;
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        stableId = StableId.get(this);
        // 把稳定 id 打到 logcat —— Mac 端脚本靠这行拿到 id，
        // 用于本地 Bonjour 代理的 TXT（规范要求 TXT id 与 hello id 一致）。
        // 走 adb 是唯一能可靠拿到它的途径（SharedPreferences 无 root 读不到）。
        Log.i(TAG, "OD_ID=" + stableId);
        nsd = new NsdAdvertiser(this, "Android-" + stableId.substring(0, 4), stableId);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_WIFI_DISCOVERY)) {
            wifiDiscovery = intent.getBooleanExtra(EXTRA_WIFI_DISCOVERY, false);
        }
        startForeground(NOTIF_ID, buildNotification(
                wifiDiscovery ? "监听中（WiFi 发现已开）" : "监听中（ADB/USB 模式）"));
        acquireWakeLock();
        if (wifiDiscovery) {
            nsd.start(); // 仅 WiFi 模式才广播，ADB 模式下保持静默
        }
        startListening();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        stopListening();
        nsd.stop();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- 监听

    private void startListening() {
        if (alive) return;
        alive = true;
        acceptThread = new Thread(this::acceptLoop, "od-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        status(wifiDiscovery
                ? "监听 TCP " + Protocol.PORT + "（WiFi 发现已开）"
                : "监听 TCP " + Protocol.PORT + "（ADB/USB 模式，等待隧道连入）");
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(Protocol.PORT));
        } catch (IOException e) {
            Log.e(TAG, "bind failed", e);
            status("端口 " + Protocol.PORT + " 绑定失败: " + e.getMessage());
            alive = false;
            return;
        }

        while (alive) {
            try {
                Socket s = serverSocket.accept();
                Log.i(TAG, "incoming from " + s.getRemoteSocketAddress());
                adopt(s);
            } catch (IOException e) {
                if (alive) Log.w(TAG, "accept error", e);
                break;
            }
        }
    }

    /** 新连接顶掉旧连接（规范 section 1） */
    private void adopt(Socket s) {
        try {
            s.setTcpNoDelay(true); // 规范建议：输入事件是小包，Nagle 会造成输入延迟
        } catch (Exception ignored) {
        }

        Socket old = current;
        current = s;
        controlSocket = s;
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {
            }
        }

        try {
            sendHello(s.getOutputStream());
        } catch (IOException e) {
            Log.e(TAG, "hello failed", e);
        }

        startReadLoop(s);
        startPingLoop(s);
        status("已连接: " + s.getRemoteSocketAddress());
        updateNotification("已连接 " + s.getRemoteSocketAddress());
    }

    /** hello 必须是连接后第一条消息（规范 6.1） */
    private void sendHello(OutputStream out) throws IOException {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        float scale = dm.density;

        // 老设备解码能力有限：用 pv3 的 maxEncode* 字段声明上限，
        // 否则 Mac 会按面板分辨率推流，老平板根本解不动（规范 6.5）
        int capW = Math.min(w, 1920);
        int capH = Math.min(h, 1080);

        String json = Protocol.hello(w, h, scale, "Android",
                stableId, capW, capH);
        Log.i(TAG, "hello: " + json);
        out.write(Protocol.frameJson(json));
        out.flush();
    }

    // ---------------------------------------------------------------- 收帧

    private void startReadLoop(final Socket s) {
        if (readThread != null) readThread.interrupt();
        readThread = new Thread(() -> readLoop(s), "od-read");
        readThread.setDaemon(true);
        readThread.start();
    }

    private void readLoop(Socket s) {
        byte[] lenBuf = new byte[4];
        try {
            InputStream in = s.getInputStream();
            while (alive && !s.isClosed()) {
                if (!readFully(in, lenBuf, 4)) break;
                int len = Protocol.readLength(lenBuf, 0);
                if (len <= 0 || len >= (1 << 24)) {
                    Log.w(TAG, "bad frame length " + len);
                    break;
                }
                byte[] payload = new byte[len];
                if (!readFully(in, payload, len)) break;

                int kind = Protocol.classify(payload, len);
                if (kind == Protocol.TYPE_CONTROL) {
                    handleControl(new String(payload, "UTF-8"));
                } else {
                    handleVideo(payload, len);
                }
            }
        } catch (Exception e) {
            if (alive) Log.w(TAG, "read loop ended", e);
        } finally {
            Log.i(TAG, "connection closed");
            status("连接断开，等待重连…");
            updateNotification("等待 Mac 连接…");
        }
    }

    private void handleControl(String json) {
        String type = Protocol.jsonType(json);
        if (type == null) return; // 不可解析的消息按规范应忽略

        // 规范 section 6：未知 type 必须忽略，不能当错误处理
        if ("welcome".equals(type)) {
            Double pv = Protocol.jsonNumber(json, "pv");
            Double min = Protocol.jsonNumber(json, "min");
            Log.i(TAG, "welcome pv=" + pv + " min=" + min);
            status("Mac 已握手 (pv=" + (pv == null ? 1 : pv.intValue()) + ")");
        } else if ("updateRequired".equals(type)) {
            String msg = Protocol.jsonString(json, "message");
            status("需要更新: " + msg);
        } else if ("pong".equals(type)) {
            // 时钟同步（规范 8.1）——本实现不做延迟统计，忽略即可
        } else if ("cursor".equals(type) || "cursorImg".equals(type)) {
            // 光标叠加：可选功能，本实现不渲染
        }
        // 其余 type（ping / stats / 未来的新类型）一律忽略
    }

    private void handleVideo(byte[] payload, int len) {
        int sc = Protocol.findAnnexBStart(payload, len);
        if (sc < 0) return; // 没有起始码，丢弃
        if (decoder == null) return;

        // 跳过 telemetry JSON 前缀（规范 5.1），从起始码开始喂
        decoder.feed(payload, sc, len - sc);

        // 解码器还没起来而收到的又不是 IDR，就请求关键帧
        if (!decoder.isReady()) {
            requestKeyframe();
        }
    }

    /**
     * 供 UI 层调用：往当前连接发一条控制消息（触控 / 滚动 / 关键帧请求）。
     * 在网络线程执行，不阻塞 UI。
     */
    public static void sendControl(final String json) {
        new Thread(() -> {
            Socket s = controlSocket;
            if (s == null || s.isClosed()) return;
            try {
                OutputStream out = s.getOutputStream();
                synchronized (out) {
                    out.write(Protocol.frameJson(json));
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }, "od-send").start();
    }

    /** 每 2 秒 ping（规范 8.2：超过 5 秒静默双端判定链路死亡） */
    private void startPingLoop(final Socket s) {
        if (pingThread != null) pingThread.interrupt();
        pingThread = new Thread(() -> {
            while (alive && !s.isClosed()) {
                try {
                    OutputStream out = s.getOutputStream();
                    out.write(Protocol.frameJson(
                            Protocol.ping(System.currentTimeMillis())));
                    out.flush();
                } catch (Exception e) {
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "od-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    private void requestKeyframe() {
        try {
            Socket s = current;
            if (s != null && !s.isClosed()) {
                s.getOutputStream().write(Protocol.frameJson(
                        Protocol.keyframeRequest()));
                s.getOutputStream().flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int len)
            throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }

    // ---------------------------------------------------------------- 杂项

    private void stopListening() {
        controlSocket = null;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (current != null) current.close();
        } catch (IOException ignored) {
        }
        if (acceptThread != null) acceptThread.interrupt();
        if (readThread != null) readThread.interrupt();
        if (pingThread != null) pingThread.interrupt();
    }

    private void status(String text) {
        Log.i(TAG, text);
        if (statusCb != null) statusCb.onStatus(text);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opendisplay:rx");
            wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "wakelock failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "OpenDisplay 接收端",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        return new Notification.Builder(this)
                .setContentTitle("OpenDisplay 接收端")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
