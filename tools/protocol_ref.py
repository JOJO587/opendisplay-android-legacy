#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OpenDisplay Wire Protocol (pv 3) 参考实现 —— Python 版。

用途：
  1. 与 Android 端 Protocol.java 做等价性对照（算法逐行对应）
  2. 在推 GitHub 编译前，先在桌面验证分帧 / demux / 参数集提取逻辑
  3. fake_sender.py 复用本模块，用来在没有 Mac 的情况下端到端自测安卓端

规范依据：peetzweg/opendisplay 的 PROTOCOL.md (pv 3)
"""

PORT = 9000
SERVICE_TYPE = "_opensidecar._tcp"
PV = 3

JSON_MAX = 32768
TYPE_VIDEO = 0
TYPE_CONTROL = 1

NAL_SPS, NAL_PPS, NAL_IDR, NAL_SLICE = 7, 8, 5, 1
START_CODE = b"\x00\x00\x00\x01"


# ---------------------------------------------------------------- 分帧

def frame(payload: bytes) -> bytes:
    """[4 字节大端长度][payload]"""
    return len(payload).to_bytes(4, "big") + payload


def frame_json(s: str) -> bytes:
    return frame(s.encode("utf-8"))


def read_length(buf: bytes) -> int:
    return int.from_bytes(buf[:4], "big")


# ------------------------------------------------- section 4: demux

def classify(payload: bytes) -> int:
    """
    长度 < 32768 且 首字节 '{' 且 不含 NUL  →  JSON 控制消息
    否则                                    →  H.264 视频帧

    注意规范特别强调的边界：视频帧前面可能带 JSON telemetry 前缀
    {"cap":..,"snd":..}，它以 '{' 开头，但因为后面有 Annex B 起始码
    (00 00 00 01) 而必然含有 NUL，所以必须判为 VIDEO。
    """
    if len(payload) >= JSON_MAX or len(payload) <= 0:
        return TYPE_VIDEO
    if payload[0:1] != b"{":
        return TYPE_VIDEO
    if b"\x00" in payload:
        return TYPE_VIDEO
    return TYPE_CONTROL


# --------------------------------------- section 5.1: Annex B 处理

def find_annexb_start(p: bytes) -> int:
    """第一个 00 00 00 01 的位置；无则 -1"""
    return p.find(START_CODE)


def nalu_type(p: bytes, sc: int) -> int:
    i = sc + 4
    if i >= len(p):
        return -1
    return p[i] & 0x1F


def split_nalus(p: bytes, offset: int):
    """按 4 字节起始码切分 NALU，yield (起始码位置, NALU 含起始码的字节)"""
    i = offset
    n = len(p)
    while i < n:
        sc = p.find(START_CODE, i)
        if sc < 0:
            break
        nxt = p.find(START_CODE, sc + 4)
        end = n if nxt < 0 else nxt
        yield sc, p[sc:end]
        i = end


def extract_parameter_sets(p: bytes, offset: int):
    """提取 (SPS, PPS)，均含 4 字节起始码；缺失为 None"""
    sps = pps = None
    for sc, nalu in split_nalus(p, offset):
        t = nalu_type(p, sc)
        if t == NAL_SPS and sps is None:
            sps = nalu
        elif t == NAL_PPS and pps is None:
            pps = nalu
    return sps, pps


# ---------------------------------------- 接收端 -> 发送端 控制消息

def hello(wide, high, scale, device, ident, max_w=None, max_h=None) -> str:
    s = (f'{{"type":"hello","pixelsWide":{wide},"pixelsHigh":{high},'
         f'"scale":{scale},"device":"{device}"')
    if ident:
        s += f',"id":"{ident}"'
    s += f',"pv":{PV}'
    if max_w:
        s += f',"maxEncodeWide":{max_w}'
    if max_h:
        s += f',"maxEncodeHigh":{max_h}'
    return s + "}"


def ping(t_ms) -> str:
    return f'{{"type":"ping","t":{t_ms}}}'


def keyframe_request() -> str:
    return '{"type":"kf"}'


def touch(phase, x, y, t_ms=None) -> str:
    s = f'{{"type":"touch","phase":"{phase}","x":{x},"y":{y}'
    if t_ms is not None:
        s += f',"t":{t_ms}'
    return s + "}"


def scroll(dx, dy) -> str:
    return f'{{"type":"scroll","dx":{dx},"dy":{dy}}}'


def closing() -> str:
    return '{"type":"closing"}'


# ------------------------------------------------------ 解帧器

class Deframer:
    """
    TCP 是字节流，没有消息边界：一帧可能分多次到达，
    也可能多帧粘在一个 read 里。这里做累积 + 重组。
    """

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes):
        self.buf.extend(data)

    def frames(self):
        """产出完整帧的 payload（bytes）"""
        while True:
            if len(self.buf) < 4:
                return
            n = int.from_bytes(self.buf[:4], "big")
            if n < 0 or n >= (1 << 24):
                raise ValueError(f"bad length {n}")
            if len(self.buf) < 4 + n:
                return
            payload = bytes(self.buf[4:4 + n])
            del self.buf[:4 + n]
            yield payload
