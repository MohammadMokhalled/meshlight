# Validation record

[Project overview](../README.md) · [Run tests](testing.md) · [Physical checks](testing.md#physical-checks)

These dated records describe validation at earlier implementation stages.
They are not a promise that every later revision has been tested on hardware.
The referenced files under `build/` are ignored local artifacts and are not
included in a fresh clone. Use the [test commands](testing.md) to reproduce
automated checks, or download reports from an available Actions run.

During the documentation review on **2026-10-22**, all **34 Python tooling tests**
passed. Existing `build/rtw/twister.json` and `build/citw/twister.json` reports
were inspected: each records all five scenarios and 76 cases passing.
The CI report validator accepted the latter report. Workflow linting, local
documentation links/anchors, and Bash/PowerShell example syntax also passed.
The lighting C example passed a syntax-only check using the compiler and headers
from an existing nRF54L15-DK build. Firmware tests, full board builds, and
physical checks were not rerun for this documentation-only change.

## MIT license adoption — 2026-10-22

Added the project MIT license, source SPDX declarations, and license inclusion
in firmware packages. Package checksums cover the license, and release
validation rejects packages that omit it.

| Check | Result |
| --- | --- |
| Python tooling tests | 36 passed, including missing/empty license and missing packaged license checks |
| Zephyr checkpatch, seven C/header/Kconfig files | Zero errors and warnings; license-tag checks enabled |
| `git diff --check` | Passed |

## Automatic refresh validation — 2026-10-22

This record predates adoption of the MIT license and SPDX declarations.

Validation used the same nRF Connect SDK 3.3.0, Arm GCC 12.2.0, and QEMU 11.1.0
environment as the earlier checks below. Only process-local SDK/Git settings
were used, and both board configurations remain unchanged.

| Check | Result |
| --- | --- |
| Manual mode, 1 / 6 / 17 LEDs | 20/20 cases per scenario; 60 passed |
| Automatic refresh, default 50 ms | 8/8 cases passed |
| Automatic refresh, custom 20 ms | 8/8 cases passed |
| nRF54L15-DK production build | Passed; 46940 bytes flash and 10864 bytes RAM |
| NUCLEO-WB55RG production build | Passed; 40340 bytes flash and 9152 bytes RAM |
| Zephyr checkpatch, ten changed code/configuration files | Zero errors and zero warnings, excluding only missing license tags |
| `git diff --check` | Passed |
| `native_sim` under WSL | Attempted; blocked by Ubuntu's missing `ext4.vhdx` (`Wsl/Service/CreateInstance/MountDisk/ERROR_PATH_NOT_FOUND`) |
| Flashing / physical waveform verification | Outside this change; not performed |

The final `build/rtw/twister.json` records all five scenarios as passed: 76 cases
in total. The automatic scenarios were rerun after selecting a 1000 Hz test
clock; the 60 passing manual cases were retained from the initial run. This
resolves injected delays that the default QEMU 10 ms tick rounded excessively.
The production board tick rates were not changed. Logs are
`build/refresh-twister.log`, `build/refresh-twister-retry.log`,
`build/refresh-dk-build.log`, and `build/refresh-wb55-build.log`.

Twister's read-only SDK parser-cache warnings and existing short-path junction
messages did not prevent building or executing tests. No project SPDX license
declarations were added. Physical timing and output still need the
[manual checks](testing.md#physical-checks); emulator tests do not establish
real-board scheduling bounds.

## Per-LED RGB/intensity validation before automatic refresh — 2026-10-22

Validation used nRF Connect SDK 3.3.0 (Zephyr 4.3.99), Arm GCC 12.2.0, and
QEMU 11.1.0 on Windows. SDK environment and Git access settings were applied
to the validation processes only. The Nucleo build used the existing local
STM32 HAL dependency; no SDK manifest or global configuration was changed.

| Check | Result |
| --- | --- |
| QEMU, 1 LED | 20/20 Ztest cases passed |
| QEMU, 6 LEDs | 20/20 Ztest cases passed |
| QEMU, 17 LEDs | 20/20 Ztest cases passed |
| nRF54L15-DK production build | Passed; 46352 bytes flash and 9168 bytes RAM |
| NUCLEO-WB55RG production build | Passed; 39724 bytes flash and 7424 bytes RAM |
| Zephyr checkpatch, all five changed C/header files | Zero errors and zero warnings |
| `git diff --check` | Passed |
| `native_sim` under WSL | Blocked: Ubuntu's `ext4.vhdx` path is missing (`Wsl/Service/CreateInstance/MountDisk/ERROR_PATH_NOT_FOUND`) |
| Flashing / visible strip output / timing waveform | Not performed for this revision |

The final Twister report is `build/rgbtw/twister.json`; production build logs
are `build/rgb-dk-build.log` and `build/rgb-wb55-build.log`. All three test
configurations passed in one run. Twister emitted startup parser-cache warnings
because the SDK directory is read-only; these did not prevent building or
executing tests. Hardware acceptance still requires the
[physical checks](testing.md#physical-checks).

## Historical validation

These results describe the earlier uniform-color implementation and do not
validate the current per-LED API or gamma pipeline.

| Date | Check | Recorded result |
| --- | --- | --- |
| 2026-10-20 | nRF54L15-DK application build | Passed |
| 2026-10-20 | QEMU, 1 / 6 / 17 LEDs | 19/19 cases per scenario |
| 2026-10-20 | Zero-length configuration | Rejected by the expected build assertion |
| 2026-10-20 | Checkpatch / whitespace | Passed |
| 2026-10-21 | NUCLEO-WB55RG application build | Passed |
| 2026-10-21 | ST-LINK flash and readback verification | Passed |
| 2026-10-21 | UART startup | Captured `Lighting demo started` |
| Both dates | Visible strip output / timing waveform | Not physically verified |
