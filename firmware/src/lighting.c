/**
 * @file
 * @brief Private per-LED selections and one gamma-corrected strip output path.
 *
 * Setters change requested values; only initialization and explicit apply
 * transmit. A separate framebuffer protects requested values from drivers
 * that overwrite their input, and allows failed transfers to be retried.
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
#include <zephyr/sys/util.h>

/*
 * ============================================================================
 * MACROS AND CONSTANTS
 * ============================================================================
 */

/* Board configuration owns transport, pin routing, color order, and length. */
#define STRIP_NODE DT_ALIAS(led_strip)
#define STRIP_LENGTH DT_PROP(STRIP_NODE, chain_length)

BUILD_ASSERT(DT_NODE_HAS_STATUS(STRIP_NODE, okay), "An enabled led-strip alias is required");
BUILD_ASSERT(STRIP_LENGTH > 0, "The LED strip must contain at least one pixel");
BUILD_ASSERT(ARRAY_SIZE(gamma_table) == 256U, "Gamma table must cover every 8-bit value");

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

/*
 * ============================================================================
 * PRIVATE FUNCTION DECLARATIONS
 * ============================================================================
 */

static int lock_lighting(void);
static int lock_initialized(void);
static uint8_t render_channel(uint8_t channel, uint8_t intensity);
static int render_frame(void);

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
