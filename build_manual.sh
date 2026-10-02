#!/bin/bash
# Manual offline build: aapt2 -> javac -> d8 -> zipalign -> apksigner
set -e
# Portable Temurin JDK (no system java on this VM)
export JAVA_HOME="$HOME/jdk/jdk-17.0.11+9"
export PATH="$JAVA_HOME/bin:$PATH"
PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
AP="$SDK/platforms/android-34/android.jar"
SRC="$PROJ/app/src/main"
OUT="$PROJ/app/build/manual"
FINAL="$PROJ/app/build/outputs/apk/debug"

mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dexout" "$FINAL"

# Shizuku client libs (api + provider + aidl + shared AARs from Maven Central).
# Downloaded once and cached in libs/ (git-ignored); extracted jars join the
# compile and dex classpaths below. NOTE: api alone is NOT enough — its
# classes reference moe.shizuku.server.* which lives in the aidl artifact
# (missing it = NoClassDefFoundError the moment Shizuku is touched).
SHIZUKU_VER="13.1.5"
SHIZUKU_ARTS="api provider aidl shared"
mkdir -p "$PROJ/libs"
for art in $SHIZUKU_ARTS; do
  if [ ! -f "$PROJ/libs/shizuku-$art.aar" ]; then
    echo "downloading shizuku $art $SHIZUKU_VER..."
    curl -sL -o "$PROJ/libs/shizuku-$art.aar" \
      "https://repo1.maven.org/maven2/dev/rikka/shizuku/$art/$SHIZUKU_VER/$art-$SHIZUKU_VER.aar"
  fi
  mkdir -p "$PROJ/libs/extracted/$art"
  unzip -o -q "$PROJ/libs/shizuku-$art.aar" "classes.jar" -d "$PROJ/libs/extracted/$art"
done
SHIZUKU_CP=""
SHIZUKU_DEX=""
for art in $SHIZUKU_ARTS; do
  SHIZUKU_CP="$SHIZUKU_CP:$PROJ/libs/extracted/$art/classes.jar"
  SHIZUKU_DEX="$SHIZUKU_DEX $PROJ/libs/extracted/$art/classes.jar"
done
SHIZUKU_CP="${SHIZUKU_CP#:}"

if [ ! -f "$PROJ/debug.keystore" ]; then
  keytool -genkeypair -keystore "$PROJ/debug.keystore" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 -storepass android -keypass android \
    -dname "CN=Android Debug, O=Android, C=US"
  echo "generated NEW debug key"
else
  echo "reusing existing debug key - signature unchanged"
fi

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/unaligned.apk" -I "$AP" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  "$OUT/res.zip"

echo "[3/6] javac"
find "$SRC/java" -name "*.java" > "$OUT/sources.txt"
RJAVA=$(find "$OUT/gen" -name "R.java")
javac -encoding UTF-8 -source 17 -target 17 -cp "$AP:$SHIZUKU_CP" -d "$OUT/obj" \
  @"$OUT/sources.txt" "$RJAVA"

echo "[4/6] jar + d8"
jar cf "$OUT/classes.jar" -C "$OUT/obj" .
"$BT/d8" --min-api 26 --lib "$AP" --output "$OUT/dexout" "$OUT/classes.jar" \
  $SHIZUKU_DEX
cp "$OUT/dexout/classes.dex" "$OUT/classes.dex"
(cd "$OUT" && zip -q unaligned.apk classes.dex)

echo "[5/6] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[6/6] apksigner"
"$BT/apksigner" sign --ks "$PROJ/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$FINAL/app-debug.apk" "$OUT/aligned.apk"

echo "DONE: $FINAL/app-debug.apk"
ls -la "$FINAL/app-debug.apk"
