# 发布与更新

客户端支持**应用内自动更新**：你发布新版后，用户下次打开 App 就会看到更新横幅，点一下即可升级。

## 一条命令发布

```bash
cd ~/代码/ueasy
./release.sh 1.2.1 "修复内容第一行
第二行"
```

脚本会自动完成：

1. `versionCode` +1、`versionName` 改成你传的值
2. 构建 APK
3. 更新分发仓库 `~/代码/ueasy-dist`（APK + version.json）并 push
4. 打 tag、创建 GitHub Release（附 APK）

## 为什么用 Release API

客户端检查更新的顺序：

| 顺序 | 源 | 特性 |
|---|---|---|
| 1 | `api.github.com/.../releases/latest` | **无 CDN 缓存，发布即生效** |
| 2 | `cdn.jsdelivr.net/gh/...` | 国内快，但有缓存 |
| 3 | `fastly.jsdelivr.net` / `gcore.jsdelivr.net` | 备用节点 |
| 4 | `raw.githubusercontent.com` | 最后兜底 |

初版只用 raw.githubusercontent.com，实测手机端返回 **HTTP 503**（国内限流），检查更新直接失败。改成 API 优先后实测通过。

## 客户端行为

- 启动 2.5 秒后静默检查，有新版本才显示横幅，不打扰
- 横幅显示「发现新版本 x.y.z，点右侧更新」
- 点「更新」→ 下载（显示百分比）→ 自动调起系统安装器
- 用户只需点一次「安装」
- 全程写日志：`/sdcard/Download/bleapk-logs/`

## 实测记录（v1.1.0 → v1.2.0）

```
检查更新 API https://api.github.com/repos/.../releases/latest
API 可用: 1.2.0 https://github.com/.../releases/download/v1.2.0/ueasy.apk
版本比较 当前=1.1.0 远端=1.2.0 更新=true
有新版本，交给界面展示
开始下载 ...
下载完成 ... size=284698
```

下载文件 MD5 与仓库文件完全一致（`a6cb31ba...`），安装后版本变为 1.2.0。

## 注意事项

- **不要手动改 `versionCode`**，脚本会自增
- 分发仓库 `ueasy-dist` 是 public（更新检查需要），只放 APK 和清单，**不含源码**
- 源码仓库 `ueasy` 保持 private
- 包名 `com.ueasy.client` 与原版 U易 共存，签名固定（`app/blekey.jks`），否则无法覆盖安装
- GitHub Release 下载链接在国内偶有波动，若用户反馈下载慢，可把 `version.json` 的 url 换成 jsDelivr 地址（但需接受缓存延迟）
