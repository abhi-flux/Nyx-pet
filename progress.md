# Nyx — Project Progress

Android on-screen pet assistant. Trained skill-by-skill to perform on-screen actions
(open apps, search, tap, type, record results to a file) via Accessibility Service.
Budget: ₹0 — Kotlin + Android Studio + sideloaded APK, no Play Store, no paid APIs yet.

**How to use this file:** upload this + the current `/nyx` code folder into a new chat
with any LLM to resume work exactly where we left off. Update the checklist and the
"Current State" section after every change.

---

## Current State (as of Phase 0–2 drop)
- Project skeleton created, compiles as an installable APK once opened in Android Studio.
- `MainActivity`: onboarding screen, requests overlay + accessibility permissions.
- `PetOverlayService`: draws a draggable floating bubble ("🐾" placeholder) on top of all apps.
- `NyxAccessibilityService`: stub with working `tapAt()`, `swipe()`, `typeIntoFocusedField()` —
  Nyx's "hands," not yet wired to any trigger.
- No skill recording/storage yet. No AI/NLP yet. No result-file logging yet.

## Architecture Decisions
- **Language:** Kotlin, native Android (not Flutter/React Native) — best access to
  Accessibility APIs and lowest overhead for a background overlay service.
- **Min SDK:** 26 (Android 8.0+) — required for reliable `dispatchGesture`.
- **Storage:** Room (SQLite, on-device) for skills — no server, no cost, works offline.
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
  - No `gradlew`/wrapper jar committed — CI installs Gradle directly via
    `gradle/actions/setup-gradle`, so nothing to keep in sync manually.

## Phase Checklist
- [x] Phase 0 — Project skeleton, manifest, permissions flow
- [x] Phase 1 — Floating draggable pet overlay
- [x] Phase 2 — Accessibility service stub: tapAt / swipe / typeIntoFocusedField
- [x] Phase 2.5 — CI build pipeline: GitHub Actions builds APK, no local Android Studio
- [ ] Phase 3 — Skill Recorder: capture a real action sequence (taps, swipes, text,
      delays, screenshots of what was tapped for reference) → save as JSON via Room
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
Build **Phase 3: Skill Recorder**.
- Add Room DB (`Skill` entity: id, name, trigger phrase, list of `SkillStep`)
- `SkillStep`: action type (TAP / SWIPE / TYPE / WAIT / OPEN_APP), x, y, text, delayMs
- Recording mode: overlay shows "● Recording" while `NyxAccessibilityService` logs
  every gesture/text entry the user performs, in order
- Save button in `MainActivity` (or a new `SkillListActivity`) to name and store the skill
