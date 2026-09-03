# 实现计划：USB 断线后的 WiFi 兜底

对应设计：[2026-09-04-wifi-fallback-design.md](2026-09-04-wifi-fallback-design.md)

## 环境约束（影响 TDD 落地方式）

- **本地无 Android SDK**，且项目刻意保持**零外部依赖**（无 JUnit/Robolectric）。
- 因此：`./gradlew test` 在本机跑不了，编译只能靠 GitHub Actions。
- TDD 落地点 = **仅依赖 JDK 的纯函数**（`LinkType`），用 `javac` + `java` 直接跑测试；
  涉及 Android 框架的部分（NSD / Service / 通知）走**静态审查 + CI 编译 + 真机验证**。

## Task 1 — `LinkType` 纯函数 + JVM 单测（TDD：红 → 绿）

**文件**：新建 `app/src/main/java/org/opendisplay/legacy/LinkType.java`
**测试**：新建 `tools/LinkTypeTest.java`（不进 gradle，纯 JDK 跑，保住零依赖取舍）

要求：
- `LinkType.java` **只允许 import `java.net.*`**（不得引入任何 Android 类），否则本地测不了。
- API：
  ```java
  public static boolean isUsbTunnel(InetAddress addr) // null → false；回环 → true
  public static String  label(InetAddress addr)       // "USB" / "WiFi"（null → "WiFi"）
  ```
- 先写测试再写实现。测试用例至少覆盖：
  - `127.0.0.1` → USB
  - `::1` → USB
  - `192.168.1.23` → WiFi
  - `null` → 不抛异常，label 返回 "WiFi"
- 验收命令：
  ```bash
  mkdir -p /tmp/od_linktest && cd /tmp/od_linktest
  javac -d . <项目>/app/src/main/java/org/opendisplay/legacy/LinkType.java \
             <项目>/tools/LinkTypeTest.java
  java -cp . LinkTypeTest     # 期望：ALL PASS
  ```

## Task 2 — mDNS 广播常开 + 清理死开关

**文件**：`ReceiverService.java`、`MainActivity.java`

- `ReceiverService.onStartCommand`：`nsd.start()` 无条件调用，去掉 `if (wifiDiscovery)`。
- 删除死开关：`ReceiverService.EXTRA_WIFI_DISCOVERY`、`ReceiverService.wifiDiscovery`
  字段、`MainActivity.wifiDiscovery` 字段与 `putExtra(...)` 调用。
  （该 flag 恒为 false 且无 UI 开关，属死代码；YAGNI 要求顺手清掉。）
- 更新 `ReceiverService.java:46-54` 那段已过时的注释：写明「Mac 端 dedupeSessions
  保有线、踢 WiFi，故常开安全」。
- 通知/状态文案：`"监听中（ADB/USB 模式）"` / `"等待 Mac 连接…"` 之类改成同时体现
  USB 与 WiFi 均可连入。

## Task 3 — 接入链路标签（连接时 + 断开时）

**文件**：`ReceiverService.java`

- `adopt(Socket s)`：用 `LinkType.label(s.getInetAddress())` 记录当前链路；
  状态与通知显示 `已连接（USB）` / `已连接（WiFi）`。
  - 注意：`s.getInetAddress()` 可能为 null（socket 已关），须走 LinkType 的 null 兜底。
- `readLoop` 的 finally（约 305-314 行）：断开提示按链路区分 ——
  若断开的是 USB 链路，提示「USB 已断开，可在 Mac 上点 WiFi 连接」；
  WiFi 链路则维持原「连接断开，等待重连…」。
- 不得破坏既有行为：`onConnectionChanged(false)` 仍必须发出（触摸转发依赖它）。

## Task 4 — 审查与交付

1. 派 spec-reviewer subagent：逐条核对实现与设计文档是否一致。
2. 派 code-quality reviewer subagent：API 23 兼容性（不得用 API 24+ 的写法）、
   线程安全（volatile/同步沿用既有约定）、无死代码残留。
3. 本地 commit → push → GitHub Actions 编译 → 真机验证（验收标准见设计文档）。
4. 把各 subagent 的原始报告整理进 `docs/2026-09-04-wifi-fallback-subagent-records.md`
   并交付用户（流程透明要求）。
