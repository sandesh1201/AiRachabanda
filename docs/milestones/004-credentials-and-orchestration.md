# Milestone 004 — credentials and orchestration

Date: 23 November 2026.
Status: PARTIAL.

The portrait phone display remains corrected. Credential storage, the Room discussion schema, and the orchestration engine are implemented and covered by unit tests. The visible assisted critique action was exercised on the phone. At the time of this report the engine was not yet the path behind that button. Milestone 004A later connected it. The baseline for that work is commit `e418e1d`. No live provider request was sent. The owner instruction ended at "Required capabilities," so no assignment rule beyond complexity and required capabilities was added in Milestone 004.

## 1. Baseline

The working tree was at `c3ee921`, on `master`, and contained the Milestone 003A implementation. The debug APK then on disk was the 4:06:54 PM build.

| Check | Milestone 003A APK | Milestone 004 APK |
| --- | --- | --- |
| Size | 20,434,743 bytes | 20,101,308 bytes |
| SHA-256 | `F0FD3B59631E1F4B7258465CA2871B2CDC156F7A14DA12C1ADF7D30FE1287774` | `B64527D65B847C203E8387BB4D10A78AB44FA690B645913EA0857BBF400500BD` |
| Built | 23 November 2026, 4:06:54 PM | 23 November 2026, 4:34:34 PM |
| versionName / versionCode | 0.3.0 / 3 | 0.4.0 / 4 |
| Prepare Claude critique in classes.dex | Present | Present |
| Nonfunctional credential stub | Present | Absent |

The identical 20,434,743-byte length reported for Milestones 003 and 003A did not mean the packages were the same file. The 003A package already contained `Prepare Claude critique` in `classes4.dex`. Its hash cannot be compared with the Milestone 003 package because that older file had been overwritten. The Milestone 004 package is smaller and has a different hash.

The installed package after `adb install -r` is versionName 0.4.0 and versionCode 4.

## 2. Emulator

The Pixel 9a guest was booted with `-no-snapshot-load -memory 2048 -cores 2 -gpu swiftshader_indirect -no-boot-anim`. The emulator raised RAM to 4096 MB and completed boot in 59,067 ms. No "System UI isn't responding" dialog appeared during this session.

After boot, and again after the critique check:

```
adb shell wm size
Physical size: 1080x2424

adb shell wm density
Physical density: 420

adb shell settings get global display_size_forced
(empty)

adb shell settings get global display_density_forced
null
```

Window Manager:

```
init=1080x2424 420dpi
cur=1080x2424 app=1080x2424
sw411dp w411dp h923dp 420dpi port
mBounds=Rect(0, 0 - 1080, 2424)
mDisplayRotation=ROTATION_0
mWindowingMode=fullscreen
```

The application window is 411×923 dp, which is compact width. The screen uses the bottom navigation bar and one column. No display override was written. The existing AVD was not wiped.

## 3. Critique action on the phone

The existing "Council notes" conversation still held the imported Claude reply. In portrait, **Prepare Claude critique** was enabled across the full window. Tapping it showed:

"Claude critique prepared for round 2. Claude is not researching inside this app. This critique is assisted. No underlying model identity is on record, so it is not independent verification."

The new task is Claude, round 2, "Waiting for your response." Its prompt is the Devil's Advocate prompt and quotes "Claude reply via paste." Screenshots:

- `docs/milestones/004-screenshots/01-council-critique-portrait.png`
- `docs/milestones/004-screenshots/02-critique-prepared-portrait.png`
- `docs/milestones/004-screenshots/03-critique-task-portrait.png`

This button still uses the assisted-task path. It does not call `OrchestrationEngine`.

## 4. Credentials

`AndroidKeystoreGateway` replaces the gateway that refused to store a secret. Each provider account gets its own Android Keystore AES-256 key. Ciphertext is AES-GCM with a cipher-generated 12-byte initialization vector and a 128-bit tag. Files live under the app's private `credentials` directory. Backup rules already exclude files. The key does not require biometric authentication or an unlocked device.

Unit tests, using synthetic secrets and an in-memory key source behind the production envelope, passed for encryption, replacement, deletion, provider isolation, restart, tampering, a missing key, and concurrent replacement. A Robolectric test checks that the key specification is 256-bit and does not require user authentication. The JVM tests do not invoke the AndroidKeyStore provider itself. No real API key was requested or stored.

Saving "Not configured" deletes that provider's default credential. Deleting local content deletes all stored credentials.

## 5. Database

Schema version is 2. `Migrations.MIGRATION_1_2` adds discussion tables and nullable or defaulted columns. A test builds a version 1 database with a conversation, message, assisted task, and imported response, then opens it with Room and the migration. The rows survive, both model-identity columns are `unknown`, and the new tables exist. Room's schema check runs on open. Production does not call `fallbackToDestructiveMigration`.

## 6. Orchestration

`OrchestrationEngine` persists session state, task state, checkpoints, and budget entries. Assisted work does not block an automated participant. Automated work is refused unless `ZeroCostPolicy` allows it and the budget can pay for the request. ChatGPT and Claude stay ineligible. Critique rounds stop at the configured limit. A final conclusion does not delete preliminary conclusions or open objections. Pause, resume, cancel, deadline expiry, and the follow-up round limit are tested.

Roles come from question complexity and required capabilities only. Low complexity assigns research and synthesis. Adding the critique capability also assigns a Devil's Advocate, using a different participant from the final synthesizer when two participants are eligible. The instruction stopped at "Required capabilities," so no other assignment inputs were invented.

## 7. Build

`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`

BUILD SUCCESSFUL. Lint report: no errors or warnings. Unit tests: 20 tests, 0 failures.

- CouncilRepositoryTest: 3
- CredentialStoreTest: 3
- DiscussionMigrationTest: 1
- KeystoreSpecTest: 1
- OrchestrationEngineTest: 5
- PolicyTest: 7

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## 8. Limitations

- The orchestration engine is in the application container and unit tests, not behind the visible council buttons.
- AndroidKeyStore was not called from the unit tests. The production key source is `AndroidKeystoreKeySource`.
- No Galaxy Z Fold 6 check and no new landscape pass in this milestone. The portrait window was rechecked.
- No live inference, no paid request, and no external account.
- Accuracy is not claimed.
- Changes are not committed. The milestone text did not request a commit.

## 9. Recommended next milestone

Connect the existing council actions to `OrchestrationEngine` without a new navigation structure, while the Pixel 9a guest stays at 1080×2424 and 420 dpi. Keep ChatGPT `/v1/responses` disabled.
