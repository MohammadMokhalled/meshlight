# Setup and wiring

[Project overview](../README.md) · [Lighting API](lighting.md) · [Testing](testing.md) · [CI and releases](ci.md)

## Prerequisites

Install **nRF Connect SDK 3.3.0** and its matching toolchain, then open an SDK
terminal. Follow Nordic's [SDK installation guide](https://docs.nordicsemi.com/r/bundle/nrf-connect-vscode/page/get_started/quick_setup.html/installing-sdk-and-toolchain-for-the-first-time)
and select version 3.3.0. The terminal must provide Git, Python, CMake, Ninja, and
`west`. Use a full Git clone: local firmware versioning requires history and tags.

For flashing, connect the board's onboard debugger by USB and install the runner
tools for the selected board. The Nucleo uses ST-LINK with STM32CubeProgrammer;
the Nordic boards' default runner uses `nrfutil` with device support. Keep strip
power separate from the data GPIO and follow the connections below.

## Prepare the workspace

Choose one of these two paths. The repository manifest is the reproducible path
used by CI; an existing Nordic SDK installation can also build the application.

### Use the repository manifest

Start in an empty, writable parent directory **outside any existing west
workspace**. In the SDK 3.3.0 toolchain terminal, run:

```sh
git clone https://github.com/MohammadMokhalled/meshlight.git meshlight
west init -l meshlight
west update -o=--depth=1 -n
west zephyr-export
cd meshlight
```

This keeps the application in `meshlight/` and installs SDK dependencies alongside
it in the parent workspace. The root [west.yml](../west.yml) pins Nordic SDK 3.3.0
and the STM32 HAL, so all four boards build without an extra module argument.
Shallow dependency downloads are intentional; the application clone must retain
its full history.

Point `ZEPHYR_BASE` at this workspace's Zephyr tree so an SDK terminal's existing
setting cannot select another installation. From `meshlight/`, use the command
for your shell:

```powershell
$env:ZEPHYR_BASE = (Resolve-Path ../zephyr).Path
```

```sh
export ZEPHYR_BASE="$(cd ../zephyr && pwd)"
```

Keep this terminal open for the build and test commands. A new terminal needs
the matching SDK toolchain environment and `ZEPHYR_BASE` again.

### Use an existing Nordic SDK workspace

Open the existing SDK **3.3.0** terminal, confirm `ZEPHYR_BASE` identifies that
installation's `zephyr` directory, and change to the Meshlight repository root.
The Nordic DKs can build directly.

Keep the working directory at the **existing SDK's root** when the Meshlight
checkout is inside another west workspace. Use absolute application and build
paths so west discovers the SDK's installed modules. For example (PowerShell):

```powershell
cd C:\ncs\v3.3.0
$meshlightRepo = 'C:\projects\nrf\meshlight'
west build --no-sysbuild -b nrf52dk/nrf52832 -d "$meshlightRepo/build/nrf52dk-sdk" "$meshlightRepo/firmware"
west flash -d "$meshlightRepo/build/nrf52dk-sdk"
```

The board-specific commands below assume the repository-manifest workspace.
With an existing SDK, substitute absolute paths as in this example.

Nordic's SDK manifest omits the STM32 HAL. For the Nucleo, download the official
HAL at the revision pinned in this repository's manifest. The following
**PowerShell** commands put it in the ignored build directory:

```powershell
$stm32Revision = 'e05bb4707857ecf4344c29a9407aaa4227407546'
New-Item -ItemType Directory -Force build/deps/hal_stm32 | Out-Null
Invoke-WebRequest "https://github.com/zephyrproject-rtos/hal_stm32/archive/$stm32Revision.tar.gz" -OutFile build/deps/hal_stm32.tar.gz
tar.exe -xzf build/deps/hal_stm32.tar.gz -C build/deps/hal_stm32 --strip-components=1
```

Run this once, or reuse a directory already prepared at this exact revision.
Use the extra-module build command in [build and flash](#build-and-flash).
This path does not change the SDK installation's manifest.

## Connections

Use the strip's specified supply voltage and a supply rated for the configured
LED count at maximum output. A usual 5 V WS2812 strip needs a regulated 5 V
supply; do not power it from a GPIO. Connect board, strip supply, and level
shifter grounds together. Use a non-inverting level shifter matched to the
board's actual I/O voltage and the strip's data-input requirements.

### nRF54L15-DK

The DK defaults to **1.8 V I/O**. For a 5 V strip, use a level shifter that
explicitly supports **1.8 V input and 5 V output**. If the DK supply was changed
in Board Configurator, select the shifter for that actual voltage. See Nordic's
[DK power supply documentation](https://docs.nordicsemi.com/r/bundle/ug_nrf54l15_dk/page/ug/nrf54l15_dk/hw_desription/power_supply.html).

**P1.12 means GPIO port 1, pin 12. It is physical position 15 on the PORT P1
header.** Nordic lists this mapping in its
[DK connection table](https://docs.nordicsemi.com/r/bundle/ug_npm1304_ek/page/ug/npm1304_ek/using_nrf54l15_dk.html).

| Connection | Destination |
| --- | --- |
| DK GPIO P1.12 (PORT P1 position 15) | Level-shifter input; shifted output to the first LED's DIN |
| DK GND | Strip supply ground and level-shifter ground |
| Strip supply positive | Strip power input at its specified voltage |
| DK P1.11 | Reserved SPI clock; leave unconnected to the strip |

### nRF52-DK (PCA10040 / nRF52832)

Use **GPIO P0.23 / Arduino D11** for strip data. `nrf52dk` here means the
nRF52832 DK, not an nRF52840 DK. The normal USB-powered DK uses approximately
3 V GPIO levels; match the level shifter to the actual VDD voltage.
The [Zephyr board source](https://github.com/nrfconnect/sdk-zephyr/blob/fd9204a02d52630660ce8d729945a4dd743feabf/boards/nordic/nrf52dk/nrf52dk_nrf52832.dts)
maps D11 to P0.23.

| Connection | Destination |
| --- | --- |
| P0.23 / D11 | Level-shifter input; shifted output to the first LED's DIN |
| DK GND | Strip supply ground and level-shifter ground |
| Strip supply positive | Strip power input at its specified voltage |
| P0.25 / D13 and P0.24 / D12 | Reserved I2S clocks; leave unconnected |

This board uses the I2S peripheral as a continuous DMA waveform generator.
The strip still receives ordinary WS2812 data and needs only DIN. This avoids
gaps between the nRF52832 SPIM peripheral's 255-byte DMA chunks on long strips.

### nRF5340-DK (PCA10095)

Use **GPIO P1.13 / Arduino D11** for strip data. P1.13 means GPIO port 1, pin
13. Match the level shifter to the DK's actual VDD voltage (normally about 3 V
with its default supply configuration). The
[Zephyr board source](https://github.com/nrfconnect/sdk-zephyr/blob/fd9204a02d52630660ce8d729945a4dd743feabf/boards/nordic/nrf5340dk/nrf5340dk_common.dtsi)
maps D11 to P1.13.

| Connection | Destination |
| --- | --- |
| P1.13 / D11 | Level-shifter input; shifted output to the first LED's DIN |
| DK GND | Strip supply ground and level-shifter ground |
| Strip supply positive | Strip power input at its specified voltage |
| P1.15 / D13 | Reserved SPI clock; leave unconnected |

### NUCLEO-WB55RG

Use **Arduino D11 / GPIO PA7 / CN5 pin 4** for data. The board's I/O voltage is
3.3 V; for a 5 V strip, use a suitable 3.3 V-to-5 V level shifter. ST documents
the connector mapping in the
[UM2435 user manual](https://www.st.com/resource/en/user_manual/um2435-pnucleowb55-mb1355-and-mb1293-stmicroelectronics.pdf).

| Connection | Destination |
| --- | --- |
| PA7 / D11 / CN5 pin 4 | Level-shifter input; shifted output to the first LED's DIN |
| GND / CN5 pin 7 | Strip supply ground and level-shifter ground |
| Strip supply positive | Strip power input at its specified voltage |
| PA5 / D13 / CN5 pin 6 | Reserved SPI clock; leave unconnected to the strip |

### Common strip wiring

Connect DIN at the input end of the strip; DOUT connects to the next LED. Keep
the data connection short and follow the strip manufacturer's requirements for
decoupling, signal series resistance, and power injection. The strip needs no
MISO or chip select. All board configurations preserve the default UART console.

## Build and flash

Run these commands from the **repository root** in the prepared SDK terminal.
Use a separate build directory for each board.

### nRF54L15-DK

```sh
west build --no-sysbuild -b nrf54l15dk/nrf54l15/cpuapp -d build/dk firmware
west flash -d build/dk
```

### nRF52-DK

```sh
west build --no-sysbuild -b nrf52dk/nrf52832 -d build/nrf52dk firmware
west flash -d build/nrf52dk
```

### nRF5340-DK

```sh
west build --sysbuild -b nrf5340dk/nrf5340/cpuapp -d build/nrf5340dk firmware
west flash -d build/nrf5340dk
```

Use the top-level build directory for flashing: this programs **both cores**.
The application core runs Meshlight; the network core runs Nordic's `ipc_radio`
Bluetooth controller. Do not add `--no-sysbuild` or flash only the `firmware`
domain for a first installation. The repository's
[sysbuild defaults](../firmware/Kconfig.sysbuild) select the controller and retain
the devicetree flash layout; its [radio configuration](../firmware/sysbuild/ipc_radio.conf)
provides the five advertising sets used by Mesh and GATT Proxy.

### NUCLEO-WB55RG

With the repository-manifest workspace:

```sh
west build --no-sysbuild -b nucleo_wb55rg -d build/wb55 firmware
west flash -d build/wb55
```

With an existing Nordic SDK and the additional HAL prepared above (PowerShell):

```powershell
west build --no-sysbuild -b nucleo_wb55rg -d build/wb55 firmware -- "-DEXTRA_ZEPHYR_MODULES=$PWD/build/deps/hal_stm32"
west flash -d build/wb55
```

Single-core build outputs are `zephyr.hex`, `zephyr.bin`, and `zephyr.elf` under
the chosen build directory's `zephyr/` folder. For nRF5340, application outputs
are in `firmware/zephyr/` and network-core outputs are in `ipc_radio/zephyr/`.
Startup logs include the resolved firmware
version and source revision, followed by `Lighting ready` on successful startup.
Saved lighting state is restored; otherwise the strip starts white at 50%
intensity with power on. It waits for commands and does not animate.
For Bluetooth control, follow [Mesh provisioning](mesh.md).
Enable the [developer shell](storage.md#developer-uart-shell)
for manual control.

## Configure the strip

Board configuration is selected automatically from [firmware/boards/](../firmware/boards/):

| Board | Overlay and configuration basename |
| --- | --- |
| nRF54L15-DK | `nrf54l15dk_nrf54l15_cpuapp` |
| nRF52-DK | `nrf52dk_nrf52832` |
| nRF5340-DK | `nrf5340dk_nrf5340_cpuapp` |
| NUCLEO-WB55RG | `nucleo_wb55rg` |

- Set `chain-length = <6>` in the `.overlay` to the actual LED count. It must be
  positive; the driver and application storage both use this value.
- Set `color-mapping` to match the strip's wire order. The API stays RGB.
- For different Nordic pins, update both default and sleep pinctrl groups.
  On nRF54L15, UART21 and I2C21 share the SPI21 instance and must stay disabled.
  On nRF52, keep SPI2 disabled because the I2S pins occupy its Arduino signals.

Rebuild after configuration changes. If reusing a build directory after changing
the board or workspace, pass `-p always` to `west build` for a pristine rebuild.
Refresh settings are shared application options described in the
[lighting guide](lighting.md#refresh-configuration).

The nRF52832's 64 KiB RAM limits capacity: its production build is checked with
**128 LEDs**, and its developer-shell build with **six LEDs**. The other boards
are checked with 339 LEDs. Larger nRF52 strips or additional shell features need
a fresh RAM-budget review. CI uses [nrf52_long_strip.overlay](../firmware/nrf52_long_strip.overlay)
for that board's capacity build. The nRF52 shell uses Zephyr's minimal feature
defaults; the lighting commands remain available.

### Configured signal timing

The following values are calculated from the overlays; they are targets for
physical measurement.

| Board | Data bit clock | Encoded zero / one | Symbol duration | Zero / one high time |
| --- | --- | --- | --- | --- |
| nRF54L15-DK | 4 MHz | `11000` / `11100` | 1.25 µs | 500 ns / 750 ns |
| nRF52-DK (I2S) | 3.2 MHz | `1000` / `1100` | 1.25 µs | 312.5 ns / 625 ns |
| nRF5340-DK | 4 MHz | `11000` / `11100` | 1.25 µs | 500 ns / 750 ns |
| NUCLEO-WB55RG | 8 MHz | `11000000` / `11111000` | 1 µs | 250 ns / 625 ns |

All boards configure a **300 µs minimum reset delay** after each frame. The
Nucleo enables SPI DMA for continuous byte transfers. Strip revisions can have
different timing limits: measure the waveform and compare it with the exact
strip's datasheet. Zephyr's
[WS2812 SPI binding](https://docs.zephyrproject.org/latest/build/dts/api/bindings/led_strip/worldsemi%2Cws2812-spi.html)
describes the timing properties. Follow the [physical checks](testing.md#physical-checks)
before treating a board/strip combination as validated.
