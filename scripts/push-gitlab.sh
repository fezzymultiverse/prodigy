#!/usr/bin/env bash
# Push this project to GitLab from Termux. GitLab CI then builds prodigy.apk.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
command -v git >/dev/null || { echo "Run: pkg install git"; exit 1; }
[ -d .git ] || { git init -b main >/dev/null; echo "git repo created"; }
[ -n "$(git config user.name || true)" ] || { printf 'Name: '; read -r N; git config user.name "${N:-philfesters}"; }
[ -n "$(git config user.email || true)" ] || { printf 'Email: '; read -r E; git config user.email "${E:-philfesters@users.noreply.gitlab.com}"; }
git remote get-url gitlab >/dev/null 2>&1 || {
  printf 'GitLab repo path (example username/syncing): '; read -r R
  [ -n "$R" ] || { echo "repo needed"; exit 1; }
  git remote add gitlab "https://gitlab.com/$R.git"
}
git add -A
git diff --cached --quiet || git commit -q -m "V89 update" && echo "committed"
git branch -M main
printf 'GitLab username: '; read -r U
printf 'GitLab access token (hidden): '; read -r -s T; printf '\n'
AP="$(mktemp)"; printf '#!/usr/bin/env bash\ncase "$1" in *sername*) printf "%%s" "$PZ_U";; *) printf "%%s" "$PZ_T";; esac\n' > "$AP"; chmod 700 "$AP"
PZ_U="$U" PZ_T="$T" GIT_ASKPASS="$AP" GIT_TERMINAL_PROMPT=0 git push -u gitlab main
RC=$?; rm -f "$AP"
[ $RC -eq 0 ] && echo "Pushed. Watch the build under Build > Pipelines in your GitLab project." || echo "Push failed. Check the token has write_repository scope."
