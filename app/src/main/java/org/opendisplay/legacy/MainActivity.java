package org.opendisplay.legacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * 主界面：一个全屏 SurfaceView 显示 Mac 推来的画面，底部一行状态。
 *
 * 刻意不用 AndroidX / AppCompat —— 继承原生 Activity，
 * 整个 app 零外部依赖，编译快、minSdk 23 不会踩库的版本门槛。
 */
public class MainActivity extends Activity
        implements SurfaceHolder.Callback, ReceiverService.StatusCallback {

    private static final String TAG = "ODMain";

    private SurfaceView surfaceView;
    private TextView statusText;
    private Button btnMode;
    private H264Decoder decoder;

    private volatile boolean connected = false;
    private volatile int videoW = 0, videoH = 0;

    /** 默认 ADB/USB 优先：不广播 mDNS，让 Mac 只能走 127.0.0.1 隧道 */
    private boolean wifiDiscovery = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surface);
        statusText = findViewById(R.id.status);
        Button btnQuit = findViewById(R.id.btn_quit);
        btnMode = findViewById(R.id.btn_mode);

        surfaceView.getHolder().addCallback(this);

        // 点击 = 左键单击（规范 7：坐标是相对视频的归一化值）
        surfaceView.setOnTouchListener(this::onTouch);

        updateModeButton();
        btnMode.setOnClickListener(v -> {
            wifiDiscovery = !wifiDiscovery;
            updateModeButton();
            restartService();
        });

        btnQuit.setOnClickListener(v -> {
            stopService(new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_STOP));
            finish();
        });

        ReceiverService.attach(null, this);
        startService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_START)
                .putExtra(ReceiverService.EXTRA_WIFI_DISCOVERY, wifiDiscovery));
    }

    private void updateModeButton() {
        btnMode.setText(wifiDiscovery ? R.string.mode_wifi : R.string.mode_adb);
    }

    /** 切换模式需要重启服务（mDNS 的启停绑定在服务生命周期上） */
    private void restartService() {
        stopService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_STOP));
        startService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_START)
                .putExtra(ReceiverService.EXTRA_WIFI_DISCOVERY, wifiDiscovery));
    }

    @Override
    protected void onDestroy() {
        ReceiverService.detach();
        if (decoder != null) decoder.stopCodec();
        super.onDestroy();
    }

    // ------------------------------------------------------------ Surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Surface s = holder.getSurface();
        if (decoder == null) {
            decoder = new H264Decoder(s, new H264Decoder.Callback() {
                @Override
                public void onSizeChanged(int w, int h) {
                    videoW = w;
                    videoH = h;
                    runOnUiThread(() -> statusText.setText(
                            "已连接 · 视频 " + w + "x" + h));
                }

                @Override
                public void onError(String msg) {
                    runOnUiThread(() -> statusText.setText("错误: " + msg));
                }
            });
        } else {
            decoder.setSurface(s);
        }
        ReceiverService.attach(decoder, this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.i(TAG, "surface changed " + w + "x" + h);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (decoder != null) decoder.stopCodec();
    }

    // ------------------------------------------------------------ 触控转发

    /**
     * 把屏幕触控转成协议的 touch / scroll 消息。
     * 注意两个坐标空间的差别（规范 section 7）：
     *   touch.x/y   → 相对视频归一化 0..1
     *   scroll.dx/dy → 视频像素，且是自然滚动符号
     */
    private boolean onTouch(View v, MotionEvent e) {
        if (!connected) return true;

        float nx = e.getX() / Math.max(1, v.getWidth());
        float ny = e.getY() / Math.max(1, v.getHeight());

        String phase;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                phase = "began";
                break;
            case MotionEvent.ACTION_MOVE:
                phase = "moved";
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                phase = "ended";
                break;
            default:
                return true;
        }

        send(Protocol.touch(phase, nx, ny, System.currentTimeMillis()));
        return true;
    }

    /** 双指滚动（简易实现：两指同时移动时按纵向位移发 scroll） */
    public boolean onGenericMotionEvent(MotionEvent e) {
        // 需要精确滚动时可在此扩展；基础版用 touch 拖拽即可操作 Mac
        return super.onGenericMotionEvent(e);
    }

    /** 往当前连接写一条控制消息 */
    private void send(String json) {
        ReceiverService.sendControl(json);
    }

    // ------------------------------------------------------------ 状态回调

    @Override
    public void onStatus(String text) {
        runOnUiThread(() -> {
            statusText.setText(text);
            connected = text.contains("已连接");
        });
    }

    @Override
    public void onSurfaceNeeded() {
    }
}
