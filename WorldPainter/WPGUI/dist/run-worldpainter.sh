#!/usr/bin/env bash
# ===========================================================================
#  WorldPainter fork launcher (Linux / macOS)
#
#  Usage: place this script next to worldpainter-fork-<version>-fat.jar and
#         run ./run-worldpainter.sh (chmod +x first if necessary).
#
#  Requirements: a Java 17 (or newer) runtime in PATH or under JAVA_HOME.
#    Get one at https://adoptium.net/temurin/releases/?version=17
# ===========================================================================

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# Pick the JVM: JAVA_HOME wins, else fall back to the PATH "java".
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN="java"
fi

# Find the fat JAR.
JAR=""
for f in "$SCRIPT_DIR"/worldpainter-fork-*-fat.jar; do
    if [[ -f "$f" ]]; then
        JAR="$f"
        break
    fi
done
if [[ -z "$JAR" ]]; then
    echo "Could not find worldpainter-fork-*-fat.jar next to this launcher." >&2
    echo "Place this launcher in the same directory as the fat JAR and try again." >&2
    exit 1
fi

# --add-opens flags are required because jide-oss 3.6.18 reflects into the
# JDK look-and-feel internals to install its style extensions, and the JDK
# 17 module system blocks that without explicit grants.
exec "$JAVA_BIN" \
    --add-opens=java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED \
    --add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED \
    --add-opens=java.desktop/com.sun.java.swing.plaf.motif=ALL-UNNAMED \
    --add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED \
    --add-opens=java.desktop/javax.swing.plaf.synth=ALL-UNNAMED \
    -Xmx4g \
    -jar "$JAR"
