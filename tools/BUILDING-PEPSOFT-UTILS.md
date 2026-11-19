# Building patched pepsoft-utils locally

WP's root pom (`WorldPainter/pom.xml`) references
`org.pepsoft.utils:Utils:2.0.2-local`, a forked version of Captain-Chaos's
[pepsoft-utils](https://github.com/Captain-Chaos/Utils) repo with two small
patches required by PR #478:

- `UnsafeRandom` is made `public` (was package-private — could not be used
  from `org.pepsoft.worldpainter.Terrain` directly)
- `UnsafeRandom.fastNextInt(long, int)` and `UnsafeRandom.nextFloat()` are
  added

Anyone building this fork from a clean checkout has to rebuild and reinstall
this artifact once.

## Prerequisites

Same as for ext-glass: JDK 17, the portable Maven under `tools/`, and the
toolchain config at `~/.m2/toolchains.xml`.

## Step 1 — Clone the upstream Utils repo

```powershell
cd <repo-root>\tools
git clone --depth 1 --filter=blob:none `
    "https://github.com/Captain-Chaos/Utils.git" pepsoft-utils
```

## Step 2 — Apply the two patches

Edit `tools\pepsoft-utils\src\main\java\org\pepsoft\util\UnsafeRandom.java`:

1. Change `class UnsafeRandom` → `public class UnsafeRandom`
2. Change the package-private constructor to `public`
3. Add this static method (anywhere inside the class):

```java
/**
 * Stateless variant: applies the same LCG once to the supplied seed and returns a value in
 * {@code [0, bound)}. Drop-in replacement for {@code new UnsafeRandom(seed).nextInt(bound)}
 * but without the allocation, for hot loops that want a deterministic seed-derived sample
 * without keeping a stateful generator around.
 */
public static int fastNextInt(long seed, int bound) {
    long s = ((seed ^ MUL) * MUL + ADD) & MSK;
    int r = (int) (s >> 17); // 31 bits
    int m = bound - 1;
    if ((bound & m) == 0) {
        return (int) ((bound * (long) r) >> 31);
    }
    return r % bound;
}
```

4. Add this `nextFloat()` method (used by `Terrain.java`):

```java
/**
 * Uniformly distributed {@code float} in {@code [0.0f, 1.0f)}, advancing the generator.
 */
public float nextFloat() {
    return next(24) / ((float) (1 << 24));
}
```

5. Make `int nextInt(int bound)` `public` (was package-private).

## Step 3 — Pin the version

Edit `tools\pepsoft-utils\pom.xml`:

```xml
<version>2.0.2-local</version>
```

(was `2.0.2-SNAPSHOT` upstream)

## Step 4 — Build and install

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;<repo-root>\tools\apache-maven-3.9.15\bin;$env:Path"

cd <repo-root>\tools\pepsoft-utils
mvn install -DskipTests=true
```

Look for `BUILD SUCCESS`. The artifact is now installed as
`org.pepsoft.utils:Utils:2.0.2-local` in your local `~/.m2/repository`,
which matches the version in `WorldPainter/pom.xml`.

## Notes

- If/when Captain Chaos publishes a real `2.0.2` release that exposes
  `UnsafeRandom`, we should drop the `-local` suffix and use the upstream
  jar directly.
- The implementation of `fastNextInt` here is functionally equivalent to
  `new UnsafeRandom(seed).nextInt(bound)` but allocation-free; MCRcortex's
  unpublished version may have used a different mix function. Anyone
  benchmarking PR #478's perf claims should keep this in mind.
