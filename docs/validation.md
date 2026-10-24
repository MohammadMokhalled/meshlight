# Validation record

[Project overview](../README.md) · [Run tests](testing.md) · [Physical checks](testing.md#physical-checks)

These dated records describe validation at earlier implementation stages.
They are not a promise that every later revision has been tested on hardware.
The referenced files under `build/` are ignored local artifacts and are not
included in a fresh clone. Use the [test commands](testing.md) to reproduce
automated checks, or download reports from an available Actions run.

## Automatic persistence simplification — 2026-10-24

All 17 QEMU scenarios and 291 test cases passed, with no skipped cases. The CI
report validator, C style checks, whitespace checks, and updated documentation
links passed. Coverage includes v2 colors/intensities, both legacy power values,
zero intensity, missing/corrupt/future/oversized records, backend failures,
debounce without checkpoints, power-only changes, edits during writes, stale
queued callbacks, retry backoff, and backend physical duplicate-write avoidance.

Both physical board targets built with the developer shell enabled and disabled.
The nRF `build/dk-dev` image was rebuilt but not flashed. These checks did not
verify physical flash retention or interrupted-write recovery on hardware.

| Board / shell | Flash | RAM |
| --- | --- | --- |
| nRF54L15 / off | 62,208 B | 18,736 B |
| nRF54L15 / on | 93,692 B | 23,576 B |
| STM32WB55 / off | 52,648 B | 17,088 B |
| STM32WB55 / on | 84,880 B | 22,080 B |

Local artifacts: `build/simple-persistence-tests/`,
`build/simple-persistence-tests.log`, `build/simple-persistence-checkpatch.log`,
and `build/simple-persist-*.log`. The [manual retention checks](storage.md#manual-retention-checks)
include restoration with power on after a saved selection was switched off.

## Separate lighting shell controls — 2026-10-24

All 13 QEMU scenarios and 177 cases passed, including partial color/intensity
updates, field preservation, concurrent setters, automatic persistence, invalid
shell arguments, and rejection of the removed shell save/restore commands.
The CI report validator, C style checks, whitespace checks, and updated
documentation links passed. Both physical board targets built with the shell
enabled and disabled; the nRF `build/dk-dev` image was also rebuilt.

Local artifacts are `build/shell-controls-tests/`, `build/shell-controls-tests.log`,
`build/shell-controls-checkpatch.log`, and `build/shell-controls-persist-*.log`.
These checks did not flash or exercise the updated firmware on hardware.

## Module layout — 2026-10-23

After grouping sources under `src/lighting/` and `src/storage/`, all 13 scenarios
and 162 test cases passed again. Both board targets built with the developer
shell enabled and disabled, with unchanged flash/RAM usage. The CI report
validator, C style checks, whitespace checks, and documentation links passed.
Local artifacts are `build/layout-verified/`, `build/layout-tests.log`,
`build/layout-checkpatch.log`, and `build/layout-persist-*.log`.

## Persistent settings and developer shell — 2026-10-23

Validated with nRF Connect SDK 3.3.0 and its matching Windows toolchain. Both
products use explicit application/storage partitions and boot at flash offset
zero. The STM32 Settings partition was moved inside its declared 808 KiB flash
range; compile-time checks reject partition bounds errors and code overlap.

| Check | Result |
| --- | --- |
| nRF54L15 production build | Passed; 62976 bytes flash and 18792 bytes RAM |
| nRF54L15 developer-shell build | Passed; 94448 bytes flash and 23632 bytes RAM |
| STM32WB55 production build | Passed; 53404 bytes flash and 17152 bytes RAM |
| STM32WB55 developer-shell build | Passed; 85636 bytes flash and 22144 bytes RAM |
| QEMU firmware tests | Passed; all 13 scenarios and 162 cases, no skips |
| CI report validator | Passed against the fresh test plan |
| Python tooling tests | Passed; all 36 tests |
| Workflow lint, C/Kconfig style, whitespace | Passed |
| Flashing, UART interaction, physical power cycles | Not performed |

The Settings tests exercise actual NVS/ZMS backends with simulated NOR/RRAM,
payload bit flips, failed writes, live backend reconstruction, and sector
rotation. Lighting tests include six boot conditions, save timing and retries,
concurrent setters during storage I/O, restore races, malformed/oversized records,
explicit repair after failed restore, and shell command parsing. The production
ELFs/configurations were also checked for CRC enablement, flash bounds, zero boot
offset, shell exclusion, and the appropriate logging backend.

Local artifacts: `build/persist-verified/twister.json` and `testplan.json`,
`build/persist-verified.log`, `build/persist-python-tests.log`,
`build/persist-checkpatch-final.log`, `build/persist-repair-checkpatch.log`, and
the `build/persist-{dk2,dk-shell,wb55,wb55-shell}` build directories with their
corresponding `-final.log` files. These builds resolve the normal Git version.
See [manual retention checks](storage.md#manual-retention-checks) for remaining
hardware validation. No claim of physical retention or flash timing is made.

## Documentation review — 2026-10-22

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
