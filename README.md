# OpenDisplay Legacy Receiver (Android)

[![CI](https://github.com/JOJO587/opendisplay-android-legacy/actions/workflows/build.yml/badge.svg)](https://github.com/JOJO587/opendisplay-android-legacy/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/JOJO587/opendisplay-android-legacy)](https://github.com/JOJO587/opendisplay-android-legacy/releases)
![Platform](https://img.shields.io/badge/platform-Android%206.0%2B-green)
![Deps](https://img.shields.io/badge/dependencies-zero-blue)

Turn an old **Android 6.0+ (API 23)** tablet into a wired/wireless second display for your Mac — a lightweight, dependency-free receiver for the [OpenDisplay](https://github.com/peetzweg/opendisplay) protocol (wire protocol pv 3).

把一台老旧的 **Android 6.0+（API 23）** 平板变成 Mac 的扩展屏 —— 基于 [OpenDisplay](https://github.com/peetzweg/opendisplay) 协议（wire protocol pv 3）从零实现的轻量接收端，零第三方依赖。

---

## Why this exists / 为什么有这个项目

Most modern second-display apps (Duet, Sidecar clones) dropped support for old Android tablets. The upstream OpenDisplay project is excellent but its Mac sender only auto-discovers Apple devices over USB (usbmuxd) — Android receivers need a little help to be found. This project is a **pure-Java receiver** that:

- compiles down to **minSdk 23** (no Kotlin / AndroidX / Compose — they require newer APIs),
- stays discoverable over **both USB and WiFi at the same time**,
- reports the device's **real physical resolution** with no artificial cap.

如今主流的扩展屏应用早已放弃老旧安卓平板。上游 OpenDisplay 的 Mac 发送端只通过 usbmuxd 自动发现苹果设备，安卓接收端需要自己"被看见"。本项目是一个**纯 Java 接收端**：

- 可编译到 **minSdk 23**（不用 Kotlin / AndroidX / Compose——它们要求更高的 API）；
- **USB 与 WiFi 同时可被发现**，互不冲突；
- 上报设备**真实物理分辨率**，不做人为限制。

## Features / 功能特性

| | |
|---|---|
| 🔌 **USB mode (recommended)** | `adb forward` tunnel + Mac-side manual address → lowest latency, no WiFi needed, charges while in use. **USB 模式（推荐）**：`adb forward` 隧道 + Mac 端手动地址，延迟最低、不依赖 WiFi、边用边充电 |
| 📶 **WiFi mode** | NSD/mDNS advertised **always on** — the Mac discovers the device on the local network by itself. **WiFi 模式**：NSD 广播常开，Mac 自动发现同网段设备 |
| 🔁 **Failover hint** | If the USB tunnel drops, the device stays visible over WiFi; the Android notification tells you to switch. **断线兜底提示**：USB 隧道断开后设备在 WiFi 上仍然可见，安卓通知会提示切换 |
| 🖥️ **Native resolution** | Hello reports the real panel size (via `getRealMetrics`) — no hardcoded resolution cap. **原生分辨率**：hello 报告真实物理分辨率，无写死上限 |
| 🖱️ **Touch input** | Single tap / drag forwarded to the Mac as cursor events. **触摸回传**：单击/拖动转发为 Mac 光标事件 |
| 🚀 **Boot autostart** | Starts listening after reboot (incl. EMUI quick-boot). **开机自启**：重启后自动开始监听（含 EMUI 快速启动） |

## Requirements / 环境要求

- **Android**: 6.0 (API 23) or newer / 安卓 6.0 或更高
- **Mac**: macOS with the official [OpenDisplay Mac app](https://github.com/peetzweg/opendisplay/releases) installed / 安装官方 OpenDisplay Mac 端
- **adb** on the Mac for USB mode: `brew install android-platform-tools` / USB 模式需在 Mac 上安装 adb
- On the tablet: Developer options → **USB debugging** enabled / 平板需开启开发者选项中的 USB 调试

## Installation / 安装

Grab the latest APK from [**Releases**](../../releases), then:

从 [**Releases**](../../releases) 下载最新 APK，然后：

```bash
adb install app-debug.apk
# If upgrading from a differently-signed build / 如果从其他签名的旧版升级：
adb uninstall org.opendisplay.legacy && adb install app-debug.apk
```

## Usage / 使用方法

### Scenario A — USB (recommended) / 场景 A：USB 连接（推荐）

The Mac sender cannot see Android devices over USB by itself (usbmuxd is Apple-only), so we bridge TCP through the USB cable. Mac 发送端无法通过 USB 直接发现安卓设备（usbmuxd 仅支持苹果设备），因此通过 USB 线桥接 TCP：

```bash
# 1. On the Mac: build the tunnel / 在 Mac 上建立隧道
adb forward tcp:9000 tcp:9000

# 2. Tell the Mac app where to find the device (one-time setup)
#    告诉 Mac app 设备地址（一次性设置）
defaults write com.peetzweg.opensidecar.mac host -string "127.0.0.1"
defaults write com.peetzweg.opensidecar.mac port -int 9000
#    Restart the Mac app / 重启 Mac 端 app
```

The Mac app now shows a **Manual (127.0.0.1:9000)** entry and connects automatically while the tunnel is up. Mac 端会出现 **Manual (127.0.0.1:9000)** 条目，隧道存在时自动连接。

**One-click with macOS Shortcuts / 用 macOS 快捷指令一键建桥：**

The bundled [`tools/usb_bridge_shortcut.applescript`](tools/usb_bridge_shortcut.applescript) checks for a connected device, builds the tunnel, and returns a ✅/❌ result. Steps / 步骤：

1. Open **Shortcuts** → new shortcut → add **Run AppleScript** action / 打开快捷指令 → 新建 → 添加「运行 AppleScript」
2. Paste the script / 粘贴脚本内容
3. (Optional) add **Show Result** after it to see the outcome / （可选）后面加一步「显示结果」查看成功与否
4. Pin it to the menu bar — one click sets up the USB bridge / 固定到菜单栏，一键建桥

> ⚠️ Note the script hardcodes the adb path `/opt/homebrew/bin/adb` (Homebrew on Apple Silicon). Adjust the `adbPath` property if yours differs. / 注意脚本内 adb 路径默认为 Homebrew（Apple Silicon）位置，不同请修改 `adbPath`。

### Scenario B — WiFi / 场景 B：WiFi 连接

Both devices on the same network → the tablet advertises itself via mDNS **constantly** → the Mac app lists it automatically. Click to connect. No adb needed.

两台设备在同一网络 → 平板持续通过 mDNS 广播 → Mac 端列表自动出现设备，点击连接即可，无需 adb。

### Scenario C — USB with WiFi fallback / 场景 C：USB 为主，WiFi 兜底

This is the intended daily setup: use USB daily; if the cable is unplugged, the tablet is still visible over WiFi — the Mac app lists the WiFi entry and you can click it to continue. When you plug the cable back and re-run the bridge script, the Mac reconnects over USB.

这是推荐的日常形态：平时走 USB；拔线后平板在 WiFi 上仍然可见，Mac 列表出现 WiFi 条目，点一下即可继续；重新插线并运行建桥脚本后，Mac 会自动连回 USB。

## How it works / 工作原理

```
Mac (sender)                                Android (receiver)
────────────                                ──────────────────
OpenDisplay app
   │ dial 127.0.0.1:9000  ←── adb forward ──→  listens on TCP :9000
   │   (USB tunnel)                               │  hello / ping
   │                                              ▼
   └── or dial <device-ip>:9000                H.264 → MediaCodec
       (WiFi, auto-discovered)                 → SurfaceView + touch events back
```

- The **sender always dials; the receiver only listens** (protocol rule). / 协议规定发送端永远主动拨号、接收端只监听。
- Keeping NSD advertising always-on is safe: the Mac deduplicates sessions and prefers the wired one. / NSD 广播常开是安全的：Mac 端会话去重时会优先保留有线会话。

## Troubleshooting / 故障排查

| Symptom / 现象 | Fix / 处理 |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall org.opendisplay.legacy` first, then install / 先卸载再装（签名不一致） |
| Mac shows "Waiting for receiver" on the Manual entry | Tunnel is down — run `adb forward tcp:9000 tcp:9000` or the Shortcuts script / 隧道未建立，重新建桥 |
| Device not in the Mac's WiFi list | Both on the same network? Mac app needs **Local Network** permission / 确认同网段且 Mac 已授予「本地网络」权限 |
| `adb devices` shows `unauthorized` | Accept the USB debugging prompt on the tablet / 在平板上点允许 USB 调试 |
| Black screen after connect | Check `adb logcat -s ODService ODDecoder` / 查看设备日志 |
| Status shows WiFi although USB cable is plugged | Tunnel not built — rebuild the bridge / 隧道未建立，重建即可 |

## Build from source / 从源码构建

```bash
./gradlew assembleDebug
# Requires JDK 17 + Android SDK (compileSdk 34)
# 需要 JDK 17 + Android SDK（compileSdk 34）
```

CI builds automatically on every push (GitHub Actions, `ubuntu-latest`); the APK lands in the run's **Artifacts** as `opendisplay-legacy-debug`. 每次 push 后 CI 自动构建，APK 在 Actions 运行详情的 Artifacts 里（`opendisplay-legacy-debug`）。

## Tested on / 已验证设备

- Huawei tablet JDN-W09 · Android 6.0 (EMUI 4.x) — 1920×1200 panel, USB + WiFi + failover verified
- 华为平板 JDN-W09 · Android 6.0（EMUI 4.x）——1920×1200 面板，USB / WiFi / 断线兜底均已实测

## Credits / 致谢

- [peetzweg/opendisplay](https://github.com/peetzweg/opendisplay) — protocol definition & official Mac sender / 协议定义与官方 Mac 发送端
- [josepacelli/opendisplay-android](https://github.com/josepacelli/opendisplay-android) — reference receiver implementation / 参考接收端实现
- [gprot42/android-opendisplay](https://github.com/gprot42/android-opendisplay) — reference "always listen + always advertise" design / 「常监听 + 常广播」设计参考
