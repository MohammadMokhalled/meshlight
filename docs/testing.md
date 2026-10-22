# Testing

[Project overview](../README.md) · [Setup and wiring](setup.md) · [Lighting API](lighting.md) · [CI and releases](ci.md)

The tests compile the production lighting module against fake Zephyr strip
devices. They exercise state, rendering, concurrency, and scheduling without
physical LEDs. QEMU is the firmware test platform used by CI.

## Automated coverage

| Suite | Scenarios | Cases per scenario | Total |
| --- | --- | --- | --- |
| [lighting](../firmware/tests/lighting/) | 1, 6, and 17 LEDs; automatic refresh disabled | 20 | 60 |
| [lighting_refresh](../firmware/tests/lighting_refresh/) | 6 LEDs; automatic refresh at 50 ms and 20 ms | 8 | 16 |
| **Firmware total** | **5 scenarios** | | **76** |

Manual-mode tests cover initialization and retry, per-LED and uniform updates,
requested-state copies, invalid arguments, power restoration, gamma reference
vectors, rounding, quantization, monotonicity, and interrupt-context rejection.
Concurrency tests check that transfers do not overlap and uniform frames are
not partially updated.

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

Confirm **five scenarios and 76 cases pass**, with no missing or skipped cases.
The same report validator used by CI checks the run against its generated plan:

```sh
python scripts/ci.py verify-tests --report build/twister-qemu/twister.json --expected build/twister-qemu/testplan.json
```

Twister writes JSON, JUnit XML, and per-scenario logs under the output directory.
The validator checks the discovered plan rather than hard-coding the current
scenario count; update this inventory when adding tests.

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
perl "$ZEPHYR_BASE/scripts/checkpatch.pl" --no-tree --ignore SPDX_LICENSE_TAG --file \
  firmware/src/lighting.c firmware/src/lighting.h \
  firmware/src/lighting_gamma_table.h firmware/src/main.c \
  firmware/Kconfig firmware/tests/lighting/src/main.c \
  firmware/tests/lighting_refresh/src/main.c
git diff --check
```

Only the missing-license-tag check is excluded: a project license has not been
selected, and project files currently have no SPDX declarations.
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
Replace the demo loop before manual API checks so it does not overwrite requests.
Unless stated otherwise, these checks assume automatic refresh is enabled.

| Check | Expected result |
| --- | --- |
| Boot and reboot | Initialization clears every LED; the application starts the RGB/intensity demo |
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
