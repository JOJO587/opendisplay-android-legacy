#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
协议逻辑自测 —— 推 GitHub 编译前先在本地跑通。

覆盖规范里的关键要求与最容易踩的坑：
  section 3  分帧：长度前缀、粘包、拆包
  section 4  demux 启发式，含 telemetry 前缀这个高危边界
  section 5.1 Annex B 起始码定位、SPS/PPS 提取
  section 7  坐标空间（touch 归一化 / scroll 用像素）

运行： python3 tools/test_protocol.py
"""

import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from protocol_ref import (
    frame, frame_json, read_length, classify, TYPE_VIDEO, TYPE_CONTROL,
    find_annexb_start, extract_parameter_sets, Deframer,
    hello, ping, keyframe_request, touch, scroll,
    NAL_SPS, NAL_PPS, NAL_IDR, NAL_SLICE, nalu_type,
)

PASS, FAIL = [], []


def check(name, cond, extra=""):
    if cond:
        PASS.append(name)
        print(f"  [OK]   {name}")
    else:
        FAIL.append(name)
        print(f"  [FAIL] {name} {extra}")


def mk_nalu(nal_type, payload=b"\x01\x02\x03"):
    """构造一个带 4 字节起始码的 NALU（内容为占位字节，只保证 type 位正确）"""
    return b"\x00\x00\x00\x01" + bytes([0x40 | (nal_type & 0x1F)]) + payload


print("=" * 68)
print("OpenDisplay pv3 协议自测")
print("=" * 68)

# ---------------------------------------------------------------- section 3
print("\n[1] 分帧 framing (section 3)")

p = b"hello"
f = frame(p)
check("长度前缀为大端 4 字节", f[:4] == b"\x00\x00\x00\x05", f[:4].hex())
check("payload 原样附加", f[4:] == p)
check("read_length 回读一致", read_length(f) == len(p))

big = b"x" * 70000
check("超过 64KB 的帧长度正确", read_length(frame(big)) == 70000)

# 粘包：两帧挤在一个 read 里
d = Deframer()
d.feed(frame_json('{"type":"ping","t":1}') + frame_json('{"type":"kf"}'))
out = list(d.frames())
check("粘包能拆成 2 帧", len(out) == 2, f"got {len(out)}")
check("粘包第 1 帧内容正确", out and json.loads(out[0])["type"] == "ping")
check("粘包第 2 帧内容正确", len(out) > 1 and json.loads(out[1])["type"] == "kf")

# 拆包：一帧分 5 次到达
d = Deframer()
payload = b"y" * 500
raw = frame(payload)
for i in range(0, len(raw), 97):
    d.feed(raw[i:i + 97])
out = list(d.frames())
check("拆包能重组出 1 帧", len(out) == 1, f"got {len(out)}")
check("拆包重组内容正确", out and out[0] == payload)

# ---------------------------------------------------------------- section 4
print("\n[2] demux 启发式 (section 4) —— 高危边界")

# (a) 普通控制消息
check("纯 JSON 控制消息 → CONTROL",
      classify(b'{"type":"pong","t":123,"mt":456}') == TYPE_CONTROL)

# (b) cursorImg：base64 PNG，无 NUL，<32768，首字节 '{'
cursor_img = b'{"type":"cursorImg","nw":0.02,"nh":0.03,"ax":0,"ay":0,"png":"' + b"A" * 20000 + b'"}'
check("cursorImg(base64, 无NUL) → CONTROL",
      classify(cursor_img) == TYPE_CONTROL)

# (c) 关键边界：video 帧带 telemetry JSON 前缀
#     以 '{' 开头，但后面有 00 00 00 01 → 含 NUL → 必须是 VIDEO
tele = b'{"cap":1756789012345,"snd":1756789012399}'
video = tele + mk_nalu(NAL_SPS) + mk_nalu(NAL_PPS) + mk_nalu(NAL_IDR)
check("★ telemetry 前缀 + 视频 → VIDEO（不能误判成 JSON）",
      classify(video) == TYPE_VIDEO,
      f"len={len(video)} first={chr(video[0])}")

# (d) 纯视频帧（无 telemetry）
pure_video = mk_nalu(NAL_SPS) + mk_nalu(NAL_PPS) + mk_nalu(NAL_IDR)
check("纯 Annex B 视频帧 → VIDEO", classify(pure_video) == TYPE_VIDEO)

# (e) 超大 JSON（>= 32768）按规范也走视频路径
huge_json = b'{"type":"x","d":"' + b"A" * 40000 + b'"}'
check("超长 JSON(>=32768) → VIDEO（规范规定的上限行为）",
      classify(huge_json) == TYPE_VIDEO)

# (f) 非 '{' 开头的小 payload
check("非 '{' 开头 → VIDEO", classify(b"\x01\x02\x03") == TYPE_VIDEO)

# (g) 含 NUL 的 JSON（理论上不该出现）→ VIDEO
check("含 NUL 的 '{' 开头数据 → VIDEO",
      classify(b'{"type":"x","a":"\x00"}') == TYPE_VIDEO)

# ---------------------------------------------------------------- section 5.1
print("\n[3] Annex B 解析 (section 5.1)")

sc = find_annexb_start(video)
check("能定位 telemetry 后的起始码", sc == len(tele), f"sc={sc} expect={len(tele)}")
check("起始码位置后确实是 00 00 00 01", video[sc:sc + 4] == b"\x00\x00\x00\x01")

sps, pps = extract_parameter_sets(video, sc)
check("提取到 SPS", sps is not None and nalu_type(sps, 0) == NAL_SPS)
check("提取到 PPS", pps is not None and nalu_type(pps, 0) == NAL_PPS)
check("SPS 含起始码（可直接喂 MediaCodec csd-0）",
      sps is not None and sps[:4] == b"\x00\x00\x00\x01")

# 无 telemetry 的帧
sc2 = find_annexb_start(pure_video)
check("无 telemetry 时起始码在 0", sc2 == 0)
sps2, pps2 = extract_parameter_sets(pure_video, 0)
check("无 telemetry 帧也能提取 SPS/PPS", sps2 is not None and pps2 is not None)

# 只有 slice 的非关键帧（无 SPS/PPS）
non_idr = mk_nalu(NAL_SLICE, b"\x09\x08")
sps3, pps3 = extract_parameter_sets(non_idr, 0)
check("非关键帧提不到 SPS/PPS（返回 None，不应崩溃）",
      sps3 is None and pps3 is None)

# 3 字节起始码按规范不应出现，但代码不应崩
odd = b"\x00\x00\x01\x65\x01"
check("3 字节起始码不导致异常", find_annexb_start(odd) == -1)

# ---------------------------------------------------------------- 控制消息
print("\n[4] 控制消息构造 (section 6.1 / 7)")

h = hello(1280, 800, 1.0, "Android", "abc-123", 1920, 1080)
hj = json.loads(h)
check("hello 是合法 JSON", hj["type"] == "hello")
check("hello 带 pixelsWide/High", hj["pixelsWide"] == 1280 and hj["pixelsHigh"] == 800)
check("hello 带 pv=3", hj["pv"] == 3)
check("hello 带 maxEncode 上限（老平板必须设）",
      hj["maxEncodeWide"] == 1920 and hj["maxEncodeHigh"] == 1080)

check("ping 合法", json.loads(ping(1756789012345))["type"] == "ping")
check("kf 合法", json.loads(keyframe_request())["type"] == "kf")

t = json.loads(touch("began", 0.5, 0.25, 1756789012345))
check("touch 坐标为归一化 0..1", 0 <= t["x"] <= 1 and 0 <= t["y"] <= 1)

s = json.loads(scroll(0, -10))
check("scroll 单位是像素而非归一化", s["dy"] == -10)

# touch 坐标越界应被 clamp（Java 侧实现）
print("\n[5] 端到端：模拟一次会话的字节流")

# 模拟发送端发来的：welcome + 视频帧(IDR) + cursor + ping
stream = (
    frame_json('{"type":"welcome","pv":3,"min":1}')
    + frame(tele + mk_nalu(NAL_SPS) + mk_nalu(NAL_PPS) + mk_nalu(NAL_IDR))
    + frame_json('{"type":"cursor","x":0.5,"y":0.5,"v":1,"s":1}')
    + frame(mk_nalu(NAL_SLICE, b"\x01"))
    + frame_json('{"type":"ping","drops":0,"encDrops":0}')
)
d = Deframer()
d.feed(stream)
frames = list(d.frames())
check("端到端解出 5 帧", len(frames) == 5, f"got {len(frames)}")

kinds = [classify(f) for f in frames]
check("帧类型序列 = 控制/视频/控制/视频/控制",
      kinds == [TYPE_CONTROL, TYPE_VIDEO, TYPE_CONTROL, TYPE_VIDEO, TYPE_CONTROL],
      f"got {kinds}")

types = []
for f, k in zip(frames, kinds):
    if k == TYPE_CONTROL:
        try:
            types.append(json.loads(f.decode("utf-8"))["type"])
        except Exception:
            types.append("?")
check("控制消息类型 = welcome/cursor/ping",
      types == ["welcome", "cursor", "ping"], f"got {types}")

# 视频帧解码路径模拟
vid = frames[1]
vsc = find_annexb_start(vid)
vsps, vpps = extract_parameter_sets(vid, vsc)
check("视频帧可提取 SPS/PPS 供 MediaCodec configure",
      vsps is not None and vpps is not None)

print("\n" + "=" * 68)
print(f"结果：{len(PASS)} 通过 / {len(FAIL)} 失败")
if FAIL:
    print("失败项：")
    for n in FAIL:
        print("  -", n)
    sys.exit(1)
print("全部通过 —— 协议逻辑与 PROTOCOL.md (pv 3) 一致")
print("=" * 68)
