# WorldPainter: the LollyWood fork

An unofficial fork of [WorldPainter](https://github.com/Captain-Chaos/WorldPainter) by Pepijn Schmitz (Captain Chaos), made for a
friend's large maps. Not affiliated with or supported by upstream; report problems with this fork here, not there. Licence: GPLv3,
as upstream (`LICENSE`).

## What is different (branch `lollywood`, version `2.26.1-fork`)
- **No JIDE.** The commercial JIDE Docking Framework is replaced by the open-source DockingFrames (LGPL) with its Glass tab
  painter, so the fork builds without a JIDE licence. A layout saved by release WorldPainter is imported (panel places, floating
  and auto-hidden panels); the fork keeps its own layout data beside JIDE's, so release WorldPainter's layout stays untouched.
- **Large worlds:** lazy region paging (`PagingTileMap`, a lazy `.world` archive, a RAM budget), saving that releases the archive
  before the backup rename, and the performance work of upstream draft PR #478 (with two small patches to pepsoft Utils).
- **Fixes:** the autosave NullPointerException of upstream issue #506 (`Tile.prepareForSaving`), and others in the commit log.
- **One shared configuration:** the version is `2.26.1-fork`, not a snapshot, so it uses release WorldPainter's configuration
  folder and needs no migration.

## Building
JDK 17 (a Maven toolchain of type jdk, version 17) and Maven 3.9. Two dependencies are not in public Maven repositories and are
built once from source:
1. DockingFrames' Glass extension: `tools/BUILDING-GLASS.md`.
2. pepsoft Utils `2.0.2-local` (upstream Utils with two patches for PR #478): `tools/BUILDING-PEPSOFT-UTILS.md`, or apply
   `tools/pepsoft-utils.patch` to a clone of https://github.com/Captain-Chaos/Utils.
Then, from `WorldPainter/`: `mvn -pl WPGUI -am -Pdist package -DskipTests=true` gives one executable JAR
(`WPGUI/target/worldpainter-fork-2.26.1-fork-fat.jar`) with the launchers in `WPGUI/dist/`. Unit tests of the paging code:
`tools/run-unit-tests.sh`.
