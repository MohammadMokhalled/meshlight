# Persistent lighting and developer shell

Lighting automatically restores its last committed selection before displaying
its first frame. With no valid snapshot it starts white, at 50% requested
intensity, with power on. It then waits for control input; there is no animation.
Power always starts on. On/off changes are runtime-only and never stored;
zero-intensity selections still render dark.

## Storage API

`storage_init()` initializes Zephyr Settings without loading other subsystems.
It is idempotent and retryable. After initialization, `storage_store(key, data,
length)` synchronously commits a nonempty binary value. `storage_restore(key,
data, capacity, &actual_length)` reads exactly that key, returning `-ENOENT` for
missing values or `-ENOSPC` with the required length for an undersized buffer.
Other errors leave the reported length zero; never consume the buffer on error.
All functions require thread context and serialize their operations.

The nRF54L15 uses ZMS with payload CRC and duplicate-write avoidance; STM32WB55
uses NVS with payload CRC. Entire values are read so CRC verification runs.
No filesystem or additional application checksum is used. The wrapper never
erases storage to recover from an error. A blank partition is initialized by
the backend. CRC detects accidental corruption; Settings does not encrypt secrets.
Future Bluetooth Mesh code must perform its own initialization and Settings load;
lighting never calls the global `settings_load()`.

## Flash layout

Both boards run a standalone image at flash offset zero. Devicetree explicitly
separates the application from Settings, and `CONFIG_USE_DT_CODE_PARTITION=y`
constrains the linker to the application partition.

| Board | Application offset / size | Settings offset / size |
| --- | --- | --- |
| nRF54L15 | `0` / 1392 KiB | `0x15c000` / 36 KiB |
| STM32WB55 | `0` / 800 KiB | `0xc8000` / 8 KiB |

Offsets are relative to the memory device (STM32 flash base is `0x08000000`).
The Nordic storage location is unchanged from the SDK. The SDK's default STM32
storage at `0xd9000` lies outside its declared 808 KiB application flash device;
the project moves storage into its last two accessible 4 KiB pages. Neither
board uses the SDK's unused MCUboot slots. Adding a bootloader or Mesh capacity
requirements later needs a deliberate partition-layout review.

Preserve these partitions across firmware updates. A full-chip erase removes
saved state; do not use it when testing update retention. Enable backend CRC
consistently in all subsequent releases.

## Format and compatibility

`meshlight/lighting` contains canonical CBOR: `{0: 2, 2: LEDs}`. Field `0` is
schema version 2; LEDs is a nonempty array of `[r, g, b, intensity]` arrays.
RGB is 0..255 and intensity is 0..100. Only requested values are saved, before
scaling or gamma correction. Power is absent; field `1` is retired. The record
limit is 3072 bytes; a build-time assertion ensures the configured strip fits
even at maximum encoded size.

Version 1 records (`{0: 1, 1: power, 2: LEDs}`) remain readable. Their old power
field is validated as a boolean but ignored. Startup always selects power on.
Loading a legacy record does not rewrite it; the next real RGB/intensity change
saves version 2. Firmware that only understands version 1 cannot read version 2
records after a downgrade.

Readers validate required fields, reject duplicate known fields, invalid types,
truncated/trailing data, and unsupported versions, and skip unknown optional map
fields. Keep existing field meanings stable; add optional map fields with new
IDs. Incompatible future schemas require an explicit migration. When LED count
changes, matching indices are restored, additional LEDs default to white/50%,
and surplus saved entries are validated then ignored.

Startup is the only restoration path. Missing/invalid data selects white/50%
without rewriting or erasing the record. Backend failures are logged. A later
actual RGB/intensity change can replace the old record through automatic saving.

## Save scheduling and threading

Every actual RGB/intensity change requests a save on a private work queue. The
single delayable work item is rescheduled for `CONFIG_LIGHTING_SAVE_DELAY_MS`
(1000 ms by default) after the latest change. Continuous edits postpone saving
until they stop; there is no periodic checkpoint. Unchanged setters and power
changes neither request a save nor extend the deadline. Choosing startup defaults
or restoring a saved selection alone does not write a snapshot.

The worker copies requested values and clears a pending flag under the lighting
mutex, then releases it before encoding and flash I/O. Later edits set the flag
and schedule another save; completing an older write never clears a newer edit.
A deadline check prevents an already queued callback from saving too early after
a new edit. The queue uses preemptible priority 8, below rendering at priority 7,
and a configurable 3072-byte stack. No save work uses the system work queue.

Failures retain pending state, log a rate-limited error, and retry after
`CONFIG_LIGHTING_SAVE_RETRY_MS` (5000 ms by default), even without another edit.
A subsequent edit restarts the normal debounce. NVS and the enabled ZMS
`CONFIG_ZMS_NO_DOUBLE_WRITE` avoid rewriting identical stored values; lighting
keeps no duplicate snapshot cache.

There are no public lighting save/restore triggers. The storage module retains
its synchronous data API for startup reads and worker writes. A successful
lighting setter confirms RAM acceptance only; a power cut can lose pending
changes. Flash I/O holds no lighting mutex, so setters and rendering can continue.

## Developer UART shell

The shell is off by default. Enable its build-time flag using the supplied
fragment (use a separate build directory):

```sh
west build --no-sysbuild -b nrf54l15dk/nrf54l15/cpuapp -d build/dk-dev firmware -- "-DEXTRA_CONF_FILE=dev_shell.conf"
west flash -d build/dk-dev
```

Use `-b nucleo_wb55rg -d build/wb55-dev` for STM32, adding its external HAL module
argument if using an existing SDK workspace as described in [setup](setup.md).
The fragment enables `CONFIG_MESHLIGHT_DEV_SHELL=y` and routes logging through
the shell. A normal build omits shell transport and lighting commands.

Connect a serial terminal to the onboard debugger's virtual COM port at
**115200 baud, 8 data bits, no parity, 1 stop bit, no flow control**. This uses
the board's existing console UART, not a new USB interface on the application MCU.

```text
lighting show
lighting set all 255 255 255 50
lighting set 0 255 0 0 25
lighting color all 0 0 255
lighting color 0 255 0 0
lighting intensity all 50
lighting intensity 0 25
lighting power off
lighting power on
```

Indices start at zero. `set` changes color and intensity together. `color`
preserves each targeted LED's intensity; `intensity` preserves its color.
Use `all` or a single index with all three commands. RGB accepts 0..255; intensity
accepts 0..100. Partial updates are atomic, including with concurrent controls.

Color/intensity changes save automatically after one second without changes.
Continuous edits postpone saving; power changes are runtime-only. Restoration happens at startup;
there are no shell `save` or `restore` commands. `show` reports requested state,
not hardware readback. Commands validate every argument before changing state.
In manual refresh builds they also call `lighting_apply()` after successful changes.

## Manual retention checks

1. On erased storage, confirm white/50%/on and `Lighting ready`, with no animation.
2. Use `lighting set` to choose different colors/intensities on adjacent LEDs.
   Wait more than one second, then reset or power-cycle. Confirm the same selections.
3. Turn power off through the shell and power-cycle. Confirm power is on again
   with the saved colors/intensities. Nonzero selections must become visible.
   Also try changing color/intensity while off, waiting more than one second,
   then power-cycling: the new selections must restore with power on.
4. Use `lighting color all 0 0 255` and confirm each LED keeps its intensity.
   Then use `lighting intensity 0 25` and confirm colors and other LEDs are
   unchanged. Wait more than one second and power-cycle to check retention.
5. Issue rapid changes for longer than five seconds. Confirm saving remains
   postponed using a debugger, then stop, wait more than one second, and check
   the final selection after a power cycle.
6. Repeat power cuts during writes. The next boot must either restore a valid
   snapshot or report an error and use white/50% defaults, without hanging or
   erasing the partition as a recovery action.
7. Flash a normal shell-disabled image without a full erase. Confirm retained
   output and ordinary logs, and verify shell commands are unavailable.

Hardware retention, flash timing, and electrical behavior require these physical
checks; emulator coverage does not establish them.
