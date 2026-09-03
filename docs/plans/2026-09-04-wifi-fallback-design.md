# 设计：USB 断线后的 WiFi 兜底（接收端常开广播 + 链路识别）

日期：2026-09-04
流程：Superpowers（Brainstorm → Plan → Build → Review → Finish）

## 需求（用户原话，需求层，非技术方案）

- **主力走 USB**，且**不能接受任何"必须先有 WiFi"的代价**（外出、无网、只插一根线时照样要能用）。
- 想要的是**兜底**：USB 断掉之后，能通过 WiFi 再连上。

## 关键事实（均已核对上游源码，不是推测）

### 1. 官方 Mac 端本来就内置了「拔线回 WiFi」

`peetzweg/opendisplay` → `Mac/OpenSidecarMacApp.swift:208-215` 注释原文：

> "unplugging it **fails over to WiFi when the device's service is visible**
>  — otherwise the session ends after the usual grace"

即：**唯一条件是安卓端的 WiFi 服务（mDNS 广播）可见**。

而我们恰恰把广播关了 —— `ReceiverService.java:137`：

```java
if (wifiDiscovery) {
    nsd.start(); // 仅 WiFi 模式才广播，ADB 模式下保持静默
}
```

且 `MainActivity.java:45` 的 `wifiDiscovery` 是**硬编码 false、无 UI 开关**的死开关。
⇒ 结论：**不是要新做功能，是把我们自己关上的门打开。**

### 2. 当初关广播的顾虑已被证伪

`ReceiverService.java:46-54` 注释担心「Mac 会挑 WiFi 服务、导致 USB 白建」。
但 `Mac/OpenSidecarMacApp.swift:431-451` 的 `dedupeSessions()`：

> "two sessions for one device — **keeping the cable, dropping** (the WiFi one)"

Mac 端对同一设备出现两条会话时，**明确保留有线（cable）、踢掉 WiFi**。
⇒ 广播常开**不会**让 Mac 改走 WiFi，USB 优先权由 Mac 端兜底。

### 3. 链路识别可行（实测）

2026-09-04 实测（Mac 经 adb forward 桥连入）：

```
I/ODService: incoming from /127.0.0.1:52881
```

- 经 **adb 桥**进来的连接，安卓端看到的对端是 **127.0.0.1**（回环）
- 经 **WiFi** 进来的是 **192.168.x.x**

⇒ `InetAddress.isLoopbackAddress()` 即可可靠区分 USB / WiFi。

### 4. 参考项目的做法（佐证，非自创）

`gprot42/android-opendisplay` → `ConnectionMode.kt` 注释：

> "The receiver **always** listens on TCP :9000 for both USB (adb forward / tether)
>  and Wi‑Fi while the app is open" —— 模式只是 UI hint，**两条路不互斥**。

`MainActivity.kt:244`：*"Always listen on :9000 and re-advertise when visible"*，
295-300 无条件 `nsd.register(...)`。

## 明确不做的事（YAGNI，避免范围蔓延）

- **不做 USB 拔插广播监听**。理由：
  1. 平板是 USB **device** 模式，`UsbManager.ACTION_USB_DEVICE_DETACHED` 属 host 模式 API，不可靠；
  2. 真正的痛点（桥断后 socket 半开不 EOF）已由**每 2 秒 ping** 覆盖（ping 失败 → 异常 → readLoop 退出 → 清理）；
  3. 「断开时按链路给出提示」已足以满足需求。
- **不动 Mac 端 override**（`host=127.0.0.1`）。保留它正是"纯 USB 无 WiFi 也能连"的保证，是用户核心诉求，零妥协。
- **不新增 hello.addrs**。保留 override 时会话 transport 固定为 127.0.0.1，Mac 的 cable-upgrade 探测只在 WiFi 会话时启动，此字段无用。
- **不引入任何依赖**（项目刻意保持零外部依赖以支持 API 23 + 极简 CI）。

## 设计

### 改动 1：mDNS 广播常开
`ReceiverService.onStartCommand` 中 `nsd.start()` 改为无条件调用；
同步清理死开关（`EXTRA_WIFI_DISCOVERY` / `wifiDiscovery` 及 MainActivity 里的硬编码 false），
避免留下永不生效的死代码。更新注释，写明「为何常开是安全的」（dedupeSessions 保有线）。

### 改动 2：链路识别与显示
抽取**仅依赖 JDK** 的纯函数，便于本地 JVM 单测：

```java
// LinkType.java（只 import java.net.*，无 Android 依赖）
static boolean isUsbTunnel(InetAddress addr);  // 回环 = adb 桥 = USB
static String label(InetAddress addr);         // "USB" / "WiFi"
```

- `adopt()` 记录当前链路，状态与通知显示「已连接（USB）」/「已连接（WiFi）」
- 断开时若断开的是 USB 链路 → 提示「USB 已断开，可在 Mac 上点 WiFi 连接」

## 验收标准

1. `LinkTypeTest` 全绿（纯 JDK 编译运行，不依赖 Android SDK）
2. GitHub Actions 编译通过
3. 真机：USB 连接时状态显示「已连接（USB）」，画质与现在一致（1920×1200）
4. 真机：Mac 端设备列表可见该设备的 WiFi 条目（证明广播常开）
5. 真机：拔线后提示「可在 Mac 上点 WiFi 连接」，在 Mac 上点该条目能连上（兜底成立）

## 风险

- 广播常开后，Mac 端若曾在 `wifiRemembered` 中记住该设备，启动时 12 秒窗口内可能短暂
  出现两条会话 —— 已由 `dedupeSessions()` 自动收敛为保留 USB，无需我们处理。
