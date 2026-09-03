package org.opendisplay.legacy;

/**
 * OpenDisplay Wire Protocol (pv 3) — 纯 Java 实现，零外部依赖。
 *
 * 本文件刻意不 import 任何 android.* 类，也不依赖 org.json，
 * 因此可以直接用 javac 编译并在桌面 JVM 上做单元测试。
 *
 * 规范要点（摘自上游 PROTOCOL.md）：
 *  - 接收端监听 TCP 9000，发送端主动连入（section 1）
 *  - 每个消息：[4 字节大端长度][payload]，长度只算 payload（section 3）
 *  - 发送端→接收端的帧可能是视频或 JSON 控制消息，靠启发式区分（section 4）：
 *      长度 < 32768 且 首字节=='{' 且 payload 内不含 0x00  → JSON 控制消息
 *      否则                                                → H.264 Annex B 视频帧
 *  - 视频为 H.264 Annex B，4 字节起始码 00 00 00 01，
 *    首帧前可能有一段 JSON telemetry 前缀（{"cap":..,"snd":..}），需跳过（section 5.1）
 *  - 连接建立后接收端必须第一个发 hello（section 6.1）
 *  - 静默超过 5s 双端判定链路死亡，因此 ping 每 2s 一次（section 8.2）
 */
public final class Protocol {

    public static final int PORT = 9000;
    public static final String SERVICE_TYPE = "_opensidecar._tcp";
    /**
     * 协议版本。对齐真实 Mac 发送端（peetzweg/OpenDisplay，上游 Shared/Protocol.swift）：
     * 参考实现 io.github.josepacelli 的 WireProtocol.VERSION = 2。
     * 之前误写成 3，Mac 见到不认识的更高版本会关掉「本地光标叠加」等可选特性，
     * 导致 pad 上收不到 cursor / cursorImg 消息（视频与触控是核心功能，照常工作）。
     */
    public static final int PV = 2;

    /** section 4：判定为 JSON 控制消息的长度上限 */
    private static final int JSON_MAX = 32768;
    private static final byte BRACE = 0x7B; // '{'
    private static final byte NUL = 0x00;

    /** 帧类型 */
    public static final int TYPE_VIDEO = 0;
    public static final int TYPE_CONTROL = 1;

    private Protocol() {
    }

    // ------------------------------------------------------------------
    // 分帧（framing）
    // ------------------------------------------------------------------

    /** 把 payload 封装成一个 wire frame：[4 字节大端长度][payload] */
    public static byte[] frame(byte[] payload) {
        byte[] out = new byte[4 + payload.length];
        int n = payload.length;
        out[0] = (byte) ((n >>> 24) & 0xFF);
        out[1] = (byte) ((n >>> 16) & 0xFF);
        out[2] = (byte) ((n >>> 8) & 0xFF);
        out[3] = (byte) (n & 0xFF);
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    /** 把 UTF-8 JSON 字符串封装成 wire frame */
    public static byte[] frameJson(String json) {
        return frame(utf8(json));
    }

    /** 从 4 字节大端字节数组解析长度（buf 从 off 开始） */
    public static int readLength(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24)
                | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8)
                | (buf[off + 3] & 0xFF);
    }

    // ------------------------------------------------------------------
    // section 4：channel demux
    // ------------------------------------------------------------------

    /**
     * 判定发送端→接收端的帧是 JSON 控制消息还是 H.264 视频帧。
     * 三项同时成立才是控制消息：长度 < 32768、首字节 '{'、payload 不含 NUL。
     */
    public static int classify(byte[] payload, int len) {
        if (len >= JSON_MAX) return TYPE_VIDEO;
        if (len <= 0) return TYPE_VIDEO;
        if (payload[0] != BRACE) return TYPE_VIDEO;
        for (int i = 0; i < len; i++) {
            if (payload[i] == NUL) return TYPE_VIDEO;
        }
        return TYPE_CONTROL;
    }

    // ------------------------------------------------------------------
    // section 5.1：视频帧 — 跳过 telemetry 前缀，定位第一个起始码
    // ------------------------------------------------------------------

    /**
     * 返回 payload 中第一个 Annex B 起始码 00 00 00 01 的位置；
     * 找不到返回 -1。telemetry 前缀（若有）就是 [0, offset) 这段 JSON。
     */
    public static int findAnnexBStart(byte[] p, int len) {
        for (int i = 0; i + 3 < len; i++) {
            if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    /** NALU 类型：起始码后第 1 个字节的低 5 位 */
    public static int naluType(byte[] p, int startCodePos) {
        int i = startCodePos + 4;
        if (i >= p.length) return -1;
        return p[i] & 0x1F;
    }

    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;
    public static final int NAL_IDR = 5;
    public static final int NAL_SLICE = 1;

    /**
     * 从一帧 Annex B 数据里提取 SPS / PPS（用于 MediaCodec 的 csd-0 / csd-1）。
     * 返回一个长度为 2 的数组：[SPS(含起始码), PPS(含起始码)]，缺失项为 null。
     */
    public static byte[][] extractParameterSets(byte[] p, int offset, int len) {
        byte[] sps = null, pps = null;
        int i = offset;
        int end = offset + len;
        while (i < end) {
            int sc = -1;
            for (int j = i; j + 3 < end; j++) {
                if (p[j] == 0 && p[j + 1] == 0 && p[j + 2] == 0 && p[j + 3] == 1) {
                    sc = j;
                    break;
                }
            }
            if (sc < 0) break;
            // 找下一个起始码，确定本 NALU 边界
            int next = -1;
            for (int j = sc + 4; j + 3 < end; j++) {
                if (p[j] == 0 && p[j + 1] == 0 && p[j + 2] == 0 && p[j + 3] == 1) {
                    next = j;
                    break;
                }
            }
            int naluEnd = (next < 0) ? end : next;
            int type = p[sc + 4] & 0x1F;
            int naluLen = naluEnd - sc;
            if (type == NAL_SPS && sps == null) {
                sps = new byte[naluLen];
                System.arraycopy(p, sc, sps, 0, naluLen);
            } else if (type == NAL_PPS && pps == null) {
                pps = new byte[naluLen];
                System.arraycopy(p, sc, pps, 0, naluLen);
            }
            i = naluEnd;
        }
        return new byte[][]{sps, pps};
    }

    // ------------------------------------------------------------------
    // 接收端 → 发送端 的控制消息（只需构造，格式固定，故手写 JSON）
    // ------------------------------------------------------------------

    /**
     * hello：连接后必须第一个发送，发送端据此创建虚拟显示器（section 6.1）。
     * maxEncodeWide/High 是 pv3 的可选 additive 字段，仅当接收端确有比面板
     * 分辨率更低的硬性解码上限时才填；否则传 null 让发送端按面板分辨率推流，
     * 由用户在发送端（Mac 显示器设置）自行选分辨率（对齐参考实现 josepacelli，
     * 其 hello 不带 maxEncode* 字段）。
     */
    public static String hello(int pixelsWide, int pixelsHigh, float scale,
                               String device, String id,
                               Integer maxEncodeWide, Integer maxEncodeHigh) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"hello\"");
        sb.append(",\"pixelsWide\":").append(pixelsWide);
        sb.append(",\"pixelsHigh\":").append(pixelsHigh);
        sb.append(",\"scale\":").append(scale);
        sb.append(",\"device\":\"").append(device).append('"');
        if (id != null) sb.append(",\"id\":\"").append(id).append('"');
        sb.append(",\"pv\":").append(PV);
        if (maxEncodeWide != null) sb.append(",\"maxEncodeWide\":").append(maxEncodeWide);
        if (maxEncodeHigh != null) sb.append(",\"maxEncodeHigh\":").append(maxEncodeHigh);
        sb.append('}');
        return sb.toString();
    }

    /** ping：每 2 秒一次，t 为接收端毫秒时间戳（section 6.1 / 8.2） */
    public static String ping(long tMillis) {
        return "{\"type\":\"ping\",\"t\":" + tMillis + "}";
    }

    /** kf：解码丢失时请求 IDR（section 5.3） */
    public static String keyframeRequest() {
        return "{\"type\":\"kf\"}";
    }

    /**
     * touch：坐标是相对视频的归一化值 0..1（section 7）。
     * phase ∈ began / moved / ended / cancelled
     */
    public static String touch(String phase, float x, float y, Long tMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"touch\",\"phase\":\"").append(phase).append('"');
        sb.append(",\"x\":").append(clamp01(x));
        sb.append(",\"y\":").append(clamp01(y));
        if (tMillis != null) sb.append(",\"t\":").append(tMillis);
        sb.append('}');
        return sb.toString();
    }

    /**
     * scroll：单位是视频像素（不是归一化！），自然滚动符号（section 7）。
     */
    public static String scroll(float dx, float dy) {
        return "{\"type\":\"scroll\",\"dx\":" + dx + ",\"dy\":" + dy + "}";
    }

    /** stats：自由格式遥测，发送端只记录不解析（section 6.1） */
    public static String stats(String transport, int fps, double mbps, long rtt) {
        return "{\"type\":\"stats\",\"transport\":\"" + transport + "\""
                + ",\"fps\":" + fps
                + ",\"mbps\":" + mbps
                + ",\"rtt\":" + rtt + "}";
    }

    /** closing：App 退出，会话彻底结束（section 6.1） */
    public static String closing() {
        return "{\"type\":\"closing\"}";
    }

    // ------------------------------------------------------------------
    // 极简 JSON 读取：只需要从发送端消息里取 type 和几个字段，
    // 不引入 org.json，保证本类零依赖、可桌面单测。
    // ------------------------------------------------------------------

    /** 取出顶层 "type" 字段；解析失败返回 null（规范：不可解析的消息应忽略） */
    public static String jsonType(String json) {
        return jsonString(json, "type");
    }

    /** 取出顶层字符串字段的【值】（非字段名）。该字段不是字符串值（数字/布尔/缺省）时返回 null。 */
    public static String jsonString(String json, String key) {
        int i = indexOfKey(json, key);
        if (i < 0) return null;
        // key 形如 "type"，indexOfKey 返回的是它起始引号的位置。
        // 先定位该 key 的结束引号，再向后找冒号与值。
        int keyEnd = json.indexOf('"', i + 1);
        if (keyEnd < 0) return null;
        int colon = json.indexOf(':', keyEnd);
        if (colon < 0) return null;
        // 跳过冒号后的空白
        int s = colon + 1;
        while (s < json.length() && (json.charAt(s) == ' ' || json.charAt(s) == '\t')) s++;
        if (s >= json.length() || json.charAt(s) != '"') return null; // 值不是字符串
        int valStart = s + 1;
        int valEnd = valStart;
        while (valEnd < json.length()) {
            char c = json.charAt(valEnd);
            if (c == '\\') { valEnd += 2; continue; } // 跳过转义字符
            if (c == '"') break;
            valEnd++;
        }
        if (valEnd >= json.length()) return null; // 未闭合
        return json.substring(valStart, valEnd);
    }

    /** 取出顶层数字字段值；不存在返回 null */
    public static Double jsonNumber(String json, String key) {
        int i = indexOfKey(json, key);
        if (i < 0) return null;
        // 跳过 key 后的冒号
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int s = colon + 1;
        while (s < json.length() && (json.charAt(s) == ' ' || json.charAt(s) == '\t')) s++;
        int e = s;
        while (e < json.length() && "0123456789+-.eE".indexOf(json.charAt(e)) >= 0) e++;
        if (e == s) return null;
        try {
            return Double.valueOf(json.substring(s, e));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 定位 "key" 在 JSON 中的起始下标（只匹配顶层，够用且不误伤嵌套值） */
    private static int indexOfKey(String json, String key) {
        String needle = "\"" + key + "\"";
        return json.indexOf(needle);
    }

    // ------------------------------------------------------------------

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** UTF-8 编码。Android 与桌面 JVM 行为一致。 */
    public static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // 不可能发生
            return s.getBytes();
        }
    }

    /** UTF-8 解码 */
    public static String utf8String(byte[] b, int off, int len) {
        try {
            return new String(b, off, len, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(b, off, len);
        }
    }
}
