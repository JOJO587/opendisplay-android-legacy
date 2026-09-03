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
import android.view.WindowManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

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
    /** 通知栏「退出」动作广播的 action，由 ExitReceiver 接收后整体退出 */
    public static final String ACTION_EXIT = "org.opendisplay.legacy.ACTION_EXIT";

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

    /** 由 Activity 在 surface 就绪时注入（UI 线程写、网络线程读，须 volatile） */
    private static volatile H264Decoder decoder;
    private static volatile StatusCallback statusCb;

    public interface StatusCallback {
        void onStatus(String text);

        void onSurfaceNeeded();

        /**
         * 连接状态变化（显式回调，替代"解析状态文本"的脆弱做法）。
         * true = 新连接握手完成（hello 已发出）；false = 当前连接已断开。
         * 注意：旧连接被新连接顶掉时，旧 readLoop 退出不会触发 false（见 readLoop）。
         */
        void onConnectionChanged(boolean connected);

        /** 收到 cursor 消息：visible 是否可见，x/y 归一化 0..1（左上原点） */
        void onCursor(boolean visible, float x, float y);

        /** 收到 cursorImg 消息：pngBase64 光标位图，nw/nh 归一化宽高，ax/ay 归一化热点 */
        void onCursorImage(String pngBase64, float nw, float nh, float ax, float ay);
    }

    public static void attach(H264Decoder d, StatusCallback cb) {
        decoder = d;
        statusCb = cb;
        // 晚绑定同步：Activity 重建而服务已保持连接时（START_STICKY / 重开应用），
        // 不会有新的 adopt 事件，必须在这里把现有连接状态同步给新回调，
        // 否则 connected 停在 false，触摸又被吞——和本次修的根因同型
        Socket s = controlSocket;
        if (cb != null && s != null && !s.isClosed()) {
            cb.onConnectionChanged(true);
        }
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

        Socket old;
        synchronized (this) {
            // current 的所有读改都走 this 锁：readLoop finally 的清理与
            // 新连接的注册互斥，避免"检查 s==current 后被 adopt 抢先"
            // 的 check-then-act 竞态清掉新连接
            old = current;
            current = s;
            controlSocket = s;
        }
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
        // 显式连接回调：触摸/滚动转发依赖它，不能用状态文本反推
        if (statusCb != null) statusCb.onConnectionChanged(true);
    }

    /** hello 必须是连接后第一条消息（规范 6.1） */
    private void sendHello(OutputStream out) throws IOException {
        // 用真实物理分辨率（含系统栏区域）。getDisplayMetrics() 会扣掉导航栏，
        // 导致上报比面板少一截（如 1920 → 1836）。Mac 端 VirtualDisplay 的
        // 模式列表只有 hello 报的这一档，且每 2s 强制切回（selectHiDPIMode），
        // 所以"用户想要最高分辨率" = 这里必须报面板物理上限。
        DisplayMetrics dm = new DisplayMetrics();
        // Service 没有 Activity 那个 getWindowManager()，须走 getSystemService
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        // hello 语义：pixelsWide 是横屏长边（MacSender 注释），旋转无关，
        // 统一取长/短边归一化
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        float scale = dm.density;

        // 分辨率上限不在代码里写死：直接上报面板完整原生分辨率，
        // 由发送端（Mac 显示器设置）来决定实际推流分辨率——
        // 若推流撑不住，用户在 Mac 端手动调低即可（对齐参考实现
        // io.github.josepacelli，其 hello 不带任何 maxEncode* 字段）。
        String json = Protocol.hello(w, h, scale, "Android", stableId,
                null, null);
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
            // 只对"当前"连接负责清理与通知。若已被新连接顶掉（s != current），
            // 新连接的 adopt 会发 true，这里什么都不能发。
            // 与 adopt/stopListening 同锁，保证检查+清理原子。
            boolean wasCurrent;
            synchronized (this) {
                wasCurrent = (s == current);
                if (wasCurrent) {
                    // 必须关闭并清空：否则死 socket 的 isClosed() 仍为 false，
                    // Activity 重建时 attach() 的同步检查会误报"已连接"，
                    // UI 永久卡在 connected=true（触摸被吞）+ fd 泄漏
                    current = null;
                    controlSocket = null;
                }
            }
            if (wasCurrent) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                status("连接断开，等待重连…");
                updateNotification("等待 Mac 连接…");
                if (statusCb != null) statusCb.onConnectionChanged(false);
            }
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
        } else if ("cursor".equals(type)) {
            // 光标位置/可见性（规范 section 6：x/y/v 归一化 0..1，左上原点）。
            // v 是数字 1/0（对齐参考实现 josepacelli：obj.optInt("v",0)==1）。
            Double v = Protocol.jsonNumber(json, "v");
            Double x = Protocol.jsonNumber(json, "x");
            Double y = Protocol.jsonNumber(json, "y");
            boolean visible = v != null && v != 0.0;
            float fx = x == null ? 0f : x.floatValue();
            float fy = y == null ? 0f : y.floatValue();
            if (statusCb != null) statusCb.onCursor(visible, fx, fy);
        } else if ("cursorImg".equals(type)) {
            // 光标位图（规范 section 6：png 为 base64，nw/nh/ax/ay 归一化）
            String pngB64 = Protocol.jsonString(json, "png");
            if (pngB64 != null && statusCb != null) {
                Double nw = Protocol.jsonNumber(json, "nw");
                Double nh = Protocol.jsonNumber(json, "nh");
                Double ax = Protocol.jsonNumber(json, "ax");
                Double ay = Protocol.jsonNumber(json, "ay");
                statusCb.onCursorImage(pngB64,
                        nw == null ? 0.03f : nw.floatValue(),
                        nh == null ? 0.03f : nh.floatValue(),
                        ax == null ? 0.5f : ax.floatValue(),
                        ay == null ? 0.5f : ay.floatValue());
            }
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
        alive = false;
        // 与 adopt/readLoop finally 同锁：先清 current 再关 socket，
        // readLoop 的 finally 会看到 s != current，不会误发 false，
        // 改由本方法末尾显式通知一次
        Socket cur;
        synchronized (this) {
            cur = current;
            current = null;
        }
        controlSocket = null;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (cur != null) cur.close(); // 关 socket 才能解阻塞卡在 read() 的读线程
        } catch (IOException ignored) {
        }
        if (acceptThread != null) acceptThread.interrupt();
        if (readThread != null) readThread.interrupt();
        if (pingThread != null) pingThread.interrupt();
        if (statusCb != null) statusCb.onConnectionChanged(false);
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

        // 通知栏「退出」动作：发广播给 ExitReceiver，由它停止前台服务并关闭 Activity
        Intent exitIntent = new Intent(this, ExitReceiver.class);
        exitIntent.setAction(ACTION_EXIT);
        PendingIntent exitPi = PendingIntent.getBroadcast(this, 1, exitIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder nb = new Notification.Builder(this)
                .setContentTitle("OpenDisplay 接收端")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出", exitPi)
                .setOngoing(true);
        // O+ 必须绑定通知渠道，否则 startForeground 抛 RemoteServiceException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb.setChannelId(CHANNEL_ID);
        }
        return nb.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
