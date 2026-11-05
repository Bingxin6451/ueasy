#!/bin/bash
set -e
cd "$(dirname "$0")"
AJAR=/tmp/android-all.jar
R8=/tmp/r8.jar
APKTOOL=/tmp/apktool.jar
JDK=/opt/homebrew/opt/openjdk@27/bin
export PATH="$JDK:$PATH"

# 1. 编译
rm -rf classes dexout out.apk res.zip
mkdir -p classes dexout
javac -source 11 -target 11 -classpath "$AJAR" -d classes MainActivity.java UiKit.java LogSink.java
echo "[OK] javac"

# 2. dex via r8(d8)
java -cp "$R8" com.android.tools.r8.D8 --lib "$AJAR" --release --min-api 24 --output dexout $(find classes -name '*.class')
echo "[OK] d8"

# 3. 定位 aapt2：优先 $AAPT2，其次 build-tools，最后从 apktool.jar 提取
if [ -n "$AAPT2_BIN" ] && [ -x "$AAPT2_BIN" ]; then
  AAPT2="$AAPT2_BIN"
elif [ -x /tmp/btools/android-14/aapt2 ]; then
  AAPT2=/tmp/btools/android-14/aapt2
elif [ -f "$APKTOOL" ]; then
  mkdir -p apkbin && cd apkbin
  unzip -o -q "$APKTOOL" "prebuilt/macosx/*"
  AAPT2="$(pwd)/$(find . -name 'aapt2*' -type f | head -1)"
  chmod +x "$AAPT2"
  cd ..
else
  echo "找不到 aapt2，请设置 AAPT2_BIN"; exit 1
fi
echo "[OK] aapt2 -> $AAPT2"

# 4. aapt2 compile & link
if [ -d res ]; then
  "$AAPT2" compile --dir res -o res.zip
  "$AAPT2" link -o out.apk -I "$AJAR" -R res.zip --manifest AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 23
else
  "$AAPT2" link -o out.apk -I "$AJAR" --manifest AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 23
fi
echo "[OK] aapt2 link"

# 5. 塞 dex
cd dexout && zip -q ../out.apk classes.dex && cd ..
echo "[OK] dex-in-apk"

# 6. 签名
if [ ! -f blekey.jks ]; then
  keytool -genkeypair -keystore blekey.jks -alias ble -keyalg RSA -keysize 2048 -validity 3650 -storepass blepass123 -keypass blepass123 -dname "CN=UEasy"
fi
cp out.apk ueasy.apk
jarsigner -keystore blekey.jks -storepass blepass123 -keypass blepass123 ueasy.apk ble
echo "[OK] sign"
ls -la ueasy.apk
