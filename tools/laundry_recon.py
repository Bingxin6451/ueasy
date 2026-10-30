#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描洗衣房所有机器 -> 云端反查设备ID/费率表/密钥"""
import asyncio
import hashlib
import json
import subprocess
import sys
import time
import urllib.parse

sys.path.insert(0, '/Users/ouyang/代码/uyxy')
from bleak import BleakScanner

tok = 'f53081de16c1fdd105f8760094af1b77'
uid = 'TlRVME9URTU='


def signed_post(path, extra=None):
    dt = int(time.time() * 1000)
    params = {'platform': 'web', 'dateTime': str(dt), 'userId': uid, 'token': tok, **(extra or {})}
    n = ''.join(f'{k}={params[k]}&' for k in sorted(params)) + tok
    params['sign'] = hashlib.sha256(n.encode()).hexdigest()
    r = subprocess.run(['curl', '-s', '--max-time', '20', '-X', 'POST',
                        f'https://h5.uyxy.xin:9000/ue/app{path}', '-d', urllib.parse.urlencode(params)],
                       capture_output=True, text=True)
    return r.stdout.strip()


def lookup(device_num):
    """batchQueryDeviceInfo by deviceNum"""
    out = signed_post('/device/batchQueryDeviceInfo', {'deviceNum': device_num, 'type': '1'})
    try:
        j = json.loads(out)
        data = j.get('data')
        if isinstance(data, dict):
            lst = data.get('listDeviceInfo') or data.get('list') or []
            if isinstance(lst, list) and lst:
                return lst[0]
        elif isinstance(data, list) and data:
            return data[0]
    except Exception:
        pass
    return None


async def main():
    print("[*] 扫描洗衣房设备 (60秒)...")
    seen = {}
    for _ in range(6):
        try:
            devs = await BleakScanner.discover(timeout=10)
        except Exception as e:
            print(f"  扫描失败 {e}")
            devs = []
        for d in devs:
            if d.name and len(d.name) == 6 and all(c in '0123456789ABCDEF' for c in d.name.upper()):
                if d.name not in seen:
                    seen[d.name] = d
                    print(f"[+] 发现 {d.name} ({d.address})")
        await asyncio.sleep(0.5)

    if not seen:
        print("[-] 没扫到6位命名设备")
        return
    print(f"\n[*] 共 {len(seen)} 台, 开始云端反查...")
    results = {}
    for name, d in seen.items():
        info = lookup(name)
        if info:
            iid = info.get('deviceInfId')
            tid = info.get('deviceTypeId')
            way = info.get('deviceWayName') or info.get('wayName')
            dev_name = info.get('deviceName')
            print(f"  {name}: deviceInfId={iid} typeId={tid} way={way} 位置={dev_name}")
            # 查费率
            out = signed_post('/device/findDeviceRate', {'deviceTypeId': str(tid), 'deviceInfId': str(iid), 'schId': '46962'})
            try:
                j = json.loads(out)
                rd = j.get('data') or {}
                key = rd.get('schoolKey')
                rates = rd.get('listDeviceRateNumber') or []
                print(f"    密钥: {key}")
                for r in rates:
                    print(f"    档位: pulse={r.get('pulse')} type={r.get('rateType')} {r.get('rateNote')} {r.get('rateMoney')}元")
            except Exception:
                print(f"    费率查询失败: {out[:150]}")
        else:
            print(f"  {name}: 云端查无")
    print("[DONE]")


asyncio.run(main())
