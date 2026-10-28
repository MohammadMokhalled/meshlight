# MeshLight for Android

A local Bluetooth Mesh controller for the firmware in `../firmware`. The app uses Kotlin, Jetpack Compose Material 3, Navigation 3, Hilt, Room, DataStore, and the unmodified Nordic Android Mesh library.

## Use the app

1. Create a home, or choose **Explore a demo home** to try virtual lights. Demo mode is explicit and never sends Bluetooth commands.
2. Power on an unprovisioned light nearby. Choose **Add a light**, allow Bluetooth access, search, and name the light. Provisioning and configuration run directly over Bluetooth; no other provisioner app is needed.
3. If setup is interrupted after provisioning, the light stays in the home with **Finish setup**. Keep it powered on and retry.
4. Change whole-light power, RGB color, or brightness independently. The grid icon opens the LED editor. Tap one LED, use **Select LEDs** for multiple LEDs, or select a numbered range. Reverse display order changes presentation only.
5. Create **Rooms** for whole lights. The app configures subscriptions on Generic OnOff, Generic Level, and MeshLight vendor models. A light can join four rooms. Partial changes remain visible and can be retried.
6. Create **LED zones** for individual LEDs, including selections across lights. Preview changes and tap **Apply**. These are paced unicast operations, so LEDs do not all change at exactly the same instant.
7. Capture a **Preset** from selected LEDs, or save a static gradient or alternating color pattern. Patterns change RGB only. Captures read missing or stale LED values before saving.

The theme supports light, dark, and system appearance. Device colors are optional. Navigation uses bottom tabs on phones and a rail on wider windows; Navigation 3 scene strategies handle list/detail layouts. Tab back stacks and editor selections survive saved-instance restoration.

## Understand light state

- Unknown, mixed, last-known, preview, and reported values are distinct.
- Offline edits remain previews. Reconnection does not silently apply them. Connect and use **Apply changes**.
- A mixed brightness value is an average. Moving the control sets uniform brightness for the selected LEDs.
- Color edits preserve brightness and power; brightness edits preserve RGB and power.
- Whole-light power and brightness support transitions. RGB changes are immediate.
- An acknowledgement confirms firmware RAM state, not physical LED output.
- Firmware saves RGB and brightness after approximately one second. Power is runtime state; rebooting the firmware enables the LEDs again.
- **Find this light** uses Health Attention on the board status LED, rather than blinking the LED strip.

The app reads visible LEDs first and virtualizes long strips. The firmware supports 1–339 LEDs per node. The initial hardware acceptance target is up to 20 lights.

## Bluetooth and lifecycle

Android 12 and later need Nearby Devices permissions. Android 7–11 need fine location permission and enabled location services for Bluetooth scanning. Bluetooth must also be on. The app explains denied permissions and links to app settings when necessary.

One proxy connection is active at a time. Proxy advertisements are checked against the selected network and controller reply filtering is configured before control starts. Discovery, provisioning, reads, and large edits are bounded or cancellable. Bluetooth work stops when the app leaves the foreground.

If an earlier build added a light but firmware reported `Failed to decode Proxy Configuration (err -2)`, install the updated app and choose **Finish setup** on that light. The app repairs a controller/light address collision before reconnecting, preserving the light's mesh credentials. A factory reset is unnecessary for this case.

Commands are globally paced, slider edits are coalesced, and final edits are verified. Retries preserve transaction identity. Failed and skipped targets stay as explicit drafts. Pending packets are bound to their network and connection generation.

If a foreground Bluetooth link drops, final edits submitted on that link remain pending and are sent again after the proxy reconnects. Preview samples are discarded. Editing a light while offline keeps its drafts for explicit Apply and replaces any older reconnect intent for that light. Cancellation, backgrounding, and network changes clear reconnect work.

While a proxy is connected, a two-second GATT descriptor read keeps an idle link active without changing mesh keys, sequence numbers, or the proxy filter. A failed read reconnects the proxy. The STM32WB firmware requests a four-second supervision timeout to tolerate brief radio contention.

## Network ownership, backup, and recovery

This app is designed for one owner phone at a time.

**Back up network** writes a password-encrypted MeshLight backup containing the Mesh CDB and app metadata: names, rooms, zones, presets, and saved colors. The portable format uses Argon2id and AES-256-GCM with authenticated parameters and random salt/nonce. Passwords are not saved in instance state or preferences.

**Transfer to another phone** writes the encrypted file first, then disables control of that network on this phone. Import the file on the new phone. Keep the old phone from controlling the same network.

**Import a network** also accepts a standard Mesh CDB. A CDB is unencrypted and contains network and device keys; export requires an explicit notice. Import validates credentials and address ranges, refuses to overwrite an existing live network, and allocates a fresh controller identity and address range. Imported observations become stale. Sequence counters from the previous controller are never used as the new controller's transmit counters.

The Nordic database remains the authority for mesh keys, addresses, IV state, and protocol state. App metadata lives in a separate Room database. Large snapshots are split into small, atomically saved rows so a 20-light home with 339 LEDs per light and detailed presets does not exceed Android SQLite cursor limits. Schema 1 snapshots are migrated without discarding existing homes. A synchronous local sequence watermark is committed before transmitting, and local controller ownership is recorded separately. Automatic cloud backup and device-transfer backup are excluded. Storage uses standard app-private Android storage, as selected in the approved plan; the Nordic library is not forked.

The app includes two small SDK-package Java bridges for network selection and Health Attention. They target Nordic Mesh 3.5.0 without modifying its library. Review these bridges and rerun protocol and ownership checks before upgrading that dependency.

Use **Mesh settings** under light information for TTL, relay, and proxy settings. Settings are read before writes. Disabling a current proxy requires confirmation because it can disconnect the phone.

**Factory reset light** resets both mesh credentials and saved lighting. **Forget light on this phone** leaves the physical device provisioned; its old address remains reserved. Related zones and presets are invalidated after reset, forgetting, or changed capabilities. A partly removed room keeps its address until all removals are confirmed.

Physical reset: hold firmware button 0 for **more than 30 seconds**, then release. Exactly 30 seconds does not reset it.

## Build and test

Use the checked-in Gradle wrapper with JDK 25 (the Android Studio runtime in the current environment), SDK 37, and min SDK 24.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:jacocoDebugReport --no-configuration-cache
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-configuration-cache
.\gradlew.bat :app:assembleRelease --no-configuration-cache
.\gradlew.bat :app:validateDebugScreenshotTest --no-configuration-cache
```

The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Release signing is not configured.

Local tests cover protocol vectors, malformed statuses, level/transition conversion, planning, scheduling, transaction spacing, encrypted backup compatibility, metadata, offline apply, partial recovery, UI behavior, and state restoration. Robolectric runs UI tests without a device; its Android 16 tests include the required JDK module access flags.

Screenshot tests cover 12 screens across nine width/height combinations plus dark theme and 1.5× text, for 132 cases. References are in `app/src/screenshotTestDebug/reference/`. After an intentional visual change, run `:app:updateDebugScreenshotTest`, inspect the images, then run validation again.

The standalone Compose screenshot plugin matches AGP 9.4.1. This plugin is deprecated; AGP test suites become available starting with 9.5.0-alpha03. An AGP upgrade is a separate change.

Verification completed on 25 October 2026: **44 local tests, 132 screenshot comparisons, and 4 Android 17 instrumentation tests passed**. Debug and unsigned release builds passed; Android lint passed with warnings. See [testing strategy and validation limits](docs/TESTING.md).

Reports:

- Unit/UI tests: `app/build/reports/tests/testDebugUnitTest/index.html`
- Coverage: `app/build/reports/jacoco/jacocoDebugReport/html/index.html`
- Screenshot validation: `app/build/reports/tests/validateDebugScreenshotTest/index.html`
- Android lint: `app/build/reports/lint-results-debug.html`

On a connected device or emulator, run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
```

The instrumentation runner uses Hilt's test application. Future injected activity tests should use `@HiltAndroidTest` and `HiltAndroidRule`. Database tests exercise Android SQLite. Bluetooth provisioning still requires real BLE hardware.

## Hardware acceptance before release

The implementation and local tests do not establish radio interoperability. Verify these flows with the firmware:

- Provision the default six-LED board and 17-, 300-, and 339-LED configurations; interrupt each setup stage and resume.
- Test permission denial, permanent denial, Bluetooth off, proxy loss, app backgrounding, process restart, and phone reboot.
- Verify full RGB, independent power/brightness, mixed strip states, indexed selection boundaries, whole-strip targeting, fades, and reset persistence.
- Test four room subscriptions per light, fifth-room rejection, offline membership changes, partial deletion, retry, and address retention.
- Apply/cancel long zones and presets; verify failed targets remain recoverable and other properties are preserved.
- Transfer an encrypted backup to a different phone, reject wrong passwords and damaged files, reject an overwrite, verify a fresh controller source, and verify the original phone is disabled.
- Test relay paths and repeated reconnects with up to 20 nodes. Confirm sequence continuity and transaction behavior across app/phone restarts.
- Exercise TalkBack, large text, keyboard input, rotation/folding/resizing, cutouts, three-button/gesture navigation, IME, and dark mode on devices.

Cloud accounts, internet control, background schedules, music/animation streaming, and firmware updates are outside this release.
