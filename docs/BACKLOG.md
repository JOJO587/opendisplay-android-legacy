# BACKLOG — OpenDisplay Legacy Receiver 迭代待办

> 用户（Jony）明确要求把已发现的问题记成**待办**、后续有空再迭代。
> 每条记录带「现象 / 已核实的代码事实 / 诊断方向」，方便下次直接续上，不重复排查。
>
> 当前基线：commit `a123a29`（jsonString 根因修复 + 去分辨率上限），光标已能显示。
> 复现路径：ADB/USB `adb forward tcp:9000 tcp:9000` → Mac `Extending to Android`。
> 每次改动走：Superpowers 流程 → 用户确认 → push → GitHub Actions 编译 → 真机验证。

---

## 待办 1 — 单击/双击手势失效（本次装新版后回归）

**状态**：🟡 待办（未修，用户要求后续迭代）

### 现象
- 上上版（UX 修复版，光标未显示时）**单击/双击正常**。
- 本次装上光标修复版（`a123a29`）后，**单指单击 / 双击在 Mac 端无响应**。
- 双指滚动、三指菜单、全屏、退出、光标显示均正常（用户只报了单击/双击失效）。

### 已核实的代码事实（本次读取）
- 触摸逻辑**没被本次改动动过**：`MainActivity.onTouch`（L178）仍完整存在，挂载在 `surfaceView.setOnTouchListener(this::onTouch)`（L73）。
- 单指只发三种 phase 的 `touch`：`ACTION_DOWN→"began"`、`ACTION_MOVE→"moved"`、`ACTION_UP/CANCEL→"ended"`。**代码里没有任何"双击"检测**——单击/双击是 **Mac 端根据 touch 序列自己判定**的。所以"单击+双击一起失效"= **单指点按整条链路断了**（不是双击识别问题）。
- 出站发送链路完好：`Protocol.touch(phase,x,y,t)`（L202）→ `{"type":"touch","phase":"began","x":..,"y":..}`；`MainActivity.send` → `ReceiverService.sendControl`。
- **唯一本版对触摸路径有潜在影响的改动 = CursorOverlay 现在真的开始绘制/活动了**（jsonString 修复后 cursorImg 到达）。它是覆盖在 SurfaceView 之上的全屏 View。

### 首要怀疑：CursorOverlay 拦截了触摸（需真机确认）
- 层级：`FrameLayout` → `SurfaceView`(id=surface) → `CursorOverlay`(id=cursor_overlay) → `menu`(GONE)。
- `CursorOverlay` 代码 `setClickable(false)` + `setFocusable(false)`（L54-56），注释称"触摸穿透到 SurfaceView"。
- **但 Android 触摸分发**：z-order 更高的 CursorOverlay 会**先收到** `dispatchTouchEvent`。即使 clickable=false，若无条件 `return super.dispatchTouchEvent`，事件应继续下传到 SurfaceView——理论上应穿透。
- **为何上上版能用、本版失效**：上上版 CursorOverlay 收不到 cursorImg（jsonString bug），可能一直没实际参与渲染；本版 overlay 真正叠上去后，若 Android 6 上该 view 的某个状态（如因 `postInvalidate` 重绘/焦点）改变了分发路径，就会拦截。

### 诊断步骤（下次续做）
1. 真机 `adb logcat -s ODMain ODService`，看单指按下时有没有 `touch` 发送记录（当前代码 touch 路径**没打日志**，需先给 `onTouch` 加一行 Log 区分「事件到没到 onTouch」）。
2. 若 onTouch 未触发 → 触摸被 CursorOverlay 拦。验证法：临时给 CursorOverlay 加 `setOnTouchListener` 打日志，或把 overlay 改为不拦截（override `onTouchEvent` 返回 false / 确认 dispatch 链）。
3. 修法 A（若确为拦截）：CursorOverlay 完全不应参与触摸——可把 overlay 触摸事件无条件 `return false`，或改在 `addContentView` 后处理 / 用不带触摸的轻量方案。
4. 修法 B（若 onTouch 触发了但 Mac 无反应）：查 touch 线格式（`phase` 枚举是否 Mac 认 `began/moved/ended`，坐标是否需乘某系数）。

---

## 待办 2 — 解除最高分辨率限制未生效

**状态**：🟡 待办（未修，用户要求后续迭代）

### 现象
- 用户之前在 Mac「显示器设置」里选最高分辨率会自动回到 918×600（HiDPI 逻辑分辨率，@2x ≈1836×1200 面板/编码像素）。
- 本版去掉了 hello 里 `maxEncodeWide/High` 的 1920×1080 硬上限（对齐参考实现 josepacelli，不传该字段），**但仍选不到更高档** → 解除未生效。

### 已核实的代码事实（本次读取 + 真机日志）
- hello 现上报：`{"type":"hello","pixelsWide":1836,"pixelsHigh":1200,"scale":2.0,"device":"Android","id":"...","pv":2}` —— **确实已无 maxEncode 字段**（上限代码删干净了）。
- 注意 `pixelsWide/pixelsHigh=1836×1200` 是 `DisplayMetrics` 返回的**逻辑分辨率**（`getResources().getDisplayMetrics()`），不是物理 2560×1600。@2x 下它就是用户看到的 918×600 的 2 倍。
- Mac 回 `welcome pv=3.0` → **Mac 是 pv3**。
- ⚠️ **README 明确本项目是 pv 3**（README L9/L43/L207），而本版把 `Protocol.PV` 从 3 改成了 2 —— 这是一次**欠考虑的回退**，并非根因（jsonString 才是光标根因），但造成代码(pv2)与文档/README(pv3)及 Mac(pv3)不一致。功能上 pv2 的 hello Mac 也接受并推流，暂无碍，但应回改为 3 以对齐。

### 诊断方向（下次续做）
1. **分辨率上限的真正机制不在接收端 hello，而在 Mac 端**：Mac 按 `hello.pixelsWide/High` + `scale` 建虚拟显示器。当前 1836×1200 是从 `getResources().getDisplayMetrics()` 拿的**逻辑**分辨率（可能被系统/沉浸模式/旧 API 限制在某个值）。
   - 若想 Mac 能上更高档，接收端 hello 应报**物理像素**（`getWindowManager().getDefaultDisplay().getRealMetrics()`，Android 6 可用，返回 2560×1600）并相应调 `scale`，让 Mac 把扩展屏当更高分辨率物理屏。
2. **918×600 是否是用户看到的逻辑分辨率**：确认 `adb logcat -s ODMain` 的 `video size WxH`（当前应 ≈1836×1200 或 1652×1080）。若 Mac 仍 capped，说明 Mac 端还有别的手工档位限制（如显示器"默认缩放"），未必是接收端能解。
3. 需核对上游 peetzweg/opendisplay 真实协议对 `pixelsWide/High`、`scale` 的语义（本项目 README/`tools/fake_sender` 是"自洽参考"，非 Mac 官方；真实 Mac 在 pv3 下怎么消费 hello 字段，最好拉官方 Mac 端或 PROTOCOL.md 确认）。

---

## 附：本版遗留的两处"低优先级一致性"记录（不阻塞，顺手时可清）
- **A. `Protocol.PV` 3→2**：README 与 Mac 都是 pv3，建议改回 3 对齐（功能无碍，纯一致性）。改动很小但需走一遍 CI。
- **B. 调试日志清理**：`ReceiverService.handleControl` 顶部 `Log.i(TAG,"ctrl: "+type)`（每控制消息都打，刷屏）+ cursor/cursorImg 日志。光标已验证，应删掉 `ctrl:` 那行再发干净版。
