# Lighting API

[Project overview](../README.md) · [Setup and wiring](00-setup.md) · [Storage](03-storage.md) · [Testing](05-testing.md) · [CI and releases](06-ci.md)

The lighting module controls one WS2812 strip. It stores the color, brightness,
and power of each LED, then sends the latest state to the strip.

**Start here:**

- RGB channels range from **0 to 255**; intensity (brightness) ranges from **0 to 100%**.
- LED indices start at **0**, at the strip's data input (DIN) end.
- Changes appear automatically at the next refresh, normally within **50 ms**.
- Color and intensity are saved after **1 second without changes**. Power is not saved: LEDs start on after a restart.
- Turning an LED off keeps its color and intensity. It sends black to the strip; it does not cut electrical power.

## 1. Usage example

Call the API from a Zephyr thread and check the return value. This example makes
the strip blue at 50% intensity, then makes LED 0 red at the same intensity:

```c
#include "lighting/lighting.h"

int show_example(void)
{
	struct lighting_led_state blue = {
		.rgb = {.r = 0, .g = 0, .b = 255},
		.intensity = 50,
	};
	struct led_rgb red = {.r = 255, .g = 0, .b = 0};
	int err = lighting_init();

	if (err != 0) {
		return err;
	}
	err = lighting_set_all(&blue);
	if (err != 0) {
		return err;
	}
	return lighting_set_led_color(0, &red);
}
```

`lighting_init()` restores saved colors and intensities, or starts with white at
50% if there are no valid settings. It displays the first frame and starts the
workers. Calling it again after success leaves the current state unchanged.

The example assumes LEDs are on, as they are after initialization. Each setter
is a separate operation, so a refresh may occur between the two changes. If
automatic refresh is disabled, call `lighting_apply()` after the changes to send
them to the strip.

## 2. Find the right call

| I want to… | Use |
| --- | --- |
| Find the strip length | `lighting_get_led_count()` |
| Set one LED's color and intensity | `lighting_set_led(index, &state)` |
| Set the whole strip's color and intensity | `lighting_set_all(&state)` |
| Change only color | `lighting_set_led_color(index, &rgb)` or `lighting_set_all_color(&rgb)` |
| Change only intensity | `lighting_set_led_intensity(index, intensity)` or `lighting_set_all_intensity(intensity)` |
| Turn the whole strip on or off | `lighting_set_on(on)` |
| Read one LED's requested color and intensity | `lighting_get_led(index, &state)` |
| Send the current state now | `lighting_apply()` |

Color-only and intensity-only calls preserve the other property. All color and
intensity setters preserve power. Whole-strip setters update all LEDs together.

For combined changes, `lighting_update()` can update selected properties of one
LED or `LIGHTING_TARGET_ALL` in one operation. Use `lighting_get_status()` for a
snapshot of one LED or the whole strip, including mixed values. See
[lighting.h](../firmware/src/lighting/lighting.h) for parameters and full API contracts.

## 3. What happens after a change?

1. A setter stores the requested state in memory. Success means the request was
   accepted, not that the strip has displayed it.
2. The refresh thread applies intensity and gamma correction, then sends a frame
   to the strip. With the default settings this happens every 50 ms.
3. If color or intensity changed, a background worker saves it after one second
   without further color or intensity changes. Frequent edits delay the save.

Getters return the requested state, even before a frame is sent or after a failed
transfer. They cannot read the physical LEDs. A power change does not schedule or
delay a save. See [storage](03-storage.md) for the save policy.

## 4. Developer UART shell

The shell is **disabled by default**. Build the developer configuration in a
separate directory. For example, for the nRF54L15-DK:

```sh
west build --no-sysbuild -b nrf54l15dk/nrf54l15/cpuapp -d build/dk-dev firmware -- "-DEXTRA_CONF_FILE=dev_shell.conf"
west flash -d build/dk-dev
```

For another board, use its target and sysbuild option from
[setup](00-setup.md#4-build-and-flash). An existing Nordic SDK workspace also
needs the additional STM32 HAL module for the Nucleo build. The developer
fragment enables the shell and routes logs through it.

Connect a serial terminal to the onboard debugger's virtual COM port: **115200
baud, 8N1, no flow control**. On STM32WB55, fit the ST-LINK UART JP5 TX/RX
jumpers. Then try:

| Command | Result |
| --- | --- |
| `lighting show` | Show requested state. |
| `lighting set all 255 255 255 50` | Set all LEDs to white at 50%. |
| `lighting set 0 255 0 0 25` | Set LED 0 to red at 25%. |
| `lighting color all 0 0 255` | Set all LEDs to blue; keep intensity. |
| `lighting intensity 0 25` | Set LED 0 to 25%; keep color. |
| `lighting power off` / `lighting power on` | Turn all LEDs off or on. |

Use `all` or a zero-based LED index with `set`, `color`, and `intensity`. RGB
values range from **0–255**, intensity from **0–100**. `show` reports requested
state; it cannot read the physical LEDs. Color and intensity changes save
automatically using the same delay as mesh changes.

## 5. Refresh configuration

The defaults in [firmware/Kconfig](../firmware/Kconfig) are:

```ini
CONFIG_LIGHTING_AUTO_REFRESH=y
CONFIG_LIGHTING_REFRESH_INTERVAL_MS=50
CONFIG_LIGHTING_REFRESH_STACK_SIZE=1536
```

Change these settings in a build configuration, then rebuild. The interval can
be **1–1000 ms** and the stack size **512–8192 bytes**. If you set
`CONFIG_LIGHTING_AUTO_REFRESH=n`, your application must call `lighting_apply()`
to show changes. The default application relies on automatic refresh.

`lighting_apply()` is also available with automatic refresh enabled. It sends the
current frame immediately and returns a driver error if the transfer fails; it
does not change the background schedule. The refresh worker retries failed
transfers on its next scheduled frame.

## 6. Errors and threading

Use the API from a **thread**, not an interrupt. The exception is
`lighting_get_led_count()`, which works in any context, even before initialization.
Calls share a mutex and can wait for another call or a strip transfer to finish.

| Error | Check |
| --- | --- |
| `-EINVAL` | Required pointer or update mask is invalid. |
| `-ERANGE` | LED index is out of range or intensity is above 100%. |
| `-ENODEV` | Initialization has not succeeded, or the strip device is not ready. |
| `-EWOULDBLOCK` | A control call was made from interrupt context. |
| `-ESHUTDOWN` | The module was quiesced for reset. |
| Other negative driver error | The first frame or a manual `lighting_apply()` transfer failed. |

Invalid requests do not change stored state. A failed transfer retains the
requested values for retry, but may have partially changed the physical strip.
Even a successful transfer only confirms that the driver accepted the frame.

<details>
<summary>More about rendering, transitions, and refresh timing</summary>

- Each channel is scaled by intensity using integer rounding, then mapped through
  a fixed gamma 2.2 lookup table. For example, 255 at 50% intensity produces 56.
  Very small nonzero values can round to black.
- Rendering uses a separate framebuffer, so driver writes and repeated refreshes
  do not change the requested RGB values. The module uses static storage.
- `LIGHTING_UPDATE_TRANSIENT` is for intermediate transition frames: it changes
  the live state without changing the saved selection. A final ordinary update
  commits the result and schedules saving when needed.
- The refresh worker transmits even when the state is unchanged. It skips missed
  slots instead of queuing old frames; actual timing depends on the scheduler and
  transfer duration.
- `lighting_quiesce()` drains pending saves and closes lighting control for
  factory reset. It is used by the reset worker.

</details>
