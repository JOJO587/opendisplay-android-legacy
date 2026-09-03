# BACKLOG — OpenDisplay Legacy Receiver 迭代待办

> 用户明确要求把已发现的问题记成**待办**、后续有空再迭代。
> 每条记录带「现象 / 已核实的代码事实 / 诊断方向」，方便下次直接续上，不重复排查。
>
> 当前基线：commit `5f093c6` 之后的修复改动（见下方"已修复待验证"）。
> 复现路径：ADB/USB `adb forward tcp:9000 tcp:9000` → Mac `Extending to Android`。
> 每次改动走：Superpowers 流程 → 用户确认 → push → GitHub Actions 编译 → 真机验证。

---

## 待办 1 — 单击/双击手势失效（回归）✅ 已修复+装机验证（2026-09-04）

**装机验证（01:08）**：真实手指触摸（含拖拽 began≠ended）与合成 tap 均在 logcat 见
`touch began/ended` 正常发出，无异常崩溃。真机点击是否在 Mac 端生效待用户肉眼确认。

**根因（铁证闭环）**：`MainActivity.onStatus` 用 `connected = text.contains("已连接")` 从
状态文本反推连接状态。jsonString 修复后 `welcome` 分支第一次真正跑起来，它发出的
"Mac 已握手 (pv=3)"不含"已连接"→ `connected` 被打回 false → `onTouch` 的
`if (!connected) return true;` 把**所有单指 touch（点击）和双指 scroll 静默吞掉**。
时间线完美对上：7ea1b84（jsonType 坏→welcome 分支死→connected 保持 true→点击正常）；
a123a29（jsonType 修好→welcome 分支活→connected 变 false→点击死、光标活）。
上游 MacSender.swift 的 `case "touch"` **没有任何 pv 门槛**（此前"PV 3→2 导致触控失效"
的假设被证伪）。

**修复**：
- `StatusCallback` 新增显式回调 `onConnectionChanged(boolean)`，替代文本反推：
  - `adopt()` 连接建立后发 `true`
  - `readLoop` finally 仅当 `s == current`（同 `this` 锁保证检查+清理原子）时清理死
    socket（close + 清 current/controlSocket，防 fd 泄漏）并发 `false`
  - `stopListening()` 显式发 `false`
  - `attach()` 晚绑定时若 `controlSocket` 仍活跃则同步发 `true`（Activity 重建场景）
- `statusCb`/`decoder` 补 volatile（UI 线程写、网络线程读）
- `MainActivity.onConnectionChanged` 接管 `connected`；`onStatus` 只留显示文本
- 附带修复：独立审查发现的 wedge 路径（EOF 后死 socket 不清理 → Activity 重建时
  attach() 对死连接误报 true → UI 永久 connected=true 触摸被吞）
- 验证手段：新 APK 装后 `adb logcat -s ODMain` 应见 `touch began/ended`；
  真机点击 pad 应触发 Mac 点击

## 待办 2 — 解除最高分辨率限制未生效 ✅ 已修复+装机验证（2026-09-04）

**装机验证（01:08，Mac 日志实锤）**：
`virtual display created: id=17 960x600pt @2x` → `encoder ready: 1920x1200 H.264 18Mbps` →
`capture started: 1920x1200` → `status[usb:first]: Extending to Android (1920×1200)`。
全面板 1920×1200 像素推流已生效。（注：Mac 同时还在给另一台 iPad 设备扩展 2048×1536
的另一块虚拟屏，两者互不干扰。）

**根因（源码实锤）**：Mac 端 `VirtualDisplay.swift` 给虚拟显示器只注册**一个**
CGVirtualDisplayMode（= hello 报的像素 ÷ 2），且 `selectHiDPIMode` **每 2 秒强制切回**
该模式——Mac 日志 `(re)selected: 918x600@2x (result 0)` ×3 就是用户切换被弹回的记录。
**显示设置里选其他分辨率永远不会生效（官方设计），接收端唯一杠杆 = hello 报最大**。
而旧代码用 `getDisplayMetrics()` 扣掉了导航栏（物理 1920×1200 面板只报了 1836×1200，
`adb shell wm size` 已确认 1200x1920）。

**修复**：`sendHello` 改用 `getRealMetrics()`（API 17，minSdk 23 安全）报面板物理
分辨率，长边为 pixelsWide（MacSender 语义），1920×1200 → Mac 虚拟屏 960×600pt @2x、
推流 1920×1200（全面板像素）。Mac 端 `points = pixels/2` **硬编码除 2、无视
hello.scale**（iOS @3x 面板的历史设计），scale 字段仅存档。
**预期管理**：装后 Mac 显示器里默认档就是"最高"（960×600@2x）；选其他档仍会被弹回
（官方 2 秒执法循环），这是 Mac 端设计，非接收端能解。若推流卡顿可在 Mac app 的
画质档调低（quality.scale 会缩放捕获分辨率），接收端代码不加任何上限。

---

## 待办 3 — Android 14+ 前台服务类型缺失（🟡 待办，2026-09-04 记录）

**现状**：`minSdk 23` 设计上 Android 6.0+ 全兼容；6~13 的高版本要求（通知渠道、
startForegroundService、PendingIntent mutability、exported 声明）均已正确分支处理。

**隐患**：`targetSdk 34` + **Android 14（API 34）** 起，前台服务必须在 manifest 声明
`android:foregroundServiceType` 并配对应 `FOREGROUND_SERVICE_*` 权限，否则
`startForeground()` 抛异常崩溃。`ReceiverService` 目前未声明类型 →
**Android 14/15 设备装得上但起不来**。

**修法（小改动）**：manifest 给 `<service>` 加 `foregroundServiceType`（候选
`mediaPlayback` 或 `connectedDevice`）+ 对应权限，Java 无需改动，走一轮 CI 验证。

## 待办 4 — WiFi↔USB 自动切换（✅ 兜底版已实现并装机验证，2026-09-04）

**最终落地（用户拍板：保留 override，只要"USB 断了能走 WiFi"的兜底）**：
commit `e07529e`（CI run 33818592517 ✅，装机 07:43 验证通过）：
1. NSD 广播无条件常开（删死开关 EXTRA_WIFI_DISCOVERY/wifiDiscovery）；
2. 新增 LinkType 链路识别：回环=USB 隧道、其余=WiFi，状态/通知显示「已连接（USB/WiFi）」；
3. USB 断开时通知提示「USB 已断开，等待 WiFi 连入…」；onConnectionChanged(false) 全分支保留；
4. NsdAdvertiser 加 registerPending 防重复注册 + 同步抛异常复位（防 start 永久卡死）。
未做（明确放弃）：hello.addrs、Mac launchd 守护——保留 override 时无自动迁移收益，
且用户拒绝"必须有 WiFi"类代价。拔线后续接 = 在 Mac 设备列表点一下 WiFi 条目。

**装机实证**：重装后 adb forward 恰好失效，Mac 经 NSD 广播自动发现设备并走
WiFi（fe80:: link-local）连上，安卓日志实锤 `已连接（WiFi）`——广播常开 + 链路
识别当场自证有效。macOS 无 JDK，tools/LinkTypeTest.java 留待有 JDK 环境跑。

---

**以下为历史调研记录（其中第 3 条后续被官方 failover 注释修正：Mac 端有
"拔线后 WiFi 服务可见即切换"的机制，但触发链依赖 usbmuxd，对安卓不自动触发；
transport 为 override 回环时拔线后只会重拨回环直至超时）**：

**调研结论（重读上游 MacSender.swift / Usbmux.swift / PROTOCOL.md 6.4 后，修正上轮方案）**：

1. **USB 场景 Mac 端跑 adb 隧道命令不可省**。官方 Mac app 的 USB 发现只走
   usbmuxd（苹果设备专有协议），对安卓设备无感知；ADB 隧道只能由 USB 主机（Mac）
   发起。PROTOCOL.md 附录 B 明说：非苹果接收端就用 adb 隧道绑定。
2. **cable upgrade（hello.addrs）对安卓有一个可行变体**：Mac 探测时只禁
   WiFi/蜂窝、不禁 loopback（MacSender.swift probeForCablePath）→ 安卓在
   hello.addrs 里报 `127.0.0.1`（Mac 侧 adb forward 的回环入口），先走 WiFi 会话
   时 Mac 每 10s 探测 127.0.0.1:9000，隧道已建则自动 migrate 到 USB，全程无感。
   注意：不能报设备自身 WiFi IP（会被 WiFi 禁令拒绝，官方文档也明说手机 WiFi
   地址只会招来虚假升级）。
3. **"拔线自动回 WiFi"官方机制做不到**：migrate 后重连只重拨迁移后的地址
   （127.0.0.1），Mac 也无"自动连接发现的 WiFi 设备"逻辑（需手动点）。
   但安卓 `device="Android"` ≠ "Mac" ⇒ `currentPathDirectLink=false` ⇒ 拔线走
   scheduleReconnect 而非结束会话；只要隧道重建（重插线），Mac 的重拨循环会
   **自动续上**，无需人工。
4. **override 现状**：`defaults write com.peetzweg.opensidecar.mac host 127.0.0.1
   port 9000` 仍在生效。override 是"追加"不是"替换"——Bonjour WiFi 设备（iPad）
   照常可连。副作用：Mac app 启动即自动拨 127.0.0.1:9000（伪装成 usb:first 有线
   设备），隧道没建时状态栏常显"Waiting for receiver…"。

**候选方案**（等用户选）：
- **方案 1（推荐，体验最接近"全自动"）**：安卓端 NSD 广播常开（现在 USB 模式静默）
  + hello.addrs 报 `["127.0.0.1"]`；Mac 端装一个 launchd 常驻小守护（一次性安装），
  检测到 adb 设备插入自动 `adb forward tcp:9000 tcp:9000`。效果：插线自动升级到
  USB、拔线重插自动续上、纯 WiFi 也能连（手动点一次）；保留 override。
- **方案 2（保守）**：只做 NSD 常开 + 保留现状 override，不做 addrs/守护。
  USB 断后手动点一次 WiFi 设备续接。

## 待办 5 — APP 图标（✅ 完成 2026-09-04，待装机确认）

官方 Mac AppIcon.icns（256px 最大档）→ `sips` 切成安卓五档密度 PNG
（mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192）→
`res/mipmap-*/ic_launcher.png`，manifest `android:icon="@mipmap/ic_launcher"`。
不做 Android 8+ 自适应图标（主力设备 Android 6 不支持，且会裁切官方设计）。
素材源：`/Applications/OpenDisplay.app/Contents/Resources/AppIcon.icns`。


## 附：本版遗留的两处"低优先级一致性"（✅ 已随本轮一并完成）
- **A. `Protocol.PV` 2→3 已回改**：对齐 README 与上游 WireProtocol.version=3；
  注释已更正（Mac 无按 pv 的功能开关，之前那条"pv 高会关光标叠加"是错误推断）
- **B. 调试日志已清理**：`ctrl:` / `cursor v=` / `cursorImg pngLen=` 三处删除；
  保留 `video size`（每次编码器配置打一条）与新增的 `touch began/ended`（每次点击
  两条，回访验证用，确认稳定后可删）
