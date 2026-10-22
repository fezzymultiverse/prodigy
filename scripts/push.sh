#!/usr/bin/env bash
# S.O.I · Bojack Security · Prodigy Syncing: lint, commit and push to GitHub from Termux (no root).
# GitHub Actions then builds the signed APK and publishes it as a Release.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
TMP="${TMPDIR:-/tmp}"
say() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { say "❌ missing $1. Run: pkg install $2"; exit 1; }; }
need git git
need python3 python
python3 scripts/lint.py || { say "❌ lint failed. Fix it, then run push.sh again."; exit 1; }
VER="$(grep -o "const APPV='V[0-9]*'" www/index.html | grep -oE 'V[0-9]+')"
[ -d .git ] || { git init -b main >/dev/null 2>&1 || git init >/dev/null; git checkout -B main >/dev/null 2>&1; say "🆕 git repo created"; }
if [ -z "$(git config user.name || true)" ]; then
  printf 'Git name: '
  read -r GN
  git config user.name "${GN:-philfesters}"
fi
if [ -z "$(git config user.email || true)" ]; then
  printf 'Git email: '
  read -r GE
  git config user.email "${GE:-philfesters@users.noreply.github.com}"
fi
if ! git remote get-url origin >/dev/null 2>&1; then
  printf 'GitHub repo as owner/name (example philfesters/syncing): '
  read -r REPO
  [ -n "$REPO" ] || { say "❌ repo name needed"; exit 1; }
  git remote add origin "https://github.com/$REPO.git"
fi
REMOTE="$(git remote get-url origin)"
git add -A
BAD="$(git diff --cached --name-only | grep -Ei '\.(keystore|jks|pem|key)$|(^|/)\.env|vault.*\.json$|backup.*\.json$|release\.env$' || true)"
if [ -n "$BAD" ]; then
  say "🛑 secret-looking files are staged, not pushing:"
  say "$BAD"
  git reset -q
  exit 1
fi
if git diff --cached --quiet; then
  say "ℹ nothing new to commit"
else
  printf 'Commit message [%s update]: ' "$VER"
  read -r MSG
  [ -n "$MSG" ] || MSG="$VER update"
  git commit -q -m "$VER: $MSG" && say "✅ committed: $VER: $MSG"
fi
git branch -M main
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  gh auth setup-git >/dev/null 2>&1
  git push -u origin main || { say "❌ push failed"; exit 1; }
else
  say "ℹ gh is not logged in. Using a GitHub token for this push only (it is not stored)."
  say "   Easier next time: pkg install gh && gh auth login"
  printf 'GitHub username: '
  read -r GU
  printf 'GitHub token (hidden): '
  read -r -s GT
  printf '\n'
  [ -n "$GU" ] && [ -n "$GT" ] || { say "❌ username and token needed"; exit 1; }
  AP="$(mktemp "$TMP/prodigy-askpass.XXXXXX")"
  printf '#!/usr/bin/env bash\ncase "$1" in *sername*) printf "%%s" "$PZ_U";; *) printf "%%s" "$PZ_T";; esac\n' > "$AP"
  chmod 700 "$AP"
  PZ_U="$GU" PZ_T="$GT" GIT_ASKPASS="$AP" GIT_TERMINAL_PROMPT=0 git push -u origin main
  RC=$?
  rm -f "$AP"
  unset GT PZ_T
  [ "$RC" -eq 0 ] || { say "❌ push failed (check token has repo + workflow scope)"; exit 1; }
fi
SLUG="${REMOTE#https://github.com/}"
SLUG="${SLUG%.git}"
say ""
say "🚀 pushed $VER"
say "Build progress : https://github.com/$SLUG/actions"
say "APK appears at : https://github.com/$SLUG/releases/latest"
say "Install it over the old app. Same key + higher version = in-place update, data kept."
