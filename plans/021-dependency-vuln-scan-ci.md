# Plan 021: Add a non-blocking dependency vulnerability scan to CI

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- .github/workflows gradle/libs.versions.toml app_pojavlauncher/build.gradle app_pojavlauncher/libs`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S (add the CI job) / M (if triaging findings is included — this plan scopes to "add job + inventory", not "fix every finding")
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

This is a consumer-facing Android APK with zero automated visibility into
known CVEs in its dependency tree. `.github/workflows/` has no
dependency-scanning step of any kind, and several transitive/direct
dependencies are old enough to plausibly carry known vulnerabilities
(`commons-codec` 1.15 from 2020, `htmlcleaner` 2.6.1, `xz` 1.8). Worse,
two jars in `app_pojavlauncher/libs/` are vendored directly as files rather
than declared as Gradle coordinates — they are **invisible to any manifest-based
scanner** (Dependabot, `gradle dependencies`, OSV-Scanner's lockfile mode)
because they have no group:artifact:version metadata to look up. Shipping
an APK with unscanned, unversioned binary dependencies is a real (if
currently unquantified) supply-chain and CVE exposure. The fix here is
deliberately conservative: add a **non-blocking** scan (so it doesn't stall
unrelated work on noisy/false-positive findings) and a plain inventory of
what the two opaque jars actually are, so future triage has something to
work from.

## Current state

- No dependency-scan tooling exists in CI today. Confirmed:
  ```
  grep -rn "dependency-check\|osv\|trivy\|dependencyCheck" .github/workflows/
  ```
  returns no output. The only workflow is `.github/workflows/android.yml`
  (build-only, see Plan 009's Current state for its full step list).
- Versioned dependencies with plausibly-stale CVE surface, confirmed at
  `gradle/libs.versions.toml`:
  - Line 10: `commonsCodec = "1.15"`
  - Line 21: `xz = "1.8"`
  - Line 23: `htmlcleaner = "2.6.1"`
  - Line 25: `material = "1.5.0"` (also the subject of Plan 025, tracked separately — not a CVE concern per se, just old)
  These are pulled in as `implementation libs.commons.codec` / `implementation libs.xz` / `implementation libs.htmlcleaner` in `app_pojavlauncher/build.gradle` (lines 192, 203, 205) — i.e. they are on the **active build path**, not test-only.
- Opaque local jars, confirmed:
  ```
  ls -la app_pojavlauncher/libs/
  ```
  → exactly two files:
  - `ExagearApacheCommons.jar` (474,005 bytes)
  - `gson-2.8.6.jar` (239,939 bytes)
  Wired in at `app_pojavlauncher/build.gradle:206`:
  ```groovy
  implementation fileTree(dir: 'libs', include: ['*.jar'])
  ```
  This line has no group/artifact/version — a Dependabot or OSV-Scanner manifest scan cannot see these jars at all. `gson-2.8.6.jar`'s version is legible from its filename (Gson 2.8.6, released 2019) but is not declared anywhere Gradle-visible; `ExagearApacheCommons.jar` has no version information in its filename at all.
- CI structure to attach the new job to: `.github/workflows/android.yml` has
  a single job named `build`. This plan adds either a **new workflow file**
  or a **second job** in the same file — either is acceptable as long as it
  does not block/depend on the `build` job's success (see Scope).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Confirm no existing scan config | `grep -rn "dependency-check\|osv\|trivy\|dependencyCheck" .github/workflows/` | no output (before this plan lands) |
| Inventory the opaque jars | `ls -la app_pojavlauncher/libs/` | lists exactly `ExagearApacheCommons.jar`, `gson-2.8.6.jar` (confirm counts/names haven't changed) |
| Inspect jar manifest/contents for identification | `unzip -p app_pojavlauncher/libs/ExagearApacheCommons.jar META-INF/MANIFEST.MF` and `unzip -l app_pojavlauncher/libs/ExagearApacheCommons.jar \| head -30` | manifest/class listing to help identify origin/version |
| Build gate (Docker) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` (proves the new CI job/doc didn't break the build) |

## Scope

**In scope:**
- A new `.github/workflows/*.yml` file (recommended: `dependency-scan.yml`) — or, if you judge a second job in `android.yml` is cleaner, a new job there. Prefer a **separate workflow file** so it can run on its own schedule/trigger without coupling to the build job.
- A short inventory doc for the two jars in `app_pojavlauncher/libs/` (recommended: `docs/dependencies/vendored-jars.md`), recording: filename, size, best-effort identification (e.g. from manifest/class inspection), and known/likely version.
- Optionally pinning `commonsCodec`/`xz`/`htmlcleaner` to their current-latest patch versions in `gradle/libs.versions.toml` **only if** trivially safe (same major/minor, drop-in) — this is a stretch goal, not required for Done.

**Out of scope (do NOT do in this plan):**
- Deleting, replacing, or re-vendoring `app_pojavlauncher/libs/*.jar` — that is an explicit follow-up, not this plan's job (per the spec: "do NOT delete/replace any jar").
- Making the new CI job blocking/required — it must be non-blocking (report-only) on this first pass.
- Fixing/upgrading every dependency the scan flags — this plan adds visibility, not a remediation project.
- Any change to `app_pojavlauncher/build.gradle`'s `fileTree` wiring — leave the two jars loaded exactly as they are.

## Git workflow

- Branch: `advisor/021-dependency-vuln-scan-ci`
- Commit per logical unit (e.g. one commit for the CI workflow, one for the
  inventory doc), conventional-commit style matching `git log` (e.g.
  `ci: add non-blocking dependency vulnerability scan`, `docs: inventory vendored jars in app_pojavlauncher/libs`).
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Inventory the two vendored jars

Run:
```
ls -la app_pojavlauncher/libs/
unzip -l app_pojavlauncher/libs/ExagearApacheCommons.jar | head -40
unzip -p app_pojavlauncher/libs/ExagearApacheCommons.jar META-INF/MANIFEST.MF 2>/dev/null
unzip -l app_pojavlauncher/libs/gson-2.8.6.jar | head -40
unzip -p app_pojavlauncher/libs/gson-2.8.6.jar META-INF/MANIFEST.MF 2>/dev/null
```
Use the class-package listing and any manifest `Implementation-Version` /
`Bundle-SymbolicName` fields to identify each jar as precisely as possible
(e.g. "Apache Commons Lang/IO repackaged for the Exagear/x86 translation
layer" vs. "com.google.gson — Gson 2.8.6, per filename and confirmed by the
`com/google/gson/*` package listing").

Write `docs/dependencies/vendored-jars.md` with one entry per jar: filename,
size, byte-for-byte identification method used, best-known version, and a
one-line note that these are invisible to manifest-based dependency
scanners (this is *why* the scan in Step 2 can't cover them, and why this
doc exists as the manual substitute).

**Verify**: `test -f docs/dependencies/vendored-jars.md && grep -c "jar" docs/dependencies/vendored-jars.md` → file exists, mentions both jar names.

### Step 2: Add the non-blocking CI scan job

Create `.github/workflows/dependency-scan.yml`. Use OSV-Scanner (Google's
`google/osv-scanner-action`) or an equivalent well-maintained action that
can scan a Gradle project's resolved dependencies without requiring a
lockfile (OSV-Scanner supports scanning `build.gradle`/`.lockfile`-less
projects via its `--recursive` / manifest-parsing mode, but if that proves
unreliable for this Groovy/version-catalog project, an acceptable
alternative is Trivy's `trivy fs --scanners vuln .` filesystem scan, which
needs no build integration and covers the vendored jars' binary content
too — prefer Trivy if OSV-Scanner's Gradle-manifest support is unreliable in
practice, since Trivy's filesystem mode is the only one of the two that can
also flag known-CVE jars *by binary content*, partially covering the
otherwise-invisible `app_pojavlauncher/libs/*.jar`).

Key requirements for the workflow, regardless of which tool you pick:
- Trigger: `push`, `pull_request`, and `workflow_dispatch` (match `android.yml`'s style), optionally also `schedule` (e.g. weekly) since CVE databases update independently of code changes.
- The job must **not** fail the workflow on findings — set the scan step to always report/upload findings but not fail the job (e.g. `continue-on-error: true` on the scan step, or the tool's own "don't fail on findings" flag if it has one — prefer the tool's native flag over `continue-on-error` if available, since it distinguishes "scan crashed" from "scan found CVEs").
- Upload the scan's output (SARIF/JSON/text) as a build artifact via `actions/upload-artifact@v4`, matching the artifact-upload style already used in `android.yml` (`name`/`path`/`retention-days` keys).

**Verify**: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/dependency-scan.yml'))"` → exits 0 (valid YAML). `grep -n "continue-on-error\|allow.*fail\|exit-code" .github/workflows/dependency-scan.yml` → at least one match confirming the non-blocking behavior is explicit, not accidental.

### Step 3: Confirm the rest of the build is unaffected

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL` (the new workflow file and doc do not touch Gradle build files, so this should be unaffected — this step exists to prove that).

## Test plan

This plan is CI-config and documentation; there is no application code to
unit test. The verification is structural:
- The new workflow YAML is syntactically valid.
- The scan step is explicitly configured to not fail the job on findings.
- The inventory doc exists and names both jars.
- The existing build gate still passes (proves nothing broke).

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `.github/workflows/dependency-scan.yml` (or equivalent) exists and parses as valid YAML
- [ ] The scan job/step is confirmed non-blocking (`continue-on-error: true` or tool-native no-fail flag present)
- [ ] `docs/dependencies/vendored-jars.md` exists and mentions both `ExagearApacheCommons.jar` and `gson-2.8.6.jar` by name
- [ ] Docker `assembleDebug` still exits `BUILD SUCCESSFUL`
- [ ] `git status` shows only the new workflow file + new doc (+ optionally `gradle/libs.versions.toml` if trivial patch bumps were made)
- [ ] `plans/README.md` status row for Plan 021 updated

## STOP conditions

Stop and report back (do not improvise) if:

- `app_pojavlauncher/libs/` does not exist or is empty when you check —
  per the original finding, in that case skip the jar-inventory doc
  entirely and do only the classpath/manifest scan (Step 2); note this in
  your final report since it means the codebase has changed since this plan
  was written.
- The chosen scanning action requires credentials/secrets not available in
  this repo's Actions settings (e.g. a paid Snyk token) — fall back to a
  no-auth-required tool (OSV-Scanner or Trivy, both free/no-token for public
  scanning) rather than adding a secret requirement.
- Any attempt to make the scan step "just work" ends up making it
  block/fail the workflow — revert to explicit non-blocking config; do not
  ship a blocking scan under this plan's authority.

## Maintenance notes

- This is a **first pass**: report-only. A follow-up plan should (a) triage
  any findings the scan surfaces, (b) decide whether `ExagearApacheCommons.jar`
  and/or `gson-2.8.6.jar` can be replaced with a declared Maven coordinate
  (Gson definitely can — `com.google.code.gson:gson:2.8.6` or newer — which
  would make it scanner-visible going forward; `ExagearApacheCommons.jar`
  needs its exact origin/license confirmed first per Step 1's inventory
  before any replacement is attempted), and (c) consider whether the scan
  should become blocking once the finding backlog is triaged to zero/accepted.
- If the scheduled (weekly) trigger is added, note in the PR description
  that scheduled workflow runs don't show up in the PR's own check list —
  they run independently on `master`.
