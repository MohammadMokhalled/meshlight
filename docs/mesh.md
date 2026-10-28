# Bluetooth Mesh control

Meshlight uses one element for any strip length, with Configuration Server,
Health Server, Generic OnOff Server (`0x1000`), Generic Level Server (`0x1002`),
and Meshlight vendor server. There are no provisioner or switch/client models.
The default six-LED overlays can be changed to any length from 1 through 339.
Larger strips exceed the existing 3072-byte lighting snapshot budget and fail
at build time. Output remains RGB with the lighting module's existing gamma.

## First installation

Build with the pinned SDK 3.3.0 as described in [setup](setup.md). Mesh is enabled
in `firmware/prj.conf`. The developer shell remains optional (`dev_shell.conf`).

The STM32WB55 layout has changed: application offset/size `0 / 0xC2000`,
Settings offset/size `0xC2000 / 0x8000` (absolute `0x080C2000..0x080C9FFF`).
For the **first mesh installation**, erase only these Settings pages after
flashing CPU1, then reset the board. With STM32CubeProgrammer CLI these are
4 KiB pages **194 through 201**, inclusive; check the generated `zephyr.dts`
before using an erase command. Existing development lighting settings may be
discarded. Subsequent normal firmware updates preserve the partition.
Do not mass erase the WB55 or replace CPU2 during an ordinary application update.
The Nordic layout remains unchanged: Settings at `0x15C000`, size `0x9000`.

An nRF54L15 previously used with `onoff-app` needs a **one-time Settings erase**
before its first CRC-enabled Meshlight installation. The reference project
leaves `CONFIG_ZMS_DATA_CRC` disabled; Meshlight enables it. CRC-disabled name
and value records are incompatible with the enabled checks. Logical Node Reset
cannot reliably clean records already invalid under the new format. Symptoms
include an AppKey import/readback failure, or `Invalid data CRC` followed by
`Failed to store AppKey bt/mesh/AppKey/0 value`.

After checking the generated board layout, use these commands from an SDK
terminal with the target's debugger serial number. They erase **only Settings**
and require provisioning again; the backup contains private network state:

```powershell
nrfutil device halt --serial-number <SERIAL>
nrfutil device read --serial-number <SERIAL> --address 0x15C000 --bytes 36864 --to-file settings-before-mesh.hex
nrfutil device erase --serial-number <SERIAL> --pages 0x15C000-0x165000
west flash -d <BUILD_DIRECTORY> --dev-id <SERIAL>
```

The erase end address is exclusive. Do this once when switching from the older
storage format. Keep the CRC options and partition layout unchanged on later
updates, and use normal `west flash` without an erase to retain provisioning.

WB55 needs ST's **extended HCI-only** CPU2 controller, as used by `onoff-app`:
`stm32wb5x_BLE_HCILayer_extended_fw.bin` from STM32CubeWB v1.23.0. Install it
through Firmware Upgrade Services in STM32CubeProgrammer, following that
package's release table for the exact device. CPU2 is not built by this project.
The tested reference binary's SHA-256 is
`AB3356BB561BB5407F214A3797D42FFCDABA72A761BD8C52E9DD473BE9B9D215`.
Use ST's [versioned controller release notes](https://github.com/STMicroelectronics/STM32CubeWB/blob/v1.23.0/Projects/STM32WB_Copro_Wireless_Binaries/STM32WB5x/Release_Notes.html)
for installation prerequisites and device-specific addresses.

The platform adapter retains HCI-only initialization, background scanning,
one shared advertising set, and ST's extended-controller RAM/mailbox layout.
Two board-local SDK corrections protect shared RNG clock/register access with
hardware semaphores and complete failed advertising sends after GATT has started
the same packet. Both are enabled by default for WB55 and generate corrected
sources in the build directory without modifying the installed SDK. Keep these
corrections when updating firmware; review their source matching when upgrading
the SDK. WB55 requests a four-second peripheral supervision timeout to tolerate
brief radio contention.

WB55 software P-256 uses a 16 KiB crypto heap. For ST-LINK UART, fit the board's
JP5 TX/RX jumpers and use 115200 baud, 8N1, with flow control disabled.

## Provision using nRF Mesh

1. Create a network in nRF Mesh, scan, and provision `Meshlight` using **No OOB**.
   The hardware-derived UUID remains stable across resets and firmware updates.
   Each node consumes one unicast address, including long-strip nodes.
2. Read composition and add an Application Key. Bind it to Generic OnOff,
   Generic Level, and vendor model **company `FFFF`, model `0001`**.
3. Open Generic OnOff for strip power and Generic Level for strip intensity.
   The full slider range maps to 0–100%, rounded to the nearest percent.
   Color and intensity changes preserve power; zero intensity can therefore
   remain logically On while emitting black.
4. Open the vendor model and use the examples below for RGB, individual LEDs,
   and device information. Enter the one-byte opcode; the app adds company ID.
   Android's vendor UI accepts hexadecimal parameters; it has no Meshlight
   color picker or automatic LED-count display. A future controller can decode
   the Capabilities Status to build these controls automatically.

For multiple nodes, bind the same AppKey and subscribe the relevant models to
a group created by the provisioner. Group OnOff/Level changes affect the entire
strip on every subscribed node. Vendor target `FFFF` means every LED in each
receiving node; it is not a mesh destination address. Use unacknowledged Set
messages for repeated/group updates to avoid excessive responses.

No keys, addresses, groups, or subscriptions are hard-coded. Provisioning alone
does not configure model application access. Leave publication unassigned for
initial tests; when configured, OnOff/Level publish state changes and periodic
snapshots. Vendor publication reports the last successfully changed target.
Health Attention blinks the board's onboard LED 0, preserving strip selections.

Relay and GATT Proxy support and initial state default to enabled. Configure
`CONFIG_BT_MESH_RELAY`, `CONFIG_BT_MESH_RELAY_ENABLED`,
`CONFIG_BT_MESH_GATT_PROXY`, and `CONFIG_BT_MESH_GATT_PROXY_ENABLED` as needed.
Saved controller configuration wins over initial-state defaults on ordinary
reboots. Factory reset restores the build's defaults. PB-GATT provisioning is
independent of whether a provisioned node enables Proxy.

### A subscribed node does not respond to OnOff

The phone provides the client model. The lighting node needs only its server;
server publication is optional and is not required to receive group commands.

1. Connect the phone to a proxy in this mesh network.
2. Check that **adding the AppKey to the node succeeded**. An app's saved key or
   binding entry alone is not proof that the node has a usable AppKey. A log
   containing `Unable to import application key` means installation failed.
3. Bind the group control's same AppKey to Generic OnOff Server and subscribe
   that model to the group's numeric address.
4. Send Off and On. A received, accepted request now logs `mesh_models: OnOff
   Set`, its source/destination, AppKey index, and resulting power state. This
   controls the attached strip; the board's identification LED is separate.
5. If key installation fails, retain the preceding `mesh_crypto: PSA key import
   failed` line. It reports the underlying PSA error and an opaque key ID,
   without printing any secret key bytes. Re-add the existing AppKey after
   addressing the error; do not create a new network or reset the node merely
   to repair an unsuccessful AppKey installation.

The nRF54L15 build reserves an 8 KiB crypto heap. Both boards use 32 PSA key
slots to accommodate the configured subnets, AppKeys and key-refresh values.
Lighting/storage-only tests do not enable Bluetooth.

## Vendor protocol version 1

All multi-byte values are **little endian**. Development company ID `FFFF` and
model ID `0001` are configurable with `CONFIG_MESHLIGHT_COMPANY_ID` and
`CONFIG_MESHLIGHT_MODEL_ID`; assign the project's company ID before distribution.
The versioned wire format uses logical RGB regardless of strip wire order.

| Opcode | Operation | Parameters, in wire order |
| --- | --- | --- |
| `C0` | Capabilities Get | Empty |
| `C1` | Capabilities Status | `version:u8`, `led_count:u16`, `features:u16`, `max_intensity:u8` |
| `C2` | State Get | `target:u16` |
| `C3` | State Set | `target:u16`, `tid:u8`, `flags:u8`, optional RGB, optional intensity |
| `C4` | State Set Unacknowledged | Same as `C3` |
| `C5` | State Status | `target:u16`, `result:u8`, `flags:u8`, `r:u8`, `g:u8`, `b:u8`, `intensity:u8` |

Targets `0000..led_count-1` select one LED; `FFFF` selects all. Set flags are:

| Bit | Meaning |
| --- | --- |
| 0 | Include three RGB bytes (0–255), in R/G/B order |
| 1 | Include intensity byte (0–100), after RGB if present |
| 2 | Update power |
| 3 | Requested power is On (valid only with bit 2) |
| 4–7 | Reserved; must be zero |

At least one property bit must be set. Omitted properties are preserved.
Power Off retains requested RGB/intensity. Complete updates use at most eight
parameter bytes and fit an unsegmented access message. Vendor updates are
immediate. A duplicate `(source, destination, target, TID)` within six seconds
does not reapply; acknowledged retries still receive current status. Use a new
TID for each edit. The bounded cache holds sixteen recent transactions.

Status result: `0` success, `1` bad target, `2` bad request, `3` unavailable.
On error, state bytes are zero. Successful Status flag bit 0 is power On;
bits 1/2/3 indicate mixed RGB/intensity/power. For an all-LED Get, RGB comes
from LED 0, intensity is the rounded mean, and On means any LED is enabled.
Standard OnOff/Level Get use that same power/intensity interpretation.
Capabilities feature bits 0–5 indicate RGB, intensity, power, indexed targets,
all targets, and mixed-state reporting; current firmware returns `003F`.

| Test | Opcode | Hex parameters |
| --- | --- | --- |
| Discover capabilities | `C0` | Empty |
| Read LED 0 | `C2` | `0000` |
| Read whole strip | `C2` | `FFFF` |
| LED 0 red, preserve intensity/power | `C3` | `00000101FF0000` |
| All LEDs blue at 25%, enabled | `C3` | `FFFF020F0000FF19` |
| Disable LED 0, retain color/intensity | `C3` | `00000304` |
| Enable LED 0 | `C3` | `0000040C` |
| Set every LED to 50% intensity | `C3` | `FFFF050232` |

Six-LED capabilities parameters are `0106003F0064`. The second Set example
returns State Status parameters `FFFF00010000FF19`. TIDs above are examples;
increment them when repeating edits. Choose `C4` for no application response,
independently of the phone UI's acknowledgement checkbox.

## Transitions and persistence

Generic OnOff and Generic Level accept acknowledged and unacknowledged
operations, including optional transition/delay fields. Generic Level supports
Delta and Move, transaction baselines, rate-based movement, and explicit Stop.
Move without a nonzero transition interval performs no movement, per the model
semantics. No Default Transition Time model is advertised; omitted timing is
immediate. Invalid lengths, values and unknown transition-time encodings have
no effect. Status includes target and rounded-up remaining time while active.

Transition ticks run in work context, separate from LED transfers. Intensity
frames are quantized to the lighting API's 1% resolution. Only the settled
selection enters the existing one-second persistence debounce. RGB and power
are preserved. A conflicting vendor edit interrupts the relevant transition;
an ordinary local lighting edit supersedes in-flight mesh transitions.

Colors/intensities remain in the existing versioned CBOR snapshot. Per-LED
power is runtime-only: all LEDs start enabled after reboot, with their saved
colors/intensities. Zephyr owns mesh keys, sequence/replay state, AppKey bindings,
subscriptions, publications, and feature configuration in its Settings records.
SDK key storage uses the same Settings backend (ZMS on Nordic, NVS on WB55).
Acknowledgements confirm RAM acceptance, not completion of a flash save or SPI
transfer. As before, a power cut can lose an unsaved lighting edit.

## Factory reset and recovery

Hold **button 0** continuously for **more than 30 seconds**. Exactly 30 seconds
does not trigger reset. The Nucleo alias `sw0` is its first user button (SW1).
A button held during boot starts timing at button initialization. Short presses
have no application action. Both edges are debounced for 30 ms.

The nRF Mesh app's **Node Reset** performs the same full factory reset after
the stack sends Node Reset Status. It clears lighting as well as mesh membership.

Reset is journaled and staged: commit `meshlight/reset`, close controls, drain
lighting persistence, stop Bluetooth, and reboot. On the next boot, before
lighting or Bluetooth starts, delete all Settings records except the marker,
then delete the marker last and reboot again. The second reboot discards crypto
and backend RAM caches. This avoids SDK-owned queued writes resurrecting keys.
If cleanup is interrupted or fails, the marker remains and boot retries cleanup;
no normal mesh/provisioning startup occurs until cleanup succeeds. Nothing
erases CPU2 firmware, application flash, hardware identity, or hardware root keys.

After successful reset the strip is white at 50%, all LEDs enabled, with no
mesh membership. It advertises for provisioning. Remove stale node entries from
the phone database after a local reset, then provision and bind models again.
Factory reset remains available when mesh startup fails.

## Hardware acceptance checklist

Automated tests exercise the application model engine without a radio. On both
physical boards, still verify No-OOB PB-GATT provisioning, composition, every
example above, native OnOff/Level and transitions, saved keys/settings after
power cycling, both reset paths, reprovisioning, and stable UUIDs. Test on the
actual long strip before relying on its power and timing behavior.

For relay validation, put a relay between endpoints that demonstrably cannot
communicate directly. Disconnect the phone and compare repeated relay-off/on
runs. Nearby-node communication alone does not establish that relaying works.

OOB authentication, an app-specific controller UI, animation streaming, and
bulk frame upload are outside this implementation. Future provisioning OOB
callbacks belong in `mesh/provisioning.c` and do not change this protocol.
