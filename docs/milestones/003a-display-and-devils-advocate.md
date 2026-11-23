# Milestone 003A — phone display and Devil's Advocate

Date: 23 November 2026.
Status: PARTIAL.
The portrait phone display is corrected and verified. The Devil's Advocate role is specified, and an assisted Claude critique prompt can be prepared. Automated review, objection storage, foldable-device testing, and the accuracy benchmark are not done. No live inference request was sent.

## 1. Emulator issue root cause

The Pixel 9a hardware profile was already a portrait phone: 1080×2424 at 420 dpi. The guest had a persisted Android display override:

- `settings get global display_size_forced` = `1920,1080`
- `settings get global display_density_forced` = `240`

`wm size` reported physical 1080×2424 and override 1920×1080. Window Manager then laid every application out in a 1920×1080, 240 dpi, 1280×720 dp landscape configuration while rotation stayed `ROTATION_0`. SurfaceFlinger mapped that framebuffer into the portrait panel at `physicalFrame=Rect(0, 908 - 1080, 1515)`, a 1080×607 region centered in the 2424-tall screen. That is the small landscape window inside the phone frame. The previous session's 1920×1080 UI dump came from this override, so the app selected the expanded two-pane layout from the guest window, not from the host desktop.

The override is the emulator Desktop display mode written into guest settings. It is not an application window, freeform task, or Compose calculation. Clearing it with `wm size reset` and `wm density reset` removes it without wiping user data. After reboot it stayed cleared.

## 2. Diagnostic commands and actual outputs

ADB is `C:\Users\sande\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

```
adb devices -l
emulator-5554  device product:sdk_gphone16k_x86_64 model:sdk_gphone16k_x86_64 device:emu64xa16k

adb shell getprop ro.build.version.sdk
37

adb shell getprop sys.boot_completed
1

adb shell wm size
Physical size: 1080x2424
Override size: 1920x1080

adb shell wm density
Physical density: 420
Override density: 240

adb shell settings get system accelerometer_rotation
1

adb shell settings get system user_rotation
0

adb shell settings get global display_size_forced
1920,1080

adb shell settings get global display_density_forced
240
```

`dumpsys window` before the reset:

```
init=1080x2424 420dpi
cur=1920x1080 app=1920x1080
sw720dp w1280dp h720dp 240dpi land
mBounds=Rect(0, 0 - 1920, 1080)
mDisplayRotation=ROTATION_0
```

`dumpsys display` before the reset:

```
logicalFrame=Rect(0, 0 - 1920, 1080)
physicalFrame=Rect(0, 908 - 1080, 1515)
deviceWidth=1080 deviceHeight=2424
mStableDisplaySize=Point(1080, 2424)
```

The focused activity at diagnosis was the launcher, with the same 1920×1080 bounds. `adb screencap` of that logical display was 1920×1080.

After the reset, and again after a full reboot:

```
adb shell wm size
Physical size: 1080x2424

adb shell wm density
Physical density: 420

display_size_forced=
display_density_forced=null
```

```
init=1080x2424 420dpi cur=1080x2424 app=1080x2424
sw411dp w411dp h923dp 420dpi port
mBounds=Rect(0, 0 - 1080, 2424)
```

Landscape rotation, then restored to portrait:

```
user_rotation=1
cur=2424x1080
sw411dp w923dp h411dp 420dpi land
mDisplayRotation=ROTATION_90
screencap 2424x1080

user_rotation=0
Physical size: 1080x2424
Physical density: 420
```

## 3. Emulator configuration before and after

Before, `Pixel_9a.avd\config.ini` already contained `hw.lcd.width=1080`, `hw.lcd.height=2424`, `hw.lcd.density=420`, `hw.initialOrientation=portrait`, `skin.name=pixel_9a`, and `hw.ramSize=2048`. Those lines were not changed. A copy is in `Pixel_9a.avd\backup-m003a\`. The snapshot hardware file also records 1080×2424. The bad size lived in guest settings, not in the AVD profile.

Launch used the existing AVD, `-no-snapshot-load`, `-memory 2048`, `-cores 2`, and `-gpu swiftshader_indirect`. The emulator still logged `Increasing RAM size to 4096MB`. User data was not wiped. No new AVD was created.

After the reset, the AVD profile is unchanged and the guest override is gone.

## 4. Whether the problem affected Android Settings

It affected every application. The launcher and the window configuration were 1920×1080 before any launch of AI Rachabanda. After the reset, Android Settings fills the portrait screen (`docs/milestones/003a-screenshots/00-settings-portrait.png`, 1080×2424).

## 5. Actual Android display resolution and density

1080×2424 physical pixels, 420 dpi, portrait. SDK 37 on the installed 16 KB page-size image. No size or density override remains.

## 6. Actual application window dimensions in dp

The activity bounds equal the display: 1080×2424 px, which is 411 dp wide and 923 dp high (`sw411dp w411dp h923dp`). That is a compact width. The status-bar cutout inset is 142 px at the top. Landscape rotation makes the same panel 923 dp wide and 411 dp high.

## 7. Emulator stability results

The diagnosis boot completed and stayed up through Settings, all five destinations, an open conversation, the keyboard, and rotation. The confirmation boot completed in 101381 ms and still reported the cleared override. No "System UI isn't responding" dialog appeared in this milestone. The image still raises RAM from the requested 2048 MB to 4096 MB and warns that 16384 MiB is suggested while 16075 MiB is available. Gradle was not run while the emulator was starting.

## 8. Application source changes

No source change was required to fill the phone window. The layout already uses the Android window size class.

After the display was verified, the assisted Devil's Advocate path was added:

- `Objection`, `ObjectionCategory`, `ObjectionSeverity`, and `ResolutionStatus` in the core model.
- `DevilAdvocatePolicy` and `CritiquePrompt`. Automated review stays off. A critique is prepared only when a provider response exists, and Custom depth zero prepares nothing.
- Council action "Prepare Claude critique", which stores an assisted Claude task for the next discussion round. The existing import path attaches the returned text to that task. The prompt says the review is not independent verification.

## 9. Portrait screenshots of all five destinations

Device `screencap` images, 1080×2424:

- `docs/milestones/003a-screenshots/01-home.png`
- `docs/milestones/003a-screenshots/02-council.png`
- `docs/milestones/003a-screenshots/03-activity.png`
- `docs/milestones/003a-screenshots/04-history.png`
- `docs/milestones/003a-screenshots/05-settings.png`

Also `00-settings-portrait.png`, `06-council-open.png`, and `07-keyboard.png`.

## 10. Landscape and adaptive layout test results

Portrait 411 dp uses the bottom bar and a single column. The open conversation "Council notes" keeps that bar. The keyboard opens with `mInputShown=true` and the message field remains visible.

Landscape 923 dp uses the navigation rail and the two-pane council. Screenshot: `docs/milestones/003a-screenshots/08-landscape-council.png` (2424×1080). Orientation was restored to portrait afterward.

Galaxy Z Fold 6 was not tested. No foldable emulator was created. Folded width would follow the compact phone rules; unfolded width would follow the expanded rules already used at 923 dp.

## 11. Devil's Advocate architecture decisions

The role is orchestration, not a fake message. It runs on responses already in the conversation. It may not invent evidence, treat agreement as proof, or emit a confidence percentage. Different hosts of the same model are not independent. No underlying model identity is stored yet, so every current critique is disclosed as assisted and not independent.

Quick plans one review. Standard plans one dedicated review. Deep Research plans up to three rounds, with an explicit stop when the budget is exhausted or remaining objections are unresolved. Custom uses the saved round limit and discloses reduced verification when that limit is zero. Paid inference is not permitted. Claude automation remains off, so the current path is the assisted prompt and import.

Full workflow, fields, and the held-out benchmark are in `docs/architecture/devils-advocate.md`. Objection rows are specified and are not a Room table yet. Original responses are not overwritten.

## 12. Updated documentation paths

- `docs/PROJECT_REQUIREMENTS.md`
- `docs/architecture/devils-advocate.md`
- `docs/TEST_PLAN.md`
- `docs/ROADMAP.md`
- `docs/milestones/003a-display-and-devils-advocate.md`

## 13. Build, lint, and test results

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\sande\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`BUILD SUCCESSFUL in 3m 2s`. Lint: no issues found.

9 tests, 0 failures, 0 errors. The new test is `devilAdvocatePreparesAnAssistedCritiqueAndDoesNotAuthorizeAutomation`. The two repository tests passed.

## 14. APK path

`C:\Users\sande\Desktop\AIAPP\AIRachabanda\app\build\outputs\apk\debug\app-debug.apk`

20,434,743 bytes. Gitignored.

## 15. Remaining limitations

- The 16 KB system image still raises emulator RAM to 4096 MB on a 16 GB host.
- Folded and unfolded Galaxy Z Fold 6 were not tested.
- Objection records are not in Room yet.
- Automated Devil's Advocate rounds do not run.
- Underlying model identity is not stored, so independence cannot be claimed.
- The accuracy benchmark has not been run, and no accuracy improvement is claimed.
- The new critique control was unit-tested. The portrait screenshots are from the corrected display before that control was added.

## 16. Local Git commit

`2611939` — Restore the portrait phone display and specify the Devil's Advocate.

## 17. Recommended next milestone

Milestone 004 should keep this AVD at 1080×2424 and 420 dpi, add the objection Room migration, and store model identity when a response actually includes it. One non-inference metadata read still needs a separate owner authorization. ChatGPT `/v1/responses` stays disabled. Foldable verification waits for hardware or a foldable emulator.

## 18. Final status

PARTIAL.

The letterboxed phone display is fixed and shown in portrait device screenshots. The Devil's Advocate role is specified and the assisted Claude prompt is in the app. The rest of the review engine and the benchmark are not implemented.
