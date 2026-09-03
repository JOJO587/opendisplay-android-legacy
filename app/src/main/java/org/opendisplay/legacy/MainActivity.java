package org.opendisplay.legacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 主界面：全屏 SurfaceView 显示 Mac 推来的画面 + 鼠标光标叠加层。
 *
 * 刻意不用 AndroidX / AppCompat —— 继承原生 Activity，零外部依赖。
 *
 * 交互约定（触控手势与操控 Mac 不冲突）：
 *  - 单指按下/移动/抬起  → 左键单击 / 拖拽（规范 section 7）
 *  - 双指拖动            → 滚动（规范 section 7，自然滚动符号）
 *  - 三指轻点（<400ms 不移动）→ 唤出隐藏菜单（连接状态 + 退出 + 关闭）
 *  - 系统栏通过沉浸模式隐藏，边缘上滑可临时呼出
 */
public class MainActivity extends Activity
        implements SurfaceHolder.Callback, ReceiverService.StatusCallback {

    private static final String TAG = "ODMain";
    private static final long THREE_FINGER_MAX_MS = 400;

    private SurfaceView surfaceView;
    private CursorOverlay cursorOverlay;
    private LinearLayout menu;
    private TextView menuStatus;
    private H264Decoder decoder;

    private volatile boolean connected = false;
    private String lastStatus = "";
    private volatile int videoW = 0, videoH = 0;

    /** 默认 ADB/USB 优先：不广播 mDNS，让 Mac 只能走 127.0.0.1 隧道 */
    private boolean wifiDiscovery = false;

    // 三指手势状态
    private long threeDownTime = 0;
    private boolean threeMoved = false;

    // 双指滚动基准
    private boolean twoFingerActive = false;
    private float lastScrollX = 0, lastScrollY = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 通知栏「退出」会带 finish=true 唤起本 Activity，直接关掉即可
        if (getIntent() != null && getIntent().getBooleanExtra("finish", false)) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        surfaceView = findViewById(R.id.surface);
        cursorOverlay = findViewById(R.id.cursor_overlay);
        menu = findViewById(R.id.menu);
        menuStatus = findViewById(R.id.menu_status);

        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(this::onTouch);

        Button btnExit = findViewById(R.id.btn_exit);
        Button btnClose = findViewById(R.id.btn_close_menu);
        btnExit.setOnClickListener(v -> doExit());
        btnClose.setOnClickListener(v -> hideMenu());

        ReceiverService.attach(null, this);
        startService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_START)
                .putExtra(ReceiverService.EXTRA_WIFI_DISCOVERY, wifiDiscovery));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("finish", false)) {
            finish();
        }
    }

    // ------------------------------------------------------------ T2 全屏沉浸

    private void hideSystemUI() {
        int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    // ------------------------------------------------------------ 退出 / 菜单

    private void doExit() {
        hideMenu();
        stopService(new Intent(this, ReceiverService.class)
                .setAction(ReceiverService.ACTION_STOP));
        finish();
    }

    private void showMenu() {
        runOnUiThread(() -> {
            menuStatus.setText(lastStatus);
            menu.setVisibility(View.VISIBLE);
        });
    }

    private void hideMenu() {
        menu.setVisibility(View.GONE);
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
                }

                @Override
                public void onError(String msg) {
                    // 解码错误不打扰用户，仅记录
                    Log.w(TAG, "decoder error: " + msg);
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

    @Override
    protected void onDestroy() {
        ReceiverService.detach();
        if (decoder != null) decoder.stopCodec();
        super.onDestroy();
    }

    // ------------------------------------------------------------ T3/T4 触控手势

    private boolean onTouch(View v, MotionEvent e) {
        int pc = e.getPointerCount();

        // 三指及以上：归菜单手势，完全不转发给 Mac
        if (pc >= 3) {
            handleThreeFinger(e);
            return true;
        }

        if (!connected) return true;

        if (pc == 2) {
            return handleTwoFinger(e);
        }

        // 单指：点击 / 拖拽当作左键（规范 section 7，坐标归一化 0..1）
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

    /** T3：三指轻点（短暂按下、几乎不移动）弹菜单 */
    private void handleThreeFinger(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 3) {
                    threeDownTime = System.currentTimeMillis();
                    threeMoved = false;
                    twoFingerActive = false; // 防止从双指加指造成滚动基准残留
                }
                break;
            case MotionEvent.ACTION_MOVE:
                // 三指期间任何移动都视为拖拽而非轻点
                threeMoved = true;
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (threeDownTime > 0) {
                    long dt = System.currentTimeMillis() - threeDownTime;
                    if (dt < THREE_FINGER_MAX_MS && !threeMoved) {
                        showMenu();
                    }
                    threeDownTime = 0;
                }
                break;
        }
    }

    /** T4：双指拖动 → 滚动。dx/dy 转成视频像素，自然滚动符号 */
    private boolean handleTwoFinger(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                twoFingerActive = true;
                lastScrollX = e.getX(0);
                lastScrollY = e.getY(0);
                break;
            case MotionEvent.ACTION_MOVE:
                if (twoFingerActive && videoW > 0 && videoH > 0) {
                    float cx = e.getX(0);
                    float cy = e.getY(0);
                    float dx = cx - lastScrollX;
                    float dy = cy - lastScrollY;
                    lastScrollX = cx;
                    lastScrollY = cy;
                    if (dx != 0 || dy != 0) {
                        float scaleX = videoW / Math.max(1f, surfaceView.getWidth());
                        float scaleY = videoH / Math.max(1f, surfaceView.getHeight());
                        send(Protocol.scroll(dx * scaleX, dy * scaleY));
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                twoFingerActive = false;
                break;
        }
        return true;
    }

    /** 往当前连接写一条控制消息 */
    private void send(String json) {
        ReceiverService.sendControl(json);
    }

    // ------------------------------------------------------------ 状态回调

    @Override
    public void onStatus(String text) {
        lastStatus = text;
        connected = text.contains("已连接");
        if (!connected) {
            runOnUiThread(() -> cursorOverlay.hideCursor());
        }
    }

    @Override
    public void onSurfaceNeeded() {
    }

    // ------------------------------------------------------------ T1 光标回调

    @Override
    public void onCursor(boolean visible, float x, float y) {
        runOnUiThread(() -> cursorOverlay.setCursor(visible, x, y));
    }

    @Override
    public void onCursorImage(String pngBase64, float nw, float nh, float ax, float ay) {
        // setCursorImage 内部已在后台线程解码，无需在此切 UI 线程
        cursorOverlay.setCursorImage(pngBase64, nw, nh, ax, ay);
    }
}
