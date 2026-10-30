# U易助手 · 开源校园洗衣客户端

一个**干净、无广告、离线可用**的校园洗衣 / 烘干机控制客户端。

原版 App（`com.zdtc.ue.school`，91MB）里塞了 5 家广告 SDK（穿山甲/GroMore、百度、快手、BeiZi、逗街），开屏、信息流、账单页、结束页全是广告位。本项目不复用它，而是**从零做一个只干正事的客户端**：扫到机器 → 选程序 → 一键启动。

## 能力

| 能力 | 状态 |
|---|---|
| YQ 协议洗衣机（`0000fff0`，明文 UART） | ✅ 已实测 |
| HNH 协议烘干机（`0000fee9`，DES 会话密钥） | ✅ 已实测 |
| 按楼层机位号识别设备（`1-1` / `101` / `3F-2`） | ✅ |
| 信号强度排序、距离估算、防抖排序 | ✅ |
| 离线日志落盘（现场排障用） | ✅ |
| 云端 API（登录/下单/账单） | 🚧 接口已逆向，客户端未接 |

**不包含**：任何广告 SDK、推送、统计埋点、热修复框架。

## 目录结构

```
ueasy/
├── app/                 Android 客户端（纯 Java，无第三方依赖）
│   ├── MainActivity.java    界面 + BLE 状态机
│   ├── UiKit.java           设计系统组件
│   ├── LogSink.java         离线日志落盘
│   ├── build.sh             一键构建（javac + d8 + aapt2 + 签名）
│   └── AndroidManifest.xml
├── docs/
│   ├── 01-ble-protocols.md  三族 BLE 协议（YQ / HNH / XN）
│   ├── 02-cloud-api.md      云端接口与签名
│   └── 03-architecture.md   架构与路线图
└── tools/               逆向期脚本（Python，bleak）
```

## 构建

依赖（macOS 已验证）：

- JDK 17+（本项目用 openjdk@27）
- `/tmp/android-all.jar`（android.jar 全量，编译期）
- `/tmp/r8.jar`（d8 转 dex）
- `/tmp/apktool.jar`（只用来取里面的 aapt2）

```bash
cd app
./build.sh          # 产出 ueasy.apk，自签名
adb install -r ueasy.apk
```

## 使用

1. 走到机器旁（2 米内），打开 App
2. 首页列出附近设备，信号越好排越前，卡片显示楼层、距离、价格区间
3. 点「使用」→ 等状态变成「可以启动」
4. 选程序 → 确认金额 → 立即启动

排障：`使用帮助 → 导出日志`，日志落在 `/sdcard/Download/bleapk-logs/`。

## 路线图

- [x] YQ / HNH 双协议控制
- [x] 设备识别与楼层机位解析
- [x] 离线日志
- [ ] 云端登录 / 设备树 / 订单（接口已逆向，见 docs/02）
- [ ] 使用记录与历史账单
- [ ] 多校区配置（服务器地址、schoolKey 可配）
- [ ] 平板 / 横屏适配

## 免责

本项目为协议研究与个人学习用途，不含任何云端服务，也不代理任何计费。
设备与账号的使用后果由使用者自行承担；请遵守所在学校与运营方的规定。

## License

MIT
