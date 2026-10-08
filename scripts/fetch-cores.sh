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
    APP_ABI=arm64-v8a APP_PLATFORM=android-26 NDK_LIBS_OUT="$TMP/hev/libs" NDK_OUT="$TMP/hev/obj" )
cp "$TMP/hev/libs/arm64-v8a/libhev-socks5-tunnel.so" "$OUT/"


echo "== WARP identities (registered on the runner, outside Iran) =="
SEED="$PWD/app/src/main/assets/warp-seed"
rm -rf "$SEED"; mkdir -p "$SEED" "$TMP/idcache"
AMD=$(gh api repos/bepass-org/warp-plus/releases/latest --jq '.assets[].browser_download_url' \
      | grep -i 'linux-amd64' | grep -i '\.zip$' | head -1 || true)
if [ -n "$AMD" ]; then
  curl -fL "$AMD" -o "$TMP/warp-amd.zip"
  unzip -o "$TMP/warp-amd.zip" -d "$TMP/warp-amd" >/dev/null
  BIN=$(find "$TMP/warp-amd" -type f -name 'warp-plus*' ! -name '*.zip' | head -1)
  chmod +x "$BIN"
  "$BIN" --gool --bind 127.0.0.1:18080 --cache-dir "$TMP/idcache" > "$TMP/id.log" 2>&1 &
  PID=$!
  for i in $(seq 1 45); do
    if [ -f "$TMP/idcache/primary/wgcf-identity.json" ] && [ -f "$TMP/idcache/secondary/wgcf-identity.json" ]; then break; fi
    sleep 2
  done
  kill $PID 2>/dev/null || true
  tail -n 15 "$TMP/id.log" || true
  for d in primary secondary; do
    if [ -d "$TMP/idcache/$d" ]; then cp -r "$TMP/idcache/$d" "$SEED/"; fi
  done
  # Gool needs two accounts; fall back to the same one if the second was not created
  if [ -d "$SEED/primary" ] && [ ! -d "$SEED/secondary" ]; then cp -r "$SEED/primary" "$SEED/secondary"; fi
  test -f "$SEED/primary/wgcf-identity.json" || echo "WARNING: WARP identity was not generated"
fi
ls -R "$SEED" || true

echo "== Vazirmatn font =="
mkdir -p app/src/main/assets/fonts
F=app/src/main/assets/fonts/Vazirmatn-Regular.ttf
curl -fL "https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@master/fonts/ttf/Vazirmatn-Regular.ttf" -o "$F" \
 || curl -fL "https://github.com/rastikerdar/vazirmatn/raw/master/fonts/ttf/Vazirmatn-Regular.ttf" -o "$F" \
 || rm -f "$F"


echo "== bundled config seed (offline fallback for the collector) =="
mkdir -p app/src/main/assets
: > "$TMP/all.txt"
for u in \
  "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt" \
  "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt" \
  "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt" \
  "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/V2Ray-Config-By-EbraSha.txt" \
  "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt" \
  "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt" \
  "https://raw.githubusercontent.com/MatinGhanbari/v2ray-CONFIGs/main/subscriptions/v2ray/super-sub.txt" ; do
  if curl -fsL -m 60 "$u" -o "$TMP/src.txt"; then
    if grep -aq '://' "$TMP/src.txt"; then cat "$TMP/src.txt" >> "$TMP/all.txt"
    else base64 -d "$TMP/src.txt" >> "$TMP/all.txt" 2>/dev/null || true; fi
    echo >> "$TMP/all.txt"
  fi
done
tr -d '\r' < "$TMP/all.txt" | grep -aE '^(vless|vmess|trojan|ss|hy2|hysteria2|tuic)://' | sort -u | shuf -n 1500 > app/src/main/assets/configs-seed.txt || true
wc -l app/src/main/assets/configs-seed.txt || true

chmod +x "$OUT"/*.so || true
ls -la "$OUT"
