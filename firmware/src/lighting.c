/**
 * @file
 * @brief Private per-LED selections and one gamma-corrected strip output path.
 *
 * Setters change requested values. A private periodic thread applies them;
 * callers may also request a synchronous flush. A separate framebuffer
 * protects requested values from drivers that overwrite their input.
 */

/*
 * ============================================================================
 * INCLUDES
 * ============================================================================
 */

#include "lighting.h"
#include "lighting_gamma_table.h"

#include <errno.h>

#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/util.h>

/*
 * ============================================================================
 * MACROS AND CONSTANTS
 * ============================================================================
 */

/* Board configuration owns transport, pin routing, color order, and length. */
#define STRIP_NODE DT_ALIAS(led_strip)
#define STRIP_LENGTH DT_PROP(STRIP_NODE, chain_length)

/* Lower urgency than application control threads; rendering can block on SPI. */
#define REFRESH_THREAD_PRIORITY 7
#define REFRESH_ERROR_LOG_INTERVAL_MS 1000U

BUILD_ASSERT(DT_NODE_HAS_STATUS(STRIP_NODE, okay), "An enabled led-strip alias is required");
BUILD_ASSERT(STRIP_LENGTH > 0, "The LED strip must contain at least one pixel");
BUILD_ASSERT(ARRAY_SIZE(gamma_table) == 256U, "Gamma table must cover every 8-bit value");

#if defined(CONFIG_LIGHTING_AUTO_REFRESH)
BUILD_ASSERT(REFRESH_THREAD_PRIORITY < CONFIG_NUM_PREEMPT_PRIORITIES,
	     "Lighting refresh requires preemptible priority 7");
#endif

/* Immutable reference to the strip selected by this board's devicetree. */
static const struct device *const strip = DEVICE_DT_GET(STRIP_NODE);

/*
 * ============================================================================
 * DEFINITIONS
 * ============================================================================
 */

/* Only this module owns selections and transfer memory; no heap is needed. */
static struct lighting_led_state led_states[STRIP_LENGTH];
static struct led_rgb framebuffer[STRIP_LENGTH];
static bool strip_on;
static bool initialized;

/* Hold through each complete transfer so setters cannot change a live frame. */
K_MUTEX_DEFINE(lighting_mutex);

#if defined(CONFIG_LIGHTING_AUTO_REFRESH)
LOG_MODULE_REGISTER(lighting);

/* Created once, only after the strip's initial clear succeeds; no heap or timer. */
static K_KERNEL_STACK_DEFINE(refresh_stack, CONFIG_LIGHTING_REFRESH_STACK_SIZE);
static struct k_thread refresh_thread;
static int64_t first_refresh_ms;
#endif

/*
 * ============================================================================
 * PRIVATE FUNCTION DECLARATIONS
 * ============================================================================
 */

static int lock_lighting(void);
static int lock_initialized(void);
static uint8_t render_channel(uint8_t channel, uint8_t intensity);
static int render_frame(void);
#if defined(CONFIG_LIGHTING_AUTO_REFRESH)
static void refresh_entry(void *unused_a, void *unused_b, void *unused_c);
#endif

/*
 * ============================================================================
 * PUBLIC FUNCTION DEFINITIONS
 * ============================================================================
 */

int lighting_init(void)
{
	int err = lock_lighting();

	if (err != 0) {
		return err;
	}
	if (initialized) {
		goto out;
	}
	if (!device_is_ready(strip)) {
		err = -ENODEV;
		goto out;
	}

	/* Before first success, setters are unavailable and strip_on is false. */
	err = render_frame();
	if (err == 0) {
		for (size_t i = 0; i < ARRAY_SIZE(led_states); i++) {
			led_states[i] = (struct lighting_led_state){
				.intensity = LIGHTING_INTENSITY_MAX,
			};
		}
		initialized = true;

#if defined(CONFIG_LIGHTING_AUTO_REFRESH)
		/* Anchor timing before starting; the worker waits one full interval. */
		first_refresh_ms = k_uptime_get() + CONFIG_LIGHTING_REFRESH_INTERVAL_MS;
		k_thread_create(&refresh_thread, refresh_stack,
				K_KERNEL_STACK_SIZEOF(refresh_stack), refresh_entry,
				NULL, NULL, NULL, K_PRIO_PREEMPT(REFRESH_THREAD_PRIORITY),
				0, K_NO_WAIT);
#endif
	}

out:
	k_mutex_unlock(&lighting_mutex);
	return err;
}

size_t lighting_get_led_count(void)
{
	return STRIP_LENGTH;
}

int lighting_set_led(size_t index, const struct lighting_led_state *state)
{
	int err;

	if (state == NULL) {
		return -EINVAL;
	}
	if (index >= STRIP_LENGTH || state->intensity > LIGHTING_INTENSITY_MAX) {
		return -ERANGE;
	}
	err = lock_initialized();
	if (err != 0) {
		return err;
	}

	led_states[index] = *state;
	k_mutex_unlock(&lighting_mutex);
	return 0;
}

int lighting_set_all(const struct lighting_led_state *state)
{
	int err;

	if (state == NULL) {
		return -EINVAL;
	}
	if (state->intensity > LIGHTING_INTENSITY_MAX) {
		return -ERANGE;
	}
	err = lock_initialized();
	if (err != 0) {
		return err;
	}

	/* One lock makes the entire all-LED selection indivisible to apply. */
	for (size_t i = 0; i < ARRAY_SIZE(led_states); i++) {
		led_states[i] = *state;
	}
	k_mutex_unlock(&lighting_mutex);
	return 0;
}

int lighting_get_led(size_t index, struct lighting_led_state *state)
{
	int err;

	if (state == NULL) {
		return -EINVAL;
	}
	if (index >= STRIP_LENGTH) {
		return -ERANGE;
	}
	err = lock_initialized();
	if (err != 0) {
		return err;
	}

	*state = led_states[index];
	k_mutex_unlock(&lighting_mutex);
	return 0;
}

int lighting_set_on(bool on)
{
	int err = lock_initialized();

	if (err != 0) {
		return err;
	}

	strip_on = on;
	k_mutex_unlock(&lighting_mutex);
	return 0;
}

int lighting_get_on(bool *on)
{
	int err;

	if (on == NULL) {
		return -EINVAL;
	}
	err = lock_initialized();
	if (err != 0) {
		return err;
	}

	*on = strip_on;
	k_mutex_unlock(&lighting_mutex);
	return 0;
}

int lighting_apply(void)
{
	int err = lock_initialized();

	if (err != 0) {
		return err;
	}

	err = render_frame();
	k_mutex_unlock(&lighting_mutex);
	return err;
}

/*
 * ============================================================================
 * PRIVATE FUNCTION DEFINITIONS
 * ============================================================================
 */

/** Reject ISR callers before taking a sleeping mutex or using the strip API. */
static int lock_lighting(void)
{
	if (k_is_in_isr()) {
		return -EWOULDBLOCK;
	}

	return k_mutex_lock(&lighting_mutex, K_FOREVER);
}

/** Return with the mutex held only if initialization has succeeded. */
static int lock_initialized(void)
{
	int err = lock_lighting();

	if (err != 0) {
		return err;
	}
	if (!initialized) {
		k_mutex_unlock(&lighting_mutex);
		return -ENODEV;
	}

	return 0;
}

/** Scale with half-up rounding, then apply gamma without floating-point code. */
static uint8_t render_channel(uint8_t channel, uint8_t intensity)
{
	uint32_t scaled = ((uint32_t)channel * intensity + LIGHTING_INTENSITY_MAX / 2U) /
			  LIGHTING_INTENSITY_MAX;

	return gamma_table[scaled];
}

/**
 * Render and transmit a complete frame while holding lighting_mutex.
 * Refill every pixel, including optional scratch space, on every attempt:
 * Zephyr drivers may overwrite their input on successful or failed transfers.
 * Never pass the requested-state array to the driver or gamma-correct it in
 * place; repeated applies must give identical output without cumulative loss.
 */
static int render_frame(void)
{
	for (size_t i = 0; i < ARRAY_SIZE(framebuffer); i++) {
		const struct lighting_led_state *state = &led_states[i];
		struct led_rgb color = {0};

		if (strip_on) {
			color.r = render_channel(state->rgb.r, state->intensity);
			color.g = render_channel(state->rgb.g, state->intensity);
			color.b = render_channel(state->rgb.b, state->intensity);
		}
		framebuffer[i] = color;
	}

	return led_strip_update_rgb(strip, framebuffer, ARRAY_SIZE(framebuffer));
}

#if defined(CONFIG_LIGHTING_AUTO_REFRESH)
/**
 * Refresh from thread context on an absolute uptime schedule.
 * Adding an interval to the previous deadline avoids accumulating SPI and
 * logging execution time. Skip elapsed slots after delays: only the latest
 * selection matters, so missed frames must never create a catch-up loop.
 */
static void refresh_entry(void *unused_a, void *unused_b, void *unused_c)
{
	const int64_t period_ms = CONFIG_LIGHTING_REFRESH_INTERVAL_MS;
	int64_t deadline_ms = first_refresh_ms;

	ARG_UNUSED(unused_a);
	ARG_UNUSED(unused_b);
	ARG_UNUSED(unused_c);

	while (true) {
		int64_t now_ms;
		int err;

		/* Recheck the deadline even if a kernel wakeup interrupts the sleep. */
		while (k_uptime_get() < deadline_ms) {
			k_sleep(K_TIMEOUT_ABS_MS(deadline_ms));
		}
		err = lighting_apply();
		if (err != 0) {
			LOG_ERR_RATELIMIT_RATE(REFRESH_ERROR_LOG_INTERVAL_MS,
					      "LED strip refresh failed (%d)", err);
		}

		deadline_ms += period_ms;
		now_ms = k_uptime_get();
		if (deadline_ms <= now_ms) {
			deadline_ms += ((now_ms - deadline_ms) / period_ms + 1) * period_ms;
		}
	}
}
#endif
