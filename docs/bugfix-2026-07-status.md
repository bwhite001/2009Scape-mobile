# Bug-Fix Status — July 2026 (corrected tracker)

The original "Active Issues Log" was written against upstream
`AndroidManifest-back.xml` (2009scape/2009Scape-mobile@master). This fork has
diverged well past that; most launcher-side items are already fixed, and half
the bugs live in a different repo. Corrected state below.

On-device verification: `docs/verification/bugfix-2026-07-verify.md`.

## ✅ Done in this fork
| Bug | What | Where |
|-----|------|-------|
| **3** | ProgressService FGS type (Android 14 crash) | commit `5560d767f`; manifest `foregroundServiceType="dataSync"`, `ProgressService.java` uses `ServiceCompat.startForeground(..., type)` |
| **1** | Keyboard covers viewport | commit `5560d767f`; both game activities `windowSoftInputMode="adjustNothing"`, HD path uses `SoftKeyboardViewportShifter.attach()` in `MainActivity` |
| — | POST_NOTIFICATIONS (API 33+) | permission + runtime handling present |

## ⚠️ Not a launcher code bug
| Bug | Reality |
|-----|---------|
| **4** — `js5connect` fail | **Environment**, not code: server up on `192.168.0.243:43595`, phone on same subnet, firewall open. This fork reads **`config.json`** (via `-DconfigFile`), *not* `settings.json` — the original plan named the wrong file. |
| `__MACOSX` plugin | Bundled plugin zips in `assets/plugins/` are **clean**. The log line came from a stale device-side unpacked copy left by a prior APK. Fresh install clears it. |

## ❓ Needs on-device verdict
| Bug | Note |
|-----|------|
| **5** — clipped KB/MOUSE/SHIFT/TAB | In-game buttons render from the **control-layout JSON** (`assets/default.json`) / customcontrols, **not** `activity_java_gui_launcher.xml`. Confirm on-device; if still clipping, file a control-JSON ticket. |

## 🔴 Real remaining work — `downthecrop/rt4-client` (`lwjgl-mobile-callbacks`)
This repo ships only the prebuilt `rt4.jar`; the client source is **not** here.
These require checking out + building that GitLab project, then re-bundling the jar.
| Bug | What |
|-----|------|
| **2 / 6** | `glDrawPixels` → texture-blit for minimap + world map (blank/black in HD *and* SD) |
| **7** | Hide minimap widget when world map is open |
| **8** | World map close-button z-order vs launcher overlay |
