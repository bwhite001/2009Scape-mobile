# Implementation Plans

Two `improve`-skill batches live here.

- **Batch 1** (plans 001–006) — generated 2026-07-07 against commit `0e75ab512`; **all DONE / merged** (commit `b74e73fcb`, CI green).
- **Batch 2** (plans 007–031) — generated 2026-07-10, planned against commit `8ee361ea1` (working tree verified at `a8255de56`; cited excerpts confirmed live). All **TODO**.

Each executor: read the plan fully before starting, run its **Drift check** first (`git diff --stat 8ee361ea1..HEAD -- <in-scope paths>` for batch 2), honor its STOP conditions, and update your row below when done. Plans modify source **only** within each plan's "In scope" list; the advisor did not edit any source code.

**Verification reality for this repo:** there is no host JDK/Android SDK. The build gate runs in Docker via the checked-in `build.Dockerfile` image:

```bash
docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder \
  bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"
```

A JUnit unit-test suite now exists (`app_pojavlauncher/src/test/java/...`, starting with `customcontrols/CameraPanTest`); pure-logic seams are the test target. Behavioral/UI fixes also require the on-device checklist in `docs/verification/device-smoke-checklist.md` (Plan 006).

## Batch 1 — status (DONE)

| Plan | Title | Status |
|------|-------|--------|
| 006 | Verification baseline (build gate + device smoke checklist) | DONE (CI green) |
| 001 | Reject Zip-Slip entries in ZIP extraction | DONE (CI green) |
| 002 | Close streams + check HTTP status in monitored downloads | DONE (CI green) |
| 003 | Fail `compareSHA1` closed on read error | DONE (CI green) |
| 004 | Fix Cursor leak in `Tools.getFileName` | DONE (CI green) |
| 005 | Reload prefs on settings-exit, not per keystroke | DONE (CI green) |

## Batch 2 — execution order & status

Ordered by leverage. Independent unless "Depends on" says otherwise; same-priority plans can run on parallel branches. Cheap unblockers (007–009) first.

| Plan | Title | Priority | Effort | Cat | Depends on | Status |
|------|-------|----------|--------|-----|------------|--------|
| 007 | Remove broken `gradle-publish.yml` (fails every release) | P1 | S | dx | — | DONE |
| 008 | Fix stale "no unit tests" docs (CLAUDE.md, plans/README) | P1 | S | docs | — | DONE |
| 009 | Wire `testDebugUnitTest` into CI | P1 | S | tests | — | DONE |
| 010 | Fix `ProgressKeeper` CME on task completion | P1 | S | bug | — | DONE |
| 011 | Fix `downloadFile` stream leak + unchecked `renameTo` | P1 | S | bug | — | DONE |
| 013 | Harden config/plugin import (cancel-NPE, crash, leaks, size cap) | P1 | M | security | — | DONE |
| 018 | Extract + unit-test server-config normalizer (dedupe port logic) | P1 | M | tests | — | DONE |
| 012 | Remove 3× `System.gc()` on the UI thread | P2 | S | bug | — | DONE |
| 014 | Config-import transport (cleartext scoping, default URL, read cap) | P2 | S–M | security | — | DONE |
| 015 | Zip-bomb size/entry caps in `ZipTool.unzip` | P2 | M | security | — | DONE |
| 016 | Surface `AsyncAssetManager` unpack failures (no false "done") | P2 | M | bug | — | DONE |
| 017 | Null `MainActivity` static Views in `onDestroy` (+guards) | P2 | S–M | bug | — | DONE |
| 021 | Dependency vuln-scan CI job + inventory `libs/*.jar` | P2 | S | security | — | TODO |
| 022 | Fix SD map-drag 2→1-finger BUTTON1 hold | P2 | S | bug | — | TODO |
| 023 | Fix stylus multi-pointer stuck button | P2 | S | bug | — | TODO |
| 024 | Kotlin lint/format gate (ktlint/spotless, Kotlin-scoped) | P2 | S | dx | 009 | TODO |
| 019 | Characterize `LayoutConverter` migration + seam | P2 | M | tests | — | TODO |
| 020 | Trivial pure-function unit tests (JSONUtils, isValidString, SHA1) | P2 | S | tests | — | TODO |
| 025 | Bump `material` 1.5.0 → 1.12.x | P3 | S–M | migration | — | TODO |
| 026 | `MultiRTUtils` tar-slip + symlink containment (latent) | P3 | S | security | — | TODO |
| 027 | INVESTIGATE: in-world cleanup only under ACTION_CANCEL | P3 | S | bug | — | TODO |
| 028 | Tune shared `ThreadPoolExecutor` (low leverage) | P3 | S | tech-debt | — | TODO |
| 029 | SPIKE: server-profile switcher | P2 | M | direction | 018 | TODO |
| 030 | SPIKE: SHA-256 artifact/config integrity | P2 | L | direction | — | TODO |
| 031 | DESIGN: in-app plugin manager | P3 | L | direction | 030, 015 | TODO |

Status values: TODO | IN PROGRESS | DONE | BLOCKED (one-line reason) | REJECTED (one-line rationale)

## Dependency notes

- **007–009 are the cheap unblockers**: delete the broken workflow, correct the "no tests" docs, and make the test suite actually run in CI. Do these first — they're near-zero-risk and make every later test-bearing plan (010, 018, 019, 020) enforceable.
- **024 depends on 009** — the ktlint/spotless check should be added to the same JDK-17 CI leg the test step lands in.
- **029 depends on 018** — the profile switcher routes server selection through the `ServerConfig.normalize` seam that 018 extracts.
- **031 depends on 030 and 015** — an in-app plugin manager must not import/execute untrusted plugins before the integrity story (030) and zip caps (015) exist.
- **Config-import cluster (013 / 014 / 018)** touches overlapping files by design but with disjoint scopes: 013 = file-picker validation/leak/crash handling; 014 = transport (cleartext/default URL/read cap); 018 = the shared port/IP normalizer. Each cross-references the others by name — keep the scopes disjoint; if executed together, commit separately.
- **018 is launch-critical** (server connection): its plan forbids changing the common-case resolved port and requires an on-device connect smoke (HD + SD).

## Provenance & drift note

Batch 2 plans are stamped "planned at `8ee361ea1`" as a conservative drift base; the working tree was actually at `a8255de56` (a few concurrent commits ahead) when they were authored, and every cited excerpt was confirmed against the live file. Notable corrections the writers made vs. the initial audit leads (already reflected in the plans): 025's app theme extends `Theme.AppCompat`, not `Theme.MaterialComponents`; 016's gating is `ScapeLauncher.launchIfReady`/`ProgressUiState`, not a `runtimeReady()` method; 022's existing `mMapDragging` release block is unreachable for the 2→1 transition (bug is real); 026's `Os.symlink` ignores `dest` for both args (arg-order fix gated behind a STOP).

## Findings considered and rejected (do not re-audit)

From batch 1 (still valid): ProgressRepository listeners not removed (owned by process-lifetime container — intentional); ScapeLauncher progress listener (balanced); Compose/StateFlow threading (correct); `res/xml/provider.xml` broad root (no `<provider>` wired); debug keystore password in `build.gradle` (standard Android debug keystore, by-design).

From batch 2: the shared executor's dead keep-alive config was judged **low-leverage** and initially "not worth doing," but was written up as **Plan 028** (P3) at the operator's request to plan all findings — execute only if the area is opened for another reason.

## Not audited (either round)

Native/vendored trees (`jni/`, `jniLibs/`, the `org/lwjgl/**` mirror, `jre_lwjgl3glfw/`, empty `gl4es/`), the prebuilt `rt4.jar` client internals, `Gamepad`/control-editor internals, and `multirt/` beyond the flagged tar-slip. No dependency-CVE scan was actually run — Plan 021 adds the job (the absence is the finding).
