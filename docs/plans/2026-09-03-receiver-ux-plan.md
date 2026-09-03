# 实现计划：安卓接收端 UX 修复

日期：2026-09-03
对应设计：`docs/plans/2026-09-03-receiver-ux-design.md`

> 约束：本机无 Android SDK，无法本地编译/跑单测。TDD 的"跑测试"环节改为
> **GitHub Actions 编译通过 + 真机手动测试清单**。每个任务改完后我会先暂停，
> 与你确认后再 `git commit` 并 `git push`（推前需你拍板），由 GitHub 编译验证。

## 任务拆分

### T1 · 鼠标光标渲染
- 新增 `CursorOverlay.java`（自定义 View，全屏、不拦截触摸）。
- `ReceiverService.handleControl`：解析 `cursor`(`v/x/y`) 与 `cursorImg`(`png`→Base64 解码 / `nw/nh/ax/ay`)，回调 `StatusCallback`。
- `StatusCallback` 接口加 `onCursor` / `onCursorImage` / `onCursorReset`；`MainActivity` 实现（UI 线程解码 PNG + 边界检查 + 重绘）。
- `activity_main.xml`：加 `CursorOverlay` 置于 SurfaceView 之上。
- **验证**：编译通过；真机鼠标随 Mac 移动、形状/热点正确、`v=0` 时隐藏。

### T2 · 全屏隐藏系统栏
- `MainActivity.hideSystemUI()` 设置 IMMERSIVE_STICKY 等 flags；`onCreate` 调用 + `onWindowFocusChanged(true)` 重应用。
- **验证**：编译通过；真机系统栏隐藏、边缘上滑可临时呼出。

### T3 · 隐藏底部控件 + 退出触发
- `activity_main.xml`：删除底部 LinearLayout；加隐藏菜单容器（状态文本 + 退出 + 关闭）。
- `MainActivity`：`ACTION_EXIT` 处理（stopService+finish）；三指轻点检测 → `showMenu()`；菜单「退出」「关闭」逻辑。
- `ReceiverService.buildNotification`：加「退出」通知动作（→ `MainActivity.ACTION_EXIT`）。
- **验证**：编译通过；三指弹菜单（含状态）、通知栏退出可结束；底部条不再常驻。

### T4 · 双指滚动（补全触控，可选但建议同批）
- `MainActivity.onTouch`：`pointerCount==2` 时按主指针位移发 `Protocol.scroll(dx*videoW, dy*videoH)`。
- **验证**：真机双指滚动操控 Mac。

### T5 · 收尾
- 更新 `DEBUG_STATUS.md`（记录新 UX：全屏、光标、退出触发）。
- 本地 `git commit`（设计+计划+代码）。
- **暂停**：与你确认后 `git push` → GitHub Actions 编译 → `adb install` 产物 → 真机测试清单核对。

## 提交节奏
- 每个任务实现后本地编译验证（CI）前先暂停确认；最终统一 commit，push 前再确认一次。
- 不做超出范围的重构（YAGNI）：自动 USB→WiFi 回退、旋转适配、overlay 贴合视频矩形均留 follow-up。
