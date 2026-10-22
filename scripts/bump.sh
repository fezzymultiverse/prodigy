#!/usr/bin/env bash
# S.O.I · Bojack Security · raises the Capital-V version in every file the lint checks.
# Usage: bash scripts/bump.sh 78 "short note about the update"
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
TMP="${TMPDIR:-/tmp}"
say() { printf '%s\n' "$*"; }
CUR="$(grep -o "const APPV='V[0-9]*'" www/index.html | grep -o '[0-9]*')"
[ -n "$CUR" ] || { say "❌ APPV not found in www/index.html"; exit 1; }
N="${1:-}"
if [ -z "$N" ]; then
  say "Current version: V$CUR"
  printf 'New version number (just digits, e.g. %s): ' "$((CUR + 1))"
  read -r N
fi
N="${N#V}"
case "$N" in ''|*[!0-9]*) say "❌ digits only, like 78"; exit 1;; esac
[ "$N" -gt "$CUR" ] || { say "❌ V$N is not higher than V$CUR"; exit 1; }
NOTE="${2:-}"
if [ -z "$NOTE" ]; then
  printf 'One-line note for the README history: '
  read -r NOTE
fi
[ -n "$NOTE" ] || NOTE="Update"
BK="$TMP/prodigy-bump-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BK"
cp www/index.html www/sw.js package.json README.md scripts/patch-android.sh "$BK/"
say "💾 backup of the five files: $BK"
sed -i -E "s/^const APPV='V[0-9]+';/const APPV='V$N';/" www/index.html
sed -i -E "s/Prodigy V[0-9]+ · S\.O\.I\. × Bojack/Prodigy V$N · S.O.I. × Bojack/" www/index.html
sed -i -E "s#>V[0-9]+</text>#>V$N</text>#" www/index.html
sed -i -E "s/service worker V[0-9]+/service worker V$N/; s/'prodigy-v[0-9]+'/'prodigy-v$N'/" www/sw.js
sed -i -E "s/\"version\":\"[0-9]+\.0\.0\"/\"version\":\"$N.0.0\"/" package.json
sed -i -E "1s/^# Prodigy Syncing V[0-9]+/# Prodigy Syncing V$N/" README.md
sed -i -E "s/VERSION_NAME:-[0-9]+\.0\./VERSION_NAME:-$N.0./" scripts/patch-android.sh
LN="$(grep -n "^V$CUR:" README.md | head -1 | cut -d: -f1)"
if [ -n "$LN" ]; then
  printf 'V%s: **%s**\n' "$N" "$NOTE" > "$TMP/prodigy-bump-line.txt"
  sed -i "$((LN - 1))r $TMP/prodigy-bump-line.txt" README.md
else
  printf '\nV%s: **%s**\n' "$N" "$NOTE" >> README.md
fi
python3 scripts/lint.py && say "✅ V$N ready. Next: bash scripts/push.sh" || say "⚠ lint failed, originals are in $BK"
