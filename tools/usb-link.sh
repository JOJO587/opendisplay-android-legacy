#!/usr/bin/env bash
#
# OpenDisplay ADB/USB 链路助手（Mac 端）
#
# 作用：把安卓设备的 TCP 9000 通过 USB 隧道映射到 Mac 的 127.0.0.1:9000，
#       并在本机注册一个 Bonjour 代理服务，让 OpenDisplay Mac app 能"发现"
#       这台安卓设备 —— 且强制走 USB 而不是 WiFi。
#
# 背景（为什么需要这个脚本）：
#   1. OpenDisplay 协议规定【接收端监听、发送端连入】(PROTOCOL.md §1)。
#      安卓端监听 9000，Mac 要主动连它，所以是 host→device，命令是
#          adb forward tcp:9000 tcp:9000       ← 注意是 forward，不是 reverse
#      (adb reverse 是 device→host，方向相反，用在安卓访问 Mac 服务的场景)
#
#   2. 官方 Mac app 的两种发现方式都够不到安卓：
#        - USB 走 usbmuxd 枚举 iPhone（安卓不在其中）
#        - WiFi 走 Bonjour 发现（没有手动输 IP 的入口）
#      所以这里用 dns-sd -P 在本机注册一个代理服务，指向 127.0.0.1:9000，
#      Mac app 的 Bonjour 浏览器就会看到它，连过来正好落进 adb 隧道。
#
#   3. 同时要求安卓端【关闭】自己的 mDNS 广播（本脚本以 wifi_discovery=false
#      启动 App 来实现）。否则 Mac 会同时看到两个服务：
#        设备真实 IP(走 WiFi) 和 127.0.0.1(走 USB)，可能挑错。
#
# 用法：
#   ./usb-link.sh            建立链路（前台保持，Ctrl+C 退出并自动清理）
#   ./usb-link.sh --status   查看当前 adb / forward 状态
#   ./usb-link.sh --clean    清理 forward 与已注册的代理
#
# 依赖：brew install android-platform-tools
#
set -uo pipefail

PORT="${OD_PORT:-9000}"
PKG="org.opendisplay.legacy"
SVC="_opensidecar._tcp"
NAME="${OD_NAME:-Android-USB}"

SER=""
DNS_PID=""

C_CYAN=$'\033[36m'; C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_OFF=$'\033[0m'
log() { printf '%s[usb-link]%s %s\n' "$C_CYAN" "$C_OFF" "$*"; }
ok()  { printf '%s[usb-link]%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
err() { printf '%s[usb-link]%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; }

# ---------------------------------------------------------------- 清理

cleanup() {
    log "清理中…"
    [ -n "$DNS_PID" ] && kill "$DNS_PID" 2>/dev/null && wait "$DNS_PID" 2>/dev/null
    if [ -n "$SER" ]; then
        adb -s "$SER" forward --remove "tcp:$PORT" >/dev/null 2>&1
    fi
    ok "已清理，退出。"
    exit 0
}
trap cleanup INT TERM

# ---------------------------------------------------------------- 设备

device_online() {
    [ -n "$SER" ] || return 1
    adb devices 2>/dev/null | awk -v s="$SER" 'NR>1 && $1==s && $2=="device" {found=1} END{exit !found}'
}

wait_device() {
    local waited=0
    while :; do
        local line
        line=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')
        if [ -n "$line" ]; then
            SER="$line"
            return 0
        fi
        if adb devices 2>/dev/null | grep -q "unauthorized"; then
            err "设备未授权 USB 调试 —— 请在设备上点『允许』"
        fi
        [ $((waited % 10)) -eq 0 ] && log "等待安卓设备通过 USB 连接…"
        sleep 2
        waited=$((waited + 2))
    done
}

# ---------------------------------------------------------------- 建链

start_app() {
    # wifi_discovery=false → ADB 模式，设备端不广播 mDNS
    adb -s "$SER" shell am start -n "$PKG/.MainActivity" \
        --ez wifi_discovery false >/dev/null 2>&1
    log "已启动接收端（ADB 模式，设备端 mDNS 关闭）"
}

fetch_id() {
    # App 启动时会在 logcat 打一行 OD_ID=<uuid>（ReceiverService.onCreate）
    local i id=""
    for i in $(seq 1 15); do
        id=$(adb -s "$SER" logcat -d -s ODService 2>/dev/null \
             | grep -o 'OD_ID=[0-9a-zA-Z-]*' | tail -1 | cut -d= -f2)
        [ -n "$id" ] && { echo "$id"; return 0; }
        sleep 1
    done
    return 1
}

setup_forward() {
    adb -s "$SER" forward --remove "tcp:$PORT" >/dev/null 2>&1
    if adb -s "$SER" forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
        ok "adb forward tcp:$PORT → 设备 tcp:$PORT 已建立"
    else
        err "adb forward 失败（端口被占用？试试 --clean）"
        return 1
    fi
}

setup_proxy() {
    local id="$1"
    # dns-sd -P <Name> <Type> <Domain> <Port> <Host> <IP> [TXT...]
    # 关键是最后那个 IP=127.0.0.1：让 Mac app 解析到回环地址，落进 adb 隧道。
    # 若指向 Mac 的局域网 IP，会因为 adb forward 只监听回环而连不上。
    dns-sd -P "$NAME" "$SVC" local "$PORT" \
        "$NAME.local" "127.0.0.1" "id=$id" "pv=3" >/dev/null 2>&1 &
    DNS_PID=$!
    sleep 1
    if kill -0 "$DNS_PID" 2>/dev/null; then
        ok "已注册本地 Bonjour 代理：$NAME ($SVC) → 127.0.0.1:$PORT  id=$id pv=3"
    else
        err "dns-sd 注册失败"
        return 1
    fi
}

setup_all() {
    start_app
    local id
    if id=$(fetch_id); then
        log "拿到设备 id: $id"
    else
        err "没能从 logcat 拿到 OD_ID，用占位 id 继续（不影响连接，只影响设备识别）"
        id="adb-legacy"
    fi
    setup_forward || return 1
    setup_proxy "$id" || return 1
}

# ---------------------------------------------------------------- 子命令

do_status() {
    log "adb: $(command -v adb || echo '未安装')"
    echo "--- adb devices ---"
    adb devices -l 2>/dev/null || true
    echo "--- adb forward --list ---"
    if [ -n "$SER" ]; then adb -s "$SER" forward --list 2>/dev/null; else adb forward --list 2>/dev/null; fi
    echo "--- Bonjour 上可见的 $SVC 服务 ---"
    dns-sd -B "$SVC" local 2>/dev/null | head -20 &
    local bp=$!
    sleep 2; kill "$bp" 2>/dev/null
}

do_clean() {
    log "清理 forward…"
    if [ -n "$SER" ]; then
        adb -s "$SER" forward --remove "tcp:$PORT" >/dev/null 2>&1
    else
        adb forward --remove-all >/dev/null 2>&1
    fi
    ok "完成。如果还有残留的 dns-sd 进程：pkill -f 'dns-sd -P'"
}

# ---------------------------------------------------------------- 主流程

case "${1:-}" in
    --status) do_status; exit 0 ;;
    --clean)  do_clean;  exit 0 ;;
    "")       ;;
    *)        err "未知参数: $1"; echo "用法: $0 [--status|--clean]"; exit 1 ;;
esac

command -v adb >/dev/null 2>&1 || {
    err "没找到 adb。安装：brew install android-platform-tools"
    exit 1
}

log "OpenDisplay ADB/USB 链路助手"
log "目标端口 $PORT，服务名 $NAME"

wait_device
ok "设备已连接: $SER"

setup_all || { err "建链失败"; cleanup; }

cat <<EOF

$(printf '%s[usb-link]%s' "$C_GRN" "$C_OFF") 链路已就绪。现在：
    1. 打开 Mac 上的 OpenDisplay
    2. 设备列表里应出现 "$NAME" —— 点它连接
    3. 链路走 USB：127.0.0.1:$PORT ==[adb forward]==> 设备 $PORT

  提示：Mac app 需要「本地网络」权限才能浏览 Bonjour（首次会弹窗）。
  保持本窗口开着；Ctrl+C 会自动清理 forward 与代理注册。

EOF

# 守护：USB 松动、adb server 重启都会让 forward 失效
# OD_DAEMON_LOOPS：0 = 一直守护（默认）；设为 N 则跑 N 轮后自动清理退出。
# 这个开关主要给自测用（让测试可以同步跑完），也可用于一次性建链检查。
MAX_LOOPS="${OD_DAEMON_LOOPS:-0}"
loops=0
while :; do
    if ! device_online; then
        log "设备掉线，等待重连…"
        [ -n "$DNS_PID" ] && kill "$DNS_PID" 2>/dev/null
        DNS_PID=""
        wait_device
        ok "设备重新上线: $SER"
        setup_all
    elif ! adb -s "$SER" forward --list 2>/dev/null | grep -q "tcp:$PORT"; then
        log "forward 丢失（USB 松动或 adb 重启），重建…"
        adb -s "$SER" forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 \
            && ok "forward 已重建"
    fi

    loops=$((loops + 1))
    if [ "$MAX_LOOPS" -gt 0 ] && [ "$loops" -ge "$MAX_LOOPS" ]; then
        log "达到 OD_DAEMON_LOOPS=$MAX_LOOPS 上限，准备退出"
        cleanup
    fi
    sleep 2
done
