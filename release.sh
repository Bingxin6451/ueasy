#!/bin/bash
# 一键发布：改版本号 → 构建 → 提交分发仓库 → 打 tag → 建 Release
# 用法: ./release.sh 1.2.0 "更新说明第一行
# 第二行"
set -e
cd "$(dirname "$0")"

VER=${1:?用法: ./release.sh <版本号，如 1.2.0> [说明]}
NOTES=${2:-"$VER"}
APP=app
DIST=../ueasy-dist

# 1) 版本号：versionName 用传入值，versionCode 自增
NAME=$(grep -o 'android:versionName="[^"]*"' $APP/AndroidManifest.xml | grep -o '[0-9][^"]*')
CODE=$(grep -o 'android:versionCode="[0-9]*"' $APP/AndroidManifest.xml | grep -o '[0-9]*')
NEWCODE=$((CODE + 1))
sed -i '' "s/android:versionCode=\"$CODE\"/android:versionCode=\"$NEWCODE\"/; s/android:versionName=\"$NAME\"/android:versionName=\"$VER\"/" $APP/AndroidManifest.xml
echo "版本: $NAME($CODE) -> $VER($NEWCODE)"

# 2) 构建
(cd $APP && ./build.sh > /tmp/build.log 2>&1) || { tail -20 /tmp/build.log; exit 1; }
echo "构建完成: $(ls -lh $APP/ueasy.apk | awk '{print $5}')"

# 3) 分发仓库：更新 APK 与清单
mkdir -p $DIST
cp $APP/ueasy.apk $DIST/ueasy.apk
cat > $DIST/version.json <<EOF
{
  "versionCode": $NEWCODE,
  "versionName": "$VER",
  "url": "https://cdn.jsdelivr.net/gh/Bingxin6451/ueasy-dist@main/ueasy.apk",
  "notes": "$(echo "$NOTES" | sed 's/"/\\"/g')"
}
EOF

cd $DIST
git add -A
git -c user.name="Bingxin6451" -c user.email="175963557+Bingxin6451@users.noreply.github.com" \
    commit -q -m "release: v$VER" || echo "(无变更)"
git push -q
echo "分发仓库已推送"

# 4) Release（App 通过 API 读取，无缓存）
git tag -f "v$VER" >/dev/null 2>&1 || true
git push -f origin "v$VER" >/dev/null 2>&1 || true
gh release create "v$VER" ./ueasy.apk --title "v$VER" --notes "$NOTES" >/dev/null 2>&1 \
  && echo "Release 已创建" \
  || gh release upload "v$VER" ./ueasy.apk --clobber >/dev/null 2>&1 && echo "Release 附件已更新"

echo ""
echo "完成。客户端下次启动即可检测到 v$VER"
