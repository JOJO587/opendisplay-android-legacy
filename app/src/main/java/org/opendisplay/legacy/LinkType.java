package org.opendisplay.legacy;

import java.net.InetAddress;

/**
 * 链路类型判定：当前连进来的这条连接走的是 USB 隧道还是 WiFi。
 *
 * 判定依据（实测）：Mac 端经 adb forward 建桥后拨进来，安卓端
 * socket.getInetAddress() 看到的是 127.0.0.1（桥的平板侧入口就在本机）；
 * 而同一局域网 WiFi 连进来看到的是 192.168.x.x 这类局域网地址。
 *
 * 为什么单独成一个类、且只用 java.net：
 * 本地没有 Android SDK，把这段纯逻辑剥出来就能用 javac/java 直接跑单测
 * （见 tools/LinkTypeTest.java），不必为一行判断引入 android 依赖。
 * 因此本文件**禁止** import 任何 android.* 类，否则单测立刻跑不起来。
 */
public final class LinkType {

    public static final String USB = "USB";
    public static final String WIFI = "WiFi";

    private LinkType() {
    }

    /**
     * 是否为 USB 隧道连入。
     *
     * @param addr socket 的对端地址，可能为 null（socket 已关闭时
     *             getInetAddress() 会返回 null），此时按非 USB 处理，不抛异常。
     */
    public static boolean isUsbTunnel(InetAddress addr) {
        // null 兜底：adopt 时 socket 可能已被对端关闭，这里绝不能 NPE
        return addr != null && addr.isLoopbackAddress();
    }

    /**
     * 链路标签，用于状态栏/通知显示。
     *
     * @param addr 对端地址，null 时返回 "WiFi"（最保守的默认，与 isUsbTunnel 一致）
     */
    public static String label(InetAddress addr) {
        return isUsbTunnel(addr) ? USB : WIFI;
    }
}
