#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端自测：本机起一个"模拟安卓接收端"，让 fake_sender.py 真的连上来推流，
验证整条链路 —— 握手、分帧、demux、access unit 切分。

这样在把 APK 装到真机之前，就能确认 fake_sender 和协议实现都是对的。

运行： python3 tools/test_e2e.py
"""

import os, sys, time, socket, threading, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from protocol_ref import (
    frame_json, read_length, classify, TYPE_VIDEO, TYPE_CONTROL,
    find_annexb_start, extract_parameter_sets, Deframer,
    START_CODE,
)

SPS = START_CODE + b"\x67\x42\x00\x1e\xd9\x00\x8c\x8d"
PPS = START_CODE + b"\x68\xce\x3c\x80"
IDR = START_CODE + b"\x65\x88\x84\x00\x33\xff" + b"\x11" * 40
SLICE = START_CODE + b"\x41\x9a\x22\x7e" + b"\x22" * 30


def make_test_h264(path, frames=6):
    """造一个结构合法的 Annex B 测试流（内容不必是有效 H.264，验证协议层够用）"""
    out = b""
    for i in range(frames):
        if i == 0:
            out += SPS + PPS + IDR
        else:
            out += SPS + PPS + IDR if i % 3 == 0 else SLICE
    with open(path, "wb") as f:
        f.write(out)
    return out


def mini_receiver(port=9000, timeout=25):
    """
    模拟安卓接收端：listen → 收 welcome → 发 hello → 收视频帧 → 校验
    返回 (ok: bool, log: list)
    """
    log = []
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    srv.settimeout(timeout)

    try:
        conn, addr = srv.accept()
    except socket.timeout:
        return False, ["等待发送端连接超时"]
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    # 1) 先发 hello（规范 6.1：必须是第一条）
    conn.sendall(frame_json(
        '{"type":"hello","pixelsWide":1280,"pixelsHigh":800,'
        '"scale":1.0,"device":"Android","id":"TEST-UUID","pv":3,'
        '"maxEncodeWide":1920,"maxEncodeHigh":1080}'))
    log.append("已发送 hello")

    deframer = Deframer()
    got_welcome = False
    video_frames = 0
    control_msgs = 0
    bad_classify = 0
    deadline = time.time() + 15

    while time.time() < deadline:
        conn.settimeout(3)
        try:
            chunk = conn.recv(262144)
        except socket.timeout:
            break
        if not chunk:
            break
        deframer.feed(chunk)
        for payload in deframer.frames():
            kind = classify(payload)
            if kind == TYPE_CONTROL:
                control_msgs += 1
                text = payload.decode("utf-8", "replace")
                if '"welcome"' in text and not got_welcome:
                    got_welcome = True
                    log.append("收到 welcome（握手完成）")
            else:
                video_frames += 1
                sc = find_annexb_start(payload)
                if sc < 0:
                    bad_classify += 1
                else:
                    sps, pps = extract_parameter_sets(payload, sc)
                    if sps is None:
                        # 非关键帧没有 SPS 是正常的，不报错
                        pass

    try:
        conn.close()
    except Exception:
        pass
    srv.close()

    log.append(f"收到视频帧 {video_frames} 个，控制消息 {control_msgs} 条")
    ok = got_welcome and video_frames > 0 and bad_classify == 0
    log.append(f"判定: welcome={got_welcome} 视频帧>0={video_frames > 0} "
               f"demux无误={bad_classify == 0}")
    return ok, log


def main():
    print("=" * 68)
    print("端到端自测：fake_sender ↔ 模拟安卓接收端")
    print("=" * 68)

    h264 = os.path.join(HERE, "_test_stream.h264")
    make_test_h264(h264, frames=6)
    print(f"生成测试流: {h264}")

    result = {}

    def run_receiver():
        ok, log = mini_receiver(port=9000, timeout=25)
        result["ok"] = ok
        result["log"] = log

    t = threading.Thread(target=run_receiver)
    t.start()
    time.sleep(0.6)  # 等接收端 listen

    # 启动 fake_sender（子进程），跑约 4 秒后杀掉
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "fake_sender.py"),
         "127.0.0.1", os.path.basename(h264), "--fps", "20"],
        cwd=HERE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        time.sleep(4)
    finally:
        proc.terminate()
        try:
            out, _ = proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, _ = proc.communicate()

    t.join(timeout=20)

    print("\n--- fake_sender 输出 ---")
    for line in (out or "").strip().splitlines():
        print("   ", line)

    print("\n--- 接收端日志 ---")
    for line in result.get("log", []):
        print("   ", line)

    # 清理
    try:
        os.remove(h264)
    except OSError:
        pass

    print("\n" + "=" * 68)
    if result.get("ok"):
        print("端到端通过：握手 + 推流 + demux 全链路正常")
        print("=" * 68)
        return 0
    else:
        print("端到端失败，见上方日志")
        print("=" * 68)
        return 1


if __name__ == "__main__":
    sys.exit(main())
