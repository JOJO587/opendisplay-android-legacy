# BACKLOG — OpenDisplay Legacy Receiver 迭代待办

> 用户（Jony）明确要求把已发现的问题记成**待办**、后续有空再迭代。
> 每条记录带「现象 / 已核实的代码事实 / 诊断方向」，方便下次直接续上，不重复排查。
>
> 当前基线：commit `5f093c6` 之后的修复改动（见下方"已修复待验证"）。
> 复现路径：ADB/USB `adb forward tcp:9000 tcp:9000` → Mac `Extending to Android`。
> 每次改动走：Superpowers 流程 → 用户确认 → push → GitHub Actions 编译 → 真机验证。

---

## 待办 1 — 单击/双击手势失效（回归）✅ 已修复（2026-09-04 凌晨，待真机验证）

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

## 待办 2 — 解除最高分辨率限制未生效 ✅ 已修复（2026-09-04 凌晨，待真机验证）

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

## 附：本版遗留的两处"低优先级一致性"（✅ 已随本轮一并完成）
- **A. `Protocol.PV` 2→3 已回改**：对齐 README 与上游 WireProtocol.version=3；
  注释已更正（Mac 无按 pv 的功能开关，之前那条"pv 高会关光标叠加"是错误推断）
- **B. 调试日志已清理**：`ctrl:` / `cursor v=` / `cursorImg pngLen=` 三处删除；
  保留 `video size`（每次编码器配置打一条）与新增的 `touch began/ended`（每次点击
  两条，回访验证用，确认稳定后可删）
