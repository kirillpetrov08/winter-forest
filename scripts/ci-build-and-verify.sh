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
import glob, pathlib, re, sys, xml.etree.ElementTree as ET
label = sys.argv[1]
lines = pathlib.Path(sys.argv[2]).read_text(errors="replace").splitlines()

# 1) Kotlin/Java compile errors — по одной аннотации на файл/строку
error_pat = re.compile(r"^[ew]: file://.+/winter-forest/(?:winter-forest/)?(.+?):(\d+)(?::\d+)?\s+(.*)$")
emitted = 0
for ln in lines:
    m = error_pat.match(ln)
    if m and emitted < 8:
        fpath, line_no, msg = m.group(1), m.group(2), m.group(3)
        msg = msg.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error file={fpath},line={line_no},title=Compile::{msg}")
        emitted += 1

# 2) Упавшие юнит-тесты: имя + сообщение из JUnit XML
for xml in sorted(glob.glob("app-winterforest/build/test-results/**/*.xml", recursive=True)):
    try:
        tree = ET.parse(xml)
    except Exception:
        continue
    for case in tree.iter("testcase"):
        for tag in ("failure", "error"):
            node = case.find(tag)
            if node is None:
                continue
            msg = (node.get("message") or node.text or "").strip().replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            cls = case.get("classname") or ""
            name = case.get("name") or ""
            f = (cls.split(".")[-1] or "test") + ".kt"
            print(f"::error file=app-winterforest/src/test/kotlin/com/ayaks/winterforest/{f},title=TEST FAILED::{cls} > {name} -> {msg[:600]}")

# 3) Итоговая сводка
failed_tests = [ln for ln in lines if re.search(r"> .+ FAILED$", ln.strip()) or "tests completed" in ln]
interesting = [ln for ln in lines if any(k in ln for k in ("FAILURE:", "What went wrong:", "e: ", "Unresolved", "Cannot infer"))]
tail = [ln for ln in lines if ln.startswith("> Task") or ln.startswith("BUILD")][-6:]
snippet = "\n".join(failed_tests[:12] + interesting[:10] + ["--- TAIL ---"] + tail)
encoded = snippet[:6000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
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
