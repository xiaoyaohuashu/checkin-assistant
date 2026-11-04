#!/usr/bin/env bash
# 把一个已经真机验证过的 APK 发布为应用内更新（v2.2.16 起）。
# 用法：scripts/publish-update.sh apk/checkin-assistant-vX.Y.Z.apk "更新内容（可多行）"
# 需要 local.properties 里有：
#   UPDATE_DIR=/var/www/toolbox/<随机目录>     # 网站目录里的发布位置
#   UPDATE_URL=https://<域名>/<随机目录>/version.json
# 先写 APK，最后原子替换 version.json，手机不会读到半成品。旧 APK 保留一个，方便回滚。
set -euo pipefail
cd "$(dirname "$0")/.."

APK="${1:?用法: $0 <apk> <更新内容>}"
NOTES="${2:-}"
prop() { grep -E "^$1=" local.properties | head -1 | cut -d= -f2- | tr -d '[:space:]'; }
DIR="$(prop UPDATE_DIR)"
[ -n "$DIR" ] || { echo "local.properties 缺少 UPDATE_DIR" >&2; exit 1; }
[ -f "$APK" ] || { echo "找不到 $APK" >&2; exit 1; }

AAPT="$(ls -1 "${ANDROID_HOME:-/root/android-sdk}"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)"
[ -x "$AAPT" ] || { echo "找不到 aapt" >&2; exit 1; }
BADGE="$("$AAPT" dump badging "$APK" | sed -n 1p)"
PKG="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGE")"
CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGE")"
NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGE")"
[ "$PKG" = "com.checkinassistant" ] || { echo "包名不对: $PKG" >&2; exit 1; }

FILE="checkin-assistant-v$NAME.apk"
mkdir -p "$DIR"
cp "$APK" "$DIR/$FILE.tmp" && mv "$DIR/$FILE.tmp" "$DIR/$FILE"
SHA="$(sha256sum "$DIR/$FILE" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$DIR/$FILE")"

python3 - "$DIR/version.json.tmp" "$CODE" "$NAME" "$FILE" "$SHA" "$SIZE" "$NOTES" <<'PY'
import json, sys
out, code, name, apk, sha, size, notes = sys.argv[1:]
with open(out, "w", encoding="utf-8") as f:
    json.dump({"versionCode": int(code), "versionName": name, "apk": apk,
               "sha256": sha, "size": int(size), "notes": notes}, f, ensure_ascii=False, indent=2)
PY
mv "$DIR/version.json.tmp" "$DIR/version.json"
chmod 644 "$DIR/$FILE" "$DIR/version.json"

# 只保留当前和上一个 APK
ls -1t "$DIR"/checkin-assistant-v*.apk | tail -n +3 | xargs -r rm -f
echo "已发布 v$NAME (versionCode $CODE) → $DIR"
cat "$DIR/version.json"
