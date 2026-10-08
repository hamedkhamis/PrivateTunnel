#!/usr/bin/env bash
# Downloads / builds the native cores and places them in jniLibs as lib*.so
# Every network step is retried, because the GitHub API sometimes answers 502/504.
set -euo pipefail
OUT="$PWD/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
mkdir -p "$OUT" app/src/main/assets

retry() {
  local n=0
  until "$@"; do
    n=$((n + 1))
    if [ "$n" -ge 6 ]; then return 1; fi
    echo "retry $n/5: $*" >&2
    sleep $((n * 6))
  done
}
curlr() { curl -fsSL --retry 5 --retry-all-errors --retry-delay 4 "$@"; }

# ---------------------------------------------------------------- sing-box
echo "== sing-box =="
TAG=""
if LIST=$(retry gh api "repos/SagerNet/sing-box/releases?per_page=40" -q '.[].tag_name'); then
  TAG=$(echo "$LIST" | grep -E '^v1\.11\.[0-9]+$' | sort -V | tail -1 || true)
fi
echo "latest 1.11.x from API: ${TAG:-none}"

get_sb() {
  local T="$1" V="${1#v}" plat b
  for plat in android linux; do
    if curlr "https://github.com/SagerNet/sing-box/releases/download/$T/sing-box-$V-$plat-arm64.tar.gz" -o "$TMP/sb.tgz"; then
      rm -rf "$TMP/sb"; mkdir -p "$TMP/sb"
      tar -xzf "$TMP/sb.tgz" -C "$TMP/sb" || continue
      b=$(find "$TMP/sb" -type f -name sing-box | head -1)
      if [ -n "$b" ]; then cp "$b" "$OUT/libsingbox.so"; echo "sing-box $T ($plat) OK"; return 0; fi
    fi
  done
  return 1
}

GOT=0
for T in $TAG v1.11.15 v1.11.14 v1.11.13 v1.11.11 v1.11.9 v1.11.4; do
  [ -z "$T" ] && continue
  if get_sb "$T"; then GOT=1; break; fi
done
if [ "$GOT" != 1 ]; then echo "ERROR: could not download sing-box"; exit 1; fi

# ---------------------------------------------------------------- warp-plus
echo "== warp-plus =="
warp_url() {  # $1 = arm64|amd64
  local u
  u=$(retry gh api repos/bepass-org/warp-plus/releases/latest --jq '.assets[].browser_download_url' 2>/dev/null \
      | grep -i "linux-$1" | grep -i '\.zip$' | head -1 || true)
  if [ -z "$u" ]; then u="https://github.com/bepass-org/warp-plus/releases/latest/download/warp-plus_linux-$1.zip"; fi
  echo "$u"
}
URL=$(warp_url arm64)
echo "using $URL"
curlr "$URL" -o "$TMP/warp.zip"
unzip -o "$TMP/warp.zip" -d "$TMP/warp" >/dev/null
cp "$(find "$TMP/warp" -type f -name 'warp-plus*' ! -name '*.zip' | head -1)" "$OUT/libwarp.so"

# ---------------------------------------------------------------- hev-socks5-tunnel (JNI)
echo "== hev-socks5-tunnel =="
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
mkdir -p "$TMP/hev/jni"
clone_hev() {
  rm -rf "$TMP/hev/jni/hev-socks5-tunnel"
  git clone --recursive --depth 1 https://github.com/heiher/hev-socks5-tunnel "$TMP/hev/jni/hev-socks5-tunnel"
}
retry clone_hev
echo 'include $(call all-subdir-makefiles)' > "$TMP/hev/jni/Android.mk"
( cd "$TMP/hev" && "$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk \
    APP_ABI=arm64-v8a APP_PLATFORM=android-26 NDK_LIBS_OUT="$TMP/hev/libs" NDK_OUT="$TMP/hev/obj" )
cp "$TMP/hev/libs/arm64-v8a/libhev-socks5-tunnel.so" "$OUT/"

# ---------------------------------------------------------------- WARP identities (registered outside Iran)
echo "== WARP identities =="
SEED="$PWD/app/src/main/assets/warp-seed"
rm -rf "$SEED"; mkdir -p "$SEED" "$TMP/idcache"
AMD=$(warp_url amd64 || true)
if [ -n "$AMD" ] && curlr "$AMD" -o "$TMP/warp-amd.zip"; then
  unzip -o "$TMP/warp-amd.zip" -d "$TMP/warp-amd" >/dev/null
  BIN=$(find "$TMP/warp-amd" -type f -name 'warp-plus*' ! -name '*.zip' | head -1)
  chmod +x "$BIN"
  for attempt in 1 2 3; do
    rm -rf "$TMP/idcache"; mkdir -p "$TMP/idcache"
    "$BIN" --gool --bind 127.0.0.1:18080 --cache-dir "$TMP/idcache" > "$TMP/id.log" 2>&1 &
    PID=$!
    for i in $(seq 1 45); do
      if [ -f "$TMP/idcache/primary/wgcf-identity.json" ] && [ -f "$TMP/idcache/secondary/wgcf-identity.json" ]; then break; fi
      sleep 2
    done
    kill $PID 2>/dev/null || true
    tail -n 6 "$TMP/id.log" || true
    if [ -f "$TMP/idcache/primary/wgcf-identity.json" ]; then break; fi
    echo "identity attempt $attempt failed, retrying"; sleep 8
  done
  for d in primary secondary; do
    if [ -d "$TMP/idcache/$d" ]; then cp -r "$TMP/idcache/$d" "$SEED/"; fi
  done
  # Gool needs two accounts; fall back to the same one if the second was not created
  if [ -d "$SEED/primary" ] && [ ! -d "$SEED/secondary" ]; then cp -r "$SEED/primary" "$SEED/secondary"; fi
fi
test -f "$SEED/primary/wgcf-identity.json" || echo "WARNING: WARP identity was not generated"
ls -R "$SEED" || true

# ---------------------------------------------------------------- font
echo "== Vazirmatn font =="
mkdir -p app/src/main/assets/fonts
F=app/src/main/assets/fonts/Vazirmatn-Regular.ttf
curlr "https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@master/fonts/ttf/Vazirmatn-Regular.ttf" -o "$F" \
 || curlr "https://github.com/rastikerdar/vazirmatn/raw/master/fonts/ttf/Vazirmatn-Regular.ttf" -o "$F" \
 || rm -f "$F"

# ---------------------------------------------------------------- config seed (offline fallback for the collector)
echo "== bundled config seed =="
: > "$TMP/all.txt"
for u in \
  "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt" \
  "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt" \
  "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt" \
  "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/V2Ray-Config-By-EbraSha.txt" \
  "https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt" \
  "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt" \
  "https://raw.githubusercontent.com/MatinGhanbari/v2ray-CONFIGs/main/subscriptions/v2ray/super-sub.txt" ; do
  if curlr -m 60 "$u" -o "$TMP/src.txt"; then
    if grep -aq '://' "$TMP/src.txt"; then cat "$TMP/src.txt" >> "$TMP/all.txt"
    else base64 -d "$TMP/src.txt" >> "$TMP/all.txt" 2>/dev/null || true; fi
    echo >> "$TMP/all.txt"
  fi
done
tr -d '\r' < "$TMP/all.txt" | grep -aE '^(vless|vmess|trojan|ss|hy2|hysteria2|tuic)://' | sort -u | shuf -n 1500 > app/src/main/assets/configs-seed.txt || true
wc -l app/src/main/assets/configs-seed.txt || true

chmod +x "$OUT"/*.so || true
ls -la "$OUT"
