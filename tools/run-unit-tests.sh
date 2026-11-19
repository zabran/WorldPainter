#!/usr/bin/env bash
# Run the WPCore unit tests directly via JUnitCore.
#
# Maven surefire crashes on this project on Windows because the parent pom's
# argLine `-Duser.home=${project.build.directory}` resolves to a path containing
# spaces ("Visual Studio 2015") and `cmd.exe /X /C` can't parse it. This script
# is the workaround: it builds the project, then invokes JUnitCore directly with
# the assembled classpath.
#
# Usage:
#   tools/run-unit-tests.sh         # run all paging tests
#   tools/run-unit-tests.sh ClassA  # run a specific test class

set -euo pipefail

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot}"
PATH="$JAVA_HOME/bin:$PATH"
export JAVA_HOME PATH

# Resolve the project root, then translate to a Windows-style path because Java's
# classpath parser on Windows does not accept MSYS-style /c/Users/... paths.
PROJECT_ROOT_MSYS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    PROJECT_ROOT="$(cygpath -m "$PROJECT_ROOT_MSYS")"
else
    PROJECT_ROOT="$PROJECT_ROOT_MSYS"
fi
MVN="$PROJECT_ROOT/tools/apache-maven-3.9.15/bin/mvn"
WPCORE="$PROJECT_ROOT/WorldPainter/WPCore"

# Build everything we need.
"$MVN" -f "$PROJECT_ROOT/WorldPainter/pom.xml" -pl WPCore test-compile -q

# Resolve the dependency classpath via maven-dependency-plugin. Cache the result
# at /tmp/wpcore-test-cp.txt since this only changes when pom.xml changes.
CP_FILE="${WPCORE_TEST_CP_FILE:-/tmp/wpcore-test-cp.txt}"
if [ ! -f "$CP_FILE" ] || [ "$WPCORE/pom.xml" -nt "$CP_FILE" ]; then
    "$MVN" -f "$PROJECT_ROOT/WorldPainter/pom.xml" -pl WPCore -q \
        dependency:build-classpath \
        -DincludeScope=test \
        -Dmdep.outputFile="$CP_FILE"
fi

CLASSES="$WPCORE/target/classes"
TEST_CLASSES="$WPCORE/target/test-classes"
CP="$TEST_CLASSES;$CLASSES;$(cat "$CP_FILE")"

if [ "${1:-}" = "--debug" ]; then
    shift
    echo "PROJECT_ROOT: $PROJECT_ROOT"
    echo "TEST_CLASSES: $TEST_CLASSES"
    echo "CLASSES: $CLASSES"
    echo "CP head: ${CP:0:300}"
fi

# Default: all paging-package tests.
if [ $# -eq 0 ]; then
    TESTS=(
        org.pepsoft.worldpainter.paging.TileIndexFormatTest
        org.pepsoft.worldpainter.paging.PagingTileMapTest
    )
else
    TESTS=("$@")
fi

java -cp "$CP" org.junit.runner.JUnitCore "${TESTS[@]}"
