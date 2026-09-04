package org.opendisplay.legacy;

// Copyright (c) 2026 JOJO587
// SPDX-License-Identifier: MIT

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * H.264 解码器 —— MediaCodec 同步模式 + Surface 输出。
 *
 * 设计取舍（都是为了让它在 Android 6.0 / API 23 上跑得动）：
 *  - 用同步 API（dequeueInput/OutputBuffer）而非异步 setCallback，
 *    异步回调要 API 21+，同步模式全版本行为一致、调试也简单。
 *  - 不碰 KEY_LOW_LATENCY（API 30+ 才有），老设备没有这个参数。
 *  - 参数集从视频流里自己提取（规范 5.1：每个 IDR 前必带 SPS/PPS），
 *    不依赖任何外部带外信令。
 *  - 分辨率变化时重建解码器（规范 5.2 要求）。
 */
public class H264Decoder {

    private static final String TAG = "ODDecoder";
    private static final String MIME = "video/avc";
    private static final long TIMEOUT_US = 100_000L; // 100ms

    private MediaCodec codec;
    private Surface surface;
    private byte[] lastSps, lastPps;
    private int width, height;
    private volatile boolean running;
    private Thread drainThread;
    private long ptsUs = 0;

    /** 状态回调给 UI 层 */
    public interface Callback {
        void onSizeChanged(int w, int h);

        void onError(String msg);
    }

    private final Callback cb;

    public H264Decoder(Surface surface, Callback cb) {
        this.surface = surface;
        this.cb = cb;
    }

    /** 设置/更换输出 Surface（SurfaceView 重建时会回调） */
    public synchronized void setSurface(Surface s) {
        this.surface = s;
    }

    /**
     * 喂入一个完整的 access unit（一帧）。
     * 帧内容是从第一个 00 00 00 01 开始的 Annex B 数据（已跳过 telemetry 前缀）。
     *
     * @param data   整帧缓冲区
     * @param offset 起始码位置
     * @param len    从 offset 起的有效长度
     */
    public synchronized void feed(byte[] data, int offset, int len) {
        if (!running) {
            // 还没起解码器：必须等第一个带 SPS/PPS 的 IDR
            byte[][] ps = Protocol.extractParameterSets(data, offset, len);
            if (ps[0] == null || ps[1] == null) {
                return; // 没有参数集，丢弃直到收到 IDR
            }
            lastSps = ps[0];
            lastPps = ps[1];
            startCodec();
        }

        if (codec == null) return;

        // 检测参数集变化（发送端改分辨率时会带新的 SPS/PPS）
        byte[][] ps = Protocol.extractParameterSets(data, offset, len);
        if (ps[0] != null && !java.util.Arrays.equals(ps[0], lastSps)) {
            Log.i(TAG, "SPS changed -> rebuilding decoder");
            lastSps = ps[0];
            if (ps[1] != null) lastPps = ps[1];
            stopCodec();
            startCodec();
            if (codec == null) return;
        }

        try {
            int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
            if (inIndex < 0) {
                return; // 解码器忙，丢帧（低延迟优先于不丢帧）
            }
            ByteBuffer in = codec.getInputBuffer(inIndex);
            if (in == null) return;
            in.clear();
            in.put(data, offset, len);
            // 协议里没有 PTS（规范 5.1 明确说明），按到达节奏造一个单调递增的
            ptsUs += 33_333L; // 约 30fps
            codec.queueInputBuffer(inIndex, 0, len, ptsUs, 0);
        } catch (IllegalStateException e) {
            Log.w(TAG, "queueInput failed", e);
            stopCodec();
        }
    }

    private void startCodec() {
        if (lastSps == null || lastPps == null) return;
        try {
            codec = MediaCodec.createDecoderByType(MIME);
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, 0, 0);
            // 宽高不知道没关系，H.264 的 SPS 里带了，解码器会自己解析
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(lastSps));
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(lastPps));
            codec.configure(fmt, surface, null, 0);
            codec.start();
            running = true;
            startDrain();
            Log.i(TAG, "decoder started");
        } catch (Exception e) {
            Log.e(TAG, "startCodec failed", e);
            if (cb != null) cb.onError("解码器启动失败: " + e.getMessage());
            stopCodec();
        }
    }

    /** 输出泵：把解码后的帧渲染到 Surface */
    private void startDrain() {
        drainThread = new Thread(() -> {
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                MediaCodec c;
                synchronized (H264Decoder.this) {
                    c = codec;
                }
                if (c == null) break;
                try {
                    int out = c.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (out >= 0) {
                        // true = 渲染到 surface
                        c.releaseOutputBuffer(out, true);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break;
                        }
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = c.getOutputFormat();
                        width = f.getInteger(MediaFormat.KEY_WIDTH);
                        height = f.getInteger(MediaFormat.KEY_HEIGHT);
                        Log.i(TAG, "format -> " + width + "x" + height);
                        if (cb != null) cb.onSizeChanged(width, height);
                    }
                } catch (IllegalStateException e) {
                    if (running) Log.w(TAG, "drain error", e);
                    break;
                }
            }
        }, "od-decoder-drain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    public synchronized void stopCodec() {
        running = false;
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
        if (drainThread != null) {
            drainThread.interrupt();
            drainThread = null;
        }
    }

    /** 解码器是否还没收到过 IDR（上层据此决定是否发 kf） */
    public synchronized boolean isReady() {
        return running && codec != null;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
