#!/usr/bin/env bash
# Downloads / builds the native cores and places them in jniLibs as lib*.so
set -euo pipefail
OUT="$PWD/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
mkdir -p "$OUT"

echo "== sing-box =="
TAG=$(gh api repos/SagerNet/sing-box/releases --paginate -q '.[].tag_name' \
      | grep -E '^v1\.11\.[0-9]+$' | sort -V | tail -1)
VER=${TAG#v}
echo "using sing-box $TAG"
if curl -fL "https://github.com/SagerNet/sing-box/releases/download/$TAG/sing-box-$VER-android-arm64.tar.gz" -o "$TMP/sb.tgz"; then :; else
  echo "android build missing, falling back to linux-arm64"
  curl -fL "https://github.com/SagerNet/sing-box/releases/download/$TAG/sing-box-$VER-linux-arm64.tar.gz" -o "$TMP/sb.tgz"
fi
tar -xzf "$TMP/sb.tgz" -C "$TMP"
cp "$(find "$TMP" -type f -name sing-box | head -1)" "$OUT/libsingbox.so"

echo "== warp-plus =="
URL=$(gh api repos/bepass-org/warp-plus/releases/latest --jq '.assets[].browser_download_url' \
      | grep -i 'linux-arm64' | grep -i '\.zip$' | head -1)
echo "using $URL"
curl -fL "$URL" -o "$TMP/warp.zip"
unzip -o "$TMP/warp.zip" -d "$TMP/warp" >/dev/null
cp "$(find "$TMP/warp" -type f -name 'warp-plus*' ! -name '*.zip' | head -1)" "$OUT/libwarp.so"

echo "== hev-socks5-tunnel (JNI) =="
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
mkdir -p "$TMP/hev/jni"
git clone --recursive --depth 1 https://github.com/heiher/hev-socks5-tunnel "$TMP/hev/jni/hev-socks5-tunnel"
echo 'include $(call all-subdir-makefiles)' > "$TMP/hev/jni/Android.mk"
( cd "$TMP/hev" && "$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk \
    APP_ABI=arm64-v8a APP_PLATFORM=android-24 NDK_LIBS_OUT="$TMP/hev/libs" NDK_OUT="$TMP/hev/obj" )
cp "$TMP/hev/libs/arm64-v8a/libhev-socks5-tunnel.so" "$OUT/"

chmod +x "$OUT"/*.so || true
ls -la "$OUT"
