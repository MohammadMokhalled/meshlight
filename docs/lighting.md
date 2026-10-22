# Lighting API and design

[Project overview](../README.md) · [Setup and wiring](setup.md) · [Testing](testing.md) · [CI and releases](ci.md)

The lighting module controls one strip through Zephyr's `led-strip` devicetree
alias. It keeps requested state separate from the transfer framebuffer, so RGB
and intensity remain available after power changes or driver failures. The
public interface and return values are documented in
[lighting.h](../firmware/src/lighting.h).

## Usage example

Replace the demonstration loop in [main.c](../firmware/src/main.c) when adding
another control source; otherwise it will keep overwriting your requests.
Call the module from a Zephyr thread and check return values:

```c
#include "lighting.h"

/* Blue at 50% intensity everywhere, with the first LED red at 50%. */
int show_example(void)
{
	struct lighting_led_state pixel = {
		.rgb = {.r = 0, .g = 0, .b = 255},
		.intensity = 50,
	};
	int err = lighting_init();

	if (err != 0) {
		return err;
	}
	err = lighting_set_all(&pixel);
	if (err != 0) {
		return err;
	}
	pixel.rgb = (struct led_rgb){.r = 255};
	err = lighting_set_led(0, &pixel);
	if (err != 0) {
		return err;
	}
	return lighting_set_on(true);
}
```

With default automatic refresh, output follows on the next scheduled frame.
The function returns whether initialization and requests succeeded; later
background transfer errors are logged by the worker. The separate setters in
this example can be interleaved with a refresh. With automatic refresh disabled,
call `lighting_apply()` after setting the state and power selection, and handle
its result.

## Requested state and output

Each `struct lighting_led_state` contains RGB channels in **0–255** and intensity
in **0–100%**. LED indices start at zero at the strip's DIN end. A separate
strip-wide on/off setting controls whether those values are rendered.

| Call | Behavior |
| --- | --- |
| `lighting_init()` | Transmits black; initializes black RGB, 100% intensity, and power off; starts the worker if enabled |
| `lighting_get_led_count()` | Returns the immutable devicetree count, including before initialization |
| `lighting_set_led(index, &state)` | Copies RGB and intensity for one LED |
| `lighting_set_all(&state)` | Copies the same RGB and intensity to every LED under one lock |
| `lighting_get_led(index, &state)` | Copies one LED's raw requested values into caller-owned storage |
| `lighting_set_on(on)` / `lighting_get_on(&on)` | Stores or reads the strip-wide on/off selection |
| `lighting_apply()` | Renders and synchronously transmits the complete current selection |

After successful initialization, repeated `lighting_init()` calls preserve
state, perform no transfer, and keep the existing worker schedule. Initialization
failures can be retried; state controls remain unavailable until initialization
succeeds.

Setters store requests without transmitting. With automatic refresh enabled,
the worker renders the latest requests at its next scheduled refresh. While off,
it transmits black and retains RGB and intensity. Setting on restores the stored
selection at the next refresh; zero intensity stays black. **Off is a software
black frame; it does not disconnect the strip's power supply.**

Getters report requested state, including requests not yet transmitted or
retained after a failed transfer. They copy values; changing a returned snapshot
requires another setter call. There is no physical strip readback.

## Output processing

While on, each RGB channel is scaled by that LED's intensity using integer
half-up rounding, then passed through a gamma-2.2 lookup table:

```text
scaled = (channel * intensity + 50) / 100       # integer division
output = gamma_table[scaled]
gamma_table[x] = floor(255 * (x / 255)^2.2 + 0.5)
```

For example, channel 128 at 100% intensity produces output 56. Channel 255 at
50% intensity also produces 56 after scaling rounds to 128. RGB values below
255 are gamma-corrected even at full intensity. Very small nonzero selections
can round to black; there is no minimum-output clamp or dithering.

The private [lookup table](../firmware/src/lighting_gamma_table.h) documents its
generating formula. Firmware uses integer arithmetic and 256 bytes of lookup
data, with no runtime floating-point calculation. Gamma 2.2 is a fixed curve;
strip-specific color calibration is not implemented.

## Threading and failures

All mutable state and complete transfers share one mutex. Calls can block while
another call or SPI transfer holds the lock, and must run in thread context.
The immutable count getter is the exception: it takes no lock and can be called
from any context, including before initialization.

Each call is serialized. Separate setter calls are not an atomic batch: a
refresh can display intermediate selections between calls, even from one caller.
`lighting_set_all()` changes every LED under one lock, so a refresh cannot see
only part of that uniform update.

| Error | Meaning |
| --- | --- |
| `-EINVAL` | A required state or output pointer is `NULL` |
| `-ERANGE` | An LED index is outside the strip or intensity exceeds 100% |
| `-ENODEV` | Initialization has not succeeded, or the device is not ready during initialization |
| `-EWOULDBLOCK` | An otherwise valid control call was made from interrupt context |
| Negative driver error | The initial clear or a synchronous `lighting_apply()` transfer failed |

Invalid requests leave stored state unchanged; failed getters leave the caller's
output unchanged. For per-function error contracts, see the public header.

Rendering rebuilds a private framebuffer on every transfer. Drivers may overwrite
their input buffer, so the requested-state array is never passed to the driver.
This also prevents cumulative gamma loss across repeated refreshes. Both arrays
and the worker's stack are statically allocated; the module needs no heap.

Failed transfers preserve requests for retry. A transfer can partially affect
physical LEDs, so an error does not imply that the previous frame is intact.
A successful `lighting_apply()` means the driver accepted the complete frame;
visible output still depends on wiring, power, and strip compatibility.

## Refresh configuration

Defaults are declared in [firmware/Kconfig](../firmware/Kconfig). Override shared
settings in [firmware/prj.conf](../firmware/prj.conf), then rebuild:

```ini
CONFIG_LIGHTING_AUTO_REFRESH=y
CONFIG_LIGHTING_REFRESH_INTERVAL_MS=50
CONFIG_LIGHTING_REFRESH_STACK_SIZE=1536
```

| Option | Default | Accepted values |
| --- | --- | --- |
| `CONFIG_LIGHTING_AUTO_REFRESH` | `y` | `y` or `n`; requires LED-strip support and multithreading |
| `CONFIG_LIGHTING_REFRESH_INTERVAL_MS` | `50` | 1–1000 ms; available with automatic refresh |
| `CONFIG_LIGHTING_REFRESH_STACK_SIZE` | `1536` | 512–8192 bytes; available with automatic refresh |

The worker uses preemptible priority **7** and starts once after successful
initialization. Its first deadline is one interval after the initial clear.
Absolute deadlines prevent transfer time from accumulating into the period.
If a transfer or scheduler delay overruns a slot, missed slots are skipped and
the next transfer uses current requests. No old-frame backlog is queued.
Scheduler latency and transfer duration can reduce the achieved refresh rate;
WS2812 bit timing and reset remain the driver's responsibility.

Every scheduled frame transmits, including unchanged and off selections. The
worker retries failures at the next slot and logs transfer errors at most once
per second. Manual `lighting_apply()` calls use the same serialized output path,
return driver errors directly, and do not restart the background schedule.

With automatic refresh disabled, applications must call `lighting_apply()` to
display changes. The included demo relies on automatic refresh.
