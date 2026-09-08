#!/usr/bin/env bash
#
# Type-checks the Android sources without an Android SDK.
#
# The app module cannot be compiled without the SDK, so this runs the Kotlin
# compiler over app/ and audio-core/ with only the Kotlin stdlib on the
# classpath. Everything from android.* and androidx.* is then unresolved, which
# is expected and filtered out.
#
# The filtering is the point, and it has to be done carefully: filtering out
# every "unresolved reference" hides real ones. A reference is only ignored if
# the name is NOT declared anywhere in this project's own sources. That
# distinction is what catches, for instance, a missing import of one of our own
# classes - which a blanket filter silently swallows.
#
#   tools/check-app-sources.sh

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"
find_jar() { find "$CACHE" -name "$1" 2>/dev/null | head -1; }

STDLIB="$(find_jar 'kotlin-stdlib-2.0.21.jar')"
COMPILER="$(find_jar 'kotlin-compiler-embeddable-2.0.21.jar')"
if [ -z "$COMPILER" ] || [ -z "$STDLIB" ]; then
    echo "The Kotlin compiler is not in the Gradle cache yet." >&2
    echo "Run ./gradlew :audio-core:compileKotlin once, then try again." >&2
    exit 1
fi

CP="$COMPILER:$STDLIB"
for jar in kotlin-script-runtime-2.0.21.jar kotlin-daemon-embeddable-2.0.21.jar \
           'kotlinx-coroutines-core-jvm-*.jar' 'trove4j-*.jar' annotations-13.0.jar; do
    found="$(find_jar "$jar")"
    [ -n "$found" ] && CP="$CP:$found"
done

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -nowarn -d "$OUT/classes" -cp "$STDLIB" \
    $(find app/src/main/java audio-core/src/main/kotlin -name '*.kt') \
    > "$OUT/log" 2>&1 || true

# The type names this project declares.
#
# Types only, deliberately. Member names would be matched too eagerly: this
# project declares stop(), read() and `state`, and so does the Android
# framework, so every unresolved android.* member would look like ours. A
# missing import of one of our own types still surfaces as an unresolved
# reference to the type itself - verified by deleting one and re-running.
grep -rhoE '^\s*(class|object|interface|enum class|data class|sealed class|abstract class)\s+[A-Z][A-Za-z0-9_]*' \
    app/src/main/java audio-core/src/main/kotlin --include='*.kt' |
    awk '{print $NF}' | sort -u > "$OUT/ours"

FOUND=0
while IFS= read -r line; do
    name="$(sed -nE "s/.*unresolved reference '([^']+)'.*/\\1/p" <<<"$line")"
    if [ -n "$name" ] && grep -qxF "$name" "$OUT/ours"; then
        [ "$FOUND" -eq 0 ] && echo "Unresolved references to this project's own symbols:"
        FOUND=1
        sed -E 's|^.*/autotune/||' <<<"$line"
    fi
done < <(grep 'error:' "$OUT/log" || true)

# Anything that is not an unresolved reference is a structural problem: a syntax
# error, a return in an expression body, a bad override.
STRUCTURAL="$(grep 'error:' "$OUT/log" |
    grep -viE "unresolved reference|overrides nothing|cannot infer type|not enough information|operator' modifier|argument type mismatch|cannot access 'val File.root|none of the following candidates|overload resolution ambiguity" |
    sed -E 's|^.*/autotune/||' | sort -u || true)"

if [ -n "$STRUCTURAL" ]; then
    [ "$FOUND" -eq 0 ] && echo "Structural errors:"
    FOUND=1
    echo "$STRUCTURAL"
fi

if [ "$FOUND" -ne 0 ]; then
    echo
    echo "These are real. Android APIs are expected to be unresolved here; these are not."
    exit 1
fi

echo "app sources look sound (Android APIs unresolved as expected, nothing of ours is)"
