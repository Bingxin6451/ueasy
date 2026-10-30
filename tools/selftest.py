import sys
sys.path.insert(0, '.')
from hnh_ctl import (xn_frame, xn_dec, xn_enc, xn_crc8, xn_link_frame,
                     build_frame, xor_crc)

# XN 自检
f = xn_link_frame()
print("XN link 帧:", f.hex().upper(), "len", len(f))
assert len(f) == 36, f"XN frame len {len(f)}"
body = f[:-1]
assert xn_crc8(body) == f[-1], "CRC8 mismatch"
enc = xn_enc("13041e" + "00" * 26)
assert len(enc) == 64, "AES pad"
print("AES-CBC 密文:", enc)
assert xn_dec(enc).startswith("13041E"), "AES decrypt mismatch"
print("[PASS] XN 帧构造/AES/CRC8 正确")

# HNH 复测
f2 = build_frame("A70208", "13AED54CA8289545")
print("HNH open 帧:", f2.hex().upper(), "len", len(f2))
assert f2[-1] == int(xor_crc("A70208" + "13AED54CA8289545"), 16)
print("[PASS] HNH 帧/CRC 正确")
print("ALL PASS")
