# 逆向期脚本

Python 3 + bleak + pycryptodome，用于协议验证，不属于客户端运行时。

| 脚本 | 用途 |
|---|---|
| `hnh_ctl.py` | HNH 全流程：扫描 / 握手 / 开阀 / 心跳 / 结算，支持 `brute-key` |
| `laundry_recon.py` | 洗衣房设备侦察 |
| `selftest.py` | 协议自检 |

```bash
python3 -m venv .venv && .venv/bin/pip install bleak pycryptodome
.venv/bin/python tools/hnh_ctl.py scan
```
