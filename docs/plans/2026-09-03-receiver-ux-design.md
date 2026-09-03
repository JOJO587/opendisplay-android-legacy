# 安卓接收端 UX 修复设计（光标 / 全屏 / 退出）

日期：2026-09-03
流程：Superpowers（Brainstorm → Plan → Build → Review → Finish）

## 背景与现状

USB/ADB 链路已打通（见 `DEBUG_STATUS.md`）。当前遗留三个体验问题，按严重程度排序：

1. **扩展屏上没有鼠标** —— 最关键。
2. **安卓系统底栏（返回/Home）未隐藏** —— 不是全屏。
3. **底部常驻控件（已连接 / 模式切换 / 退出）多余**，需要隐藏，退出改成非屏常驻触发。

### 根因（已核对源码）

- `ReceiverService.handleControl` 第 275-277 行：`cursor` / `cursorImg` 被显式忽略（"光标叠加：可选功能，本实现不渲染"）。Mac 其实一直在发光标消息，接收端不画。
- `MainActivity.onCreate` 没有任何 immersive / 隐藏系统栏设置。
- `activity_main.xml` 底部 LinearLayout 常驻显示三个控件。

### 权威线格式（已核对 josepacelli——官方未改版 Mac 的适配实现）

- `cursor`：`v`（int，1=可见）、`x`、`y`（归一化 0..1，左上原点）。
- `cursorImg`：`png`（base64 PNG）、`nw`/`nh`（归一化宽高）、`ax`/`ay`（归一化热点/锚点）。
- 渲染坐标变换（josepacelli `CursorOverlay.kt` 逐字逻辑）：
  - `cursorPxX = x * boxW`，`cursorPxY = y * boxH`（box = 覆盖层尺寸 = 全屏）
  - `spriteW = nw * boxW`，`spriteH = nh * boxH`
  - `originX = cursorPxX - ax * spriteW`，`originY = cursorPxY - ay * spriteH`
  - 收到 `cursorImg` 之前画一个白点占位；PNG 解码做边界检查（>1024px 拒绝，防解压炸弹）。
  - `v=0` 或断线时隐藏。

---

## 决策

| 项 | 决定 |
|----|------|
| 光标渲染 | 新增 `CursorOverlay`（盖在 SurfaceView 之上的自定义 View），`ReceiverService` 解析后回调 `MainActivity` 更新 |
| 全屏 | `View.setSystemUiVisibility(IMMERSIVE_STICKY | FULLSCREEN | HIDE_NAVIGATION | LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION | LAYOUT_FULLSCREEN)`，在 `onCreate` + `onWindowFocusChanged(true)` 重应用 |
| 模式切换 | **移除按钮**，保持当前 ADB 静默优先（USB-only）；自动"USB 不行再 WiFi"留后续（需开 NSD 广播，本次不做） |
| 退出触发 | **通知栏常驻「退出」动作** + **三指轻点屏幕弹极简菜单**（显示连接状态 + 退出），二者都给 |
| 连接状态 | 移出屏常驻，改由菜单/通知展示 |

> 手势约束：单指点击/拖拽、双指滚动已用于操控 Mac，三指手势不与之冲突。

---

## 问题1 · 鼠标光标

**新增 `CursorOverlay.java`**（自定义 `View`，全屏、`clickable=false` 不拦截触摸）：
- 字段：`Bitmap cursorBitmap`（可为空）、`float nw/nh/ax/ay`、`float cursorX/cursorY`、`boolean visible`。
- `onDraw`：不可见则直接返回；有 bitmap 按上面公式 `drawBitmap` 到 `(originX, originY)` 并缩放至 `(spriteW, spriteH)`；无 bitmap 画白点（半径 ~6dp，黑描边）。
- 方法：`setCursor(x,y,visible)` / `setCursorImage(bmp,nw,nh,ax,ay)` / `reset()`。

**`ReceiverService.handleControl`**：
- `cursor`：`v=jsonNumber("v")==1`、`x=jsonNumber("x")`、`y=jsonNumber("y")` → `statusCb.onCursor(x,y,v)`。
- `cursorImg`：`png=Base64.decode(jsonString("png"))`、`nw/nh/ax/ay=jsonNumber` → `statusCb.onCursorImage(png,nw,nh,ax,ay)`。
- 断线 `finally`：`statusCb.onCursorReset()`。

**`StatusCallback` 接口**新增三个方法；`MainActivity` 实现（UI 线程内）：
- `onCursor`：存 x/y/visible → `cursorOverlay.postInvalidate()`。
- `onCursorImage`：`BitmapFactory` 解码（先 `inJustDecodeBounds` 边界检查，>1024 拒绝）→ 存 bitmap+归一化参数 → `postInvalidate()`。
- `onCursorReset`：`visible=false` → `postInvalidate()`。

---

## 问题2 · 全屏隐藏系统栏

`MainActivity`：
- `private void hideSystemUI()` 设置上面的 flags（用 `getWindow().getDecorView().setSystemUiVisibility(...)`，minSdk 23 不引 AndroidX）。
- `onCreate` 末尾调用；重写 `onWindowFocusChanged(boolean hasFocus)`，`hasFocus` 时重调用（沉浸被系统交互打破后自动恢复）。
- 参考 josepacelli：`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 等价于 IMMERSIVE_STICKY 的边缘滑动临时呼出（已包含在 IMMERSIVE_STICKY 行为里）。

---

## 问题3 · 隐藏底部控件 + 退出触发

- `activity_main.xml`：删除底部 LinearLayout（status / btn_mode / btn_quit）；保留 SurfaceView；新增 `CursorOverlay`（全屏，置于 SurfaceView 之上）；新增隐藏的菜单容器 `LinearLayout id=menu`（`visibility=gone`，含连接状态文本 + 退出 + 关闭）。
- `ReceiverService.buildNotification`：加 `addAction(ic_menu_delete, "退出", exitPendingIntent)`；`exitPendingIntent` → `MainActivity` 带 `ACTION_EXIT`（`FLAG_ACTIVITY_SINGLE_TOP`）。
- `MainActivity`：
  - `ACTION_EXIT` 处理（`onCreate` / `onNewIntent`）：`stopService(ACTION_STOP)` + `finish()`。
  - 三指轻点：在 `onTouch` 顶部统计 `getPointerCount()`，≥3 视为菜单手势——记录按下/位移/时长，抬起时若几乎无位移且在 ~300ms 内则 `showMenu()`；菜单手势期间**不**转发给 Mac。
  - `showMenu()`：填充当前连接状态文本，`menu.setVisibility(VISIBLE)`。
  - 菜单「退出」→ `stopAndExit()`；「关闭」→ `menu.setVisibility(GONE)`。
  - 单指 / 双指逻辑保持原样（双指补一个基础 scroll，见下）。

### 附带：双指滚动（补全触控）

`onTouch` 在 `pointerCount==2` 时，用主指针位移发 `Protocol.scroll(dx*videoW, dy*videoH)`（自然滚动符号，videoW/H 取 `decoder.getWidth/Height`，缺失时回退视图尺寸）。补全之前未实现的 `onGenericMotionEvent` 空 stub。

---

## 风险与边界

- **光标坐标空间**：归一化 `x/y` 按"视频空间"，当前 overlay 覆盖全屏 = 视频全屏区域。本机视频 ~1652x1080 与屏 1836x1200 宽高比接近（均 ~1.53），黑边可忽略；若后续出现明显 letterbox，再让 overlay 贴合视频矩形（留作 follow-up）。
- **三指手势 vs Mac 操控**：三指不转发，单/双指逻辑不变，无冲突。
- **旋转**：本次不特殊处理旋转（pad 扩展屏一般横屏）；`hello` 在连接时按当前尺寸上报。留作 follow-up。
- **本地无法编译**：本机无 Android SDK，验证靠 GitHub Actions 编译 + 真机手动测试清单（见计划）。

## 验证

1. GitHub Actions 编译通过（无语法/类型错误）。
2. 真机测试清单：
   - 鼠标光标随 Mac 移动、形状/热点正确、移出扩展屏消失。
   - 系统状态栏/导航栏隐藏；边缘上滑可临时呼出。
   - 三指轻点弹出菜单（含连接状态）；通知栏「退出」可结束接收。
   - 单指点击/拖拽、双指滚动仍能操控 Mac。
