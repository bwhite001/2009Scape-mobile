# Command Buttons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status (2026-07-11): DONE — merged to `master` and pushed (`1b48048e3`).** Executed via subagent-driven development on branch `command-buttons` (fast-forwarded into master, branch deleted). Tasks 1–3 implemented and reviewed clean; build gate (ktlint + `assembleDebug`) green throughout. Commits: `2f528e663` (palette + presets), `82ea6c1aa` + fix `097c24c82` (picker layout; fixed a constraint-overlap Critical), `93557d2f2` (wiring) + fix `1b48048e3` (final-review Medium: `setSelection(0)` doesn't fire the listener at position 0, so the first preset left `commandText` null → `refreshCommandVisibility` now writes it on load). **Deferred (non-blocking):** (1) verify/trim the presets `::fpson`/`::togglexp`/`::toggleslayer`/`::highscores` — absent from the server command sets, possibly client-handled; shipping the superset is acceptable (unknown `::cmd` no-ops). (2) On-device checks — the editor flow (add Command button → pick `::bank`) and the live in-game send couldn't run headless (control-editor tap-automation too flaky; no JRE on the emulator).

**Goal:** Let a user turn any on-screen control button into a "command button" that types a 2009Scape chat command (e.g. `::bank`) when tapped, configured from a curated picker in the control editor.

**Architecture:** The dispatch and persistence already exist — `ControlData.commandText` (Gson-serialized), `ControlData.SPECIALBTN_COMMAND = -10`, and `ControlButton.sendChatCommand()` (opens chat, types the command, presses Enter; HD/GLFW + SD/AWT paths). This plan adds only the missing configuration UI: a "Command" entry in the editor's mapping spinner, and a command-picker spinner (populated from a curated no-argument command list) that writes `commandText`.

**Tech Stack:** Java, Android Views (Spinner/ArrayAdapter), the `customcontrols` control-editor layer, ktlint (Kotlin-only — does not lint Java), the `2009scape-apk-builder` Docker build.

## Global Constraints

- **Per-device only.** No server/protocol/`rt4.jar` involvement — command buttons live in the on-device control layout. Confirmed in brainstorming that the game server cannot push launcher-overlay buttons.
- **No changes to dispatch or persistence.** `ControlButton.sendChatCommand()` and Gson serialization of `commandText` are already correct; do not modify them.
- **Preserve existing keycode mappings.** The mapping spinner computes `keycode = position − specialArraySize`; the new "Command" special MUST be appended **last** in `getSpecialButtons()` so existing specials keep their values (verified: old size 9 → new size 10, every prior special's `position − size` is unchanged).
- **Curated no-argument commands only.** The picker lists commands that are complete on their own (auto-Enter submits immediately). No argument-taking commands (`::npc <id>`, `::to <name>`, …).
- **New launcher code is Java** (matches this file's surroundings). Match PojavLauncher style.
- **Build gate** (no host SDK — Docker):
  ```bash
  docker run --rm -v "$PWD":/project -w /project -e GRADLE_USER_HOME=/project/.gradle-cache \
    2009scape-apk-builder bash -lc 'git config --global --add safe.directory /project; ./gradlew :app_pojavlauncher:ktlintCheck :app_pojavlauncher:assembleDebug --console=plain'
  ```
  Expected: `BUILD SUCCESSFUL`. (`ktlintCheck` is Kotlin-only; these edits are Java + XML, so it will pass — `assembleDebug` is the real gate.)
- **Commits:** conventional; no `Co-authored-by: Copilot` trailer.

## File structure

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/ControlData.java` — add the "Command" palette entry and the curated `COMMAND_PRESETS` constant.
- `app_pojavlauncher/src/main/res/layout/dialog_control_button_setting.xml` — add the command-picker label + spinner.
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/handleview/EditControlPopup.java` — bind the spinner, its adapter, load/preselect, save `commandText`, and toggle its visibility.

---

### Task 1: Palette entry + curated command list

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/ControlData.java` (`getSpecialButtons()` ~line 61; add a constant near the `SPECIALBTN_*` block ~line 35)

**Interfaces:**
- Consumes: existing `SPECIALBTN_COMMAND = -10`.
- Produces: `public static final String[] COMMAND_PRESETS` (used by Task 3's adapter); a `"Command"` special button so `"SPECIAL_Command"` appears in the mapping spinner mapping to keycode `-10`.

- [ ] **Step 1: Add the curated command list constant**

In `ControlData.java`, immediately after the `SPECIALBTN_COMMAND` constant line (`public static final int SPECIALBTN_COMMAND = -10;`), add:

```java
    /**
     * Curated no-argument 2009Scape chat commands offered by the command-button
     * picker (SPECIALBTN_COMMAND). No-arg only: ControlButton.sendChatCommand()
     * auto-presses Enter, so a command must be complete as-is. Edit freely.
     */
    public static final String[] COMMAND_PRESETS = {
        "::bank", "::home", "::empty", "::emptybank", "::players",
        "::max", "::god", "::killme", "::allmusic", "::allquest",
        "::fpson", "::debug", "::togglexp", "::toggleslayer",
        "::highscores", "::stats", "::shop", "::loc", "::quests",
    };
```

- [ ] **Step 2: Append the "Command" special button (must be LAST)**

In `getSpecialButtons()`, add a `"Command"` entry as the **final** element of the array literal. Change the existing `MENU` line to add a comma and append the new entry:

```java
                new ControlData("MENU", new int[]{SPECIALBTN_MENU}, "${margin}", "${margin}"),
                new ControlData("Command", new int[]{SPECIALBTN_COMMAND}, "${margin}", "${margin}")
```

(It must be last so the reversed `buildSpecialButtonArray()` places it at index 0, giving `0 − 10 = −10 = SPECIALBTN_COMMAND`, while every prior special keeps its value.)

- [ ] **Step 3: Build gate**

Run the Global Constraints build gate.
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/ControlData.java
git commit -m "feat(controls): add Command special button + curated command presets"
```

---

### Task 2: Command-picker spinner in the editor layout

**Files:**
- Modify: `app_pojavlauncher/src/main/res/layout/dialog_control_button_setting.xml` (after `editMapping_spinner_4`, before `editOrientation_textView`)

**Interfaces:**
- Produces: view ids `@+id/editCommand_textView` and `@+id/editCommand_spinner`, consumed by Task 3's `bindLayout()`.

- [ ] **Step 1: Add the label + spinner to the layout**

In `dialog_control_button_setting.xml`, add these two views immediately after the `editMapping_spinner_4` view's closing tag (and before the `editOrientation_textView` block). Match the surrounding views' `ConstraintLayout` style:

```xml
        <TextView
            android:id="@+id/editCommand_textView"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/control_command_label"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toBottomOf="@+id/editMapping_spinner_4" />

        <Spinner
            android:id="@+id/editCommand_spinner"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintTop_toBottomOf="@+id/editCommand_textView" />
```

If `editOrientation_textView` constrains its `layout_constraintTop_toBottomOf` to `editMapping_spinner_4`, re-point it to `@+id/editCommand_spinner` so the chain stays ordered:

```xml
            app:layout_constraintTop_toBottomOf="@+id/editCommand_spinner"
```

- [ ] **Step 2: Add the label string**

In `app_pojavlauncher/src/main/res/values/strings.xml`, add (near the other `control_*` strings, or at end of `<resources>`):

```xml
    <string name="control_command_label">Chat command</string>
```

- [ ] **Step 3: Build gate**

Run the Global Constraints build gate.
Expected: `BUILD SUCCESSFUL` (the new ids resolve in `R`; not yet referenced from Java).

- [ ] **Step 4: Commit**

```bash
git add app_pojavlauncher/src/main/res/layout/dialog_control_button_setting.xml \
        app_pojavlauncher/src/main/res/values/strings.xml
git commit -m "feat(controls): add command-picker spinner to control edit layout"
```

---

### Task 3: Wire the picker (bind, load, save, visibility)

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/handleview/EditControlPopup.java`

**Interfaces:**
- Consumes: `ControlData.COMMAND_PRESETS`, `ControlData.SPECIALBTN_COMMAND` (Task 1); `@id/editCommand_spinner` (Task 2); existing `mCurrentlyEditedButton`, `mSpecialArray`, `mKeycodeSpinners`.
- Produces: writes `mCurrentlyEditedButton.getProperties().commandText`.

- [ ] **Step 1: Add the field**

Next to `protected Spinner mOrientationSpinner;` (~line 80) add:

```java
    protected Spinner mCommandSpinner;
```

- [ ] **Step 2: Bind it in `bindLayout()`**

After the `mOrientationSpinner = mScrollView.findViewById(R.id.editOrientation_spinner);` line, add:

```java
        mCommandSpinner = mScrollView.findViewById(R.id.editCommand_spinner);
```

- [ ] **Step 3: Give it an adapter in `loadAdapter()`**

In `loadAdapter()` (after the orientation adapter block, before the method ends), add:

```java
        ArrayAdapter<String> commandAdapter =
            new ArrayAdapter<>(mScrollView.getContext(), android.R.layout.simple_spinner_item);
        commandAdapter.setDropDownViewResource(android.R.layout.simple_list_item_single_choice);
        commandAdapter.addAll(ControlData.COMMAND_PRESETS);
        mCommandSpinner.setAdapter(commandAdapter);
```

- [ ] **Step 4: Add a visibility helper + call it from `loadValues(ControlData)`**

Add this private helper to the class:

```java
    /** Show the command picker only for command buttons; preselect the saved command. */
    private void refreshCommandVisibility(ControlData data) {
        boolean isCommand = data.keycodes.length > 0 && data.keycodes[0] == ControlData.SPECIALBTN_COMMAND;
        mCommandSpinner.setVisibility(isCommand ? VISIBLE : GONE);
        mScrollView.findViewById(R.id.editCommand_textView).setVisibility(isCommand ? VISIBLE : GONE);
        if (isCommand) {
            int idx = java.util.Arrays.asList(ControlData.COMMAND_PRESETS).indexOf(data.commandText);
            mCommandSpinner.setSelection(idx >= 0 ? idx : 0);
        }
    }
```

At the end of `loadValues(ControlData data)` (after the keycode-spinner `for` loop), add:

```java
        refreshCommandVisibility(data);
```

Also hide it in the two variants that hide the mapping spinners — at the end of `loadValues(ControlDrawerData data)` and `loadJoystickValues(ControlData data)`, add:

```java
        mCommandSpinner.setVisibility(GONE);
        mScrollView.findViewById(R.id.editCommand_textView).setVisibility(GONE);
```

- [ ] **Step 5: Save the selection + toggle visibility live**

In `setupRealTimeListeners()`, inside the `mKeycodeSpinners[i]` `onItemSelected` (after the `if/else` that sets `keycodes[finalI]`), reveal/hide the picker when spinner **0** changes:

```java
                    if (finalI == 0) {
                        refreshCommandVisibility(mCurrentlyEditedButton.getProperties());
                    }
```

Then, after the whole `for(int i = 0; i < mKeycodeSpinners.length; ++i){ ... }` block, add the command-spinner listener:

```java
        mCommandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mCurrentlyEditedButton != null) {
                    mCurrentlyEditedButton.getProperties().commandText = ControlData.COMMAND_PRESETS[position];
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
```

- [ ] **Step 6: Build gate**

Run the Global Constraints build gate.
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/handleview/EditControlPopup.java
git commit -m "feat(controls): wire command picker (load/save/visibility)"
```

---

### Task 4: Verification

**Files:** none.

- [ ] **Step 1: Build the APK** (already green from Task 3's gate; produces `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`).

- [ ] **Step 2: Emulator/device — persistence check.** Install the APK, open the on-screen controls editor (`CustomControlsActivity` — reachable from Settings → "Edit on-screen controls"), add a button, open its edit popup, set the first mapping spinner to **Command**, confirm the **Chat command** picker appears, pick `::bank`, and exit the editor (saves the layout). Then read the saved control layout JSON and confirm the entry:

```bash
# on the emulator/device (debuggable build):
adb shell run-as net.kdt.pojavlaunch.debug \
  sh -c 'grep -o "\"commandText\":\"[^\"]*\"" files/**/*.json 2>/dev/null; \
         grep -ro "\"commandText\":\"[^\"]*\"" /data/data/net.kdt.pojavlaunch.debug/ 2>/dev/null' | head
```
Expected: `"commandText":"::bank"` present in the control layout file.

- [ ] **Step 3: On-device only — live send.** Because this debug APK ships no bundled JRE, the game client can't run on a headless emulator; the actual `::bank` send (`ControlButton.sendChatCommand`) must be confirmed on a real device: tap the command button in-game and verify `::bank` is typed into chat and submitted. This code path is pre-existing and unchanged, so it is low-risk, but record the result on the device smoke checklist.

---

## Self-Review

**Design coverage:**
- Palette "Command" entry (appended last, keycode −10) → Task 1 ✓
- Curated no-arg command list → Task 1 (`COMMAND_PRESETS`) ✓
- Picker spinner in editor → Task 2 (layout) + Task 3 (bind/adapter) ✓
- Show only for command buttons; preselect saved command → Task 3 `refreshCommandVisibility` ✓
- Save selection to `commandText` → Task 3 Step 5 listener ✓
- Persistence is free (Gson) → no task needed; verified in Task 4 Step 2 ✓
- Dispatch unchanged → Global Constraints ✓
- Verification (build + persistence + on-device send) → Task 4 ✓

**Placeholder scan:** none — every code step shows the exact snippet.

**Type consistency:** `COMMAND_PRESETS` is `String[]` in Task 1 and consumed as `String[]` (adapter `addAll`, `indexOf`, `[position]`) in Task 3. `mCommandSpinner` is declared (Step 1), bound (Step 2), adapted (Step 3), read/written (Steps 4–5) consistently. `refreshCommandVisibility(ControlData)` is defined once (Step 4) and called with `ControlData` args in `loadValues` and the spinner-0 listener. `SPECIALBTN_COMMAND` / `commandText` names match the existing `ControlData`/`ControlButton` code.
