# 云端 API

服务端：`h5.uyxy.xin:9000`，前缀 `/ue/app/`。仅记录已实测字段，客户端接入前请自行复测。

## 鉴权

```
sign = sha256( sorted("k=v&k=v&") + token )
```

每个请求带：`platform` / `dateTime`（毫秒）/ `userId` / `token` / `sign`。

登录：

```
POST user/sendRegisterMsg   { tel }
POST user/register          { tel, code, type=5 }   → { userId, token }
```

## 设备相关

| 端点 | 作用 | 关键参数 |
|---|---|---|
| `device/scanTheCode` | 扫码解析 | `qrCode` = 完整二维码 URL（裸 code 会 500） |
| `device/batchQueryDeviceInfo` | 设备清单 | `type=0` 返回最近 20 台（分页参数被服务端忽略） |
| `device/queryDeviceInfo` | 单台详情 | `deviceInfId` |
| `device/findDeviceRate` | 费率 + schoolKey | `deviceTypeId` / `deviceInfId` / `schId` |
| `device/useEquipment` | 下单 | `userId` `token` `deviceInfId` `billType` `type` `theConnectionMethod` |
| `device/endEquipment` | 结束 | — |
| `device/useClothesMachine` / `successClothesMachine` / `queryClothesMachine` | 洗烘机专用 | — |
| `coupons/clothesMachinePriceV2` | 洗烘价格 | — |
| `school/querySchoolAddress` | 组织树下钻 | `deptId` 递归 |

`findDeviceRate` 返回里 `schoolKey` 是明文——这是 HNH 握手密钥的来源之一。

## 计费模型

- **水机（XN）**：设备水表 → 4G 上报 → 云端扣款。BLE 侧无法左右账单。
- **洗烘机（YQ/HNH）**：预付制，先付款后启动，无结算环节。

## 已知服务端问题

- `batchQueryDeviceInfo` 分页失效（`deviceNum`/页码被忽略）
- `scanTheCode` 只认完整 URL
- 未登录统一 `code:199`

## 客户端接入计划

1. 登录态本地加密存储（Keystore）
2. 设备树缓存（离线可看上次扫描到的机器）
3. 下单走云端、控制走 BLE —— 两条链路解耦，云端挂了仍能用 BLE 直控
