# Vendored jars in `app_pojavlauncher/libs/`

`app_pojavlauncher/build.gradle:206` wires in every jar under this directory
via `implementation fileTree(dir: 'libs', include: ['*.jar'])` — a
directory glob, not a Gradle `group:artifact:version` coordinate. Because
these jars have no declared coordinate, manifest/lockfile-based dependency
scanners (Dependabot, `gradle dependencies`, OSV-Scanner's manifest mode)
**cannot see them at all**. This document is the manual substitute: an
inventory of what's actually in each jar, identified from its own contents
(package names, `META-INF/MANIFEST.MF`), so future triage/replacement work
has somewhere to start. It does not replace a real scan — see
`.github/workflows/dependency-scan.yml` for the automated (but jar-blind
for one of these two) coverage.

There are exactly two jars here today:

## `ExagearApacheCommons.jar`

- **Size**: 474,005 bytes
- **Identification method**: `unzip -l` class listing (397 `.class` files,
  842,523 bytes uncompressed) plus timestamp metadata; there is **no**
  `META-INF/MANIFEST.MF` or Maven `pom.properties`/`pom.xml` entry in this
  jar, so there is no embedded version string to read.
- **Contents**: repackaged classes from four separate Apache Commons
  libraries, all under their real `org.apache.commons.*` packages:
  - `org.apache.commons.collections4.*` (a handful of classes only —
    `ResettableIterator`, `CompositeCollection`, iterator helpers — not the
    full library)
  - `org.apache.commons.compress.*` (the bulk of the jar — archivers for
    ar/arj/cpio/dump/jar/sevenz/tar/zip, compressors for
    bzip2/deflate/gzip/lzma/lzw/pack200/snappy/xz/z, plus `changes/`,
    `parallel/`, `utils/`)
  - `org.apache.commons.io.*` (with `comparator/`, `filefilter/`,
    `input/`, `monitor/`, `output/` subpackages)
  - `org.apache.commons.lang3.*` (only `lang3.text.translate.*` classes
    present, not the full library)
- **Best-known version**: unknown/unconfirmed. All class files carry a
  jar-build timestamp of **2020-02-09**, consistent with (but not proof
  of) Commons Compress ~1.20 and Commons IO ~2.6, the contemporary
  releases at that date. The name ("Exagear...") suggests this was
  repackaged for the Exagear/x86 translation layer inherited from the
  upstream PojavLauncher/Boardwalk fork this repo is based on (see repo
  `CLAUDE.md`), not built for 2009Scape specifically. Because it's a
  partial, hand-assembled repackaging of four libraries into one jar
  (rather than a single upstream artifact), there is no single upstream
  version number to cite — exact per-library versions are unconfirmed and
  would need byte-for-byte class comparison against candidate Commons
  releases to pin down precisely.
- **Scanner visibility**: invisible to manifest-based scanners (no
  coordinate, no manifest). A binary-content scanner (e.g. Trivy
  filesystem mode) may still flag known-CVE class signatures inside it.

## `gson-2.8.6.jar`

- **Size**: 239,939 bytes
- **Identification method**: `META-INF/MANIFEST.MF` is present and
  unambiguous — `Bundle-SymbolicName: com.google.gson`,
  `Bundle-Version: 2.8.6`, `Bundle-Name: Gson`, plus a Maven
  `META-INF/maven/com.google.code.gson/gson/pom.xml` /
  `pom.properties` pair, plus the `com/google/gson/*` package listing
  (`Gson.class`, `GsonBuilder.class`, `JsonObject.class`, etc.). This
  jar is the unmodified upstream Gson build artifact.
- **Best-known version**: **Gson 2.8.6** (per filename and confirmed by
  manifest `Bundle-Version`), released 2019-10-04 (jar entry timestamps).
- **Scanner visibility**: invisible to manifest-based Gradle/Dependabot
  scanners today (no `group:artifact:version` in `build.gradle` — only
  the `fileTree` glob), but unlike `ExagearApacheCommons.jar` this one
  *can* be trivially made scanner-visible: it is a straight, unmodified
  upstream artifact, so replacing the file-tree entry with a declared
  `com.google.code.gson:gson:2.8.6` (or newer) coordinate would fix that
  without any behavior change. That replacement is out of scope for this
  plan (see Plan 021's "Out of scope" — the `fileTree` wiring is left
  exactly as-is here) and is noted as a follow-up.

## Follow-up (not this plan)

A later plan should decide whether `gson-2.8.6.jar` can be replaced with
the declared Maven coordinate `com.google.code.gson:gson:2.8.6` (or a
newer patch), and should pin down `ExagearApacheCommons.jar`'s exact
per-library origin/version/license before considering any replacement or
re-vendoring of it.
