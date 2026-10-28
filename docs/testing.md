# Testing

[Project overview](../README.md) · [Setup and wiring](setup.md) · [Lighting API](lighting.md) · [CI and releases](ci.md)

The tests compile the production lighting module against fake Zephyr strip
devices. They exercise state, rendering, concurrency, and scheduling without
physical LEDs. QEMU is the firmware test platform used by CI.

## Automated coverage

| Suite | Scenarios | Cases per scenario | Total |
| --- | --- | --- | --- |
| [lighting](../firmware/tests/lighting/) | 1, 6, and 17 LEDs; automatic refresh disabled | 23 | 69 |
| [lighting_refresh](../firmware/tests/lighting_refresh/) | 6 LEDs; automatic refresh at 50 ms and 20 ms | 8 | 16 |
| lighting persistence and shell | 10 boot conditions | 19 | 190 |
| Settings storage integration | NVS and ZMS with simulated flash, including legacy CRC rejection and reset retries | 11 | 22 |
| Mesh engine and opcode adapters | 1, 6, 17, 300 and 339 LEDs | 20 | 100 |
| Mesh crypto resources | Real PSA imports and encrypted Settings with simulated flash | 2 | 2 |
| Lighting reset quiescence | Save already in flight | 1 | 1 |
| **Firmware total** | **24 scenarios** | | **400** |

Manual-mode tests cover initialization and retry, per-LED and uniform updates,
requested-state copies, invalid arguments, power restoration, gamma reference
vectors, rounding, quantization, monotonicity, and interrupt-context rejection.
Concurrency tests check that transfers do not overlap and uniform frames are
not partially updated. Partial-setter tests check per-LED field preservation
and concurrent color/intensity updates. Dummy-shell tests cover combined and
separate controls, complete argument validation, automatic saving, and removal
of manual persistence commands.

Automatic-refresh tests cover the first delayed frame, repeated initialization
without duplicate workers, unchanged/off frames, automatic output, absolute
cadence, skipped slots after an overrun, transfer recovery, and concurrent
setters/manual flushes.

The fakes record complete frames and can overwrite the driver's input buffer on
success or failure. Expected gamma vectors are independent of the production
lookup table. Refresh tests use synchronized timestamped history and a 1000 Hz
test kernel clock with bounded timing tolerances. Production code needs no
test hooks or runtime worker-stop API.

## Run firmware tests with QEMU

Complete [SDK and workspace setup](setup.md#prepare-the-workspace), including
the SDK Python environment and `ZEPHYR_BASE`. Commands run from the repository
root. QEMU must be available to the SDK.

In a Linux/WSL shell:

```sh
python "$ZEPHYR_BASE/scripts/twister" \
  -T firmware/tests -p qemu_cortex_m3 \
  --inline-logs --outdir build/twister-qemu
```

In PowerShell on Windows, set `QEMU_BIN_PATH` to the directory containing
`qemu-system-arm.exe` if the SDK cannot locate it. Short paths help avoid Windows
path-length limits, and the timeout multiplier allows extra console collection time:

```powershell
python "${env:ZEPHYR_BASE}/scripts/twister" -T firmware/tests `
  -p qemu_cortex_m3 --short-build-path --ninja --timeout-multiplier 3 `
  --inline-logs --outdir build/twister-qemu
```

Confirm every discovered scenario and case passes, with no missing or skipped cases.
The same report validator used by CI checks the run against its generated plan:

```sh
python scripts/ci.py verify-tests --report build/twister-qemu/twister.json --expected build/twister-qemu/testplan.json
```

Twister writes JSON, JUnit XML, and per-scenario logs under the output directory.
The validator checks the discovered plan rather than hard-coding the current
scenario count; update this inventory when adding tests.

The mesh suite compiles the production parser, transaction cache, transition
engine, lighting code, and opcode adapters. A capturing transport verifies
acknowledged/unacknowledged handling and configured publications without a
Bluetooth controller. Fake strip devices cover 1/6/17/300/339 LEDs. Storage
integration runs against both real Settings backends with simulated flash and
injected deletion failures. These tests are discovered by the existing CI
Twister job; each board also builds the maximum-length overlay in CI.

Phone commissioning and physical radio checks remain a separate
[hardware acceptance checklist](mesh.md#hardware-acceptance-checklist).

The `mesh_crypto` suite additionally uses real PSA key import and encrypted ITS
storage. It checks AppKey installation, the separate AppKey configuration
record, and capacity for both generations of two subnets and two AppKeys. It uses a
dedicated simulated Settings partition, including when built for a physical
board; it never accesses that board's provisioning or lighting records.

## Other firmware checks

### native_sim on Linux or WSL

With the SDK Python environment active, `ZEPHYR_BASE` set, and native simulator
host dependencies installed:

```sh
python "$ZEPHYR_BASE/scripts/twister" \
  -T firmware/tests -p native_sim/native/64 \
  --inline-logs --outdir build/twister-native
```

Both suites allow this platform. See the [validation record](validation.md) for
the local WSL limitation encountered during earlier runs.

### Compile-only checks on application boards

Only the **manual-mode suite** allows the physical application boards in its
`testcase.yaml`. Limit discovery to that suite, for example:

```sh
python "$ZEPHYR_BASE/scripts/twister" \
  -T firmware/tests/lighting -p nrf54l15dk/nrf54l15/cpuapp \
  --build-only --inline-logs --outdir build/twister-dk-build
```

The Nucleo test platform name is `nucleo_wb55rg/stm32wb55xx`. It also needs the
STM32 HAL dependency described in [setup](setup.md). The automatic-refresh suite
allows only QEMU and native_sim. Compile-only tests still use a fake strip and
do not verify SPI or physical output.

## Python tooling and workflow checks

Python and Git are sufficient for the versioning and CI tooling tests:

```sh
python -m unittest discover -s scripts/tests -v
```

These tests create temporary Git histories and verify version calculation,
exact PR merges, package contents/checksums, report validation, and release retry
and failure paths. Publication tests mock GitHub and create no real releases.

With `actionlint` installed, validate the workflow files:

```sh
actionlint .github/workflows/ci.yml .github/workflows/pr.yml .github/workflows/firmware.yml
```

### C and Kconfig style

From a shell providing Perl and `diff`:

```sh
perl "$ZEPHYR_BASE/scripts/checkpatch.pl" --no-tree --file \
  firmware/src/lighting/lighting.c firmware/src/lighting/lighting.h \
  firmware/src/lighting/lighting_gamma_table.h firmware/src/main.c \
  firmware/src/storage/storage.c firmware/src/storage/storage.h \
  firmware/src/lighting/lighting_settings.c firmware/src/lighting/lighting_settings.h \
  firmware/src/lighting/lighting_shell.c \
  firmware/Kconfig firmware/tests/lighting/src/main.c \
  firmware/tests/lighting_refresh/src/main.c
git diff --check
```

Project sources use the `SPDX-License-Identifier: MIT` declaration; the complete
license is in [LICENSE](../LICENSE). License-tag checks are enabled.
`git diff --check` checks tracked changes; also inspect newly added files for
whitespace before committing.

## Results and limits

The [validation record](validation.md) preserves dated results and local artifact
paths. CI uploads firmware build logs and Twister reports, including available
diagnostics on failure. Emulator tests establish behavior with a fake device;
they do not establish electrical compatibility, visible output, or scheduling
bounds on a real board.

## Physical checks

Use the selected board and exact strip after following [wiring](setup.md#connections).
Use the [developer UART shell](storage.md#developer-uart-shell) for manual checks.
Unless stated otherwise, these checks assume automatic refresh is enabled.

| Check | Expected result |
| --- | --- |
| Boot and reboot | Saved RGB/intensity appear on the first frame; otherwise white/50%; power always starts on; no animation |
| Red, green, blue at 100% intensity | Correct channel order on every LED |
| Different colors/intensities on adjacent LEDs | Correct individual output; other LEDs retain their selections |
| Update values with automatic refresh enabled | Output changes at a subsequent scheduled refresh without manual apply |
| Stage values with automatic refresh disabled | Output stays unchanged until `lighting_apply()` |
| Intensity 0%, 1%, 50%, 100% | Zero is black; intermediate output follows gamma; very low values may be black |
| Set off | A subsequent refresh sends black to every LED |
| Change selections while off, then set on | Output stays black while off, then restores the new selections |
| Toggle on/off with intensity zero | Output stays black without changing stored intensity |
| Data waveform | Measured pulse widths and reset delay meet the exact strip's datasheet |

Record the board revision, strip part/revision, supply and I/O voltages, firmware
commit, and measured waveform when reporting a physical pass.

Storage integration tests use actual NVS/ZMS Settings backends and simulated
flash, including payload bit flips, failed writes, remounts, and physical
duplicate-write avoidance. Persistence tests use a blocking fake to verify
setters during I/O, retry scheduling, stale queued callbacks, power-only changes,
and debounce during continuous edits. Boot fixtures cover current and legacy
formats, legacy power on/off, zero intensity, missing/malformed/unsupported or
oversized records, and backend initialization/read errors. See [manual retention checks](storage.md#manual-retention-checks)
for the hardware power-cycle procedure.
