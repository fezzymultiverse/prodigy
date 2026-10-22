#!/usr/bin/env bash
# S.O.I · Bojack Security · makes ONE permanent signing key and stores it as GitHub secrets.
# Every APK built after this installs over the previous one as a normal update.
# Needs: pkg install openjdk-17 gh coreutils   (and gh auth login once)
set -uo pipefail
TMP="${TMPDIR:-/tmp}"
say() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { say "❌ missing $1. Run: pkg install $2"; exit 1; }; }
need keytool openjdk-17
need gh gh
need base64 coreutils
gh auth status >/dev/null 2>&1 || { say "❌ not logged in. Run: gh auth login"; exit 1; }
say "⚠ If your CURRENT app was built with the DEBUG_KEYSTORE_B64 secret, you may keep using that."
say "  Adding a new release key changes the signature: you must export a backup, uninstall once, install, import."
printf 'Continue and create the release key? (yes/no): '
read -r OK
[ "$OK" = "yes" ] || { say "Cancelled."; exit 0; }
KD="$HOME/.prodigy-signing"
mkdir -p "$KD"
chmod 700 "$KD"
KS="$KD/release.keystore"
ENVF="$KD/release.env"
ALIAS="prodigy"
if [ -f "$KS" ] && [ -f "$ENVF" ]; then
  say "ℹ A key already exists in $KD, re-using it (never lose this folder)."
  PASS="$(grep '^PASS=' "$ENVF" | head -1 | cut -d= -f2-)"
else
  PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Grant Festers, OU=S.O.I, O=Bojack Security, L=Cape Town, C=ZA" || { say "❌ keytool failed"; exit 1; }
  printf 'PASS=%s\nALIAS=%s\n' "$PASS" "$ALIAS" > "$ENVF"
  chmod 600 "$ENVF" "$KS"
  say "🔑 key created in $KD"
fi
DEF=""
if git -C "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" remote get-url origin >/dev/null 2>&1; then
  DEF="$(git -C "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" remote get-url origin | sed -E 's#^https://github.com/##; s#\.git$##')"
fi
printf 'GitHub repo as owner/name [%s]: ' "$DEF"
read -r REPO
[ -n "$REPO" ] || REPO="$DEF"
[ -n "$REPO" ] || { say "❌ repo needed"; exit 1; }
base64 -w0 "$KS" | gh secret set RELEASE_KEYSTORE_B64 --repo "$REPO" || { say "❌ could not set secret"; exit 1; }
printf '%s' "$PASS" | gh secret set RELEASE_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$PASS" | gh secret set RELEASE_KEY_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set RELEASE_KEY_ALIAS --repo "$REPO"
say "✅ four secrets stored in $REPO"
say ""
say "Certificate fingerprint (give this to the Android Developer Console):"
keytool -list -v -keystore "$KS" -alias "$ALIAS" -storepass "$PASS" 2>/dev/null | grep -E 'SHA1|SHA256' | head -2
say ""
printf 'Copy a safety backup of the key to your phone Download folder? (yes/no): '
read -r CP
if [ "$CP" = "yes" ]; then
  if [ -d "$HOME/storage/downloads" ]; then
    mkdir -p "$HOME/storage/downloads/Prodigy-signing-backup"
    cp "$KS" "$ENVF" "$HOME/storage/downloads/Prodigy-signing-backup/"
    say "💾 saved to Download/Prodigy-signing-backup (move it to Drive, then delete it from Download)"
  else
    say "Run termux-setup-storage first, then run this script again."
  fi
fi
say "Done. Lose this key and installed apps can never update again, so keep the backup safe."
