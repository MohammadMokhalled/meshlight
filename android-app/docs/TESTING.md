# MeshLight verification and testing strategy

The project has one Gradle module, `:app`, which contains the application and its test artifacts. There are no downstream Gradle project consumers.

## Verified on 25 October 2026

| Check | Result |
| --- | --- |
| Local JVM/Robolectric tests | 44 passed, 0 failures |
| Compose screenshot comparisons | 132 passed, 0 failures |
| Android instrumentation on API 37 / Android 17 | 4 passed, 0 failures |
| Debug and unsigned release APK builds | Passed |
| Android lint | Passed with warnings; no errors |
| Default debug build with configuration cache enabled | Passed |
| Tracked-file whitespace check | Passed |
| Android Studio project sync | Passed |
| IDE analysis of activity, navigation, mesh gateway and metadata storage | No errors; warnings remain |

The emulator smoke check covered cold startup, explicit demo entry, light details and the LED editor, creation of a real local mesh home, Bluetooth permission explanation/cancellation, and reopening that home after a process restart. The instrumented activity test verifies that power changes preserve RGB and brightness and remain visible after activity recreation. No physical light was provisioned during these checks.

## Test layers

CI integration was checked locally on 28 October 2026: 61 JVM/Robolectric
cases, 132 screenshot comparisons, and four API 37 emulator instrumentation
tests passed. Debug, unsigned release, instrumentation APK builds, coverage,
and lint also passed (lint warnings remain). The first instrumentation attempt
hit host native-memory exhaustion; retrying with two Gradle workers and a
1 GiB daemon heap passed. Hosted workflow execution remains to be verified
after pushing the workflow changes.

| Layer | Coverage | Location |
| --- | --- | --- |
| JVM protocol and planning | Vendor payload validation, standard short/extended statuses, transition conversion, selection planning, property preservation, gradients, scheduling and transaction IDs | `app/src/test/java/com/example/meshlightcontroller/` |
| Robolectric workflows | Offline drafts, explicit apply, partial recovery, encrypted transfer and CDB validation, sequence watermark and ownership, demo sessions, schema migration and large homes | Same local test directory |
| Robolectric Compose UI | Explicit demo entry, power-only edits, unknown values, confirmations, live updates in navigation entries, tab retention, saved-instance restoration, accessible creation actions | `MeshUiTest.kt` |
| Compose screenshots | Twelve screens at nine window sizes, dark appearance and 1.5× font scale; 132 comparisons | `app/src/screenshotTest/kotlin/` and `app/src/screenshotTestDebug/reference/` |
| Android instrumentation | Real Hilt activity startup, demo control and activity recreation, SQLite persistence and snapshots larger than 2 MiB | `app/src/androidTest/java/com/example/meshlightcontroller/` |
| Physical firmware acceptance | Provisioning, proxy bearers, actual status packets, group subscriptions, interrupted configuration, radio loss and multi-phone transfer | Hardware checklist in [README](../README.md#hardware-acceptance-before-release) |

### Backup restore persistence regression

An import must wait for an older save already in flight, and queued autosave
snapshots must not overwrite the imported home. Repository persistence now
serializes autosaves, explicit saves, and metadata replacement. Import publishes
the replacement state before re-enabling control, and updates ownership and
network selection in one preferences edit. Rebinding is serialized with restore.

`BackupWorkflowTest.importWaitsForInFlightAutosaveAndKeepsRestoredMetadata`
holds a real autosave inside serialization while starting an import. It checks
that import waits, then verifies both the active state and SQLite retain the
imported metadata. This regression failed before the fix and passed afterward;
the existing encrypted transfer/rejection/restore assertions remain enabled.
The test also pauses the SDK node merge during rebind and requires `loading`
to stay true. Readiness is published only after nodes, rooms, and listeners are
bound, so observers cannot mistake an intermediate empty home for a loaded one.
The complete JVM suite, 132 screenshot comparisons, APK builds, coverage, lint,
and four instrumentation tests on a fresh API 37 emulator passed after this fix.

### Provisioning address regression

The first physical setup exposed a controller/light unicast collision at `0x0001`. Provisioning completed, but Zephyr rejected subsequent Proxy Configuration packets because their source matched its own address. The phone's mesh database confirmed both nodes at that address. The supplied logcat captured scanning after the failure, without the provisioning exchange.

Nordic Mesh 3.5.0 initially stores `0x0001` as the next provisioning address. Its `startProvisioning()` checks that a free address exists, but sends the separately stored address. The gateway now calls the SDK allocator with the received element count and assigns the result immediately before starting provisioning. Before opening a proxy or provisioning connection, it also repairs existing collisions through `updateProvisioner()`, moving the local controller while retaining the light and its device key. The existing sequence watermark is restored for the controller's new source address.

`MeshAddressingTest` exercises the pinned SDK's real network model: first-light allocation, changing element counts, exclusions, address exhaustion, collision recovery, credential preservation, and an unchanged healthy controller. These tests do not establish successful BLE exchange with hardware; retry **Finish setup** with the updated APK for that check.

## Run the checks

Use JDK 25 and the checked-in wrapper. In PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:jacocoDebugReport --no-configuration-cache
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug --no-configuration-cache
.\gradlew.bat :app:validateDebugScreenshotTest --no-configuration-cache
.\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
```

For repeated work, filter local tests with `--tests 'com.example.meshlightcontroller.ProtocolTest'`. Quote Gradle instrumentation property arguments in PowerShell, for example `'-Pandroid.testInstrumentationRunnerArguments.class=com.example.meshlightcontroller.ControllerActivityTest'`.

The instrumentation runner uses `HiltTestApplication`; activity tests use `HiltAndroidRule` before the Compose activity rule. Debug and instrumentation configurations explicitly use the same JUnit 4.13.2 dependency so AGP's consistent dependency resolution does not select the older JUnit transitively supplied by debug tooling.

## Reports and artifacts

### PR and CI automation

The repository's [shared validation workflow](../../.github/workflows/firmware.yml)
runs every Android layer when any file under `android-app/` changes. Host tests,
screenshots, lint, coverage, debug/release APK builds, and compilation of the
instrumentation APK run on Windows. Instrumentation runs on an API 37 x86_64
Google APIs emulator on Ubuntu. Both jobs verify the workflow's exact Git tree
and use JDK 25, SDK `android-37.0`, build tools 36.0.0, and the checked-in wrapper.
CI validates the checked-in screenshot references without updating them.

Unchanged PRs skip Android jobs. Unchanged push/tag CI reuses APKs from a
successful ancestor push run with exactly matching Android source and validated
checksums/test evidence. If none is available, all Android checks and builds run.
The required gate rejects unexpected skips or failures. See
[CI behavior and downloads](../../docs/ci.md#android-validation-and-reuse).

The `android-apks` Actions artifact contains both debug and unsigned release
APKs, original build/test metadata, and SHA-256 checksums. Reports and logs are
uploaded separately, including available diagnostics on failure, for 30 days.
Final tagged GitHub Releases also contain both APKs permanently. App versions
remain controlled by the Gradle build file; no release signing is configured.

### Local output paths

- [Local tests](../app/build/reports/tests/testDebugUnitTest/index.html)
- [Coverage](../app/build/reports/jacoco/jacocoDebugReport/html/index.html)
- [Screenshot comparisons](../app/build/reports/tests/validateDebugScreenshotTest/index.html)
- [Android instrumentation](../app/build/reports/androidTests/connected/debug/index.html)
- [Android lint](../app/build/reports/lint-results-debug.html)
- [Installable debug APK](../app/build/outputs/apk/debug/app-debug.apk)
- Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

Generated reports and APKs are build outputs, not source-controlled artifacts. The standalone screenshot plugin used with AGP 9.4.1 is deprecated; migration to AGP test suites requires 9.5.0-alpha03 or higher and is a separate upgrade.

## Scope of the evidence

Screenshots are regression references and were visually reviewed at representative phone, tablet, dark and large-text configurations. They do not establish TalkBack quality or every device's inset behavior. Android lint and the IDE's visual inspections are separate tools; visual inspection warnings are not all resolved.

The JaCoCo report covers application backend code and excludes UI and generated classes. Nordic SDK radio paths are only partially covered. A passing report does not establish radio interoperability, physical LED output, power-loss persistence or mesh replay behavior across phones. The physical firmware acceptance checklist remains required before release.
