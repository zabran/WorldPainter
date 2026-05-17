@echo off
REM ==========================================================================
REM  WorldPainter fork launcher (Windows)
REM
REM  Usage: drop this .bat next to worldpainter-fork-<version>-fat.jar and
REM         double-click, or run from cmd.exe in this directory.
REM
REM  Requirements: a Java 17 (or newer) runtime in PATH or under JAVA_HOME.
REM    Get one at https://adoptium.net/temurin/releases/?version=17
REM ==========================================================================

setlocal

REM Pick the JVM: JAVA_HOME wins, else fall back to the PATH "java".
if defined JAVA_HOME (
    set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_BIN=java"
)

REM Find the fat JAR. We accept any name starting with worldpainter-fork.
set "JAR="
for %%F in ("%~dp0worldpainter-fork-*-fat.jar") do (
    set "JAR=%%F"
)
if "%JAR%"=="" (
    echo Could not find worldpainter-fork-*-fat.jar in this folder.
    echo Place this launcher next to the fat JAR and try again.
    pause
    exit /b 1
)

REM --add-opens flags are required because jide-oss 3.6.18 reflects into the
REM JDK look-and-feel internals to install its style extensions, and the JDK
REM 17 module system blocks that without explicit grants.
"%JAVA_BIN%" ^
  --add-opens=java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED ^
  --add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED ^
  --add-opens=java.desktop/com.sun.java.swing.plaf.motif=ALL-UNNAMED ^
  --add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED ^
  --add-opens=java.desktop/javax.swing.plaf.synth=ALL-UNNAMED ^
  -Xmx4g ^
  -jar "%JAR%"

endlocal
