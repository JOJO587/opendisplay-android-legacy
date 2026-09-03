# OpenDisplay Legacy Receiver — Android 6.0 (API 23) 接收端

让 **Android 6.0 及以上** 的旧平板 / 旧手机，变成 Mac 的第二块扩展屏。

**默认走 ADB/USB 链路**（延迟最低、不依赖路由器组播、边用边充电），
WiFi 作为可选备选。

基于 [peetzweg/opendisplay](https://github.com/peetzweg/opendisplay) 的
**Wire Protocol pv 3**（`PROTOCOL.md`）从零实现的轻量接收端，
不用 Kotlin / Jetpack Compose / AndroidX，因此能编译到 **minSdk 23**。

---

## 一、先说清楚：为什么 ADB 链路不能只靠一条 adb 命令

这是本项目最反直觉、也最容易踩空的地方，有三层：

### ① 方向必须是 `forward`，不是 `reverse`

协议规定 **接收端监听、发送端连入**（PROTOCOL.md §1）。
安卓端监听 9000，Mac 要主动连它，方向是 **主机 → 设备**：

| 命令 | 方向 | 用在这里对不对 |
|---|---|---|
| `adb forward tcp:9000 tcp:9000` | 主机 → 设备 | ✅ 正确 |
| `adb reverse tcp:9000 tcp:9000` | 设备 → 主机 | ❌ 方向反了，链路不通 |

> 常见混淆点：`reverse` 是给"设备访问 Mac 上的服务"用的
> （比如安卓浏览器访问 Mac 本地起的 HTTP 服务）。这里需求相反。

### ② Mac app 够不到安卓，需要补"最后一公里"

官方 OpenDisplay Mac app 的两种发现方式都覆盖不到安卓：

- **USB**：走 `usbmuxd` 枚举 iPhone —— 安卓不在其中
- **WiFi**：走 Bonjour 自动发现 —— **没有手动输入 IP 的入口**

所以 `adb forward` 把隧道打通后，还得让 Mac app "看得见"这个隧道入口。
做法是在 **Mac 本机** 注册一个 Bonjour 代理服务，指向 `127.0.0.1:9000`：

```bash
dns-sd -P "Android-USB" _opensidecar._tcp local 9000 \
       Android-USB.local 127.0.0.1 "id=<设备ID>" "pv=3"
```

> **IP 必须写 `127.0.0.1`，不能写 Mac 的局域网 IP。**
> `adb forward` 在主机侧只监听回环地址，指向 `192.168.x.x:9000` 会连不上。

### ③ ADB 模式下要关掉设备端 mDNS

这是"ADB 优先"能不能真正生效的关键。
如果设备继续广播 mDNS，Mac 的 Bonjour 浏览器会同时看到两个服务：

| 服务 | 解析结果 | 走哪条路 |
|---|---|---|
| 设备自己广播的 | 设备真实 IP:9000 | **WiFi**（不是我们想要的） |
| Mac 本地代理的 | 127.0.0.1:9000 | **USB 隧道**（想要） |

它可能挑中前者，USB 隧道就白建了。
所以 **ADB 模式下设备端保持静默**，只让 Mac 看到本地代理 → 强制走 USB。

---

## 二、链路总览

```
Mac (发送端)                              Android 6.0 (接收端)
────────────────────                      ────────────────────
OpenDisplay app
    │ 浏览 Bonjour
    ↓ 发现 "Android-USB"
连接 127.0.0.1:9000 ─────┐
                          │  adb forward（USB 隧道）
                          └──────────────→ 监听 TCP 9000
                                              │ hello / ping
                                              ↓ H.264 → MediaCodec → SurfaceView
```

两步都由 `tools/usb-link.sh` 自动完成。

---

## 三、怎么用

### 1. Mac 端准备

```bash
brew install android-platform-tools
```

安卓端：**设置 → 关于 → 连点版本号** 开启开发者选项，
再进 **开发者选项 → 打开 USB 调试**。

### 2. 编译安装 APK

推到 GitHub **公开**仓库，Actions 自动编译（约 3-5 分钟）：

```bash
git init && git add -A && git commit -m "OpenDisplay legacy receiver"
git remote add origin git@github.com:<用户名>/opendisplay-android-legacy.git
git push -u origin main
```

产出：**仓库 → Actions → 最新 workflow → Artifacts → `opendisplay-legacy-debug`**

> **费用**：公开仓库 + `ubuntu-latest` 标准 runner = **免费、无限分钟**。
> ⚠️ 别改成 `macos-*`：安卓 APK 不需要 macOS，且 macOS runner 在私有仓库
> 按 **10 倍** 扣分钟。也别选 larger runner（那个始终收费）。

装到设备：

```bash
adb install -r app-debug.apk
```

### 3. 一键建链

```bash
cd tools
./usb-link.sh
```

脚本会自动：等设备上线 → 以 ADB 模式启动 App → `adb forward` →
注册本地 Bonjour 代理。**保持窗口开着**（Ctrl+C 自动清理）。

然后打开 Mac 上的 OpenDisplay，设备列表里点 **Android-USB** 即可。

其他子命令：

```bash
./usb-link.sh --status   # 查看设备 / forward / Bonjour 状态
./usb-link.sh --clean    # 清理 forward 与代理注册
```

### 4. 切到 WiFi（不需要 USB 时）

App 底部有 **模式** 按钮，切到 WiFi 后会开启设备端 mDNS，
Mac 直接就能发现 —— 此时**不需要**跑 `usb-link.sh`。

---

## 四、自测（装真机前后都能跑）

```bash
# 协议算法：37 项
python3 tools/test_protocol.py

# 端到端：假 Mac 发送端 ↔ 模拟接收端
python3 tools/test_e2e.py

# usb-link.sh 链路脚本：12 项（mock adb/dns-sd，验证 forward 方向等）
bash tools/test_usb_link.sh
```

### 用假发送端测真实设备

`adb forward` 建好后，连 `127.0.0.1:9000` 就等于连到设备，
所以不需要 Mac app 也能验证真机：

```bash
./usb-link.sh &          # 先建链

ffmpeg -f lavfi -i testsrc=size=1280x720:rate=30 -t 15 \
       -c:v libx264 -pix_fmt yuv420p -f h264 test.h264

python3 tools/fake_sender.py 127.0.0.1 test.h264
```

看设备日志：

```bash
adb logcat -s ODService ODDecoder ODNsd ODMain
```

---

## 五、老设备适配要点

1. **`maxEncodeWide/High` 必填**（§6.5）
   老平板解码能力弱，若按面板分辨率推流会解不动，这里默认上限 1920×1080。
2. **MediaCodec 用同步 API**，不用 `setCallback`（异步回调 API 21+，
   同步模式全版本行为一致）。
3. **不碰 `KEY_LOW_LATENCY`**（API 30+ 才有）。
4. **前台服务分版本处理**：API 26+ 必须先建 NotificationChannel。
5. **零外部依赖**：`org.json` / `MediaCodec` / `NsdManager` 均为 Android 内置，
   编译快，也不会踩第三方库的最低 API 门槛。

---

## 六、故障排查

| 现象 | 排查 |
|---|---|
| **Mac 列表里没有 Android-USB** | `./usb-link.sh --status` 看 forward 在不在；确认 Mac app 有「本地网络」权限；`dns-sd -B _opensidecar._tcp local` 看服务是否注册上 |
| **连上但走的是 WiFi** | 确认 App 底部显示「模式: USB(ADB)」；设备端 mDNS 必须关闭 |
| **连上后黑屏** | `adb logcat -s ODDecoder` 看有没有 `decoder started`；老设备可能挑 H.264 profile，调低 Mac 端画质 |
| **几秒后断开** | ping 线程异常；确认没有 5s 以上静默 |
| **画面卡住** | 会发 `kf` 请求关键帧；或 SPS 变化后解码器未重建 |
| **`adb devices` 显示 unauthorized** | 设备上点「允许 USB 调试」 |
| **forward 建不起来** | 端口被占：`./usb-link.sh --clean` 再试 |

---

## 七、许可证

GPL-3.0，与上游 OpenDisplay 一致。分发修改版请同时开放源码。
协议实现依据上游 `PROTOCOL.md`（pv 3）。
