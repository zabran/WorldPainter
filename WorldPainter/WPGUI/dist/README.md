# WorldPainter fork — distribution package

This folder produces a single executable JAR plus a launcher script.
Together with a Java 17+ runtime, it gives a recipient (e.g. Arag)
WorldPainter without needing Maven, IDEs, or the WP source.

## How the maintainer builds the distribution

From the repository root, with the build prerequisites (JDK 17 toolchain,
local Maven, helper deps) already installed per `BUILDING.md`:

```sh
mvn -pl WPGUI -am -Pdist package -DskipTests=true
```

The `-Pdist` profile activates the `maven-shade-plugin` config in
`WPGUI/pom.xml`. After ~30 seconds you get:

```
WorldPainter/WPGUI/target/worldpainter-fork-2.26.1-fork-fat.jar    (~80-100 MB)
```

To package for shipping:

```sh
cp WorldPainter/WPGUI/target/worldpainter-fork-*-fat.jar  worldpainter-fork-release/
cp WorldPainter/WPGUI/dist/run-worldpainter.bat           worldpainter-fork-release/
cp WorldPainter/WPGUI/dist/run-worldpainter.sh            worldpainter-fork-release/
cp WorldPainter/WPGUI/dist/README.md                      worldpainter-fork-release/
zip -r worldpainter-fork-release.zip worldpainter-fork-release/
```

Upload the zip as a [GitHub release asset](https://github.com/<your-username>/WorldPainter/releases/new)
on your fork.

## How a recipient (Arag) uses it

### Prerequisites

A Java 17 (or newer) runtime. Easiest is Adoptium Temurin 17:
- Windows: https://adoptium.net/temurin/releases/?version=17 — pick the
  `.msi` installer. Tick the "Set JAVA_HOME variable" checkbox during
  install.
- Linux: `sudo apt install openjdk-17-jre` (Debian/Ubuntu) or
  equivalent for your distro.
- macOS: `brew install --cask temurin@17` if using Homebrew.

To verify: open a terminal/PowerShell and run `java -version`. Output
should contain `17` or higher.

### Run

Download the zip from the fork's [releases page](https://github.com/<your-username>/WorldPainter/releases),
extract it anywhere, then:

- **Windows**: double-click `run-worldpainter.bat`.
- **Linux / macOS**:
  ```sh
  chmod +x run-worldpainter.sh
  ./run-worldpainter.sh
  ```

WorldPainter should appear in 5-15 seconds depending on your machine.

### What's different from the official release WorldPainter

This is a personal fork of the upstream
[Captain-Chaos/WorldPainter](https://github.com/Captain-Chaos/WorldPainter)
with the following additions (full details in the commit log under `git log`):

- **Region-wise save format** (Goal A). Large maps save 20-40× faster
  via incremental ZIP-per-region writes. Default save format is the
  new compartmentalised ZIP; Save As → Legacy is available for going
  back to the GZIP-ObjectStream original.
- **Lazy region paging** (Goal B). Optional via Preferences → Large
  Maps. Drops RAM usage ~10× on large maps by keeping only the
  regions you're working on in memory.
- **3D view performance** (Goal C). Pan is instant once tiles render;
  zoom doesn't stutter; closing the 3D view actually releases its
  cached tile images instead of leaking ~1.7 GB per open/close cycle.
- **3D view light direction picker** (Goal D1). Toolbar combobox lets
  you pick which screen edge the light comes from; choice stays
  consistent across view rotations.
- **JIDE Docking Framework swap.** The commercial JIDE library is
  replaced by DockingFrames Common + jide-oss (both open source). All
  panels still dock and float; cosmetic differences are minor.

Config compatibility: the fork shares its config directory
(`%APPDATA%\WorldPainter\` on Windows) with release WorldPainter, so
custom layers / objects / preferences carry over both ways. The fork's
own first launch will copy your existing config from release WP into
place.

## Troubleshooting

**"Java is not recognized as an internal or external command"**: the JRE
isn't in your PATH. Either install Temurin 17 (ticking the PATH option
during install) or set `JAVA_HOME` to the JRE install directory before
running the launcher.

**Window flickers and closes immediately**: open a cmd / terminal in
the folder, run the launcher from there. The error message will stay
on screen instead of disappearing with the window. Send the output to
the maintainer.

**Memory error during export**: the launcher sets `-Xmx4g` (4 GB heap)
by default. Edit the launcher and increase to `-Xmx8g` or higher if
your machine has more RAM and the map is large.
