#!/usr/bin/env bash
#
# 用 mock 的 adb / dns-sd 同步跑一遍 usb-link.sh（设 OD_DAEMON_LOOPS 让它自己退出），
# 验证 ADB 优先链路的关键行为：
#
#   1. 用 adb forward（host→device），不是 reverse
#      —— 协议是接收端监听、发送端连入，方向反了链路就不通
#   2. 以 wifi_discovery=false 启动 App
#      —— ADB 模式必须关掉设备端 mDNS，否则 Mac 的 Bonjour 可能挑中
#         设备真实 IP 走 WiFi，USB 隧道白建
#   3. dns-sd -P 的 IP 参数是 127.0.0.1
#      —— adb forward 只监听回环地址，指向局域网 IP 会连不上
#   4. TXT 携带从 logcat 抓到的设备 id 与 pv=3
#   5. forward 意外丢失后，守护循环自动重建
#   6. 退出时 cleanup：移除 forward + 注销代理
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MOCK="$(mktemp -d)"
LOG="$MOCK/calls.log"
: > "$LOG"
export CALLLOG="$LOG"
export PATH="$MOCK:$PATH"

# ---------- mock adb ----------
cat > "$MOCK/adb" <<'EOF'
#!/usr/bin/env bash
echo "adb $*" >> "$CALLLOG"
[ "${1:-}" = "-s" ] && shift 2
case "${1:-}" in
  devices)
      echo "List of devices attached"
      echo "emulator-5554 device"
      ;;
  forward)
      case "${2:-}" in
        --list)
            # 第一次返回存在，之后返回空 —— 模拟 forward 中途丢失，
            # 用来验证守护循环会重建
            if [ ! -f "$CALLLOG.list1" ]; then
              : > "$CALLLOG.list1"
              echo "emulator-5554 tcp:9000 tcp:9000"
            fi
            ;;
        --remove) echo "removed" ;;
        *)        echo "9000" ;;
      esac
      ;;
  logcat)
      sleep 0.2
      echo "09-03 12:00:00.000 1234 1234 I ODService: OD_ID=0f3c1a22-9b7e-4a11-8c2d-7e5f6a1b2c3d"
      ;;
  shell) echo "Starting: Intent { cmp=org.opendisplay.legacy/.MainActivity }" ;;
  *)     echo "mock-adb: unhandled $*" ;;
esac
EOF

# ---------- mock dns-sd ----------
cat > "$MOCK/dns-sd" <<'EOF'
#!/usr/bin/env bash
echo "dns-sd $*" >> "$CALLLOG"
case "${1:-}" in
  -P) sleep 25 ;;
  -B) echo "Browsing for _opensidecar._tcp"; sleep 1 ;;
esac
EOF

chmod +x "$MOCK/adb" "$MOCK/dns-sd"

echo "================================================================"
echo " usb-link.sh ADB 优先链路 —— 同步 mock 测试"
echo "================================================================"
echo
echo "--- 运行 usb-link.sh（OD_DAEMON_LOOPS=2，跑 2 轮守护后自行退出）---"
echo

# 同步执行：脚本跑满 2 轮守护后调用 cleanup 退出
OD_DAEMON_LOOPS=2 timeout -s KILL 40 "$HERE/usb-link.sh" 2>&1 | tee "$MOCK/out.txt"
RC=${PIPESTATUS[0]}

# 兜底清理
pkill -f "sleep 25" 2>/dev/null
sleep 0.3

# ---------- 断言 ----------
echo
echo "================================================================"
echo " 断言检查"
echo "================================================================"
PASS=0; FAIL=0
chk() { if [ "$1" = "1" ]; then echo "  [OK]   $2"; PASS=$((PASS+1));
        else echo "  [FAIL] $2"; FAIL=$((FAIL+1)); fi; }

grep -qE "^adb .*forward tcp:9000 tcp:9000" "$LOG" && r=1 || r=0
chk $r "使用 adb forward（host→device，方向正确）"

grep -q "adb.*reverse" "$LOG" && r=0 || r=1
chk $r "没有误用 adb reverse"

grep -q -- "--ez wifi_discovery false" "$LOG" && r=1 || r=0
chk $r "以 wifi_discovery=false 启动 App（ADB 模式关闭设备端 mDNS）"

grep -qE "dns-sd -P .* 127\.0\.0\.1 " "$LOG" && r=1 || r=0
chk $r "dns-sd -P 指向 127.0.0.1（adb forward 只监听回环）"

grep -q "dns-sd -P .* id=0f3c1a22" "$LOG" && r=1 || r=0
chk $r "Bonjour TXT 带上从 logcat 抓到的设备 id"

grep -q "dns-sd -P .* pv=3" "$LOG" && r=1 || r=0
chk $r "Bonjour TXT 带上 pv=3"

grep -q "logcat -d -s ODService" "$LOG" && r=1 || r=0
chk $r "通过 logcat -s ODService 抓取 OD_ID"

grep -q "forward 丢失" "$MOCK/out.txt" && r=1 || r=0
chk $r "守护检测到 forward 丢失"

[ "$(grep -c 'forward tcp:9000 tcp:9000' "$LOG")" -ge 2 ] && r=1 || r=0
chk $r "守护重建了 forward（forward 命令被调用 ≥2 次）"

grep -q "forward 已重建" "$MOCK/out.txt" && r=1 || r=0
chk $r "重建成功并给出提示"

grep -q "已清理" "$MOCK/out.txt" && r=1 || r=0
chk $r "退出时触发 cleanup"

grep -qE "^adb .*forward --remove tcp:9000" "$LOG" && r=1 || r=0
chk $r "清理时执行了 forward --remove"

echo
echo "--- 实际发出的调用（去重）---"
sort -u "$LOG" | sed 's/^/    /'

echo
echo "================================================================"
echo " 结果: $PASS 通过 / $FAIL 失败   (脚本退出码 $RC)"
echo "================================================================"
rm -rf "$MOCK"
[ "$FAIL" -eq 0 ] || exit 1
