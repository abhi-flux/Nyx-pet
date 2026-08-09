# Nyx — Project Progress

Android on-screen pet assistant. Trained skill-by-skill to perform on-screen actions
(open apps, search, tap, type, record results to a file) via Accessibility Service.
Budget: ₹0 — Kotlin + Android Studio + sideloaded APK, no Play Store, no paid APIs yet.

**How to use this file:** upload this + the current `/nyx` code folder into a new chat
with any LLM to resume work exactly where we left off. Update the checklist and the
"Current State" section after every change.

---

## Current State (as of Phase 3 drop)
- Project builds and installs cleanly via GitHub Actions CI, signed with a
  persistent key (see Signing section below) — updates install in place now.
- `MainActivity`: onboarding screen, requests overlay + accessibility permissions.
- `PetOverlayService`: draggable floating bubble. A genuine tap (not a drag,
  <20px movement) now launches the Skill Recorder.
- `NyxAccessibilityService`: working `tapAt()`, `swipe()`, `typeIntoFocusedField()`.
- `RecordingOverlayService` (NEW): control bar with Tap / Type / Wait / App / Save.
  Tap → next real screen touch is captured as (x,y) AND actually performed live.
  Type → dialog for text, typed into focused field live, recorded as a step.
  Wait → adds a fixed 1s delay step.
  App → pick an installed app, launches it live, recorded as a step.
  Save → names the sequence and stores it permanently via Room (`skills` table,
  steps serialized to JSON with Gson).
- Nothing replays a saved skill yet — that's Phase 4.

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
- [ ] Phase 4 — Skill Trigger Engine: tap Nyx → menu of taught skills → replay saved
      JSON sequence step by step, waiting for each screen to load
- [ ] Phase 5 — Result Logger: each skill run appends/updates a result file
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
Build **Phase 4: Skill Trigger Engine**.
- Tapping Nyx currently always starts recording — needs to become a menu:
  "Record New Skill" vs "Run a Skill" (query `NyxDatabase.get(context).skillDao().getAll()`)
- A `SkillPlayer` class that takes a `SkillEntity`, deserializes `stepsJson` back to
  `List<SkillStep>` with Gson, and walks through them in order:
  - TAP → `NyxAccessibilityService.instance?.tapAt(step.x!!, step.y!!)`
  - TYPE → `NyxAccessibilityService.instance?.typeIntoFocusedField(step.text!!)`
  - WAIT → `delay(step.delayMs)` (needs a coroutine)
  - OPEN_APP → launch via `packageManager.getLaunchIntentForPackage(step.packageName!!)`
- Important: real apps take variable time to load. A fixed WAIT step recorded once
  may not be reliable every run — consider waiting for `onAccessibilityEvent`
  window-changed signals instead of/in addition to fixed delays, if replay proves flaky.
