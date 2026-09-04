# OpenDisplay Legacy Receiver (Android)

[![CI](https://github.com/JOJO587/opendisplay-android-legacy/actions/workflows/build.yml/badge.svg)](https://github.com/JOJO587/opendisplay-android-legacy/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/JOJO587/opendisplay-android-legacy)](https://github.com/JOJO587/opendisplay-android-legacy/releases)
![Platform](https://img.shields.io/badge/platform-Android%206.0%2B-green)
![Deps](https://img.shields.io/badge/dependencies-zero-blue)

Turn an old **Android 6.0 – 13** tablet into a wired/wireless second display for your Mac — a dependency-free receiver for the [OpenDisplay](https://github.com/peetzweg/opendisplay) protocol (pv 3).

把一台老平板变成 Mac 的扩展屏。适用系统：**Android 6.0 到 13**；Android 14+ 暂不支持（需要声明前台服务类型，TODO 待补）。

---

## 为什么有这个项目

Duet 停服、Sidecar 只支持自家设备，主流方案早就把 Android 6 这种老设备丢进了垃圾堆。

OpenDisplay 协议本身做得很好，但 Mac 发送端有两个问题：USB 发现走的是 usbmuxd，安卓根本不在它的设备列表里；WiFi 发现虽然靠 Bonjour，但 Mac app 里没有手动输入 IP 的入口，设备不主动广播就永远找不到。

这个项目就是来解决这两个卡点：**让老平板也能被发现、被连接**。纯 Java 写，不依赖任何新版 API，所以能跑在 Android 6 上。

---

## 功能特性

| | |
|---|---|
| 🔌 **USB（推荐）** | `adb forward` 建隧道 + Mac 端写死 127.0.0.1:9000 → 延迟最低，边用边充电 |
| 📶 **WiFi** | mDNS 广播常开，Mac 打开 app 就能自动发现设备，无需任何额外操作 |
| 🔁 **断线兜底** | USB 断了？平板在 WiFi 上还亮着，Mac 列表点一下就能继续 |
| 🖥️ **原生分辨率** | 上报真实物理尺寸，不做"最大 1920×1080"的强行限制 |
| 🖱️ **触摸回传** | 单指点击/拖动转发为 Mac 光标事件 |
| 🚀 **开机自启** | 重启后自动开始监听（含 EMUI 快速启动） |

---

## 环境要求

| 项目 | 要求 |
|---|---|
| Android 系统 | **6.0 – 13**（API 23–33）|
| Android 14+ | ⚠️ 暂不支持，装上会崩（TODO：补前台服务类型声明）|
| Mac | 安装官方 [OpenDisplay Mac 端](https://github.com/peetzweg/opendisplay/releases)，macOS |
| USB 模式额外需要 | Mac 上装 adb：`brew install android-platform-tools` |
| 平板端 | 开发者选项 → 开启 USB 调试 |

---

## 安装

从 [**Releases**](../../releases) 下载最新版 APK：

```bash
adb install app-debug.apk

# 从其他签名版本升级要先卸载（签名不一致会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
adb uninstall org.opendisplay.legacy && adb install app-debug.apk
```

---

## 使用方法

### 场景 A — USB（日常主力）

Mac 的 OpenDisplay app 不认识安卓设备，需要在 USB 线里架一层 TCP 隧道：

```bash
# 第一步：在 Mac 上建隧道
adb forward tcp:9000 tcp:9000

# 第二步：告诉 Mac app 去哪里找设备（只需做一次）
defaults write com.peetzweg.opensidecar.mac host -string "127.0.0.1"
defaults write com.peetzweg.opensidecar.mac port -int 9000
# 然后重启 Mac 端的 OpenDisplay app
```

Mac app 里会出现一个 **Manual (127.0.0.1:9000)** 条目，隧道在的时候就自动连上了。

嫌每次手敲麻烦？Mac 上有现成的快捷指令脚本 [`tools/usb_bridge_shortcut.applescript`](tools/usb_bridge_shortcut.applescript)，粘贴到 macOS「快捷指令」App 里，一键完成检测、建桥、校验：

1. 快捷指令 → 新建 → 添加「运行 AppleScript」
2. 脚本内容粘贴进去
3. （可选）后面加一步「显示结果」，能看到成功还是失败
4. 固定到菜单栏，随用随点

> ⚠️ 脚本里 adb 路径写的是 Apple Silicon Mac 的 Homebrew 位置（`/opt/homebrew/bin/adb`）。Intel Mac 或其他路径的同学，改一下脚本第一行的 `adbPath` 就好。

### 场景 B — WiFi（纯无线）

平板和 Mac 在同一个网络里 → 平板持续广播 mDNS → Mac app 自动发现设备。点一下就连，什么都不用装。

### 场景 C — USB 为主，WiFi 兜底（推荐日常形态）

平时插着线用，延迟最低还能充电；哪天线被扯了，Mac 列表里 WiFi 那条还在，拔了线点一下就接上；回来插上线、点一下建桥脚本，Mac 自动切回 USB。整个过程不需要关 app、不需要重连。

---

## 工作原理

```
Mac (发送端)                              Android (接收端)
────────────                              ──────────────────
OpenDisplay app
   │ 拨 127.0.0.1:9000 ←── adb forward ──→  监听 TCP :9000
   │   （USB 隧道）                              │  hello / ping
   │                                              ▼
   └── 或拨 <设备IP>:9000                    H.264 → MediaCodec
       （WiFi，Bonjour 自动发现）              → SurfaceView + 触摸事件回传
```

一条说清楚：**发送端主动拨号，接收端只负责监听**。

---

## 故障排查

| 现象 | 处理 |
|---|---|
| 装不上，报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 先 `adb uninstall org.opendisplay.legacy` 再装 |
| Mac 上 Manual 条目一直转圈 "Waiting for receiver" | 隧道没建好，重新运行快捷指令脚本或手敲 `adb forward tcp:9000 tcp:9000` |
| Mac WiFi 列表里找不到设备 | 确认平板和 Mac 在同一网段；Mac app 要有「本地网络」权限 |
| `adb devices` 显示 `unauthorized` | 在平板屏幕上点"允许 USB 调试" |
| 连上了但黑屏 | 查日志 `adb logcat -s ODService ODDecoder` |
| 插着线但状态显示 WiFi | 隧道没建立，重建即可 |

---

## 从源码构建

```bash
./gradlew assembleDebug
```

需要 JDK 17 + Android SDK（compileSdk 34）。CI 在每次 push 后自动运行，APK 在 Actions 运行详情的 **Artifacts** 里，文件名是 `opendisplay-legacy-debug`。

---

## 已验证设备

| 设备 | 系统 | 状态 |
|---|---|---|
| 华为平板 JDN-W09 | Android 6.0（EMUI 4.x）| 1920×1200 面板，USB / WiFi / 断线兜底均已实测 ✅ |

---

## 致谢

- [peetzweg/opendisplay](https://github.com/peetzweg/opendisplay) — 协议定义与官方 Mac 发送端
- [josepacelli/opendisplay-android](https://github.com/josepacelli/opendisplay-android) — 参考接收端实现
- [gprot42/android-opendisplay](https://github.com/gprot42/android-opendisplay) — "常监听 + 常广播"设计参考
