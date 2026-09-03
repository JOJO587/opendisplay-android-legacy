package org.opendisplay.legacy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.Log;
import android.view.View;

/**
 * 鼠标光标叠加层。
 *
 * 盖在解码画面（SurfaceView）之上，自己不接收触摸事件（setClickable(false)），
 * 所以单指/双指/三指手势都能继续穿透到 SurfaceView，由 MainActivity 处理。
 *
 * 线格式（对齐官方 Mac 发送端，参考 josepacelli 适配版）：
 *  - cursor 消息：v(1=可见) / x / y，归一化 0..1，左上原点
 *  - cursorImg 消息：png(base64 PNG) / nw / nh(归一化宽高) / ax / ay(归一化热点)
 * 渲染定位（与参考实现一致）：
 *   spriteW = nw * boxW，originX = x*boxW - ax*spriteW
 * 没收到图之前用白点占位；PNG 解码做 MAX_DIM 边界检查防解压炸弹。
 */
public class CursorOverlay extends View {

    private static final String TAG = "ODCursor";
    private static final int MAX_DIM = 1024; // 光标图单维上限（防解压炸弹）

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap = null;
    private boolean hasImage = false;

    private boolean visible = false;
    private float x = 0f, y = 0f;            // 归一化 0..1
    private float nw = 0.03f, nh = 0.03f;    // 归一化宽高
    private float ax = 0.5f, ay = 0.5f;      // 归一化热点

    public CursorOverlay(Context context) {
        super(context);
        init();
    }

    public CursorOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        // 关键：不拦截触摸，事件继续往下传给 SurfaceView
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    /** 接收端收到 cursor 消息：更新位置/可见性 */
    public synchronized void setCursor(boolean visible, float x, float y) {
        this.visible = visible;
        this.x = x;
        this.y = y;
        invalidate();
    }

    /** 接收端收到 cursorImg 消息：后台线程解码并缓存光标位图（避免阻塞 UI） */
    public void setCursorImage(String pngBase64,
                               float nw, float nh, float ax, float ay) {
        new Thread(() -> {
            try {
                byte[] raw = Base64.decode(pngBase64, Base64.DEFAULT);
                if (raw == null || raw.length == 0) return;

                // 先只解析边界，做尺寸闸门
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                int w = opts.outWidth, h = opts.outHeight;
                if (w <= 0 || h <= 0 || w > MAX_DIM || h > MAX_DIM) {
                    Log.w(TAG, "reject cursor image: dim " + w + "x" + h);
                    return;
                }

                opts.inJustDecodeBounds = false;
                Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                if (bmp == null) return;

                Bitmap old;
                synchronized (CursorOverlay.this) {
                    old = bitmap;
                    bitmap = bmp;
                    hasImage = true;
                    this.nw = nw;
                    this.nh = nh;
                    this.ax = ax;
                    this.ay = ay;
                }
                if (old != null) old.recycle();
                postInvalidate(); // 后台线程用 postInvalidate 请求重绘
            } catch (Exception e) {
                Log.w(TAG, "decode cursor image failed", e);
            }
        }, "od-cursor-decode").start();
    }

    /** 链路断开或 v=0 时调用，隐藏光标 */
    public synchronized void hideCursor() {
        visible = false;
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible) return;

        float boxW = getWidth();
        float boxH = getHeight();
        if (boxW <= 0 || boxH <= 0) return;

        float cursorPxX = x * boxW;
        float cursorPxY = y * boxH;

        if (hasImage && bitmap != null) {
            float spriteW = nw * boxW;
            float spriteH = nh * boxH;
            float originX = cursorPxX - ax * spriteW;
            float originY = cursorPxY - ay * spriteH;
            canvas.drawBitmap(bitmap, null,
                    new RectF(originX, originY, originX + spriteW, originY + spriteH), paint);
        } else {
            // 还没收到图：画一个白点占位，至少让光标可见
            float r = Math.max(4f, boxW * 0.006f);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cursorPxX, cursorPxY, r, paint);
        }
    }
}
