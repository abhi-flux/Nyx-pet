# Nyx — Project Progress

Android on-screen pet assistant. Trained skill-by-skill to perform on-screen actions
(open apps, search, tap, type, record results to a file) via Accessibility Service.
Budget: ₹0 — Kotlin + Android Studio + sideloaded APK, no Play Store, no paid APIs yet.

**How to use this file:** upload this + the current `/nyx` code folder into a new chat
with any LLM to resume work exactly where we left off. Update the checklist and the
"Current State" section after every change.

---

## Current State (as of Phase 6, versionCode 9)
- Project builds and installs cleanly via GitHub Actions CI, signed with a
  persistent key (see Signing section below) — updates install in place now.
- **Pet mood animations (Phase 6, NEW):** `PetOverlayService` now exposes a
  static `instance` (same pattern as `NyxAccessibilityService`) and a public
  `setMood(PetMood)` that `RecordingOverlayService` and `PlaybackOverlayService`
  call directly. All animations are built with `ObjectAnimator` — no external
  image/sprite/Lottie files, zero extra assets to manage:
  - IDLE: slow breathing scale loop + a random blink squish every 3-6s
  - RECORDING: fast red scale pulse — set when recorder opens, cleared on close
  - RUNNING: continuous rotation — set at the start of skill replay
  - SUCCESS: one-shot green bounce, then auto-returns to IDLE — on skill finish
  - ERROR: one-shot red shake, then auto-returns to IDLE — on skill failure
    (skill not found, accessibility disabled, corrupt saved steps)
  - Manual Stop (user-initiated) goes straight to IDLE, not ERROR — it wasn't
    a failure, so no need for the shake animation.
  If a future phase adds a new failure path in playback, remember to call
  `PetOverlayService.instance?.setMood(PetMood.ERROR)` there too, or it'll
  silently stay in RUNNING/spinning forever.
- **Coordinate accuracy bug fixed (important):** taps/swipes recorded near the
  top or bottom of the screen were landing in the wrong place. Root cause: our
  overlay windows (reticle, swipe markers, panel, playback status bar) didn't
  set `FLAG_LAYOUT_IN_SCREEN` / `FLAG_LAYOUT_NO_LIMITS`, so by default Android
  positions overlay windows relative to the screen area *excluding* the status
  bar and nav bar — while `dispatchGesture()` expects true full-screen raw
  coordinates. That mismatch (roughly a status-bar-height offset) is exactly
  why accuracy drifted near the edges. Fixed by adding those flags to every
  overlay window's `LayoutParams` in `RecordingOverlayService` and
  `PlaybackOverlayService`. If any *new* overlay window is added later
  (recorder, player, or pet), it MUST include these same flags or the same
  bug will silently reappear for that window.
- **Back button added:** `NyxAccessibilityService.pressBack()` calls
  `performGlobalAction(GLOBAL_ACTION_BACK)` — the real OS back action, not a
  simulated tap on a back icon (which wouldn't work system-wide). Recorder has
  an "⬅ Add Back" button (fires instantly, no capture needed, same pattern as
  Wait). `StepType.BACK` is a new enum value, handled in playback.
- `MainActivity`: onboarding screen, requests overlay + accessibility permissions.
- `PetOverlayService`: draggable floating bubble. A genuine tap (not a drag,
  <20px movement) now launches the Skill Recorder.
- `NyxAccessibilityService`: working `tapAt()`, `swipe()`, `typeIntoFocusedField()`.
- `RecordingOverlayService` (v2 — REDESIGNED after real-device testing):
  the original v1 used an invisible full-screen touch-catching layer for the
  "Tap" step, which froze the screen on real hardware (touch got captured but
  the layer never released it — likely an OEM/overlay-permission edge case).
  Replaced with a small visible DRAGGABLE RETICLE + explicit Confirm/Cancel
  buttons — you drag a red target to the exact spot, confirm, done. No
  full-screen blocking layer exists anymore.
  Also fixed: control bar is now a vertical panel (one button per row) so it
  can never run off the right edge of the screen — "Save" was there in v1,
  just unreachable on narrow screens. Added a live "Steps recorded: N" counter
  and a longer, clearer toast for Wait so every button visibly does something.
  Added an always-present ✕ Close button so a stuck session can always be
  cancelled without needing to force-stop the app from Android settings.
  Also added SWIPE steps: two draggable markers (green=start, red=end),
  drag both into place, Confirm performs the swipe live via
  `NyxAccessibilityService.swipe()` and records start/end/duration.
- `PetOverlayService`: tap no longer jumps straight into recording. It now
  opens a menu — "Record New Skill" or "My Skills" (lists every saved skill
  with step count, tap one for Run/Delete — Run is a placeholder toast until
  Phase 4 builds real replay; Delete actually removes it from Room).
- `RecordingOverlayService` panel is now DRAGGABLE by its "☰ Recording" title
  bar (same drag pattern as the pet itself), so it can be moved out of the way
  of whatever you're recording against.
- **Note on Settings app recording:** Android (since 12+) intentionally hides
  overlay windows from ALL apps on certain sensitive system screens, most
  notably inside Settings, to prevent overlay-based clickjacking of permission
  grants. This means Nyx's recording panel/reticle can become invisible while
  Settings is in the foreground on some screens. This is a real, permanent OS
  restriction, not a bug — decided NOT to add a manual-coordinate-entry
  workaround for it (rejected — keeping scope to what's actually needed).
- `PlaybackOverlayService` (Phase 4): "Run Skill" in My Skills actually
  works. Loads the skill from Room, decodes steps, replays TAP/SWIPE/TYPE/WAIT/
  OPEN_APP in order via NyxAccessibilityService. Shows a small live status bar
  ("skillname: step X/N") with a Stop button to abort mid-run at any time.
  A fixed ~1.2s buffer is added after every OPEN_APP step (app cold-starts vary
  in speed) plus a small ~400ms gap between every step for stability. If replay
  proves unreliable for slow-loading apps in practice, the next improvement is
  waiting for real window-changed accessibility events instead of fixed delays.

## Architecture Decisions
- **Language:** Kotlin, native Android (not Flutter/React Native) — best access to
  Accessibility APIs and lowest overhead for a background overlay service.
- **Min SDK:** 26 (Android 8.0+) — required for reliable `dispatchGesture`.
- **Storage:** Room (SQLite, on-device) for skills — no server, no cost, works offline.
  Steps stored as a single Gson-serialized JSON string per skill row (simpler than
  a second child table for an MVP; fine to normalize into its own table later if
  step-level querying is ever needed).
- **Recording technique:** a full-screen transparent overlay captures the next
  real touch's (x,y), removes itself, then NyxAccessibilityService replays that
  same tap live via `dispatchGesture`. This means "recording" and "actually doing
  the thing" happen together — you're not just watching, you're doing it once
  while Nyx watches and remembers.
- **Distribution:** sideloaded APK (USB install / `adb install`) — Play Store review
  blocks apps that request Accessibility for automation, and we don't need the Store.
- **AI:** deferred. Phases 0–6 use fixed trigger names/menu taps, zero AI cost.
  A paid LLM API is only introduced in Phase 7, and only if you want free-form
  natural-language commands instead of a menu.
- **Build pipeline: GitHub Actions, not local Android Studio.** Local Android
  Studio was too laggy on this machine. Instead:
  - Code is edited locally (any lightweight editor) or in GitHub's web editor.
  - `.github/workflows/build.yml` builds a debug APK in the cloud on every push
    to `main`, or on-demand via "Run workflow" in the Actions tab.
  - Finished APK is downloadable from the workflow run's "Artifacts" section
    (`nyx-debug-apk.zip` → unzip → install the `.apk` on the phone).
  - Free tier: 2,000 CI minutes/month on a private repo — this build takes a
    few minutes, so effectively unlimited for this project's pace.
  - No `gradlew`/wrapper jar committed — CI installs Gradle 8.4 directly via
    `gradle/actions/setup-gradle` (pinned version — a floating/latest version
    caused a Gradle/AGP incompatibility once already, don't remove the pin).
  - Android SDK is NOT installed via the third-party `android-actions/setup-android`
    action (unreliable/unclear failures) — instead we use the SDK that's already
    preinstalled on `ubuntu-latest` runners via `$ANDROID_SDK_ROOT/cmdline-tools`.
- **Signing (persistent key):** a keystore was generated once (`nyxkey` alias) and
  stored as 4 GitHub Actions secrets: `NYX_KEYSTORE_BASE64`, `NYX_KEYSTORE_PASSWORD`,
  `NYX_KEY_ALIAS`, `NYX_KEY_PASSWORD`. The workflow reconstructs the keystore file
  from the secret before every build and `app/build.gradle` applies it to the debug
  build type via a `keystore.properties` file (gitignored, never committed). This
  means every CI build is signed identically, so new APKs install as an UPDATE over
  the old one and all on-device data (skills in Room DB, etc.) survives. If the
  secrets are ever lost, a new keystore must be generated and users must uninstall
  once before reinstalling — don't lose these secrets.

## Phase Checklist
- [x] Phase 0 — Project skeleton, manifest, permissions flow
- [x] Phase 1 — Floating draggable pet overlay
- [x] Phase 2 — Accessibility service stub: tapAt / swipe / typeIntoFocusedField
- [x] Phase 2.5 — CI build pipeline: GitHub Actions builds APK, no local Android Studio
- [x] Phase 2.6 — Persistent signing key: updates install in place, no data loss
- [x] Phase 3 — Skill Recorder: tap Nyx → record Tap/Type/Wait/App steps → save named skill to Room
- [x] Phase 4 — Skill Trigger Engine: "Run Skill" in My Skills actually replays
      the saved steps in order via NyxAccessibilityService, with a live progress
      bar and Stop button
- [ ] Phase 5 — DEFERRED (not dropped — revisit later): Result Logger — each
      skill run appends/updates a result file
- [x] Phase 6 — Pet mood animations: breathing/blink idle, red pulse while
      recording, spin while running, green bounce on success, red shake on error
      — all built with ObjectAnimator, no external image/sprite assets needed
      (e.g. `/Android/data/com.nyx.pet/files/skill_name_results.txt`), viewable in-app
- [ ] Phase 6 — Real pet visuals: sprite/Lottie animations (idle, blink, "working",
      "done"), bubble menu UI, mood states
- [ ] Phase 7 (optional, costs money) — Natural language command parsing via an LLM
      API, so you can type/say a request instead of picking from a menu

## Known Constraints / Things to Remember
- Google will show a scary warning when enabling Accessibility for a non-Play app —
  expected, this is a personal/sideloaded app, not a supply-chain concern.
- Coordinates for taps are screen-absolute and **device-specific** — a skill recorded
  on your phone won't reliably work on a different screen resolution.
- Some apps (banking, some secure keyboards) block Accessibility-based input for
  security — Nyx won't be able to type into those fields; that's an OS-level protection,
  not a bug to "fix."
- Foreground service notification is required by Android 8+ to keep the overlay alive;
  it's set to minimum priority/silent already.

## One-Time GitHub Setup (do this once)
1. Create a new **private** GitHub repo, e.g. `nyx-pet`.
2. Push this whole `nyx/` folder to it as `main` branch.
3. Go to the repo's **Actions** tab → you'll see "Build Nyx APK" run automatically.
4. Click the finished run → scroll to **Artifacts** → download `nyx-debug-apk.zip`.
5. Unzip on your phone (or PC then transfer), tap the `.apk`, allow "install from
   unknown sources" when prompted, install.
6. Any time you (or I, in a future session) change code and push to `main`,
   a fresh APK builds automatically — no local build ever needed again.

## Next Session — Pick Up Here
Build **Phase 5: Result Logger**.
- Add a new StepType (e.g. `SAVE_RESULT`) that, when hit during replay, reads
  the currently-focused/selected on-screen text (via
  `AccessibilityNodeInfo` content, similar to how `typeIntoFocusedField` finds
  the focused node — but reading `.text` instead of setting it) and appends it
  with a timestamp to a file under `getExternalFilesDir(null)` named after the
  skill, e.g. `search_and_log_results.txt`.
- Recording side: add a "📝 Save Result Here" button to `RecordingOverlayService`
  that, like Tap capture, lets you pick which on-screen text to log (probably
  reuse the reticle-drag pattern — drag over the text you want captured, confirm,
  and read that node's text at record time to confirm it's readable).
- Consider a simple in-app screen (new Activity) to browse/view saved result
  files, since they'll otherwise only be visible via a file manager app.
