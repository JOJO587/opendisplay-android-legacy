import org.opendisplay.legacy.LinkType;

import java.net.InetAddress;

/**
 * LinkType 的纯 JDK 单测（不引入 JUnit，项目刻意零依赖）。
 *
 * 放在 tools/ 而非 app/src/test/：后者会进 gradle test 源集，
 * 而本地无 Android SDK 时 ./gradlew test 跑不起来，会破坏构建取舍。
 *
 * 跑法：
 *   mkdir -p /tmp/od_linktest && cd /tmp/od_linktest
 *   javac -d . <项目>/app/src/main/java/org/opendisplay/legacy/LinkType.java \
 *              <项目>/tools/LinkTypeTest.java
 *   java -cp . LinkTypeTest
 *
 * 全绿打印 ALL PASS，否则打印失败项并以退出码 1 结束。
 */
public class LinkTypeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        InetAddress loopbackV4 = addr("127.0.0.1");
        InetAddress loopbackV6 = addr("::1");
        InetAddress lanV4 = addr("192.168.1.23");

        // 经 adb forward 桥进来的连接，安卓端看到的对端是 127.0.0.1 → USB
        check("127.0.0.1 isUsbTunnel", true, LinkType.isUsbTunnel(loopbackV4));
        check("127.0.0.1 label", "USB", LinkType.label(loopbackV4));

        // IPv6 回环同样算 USB（adb 也可能走 ::1）
        check("::1 isUsbTunnel", true, LinkType.isUsbTunnel(loopbackV6));
        check("::1 label", "USB", LinkType.label(loopbackV6));

        // WiFi 直连进来的是局域网地址
        check("192.168.1.23 isUsbTunnel", false, LinkType.isUsbTunnel(lanV4));
        check("192.168.1.23 label", "WiFi", LinkType.label(lanV4));

        // 非回环的公网/其它网段也是 WiFi
        check("10.0.0.5 label", "WiFi", LinkType.label(addr("10.0.0.5")));

        // null（socket 已关闭时 getInetAddress() 会返回 null）不得抛异常，
        // 且按最保守的默认（WiFi）处理
        checkNullDoesNotThrow();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.out.println("SOME FAILED");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void checkNullDoesNotThrow() {
        try {
            boolean usb = LinkType.isUsbTunnel(null);
            String label = LinkType.label(null);
            check("null isUsbTunnel", false, usb);
            check("null label", "WiFi", label);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL null 安全: 抛出了 " + t);
        }
    }

    // ------------------------------------------------------------ 断言辅助

    private static void check(String name, Object expect, Object actual) {
        boolean ok = (expect == null) ? actual == null : expect.equals(actual);
        if (ok) {
            passed++;
            System.out.println("PASS " + name + " -> " + actual);
        } else {
            failed++;
            System.out.println("FAIL " + name + " -> expect=" + expect
                    + " actual=" + actual);
        }
    }

    private static InetAddress addr(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (Exception e) {
            throw new RuntimeException("无法解析 " + host, e);
        }
    }
}
