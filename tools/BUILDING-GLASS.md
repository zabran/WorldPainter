# Building docking-frames-ext-glass locally

Our fork uses the **Glass** extension for DockingFrames as its tab painter. The
extension is open source (LGPL) but never published to Maven Central, so anyone
building this fork from a clean checkout has to build it from source once.
This is a one-time, ~30-second setup.

## Prerequisites

- JDK 17 at `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` (or
  edit the paths below to match your install)
- Portable Maven 3.9.15 at `tools\apache-maven-3.9.15` (already in this folder)
- `~/.m2/toolchains.xml` configured for JDK 17 (already set up)

## Step 1 — Clone the DockingFrames source

```powershell
cd <repo-root>\tools
git clone --depth 1 --filter=blob:none `
    "https://github.com/Benoker/DockingFrames.git" df-source
```

## Step 2 — Replace the extension's `pom.xml`

Upstream's pom inherits from a parent (`docking-frames-base:1.1.3p1`) that
isn't published to Maven Central. Overwrite `df-source\docking-frames-ext-glass\pom.xml`
with this standalone version (already pinned to our 1.1.1 core/common):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>org.dockingframes</groupId>
    <artifactId>docking-frames-ext-glass</artifactId>
    <version>1.1.1-local</version>
    <packaging>jar</packaging>
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <build>
        <sourceDirectory>src</sourceDirectory>
        <resources>
            <resource>
                <directory>src</directory>
                <includes>
                    <include>glass/eclipse/theme/images/*.*</include>
                </includes>
            </resource>
        </resources>
    </build>
    <dependencies>
        <dependency>
            <groupId>org.dockingframes</groupId>
            <artifactId>docking-frames-common</artifactId>
            <version>1.1.1</version>
        </dependency>
        <dependency>
            <groupId>kux</groupId>
            <artifactId>glasslib</artifactId>
            <version>1.0</version>
        </dependency>
    </dependencies>
</project>
```

## Step 3 — Install the bundled `glasslib-1.0.jar`

The extension transitively depends on `kux:glasslib:1.0`, which the upstream
maintainer has not published to Maven Central. The jar is bundled in the
extension's source tree:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;<repo-root>\tools\apache-maven-3.9.15\bin;$env:Path"

# Copy to a path without spaces (Maven --% has trouble with spaces)
Copy-Item `
    "<repo-root>\tools\df-source\docking-frames-ext-glass\repo\kux\glasslib\1.0\glasslib-1.0.jar" `
    "$env:TEMP\glasslib\glasslib-1.0.jar" -Force

cd $env:USERPROFILE
mvn --% install:install-file -Dfile=C:/Users/<you>/AppData/Local/Temp/glasslib/glasslib-1.0.jar -DgroupId=kux -DartifactId=glasslib -Dversion=1.0 -Dpackaging=jar
```

## Step 4 — Build and install ext-glass

```powershell
cd <repo-root>\tools\df-source\docking-frames-ext-glass
mvn install -DskipTests=true
```

Look for `BUILD SUCCESS`. The artifact is now installed as
`org.dockingframes:docking-frames-ext-glass:1.1.1-local` in your local
`~/.m2/repository`, which is exactly what `WorldPainter/WPGUI/pom.xml`
references.

## Step 5 — Build WP

```powershell
cd <repo-root>\WorldPainter
mvn install -DskipTests=true
```

If WP's WPGUI module compiles, you're done. Run with
`mvn exec:exec` from `WPGUI/`.

---

If we ever ship this fork as a release, the right next step is to bundle the
ext-glass jar with our distribution rather than asking the user to build it.
For now, "developer setup" is fine.
