#!/usr/bin/env bash
#
# OpenDisplay Android 接收端 — USB/ADB 一键连接（参考实现对齐版）
#
# 背景 / 为什么不用 Bonjour 代理：
#   官方 Mac app (com.peetzweg.opensidecar.mac) 的发现路径有两条：
#     - WiFi/Bonjour：会过滤掉回环(127.0.0.1)地址，所以"在回环上造一个
#       _opensidecar._tcp 服务"这条老路永远 dial 不起来（一直 preparing）。
#     - Manual TCP override：从 UserDefaults 的 host/port 读一个固定端点，
#       连接时直接 dial 该 TCP 地址、绕过 usbmuxd/Bonjour（参考 josepacelli
#       README 的 "adb forward ... Mac dials plain TCP to a configured
#       host/port override"）。
#   因此 USB 的正确做法是：adb forward 把 Mac localhost:9000 映射到设备:9000，
#   再给 Mac app 写 UserDefaults host=127.0.0.1 port=9000，让它走 Manual 路径。
#
# 用法：
#   ./tools/usb_connect.sh
# 然后重启（或首次打开）Mac 上的 OpenDisplay.app，设备列表会出现
# "Manual (127.0.0.1:9000)"，状态变为 Extending to Android 即成功。
#
set -euo pipefail

BUNDLE_ID="com.peetzweg.opensidecar.mac"
PORT=9000

echo "==> 1) 设置 Mac app 的 Manual TCP override (UserDefaults，持久化)"
defaults write "$BUNDLE_ID" host -string "127.0.0.1"
defaults write "$BUNDLE_ID" port -string "$PORT"
echo "    host = $(defaults read "$BUNDLE_ID" host)"
echo "    port = $(defaults read "$BUNDLE_ID" port)"

echo "==> 2) 建立 adb forward (Mac localhost:$PORT -> 设备:$PORT)"
adb forward "tcp:$PORT" "tcp:$PORT"
adb forward --list | grep -q "tcp:$PORT tcp:$PORT" \
  && echo "    adb forward OK" \
  || { echo "    adb forward 失败，请检查 adb 与设备连接"; exit 1; }

echo
echo "==> 完成。请重启/打开 OpenDisplay.app（必须重启才能读到新的 UserDefaults）。"
echo "    设备列表应出现 'Manual (127.0.0.1:$PORT)'，状态变为 Extending to Android。"
echo "    如未自动连接，在 Mac app 设备列表里点选该 Manual 条目即可。"
