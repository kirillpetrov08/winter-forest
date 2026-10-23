#!/usr/bin/env bash
set -uo pipefail

# Setup Android SDK path on GitHub-hosted Ubuntu runners (pre-installed at /usr/local/lib/android/sdk)
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ ! -d "$ANDROID_HOME" ]; then
  echo "Android SDK not preinstalled on runner; bootstrapping command-line tools..."
  mkdir -p "$HOME/android-sdk/cmdline-tools"
  curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdtools.zip
  unzip -q /tmp/cmdtools.zip -d "$HOME/android-sdk/cmdline-tools"
  mv "$HOME/android-sdk/cmdline-tools/cmdline-tools" "$HOME/android-sdk/cmdline-tools/latest"
  export ANDROID_HOME="$HOME/android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

SDKMANAGER=""
for candidate in \
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "$ANDROID_HOME/tools/bin/sdkmanager"; do
  if [ -x "$candidate" ]; then
    SDKMANAGER="$candidate"
    break
  fi
done

if [ -n "$SDKMANAGER" ]; then
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
fi

if [ -n "${KEYSTORE_BASE64:-}" ]; then
  echo "$KEYSTORE_BASE64" | base64 --decode > "${GITHUB_WORKSPACE:-$PWD}/upload-keystore.jks"
  export KEYSTORE_PATH="${GITHUB_WORKSPACE:-$PWD}/upload-keystore.jks"
else
  echo "No KEYSTORE_BASE64 secret configured yet; building unsigned release artifacts."
fi

cleanup_keystore() {
  rm -f "${GITHUB_WORKSPACE:-$PWD}/upload-keystore.jks"
}
trap cleanup_keystore EXIT

chmod +x gradlew

run_gradle_step() {
  local label="$1"
  shift
  echo "============================================================"
  echo ">>> RUNNING: $label ($*)"
  echo "============================================================"
  "$@" > /tmp/ci_gradle_step.log 2>&1
  local rc=$?
  cat /tmp/ci_gradle_step.log
  if [ "$rc" -ne 0 ]; then
    python3 - "$label" /tmp/ci_gradle_step.log << 'PY'
import pathlib, re, sys
label = sys.argv[1]
lines = pathlib.Path(sys.argv[2]).read_text(errors="replace").splitlines()

# 1) Emit each Kotlin/Java compile error as its OWN file/line annotation (survives truncation)
error_pat = re.compile(r"^[ew]: file://.+/winter-forest/(?:winter-forest/)?(.+?):(\d+)(?::\d+)?\s+(.*)$")
emitted = 0
for ln in lines:
    m = error_pat.match(ln)
    if m and emitted < 40:
        fpath, line_no, msg = m.group(1), m.group(2), m.group(3)
        msg = msg.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error file={fpath},line={line_no},title=Kotlin compile error::{msg}")
        emitted += 1

# 2) Keep error lines + tail context so annotation stays well under 8 KB limit
interesting = [ln for ln in lines if any(k in ln for k in ("e: ", "FAILURE:", "What went wrong:", "> ", "Exception", "Error", "FAILED", "Unresolved", "Cannot"))]
snippet = "\n".join((interesting[-45:] + ["--- TAIL ---"] + lines[-55:]))[-6500:]
encoded = snippet.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title=GRADLE FAILED ({label})::{encoded}")
PY
    exit "$rc"
  fi
}

run_gradle_step "Test + Debug APK + Release APK + Release AAB" \
  ./gradlew test assembleDebug assembleRelease bundleRelease --stacktrace

python3 - << 'PY'
import glob, os
summary = []
for pattern in [
    "app-winterforest/build/outputs/apk/debug/*.apk",
    "app-winterforest/build/outputs/apk/release/*.apk",
    "app-winterforest/build/outputs/bundle/release/*.aab",
]:
    for p in sorted(glob.glob(pattern)):
        kb = os.path.getsize(p) // 1024
        summary.append(f"{p} ({kb} KB)")
encoded = "%0A".join(summary) if summary else "Built cleanly"
print(f"::notice title=ANDROID BUILD ARTIFACTS VERIFIED::{encoded}")
PY
