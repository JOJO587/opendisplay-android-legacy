#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
假 Mac 发送端 —— 在没有 Mac / 没有 OpenDisplay Mac app 的情况下，
端到端验证安卓接收端的协议实现是否正确。

方向说明（规范 section 1）：接收端监听，发送端连入。
所以本脚本扮演"发送端"，主动连到安卓设备的 9000 端口。

用法：
    # 1) 先造一段测试用 H.264 裸流（只要装了 ffmpeg）
    ffmpeg -f lavfi -i testsrc=size=1280x720:rate=30 -t 15 \
           -c:v libx264 -pix_fmt yuv420p -f h264 test.h264

    # 2) 安卓上打开 OpenDisplay，看到"监听 TCP 9000"后
    python3 tools/fake_sender.py 192.168.1.50 test.h264

    # 只测协议连通性，不发视频
    python3 tools/fake_sender.py 192.168.1.50 --no-video

参数：
    --no-video   只跑握手和控制消息，验证 hello/ping/demux 是否正常
    --port N     默认 9000
"""

import sys, os, time, socket, argparse, threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from protocol_ref import (
    frame, frame_json, read_length, classify, TYPE_VIDEO, TYPE_CONTROL,
    find_annexb_start, extract_parameter_sets, Deframer, hello, ping,
    NAL_SPS, NAL_PPS, NAL_IDR,
)

START_CODE = b"\x00\x00\x00\x01"


def split_access_units(data: bytes):
    """
    把 H.264 Annex B 裸流切成access unit（一帧一片）。
    规则：遇到新的 SPS 或 IDR 就认为是新的一帧开始（与规范 5.1 一致：
    IDR 前必带 SPS/PPS，一帧的所有 slice 必须在同一个 wire frame 里）。
    """
    # 收集所有起始码位置
    pos = []
    i = 0
    while True:
        j = data.find(START_CODE, i)
        if j < 0:
            break
        pos.append(j)
        i = j + 4

    units, cur = [], []
    for k, p in enumerate(pos):
        nalu_type = data[p + 4] & 0x1F if p + 4 < len(data) else -1
        # 参数集或 IDR 出现 → 开启新的一帧
        if nalu_type in (NAL_SPS, NAL_PPS, NAL_IDR) and cur:
            units.append((cur[0], data[cur[0]:p]))
            cur = [p]
        else:
            cur.append(p)
    if cur:
        units.append((cur[0], data[cur[0]:]))
    return units


def main():
    ap = argparse.ArgumentParser(description="OpenDisplay fake sender (Mac side)")
    ap.add_argument("host", help="安卓设备的 IP")
    ap.add_argument("h264", nargs="?", help="H.264 Annex B 裸流文件")
    ap.add_argument("--port", type=int, default=9000)
    ap.add_argument("--no-video", action="store_true", help="只跑握手，不发视频")
    ap.add_argument("--fps", type=float, default=30.0, help="发送帧率")
    args = ap.parse_args()

    if not args.no_video and not args.h264:
        ap.error("需要提供 h264 文件，或使用 --no-video")

    data = None
    units = []
    if args.h264:
        with open(args.h264, "rb") as f:
            data = f.read()
        units = split_access_units(data)
        print(f"载入 {args.h264}: {len(data)} 字节, 切成 {len(units)} 个 access unit")

    print(f"连接 {args.host}:{args.port} …")
    s = socket.create_connection((args.host, args.port), timeout=10)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print("已连接")

    deframer = Deframer()
    stop = threading.Event()
    got_hello = threading.Event()
    hello_info = {}

    def reader():
        """读安卓端发来的消息：hello / ping / kf / touch / scroll"""
        while not stop.is_set():
            try:
                chunk = s.recv(65536)
                if not chunk:
                    print("\n连接关闭")
                    stop.set()
                    return
                deframer.feed(chunk)
                for payload in deframer.frames():
                    # 接收端→发送端 全部是 JSON 控制消息（规范 section 4）
                    text = payload.decode("utf-8", "replace")
                    import json
                    try:
                        msg = json.loads(text)
                    except Exception:
                        print(f"  [?] 非 JSON: {text[:60]}")
                        continue
                    t = msg.get("type")
                    if t == "hello":
                        hello_info.update(msg)
                        print(f"  [hello] {msg.get('pixelsWide')}x{msg.get('pixelsHigh')}"
                              f" scale={msg.get('scale')} pv={msg.get('pv')}"
                              f" max={msg.get('maxEncodeWide')}x{msg.get('maxEncodeHigh')}")
                        got_hello.set()
                    elif t == "ping":
                        # 规范 8.1：回 pong，原样回显 t，加上发送端时钟 mt
                        pong = ('{"type":"pong","t":%s,"mt":%d}'
                                % (msg.get("t"), int(time.time() * 1000)))
                        s.sendall(frame_json(pong))
                    elif t == "kf":
                        print("  [kf] 安卓端请求关键帧")
                    elif t == "touch":
                        print(f"  [touch] {msg.get('phase')} "
                              f"({msg.get('x'):.3f}, {msg.get('y'):.3f})")
                    elif t == "scroll":
                        print(f"  [scroll] dx={msg.get('dx')} dy={msg.get('dy')}")
                    elif t == "closing":
                        print("  [closing] 安卓端退出")
                        stop.set()
                    else:
                        print(f"  [{t}] {text[:80]}")
            except Exception as e:
                if not stop.is_set():
                    print(f"\n读线程异常: {e}")
                stop.set()
                return

    threading.Thread(target=reader, daemon=True).start()

    # 等 hello（规范 6.1：hello 必须是接收端第一条消息）
    if not got_hello.wait(timeout=5):
        print("!! 5 秒内没收到 hello —— 安卓端协议实现可能有问题")
        s.close()
        return 1
    print("hello 校验通过")

    # 回 welcome（规范 6.2）
    s.sendall(frame_json('{"type":"welcome","pv":3,"min":1}'))
    print("已发送 welcome")

    if args.no_video:
        print("\n--no-video 模式：只跑控制消息，观察安卓端是否保持连接")
        print("按 Ctrl+C 退出")
        try:
            while not stop.is_set():
                s.sendall(frame_json('{"type":"ping","drops":0,"capFps":0}'))
                time.sleep(2)
        except KeyboardInterrupt:
            pass
        s.close()
        return 0

    # 发视频
    print(f"\n开始推流（{len(units)} 帧, {args.fps} fps）… Ctrl+C 停止")
    interval = 1.0 / args.fps
    sent = 0
    t0 = time.time()
    try:
        while not stop.is_set():
            for off, unit in units:
                if stop.is_set():
                    break
                # 加上规范 5.1 的 telemetry 前缀（可选，但顺便测 demux 边界）
                tele = ('{"cap":%d,"snd":%d}'
                        % (int(time.time() * 1000), int(time.time() * 1000)))
                payload = tele.encode("utf-8") + unit
                s.sendall(frame(payload))
                sent += 1
                time.sleep(interval)
    except KeyboardInterrupt:
        print("\n停止")
    except Exception as e:
        print(f"\n发送异常: {e}")

    el = time.time() - t0
    print(f"\n共发送 {sent} 帧，用时 {el:.1f}s"
          + (f"，平均 {sent / el:.1f} fps" if el > 0 else ""))
    s.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
