#!/usr/bin/env bash
#
# Builds an installable Autotune APK on Linux, installing the Android SDK first
# if there is not one already.
#
#   tools/build-apk.sh                 # build
#   tools/build-apk.sh --install       # build, then install over adb
#   tools/build-apk.sh --install 192.168.1.50   # ... to a TV at that address
#
# Everything lands under $ANDROID_SDK_ROOT (default ~/Android/Sdk). Nothing is
# installed system-wide and nothing needs root.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
CMDLINE_TOOLS_VERSION="${CMDLINE_TOOLS_VERSION:-11076708}"
PLATFORM="android-35"
BUILD_TOOLS="35.0.0"

INSTALL=false
TV_ADDRESS=""
for arg in "$@"; do
    case "$arg" in
        --install) INSTALL=true ;;
        --help|-h) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) TV_ADDRESS="$arg" ;;
    esac
done

say() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
die() { printf '\033[31merror: %s\033[0m\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- java check

# The build runs on whatever JVM launches Gradle. Too new is as broken as too
# old, and fails less legibly: AGP 8.7.3 targets 17 and Kotlin 2.0.21 cannot
# emit for JVM targets newer than itself, so a JDK 24+ build dies with a bare
# version number for an error message.
JAVA_MIN=17
JAVA_MAX=21

java_major_of() {
    # $1 is a JDK home; echoes its major version, or nothing if unusable.
    # Matches the version line wherever it appears: a JVM with JAVA_TOOL_OPTIONS
    # or _JAVA_OPTIONS set prints a notice first, so the first line is not
    # reliably the one with the version in it.
    local candidate="$1/bin/java"
    [ -x "$candidate" ] || return 0
    "$candidate" -version 2>&1 | sed -nE 's/.*[[:space:]]version "([0-9]+).*/\1/p' | head -1
}

find_supported_jdk() {
    local home major
    for home in \
        "${JAVA_HOME:-}" \
        /usr/lib/jvm/java-17-openjdk-* /usr/lib/jvm/java-21-openjdk-* \
        /usr/lib/jvm/temurin-17-* /usr/lib/jvm/temurin-21-* \
        /usr/lib/jvm/*17* /usr/lib/jvm/*21* \
        "$HOME"/.sdkman/candidates/java/17* "$HOME"/.sdkman/candidates/java/21* \
        /usr/java/* /opt/java/*
    do
        [ -d "$home" ] || continue
        major="$(java_major_of "$home")"
        [ -n "$major" ] || continue
        if [ "$major" -ge "$JAVA_MIN" ] && [ "$major" -le "$JAVA_MAX" ]; then
            echo "$home"
            return 0
        fi
    done
    return 1
}

SUPPORTED_JDK="$(find_supported_jdk || true)"

if [ -z "$SUPPORTED_JDK" ]; then
    CURRENT="$(command -v java >/dev/null 2>&1 &&
        java -version 2>&1 | sed -nE 's/.*[[:space:]]version "([0-9.]+).*/\1/p' | head -1 ||
        echo 'not installed')"
    die "no Java between $JAVA_MIN and $JAVA_MAX was found.
  currently on PATH: $CURRENT

  Java 17 is what CI builds with. Install it:
    Debian/Ubuntu:  sudo apt install openjdk-17-jdk
    Fedora:         sudo dnf install java-17-openjdk-devel
    Arch:           sudo pacman -S jdk17-openjdk

  A newer JDK will not do: the Android Gradle Plugin and Kotlin in this build
  are older than it, and fail confusingly rather than clearly."
fi

# Exported so the Gradle daemon starts on this JVM rather than whatever is on
# PATH. A daemon already running on a different JVM is not reused.
export JAVA_HOME="$SUPPORTED_JDK"
export PATH="$JAVA_HOME/bin:$PATH"
say "Java $(java_major_of "$JAVA_HOME") at $JAVA_HOME"

# ------------------------------------------------------------------ sdk setup

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
    say "Installing the Android command-line tools into $SDK_ROOT"
    command -v unzip >/dev/null 2>&1 || die "unzip is required (sudo apt install unzip)"
    ZIP="$(mktemp -d)/cmdline-tools.zip"
    URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
    echo "    $URL"
    curl -fL --progress-bar -o "$ZIP" "$URL" ||
        die "download failed. Newer builds are listed at https://developer.android.com/studio#command-line-tools-only - pass one as CMDLINE_TOOLS_VERSION=<number>"
    mkdir -p "$SDK_ROOT/cmdline-tools"
    rm -rf "$SDK_ROOT/cmdline-tools/latest" "$SDK_ROOT/cmdline-tools/cmdline-tools"
    unzip -q "$ZIP" -d "$SDK_ROOT/cmdline-tools"
    # The zip unpacks as cmdline-tools/; the SDK expects it at cmdline-tools/latest.
    mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
    rm -rf "$(dirname "$ZIP")"
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"

say "Accepting SDK licences"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

say "Installing platform $PLATFORM and build-tools $BUILD_TOOLS (a few hundred MB the first time)"
"$SDKMANAGER" --install "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" >/dev/null

# settings.gradle.kts only includes :app when it can find an SDK.
printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$REPO_ROOT/local.properties"
say "SDK ready at $SDK_ROOT"

# --------------------------------------------------------------------- build

cd "$REPO_ROOT"
say "Building"
./gradlew :app:assembleDebug

APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "the build finished but $APK is missing"

say "Built $(du -h "$APK" | cut -f1)"
echo "  $APK"

# ------------------------------------------------------------------- install

ADB="$SDK_ROOT/platform-tools/adb"

if [ "$INSTALL" != true ]; then
    echo
    echo "To install it on your TV:"
    echo "  1. On the TV: Settings > Device Preferences > About > click Build 7 times,"
    echo "     then Settings > Device Preferences > Developer options > USB/ADB debugging = on."
    echo "  2. Find the TV's IP: Settings > Network > (your network) > IP address."
    echo "  3. $ADB connect <tv-ip>:5555      # accept the prompt on the TV"
    echo "  4. $ADB install -r \"$APK\""
    echo
    echo "Or re-run: tools/build-apk.sh --install <tv-ip>"
    exit 0
fi

if [ -n "$TV_ADDRESS" ]; then
    case "$TV_ADDRESS" in
        *:*) TARGET="$TV_ADDRESS" ;;
        *)   TARGET="$TV_ADDRESS:5555" ;;
    esac
    say "Connecting to $TARGET"
    "$ADB" connect "$TARGET" || die "could not connect. Is ADB debugging enabled on the TV, and did you accept the prompt?"
fi

if [ -z "$("$ADB" devices | sed '1d' | grep -w device || true)" ]; then
    die "no device is connected. Run: $ADB connect <tv-ip>:5555"
fi

say "Installing"
"$ADB" install -r "$APK"

say "Launching"
"$ADB" shell monkey -p dev.autotune.tv -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 ||
    "$ADB" shell am start -n dev.autotune.tv/.ui.MainActivity

cat <<EOF

Installed. On the TV, work down the Autotune screen:
  - Grant audio capture   (needed after every reboot; Android will not persist it)
  - Grant app detection   (optional; picks the right per-app profile)

App detection can also be granted from here:
  $ADB shell cmd notification allow_listener dev.autotune.tv/dev.autotune.tv.session.PlaybackNotificationListener

Watch what it is doing:
  $ADB logcat -s StabilizerService:* PlaybackCapture:* DynamicsProcessor:* OutputMixSource:*
EOF
