/** @file Application startup and a repeating uniform-strip lighting demo. */

/*
 * ============================================================================
 * INCLUDES
 * ============================================================================
 */

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/util.h>

#include "lighting.h"
#include "meshlight_version.h"

/*
 * ============================================================================
 * MACROS AND CONSTANTS
 * ============================================================================
 */

/* Pulse intensity every 50 ms; advance the RGB palette at each dim endpoint. */
#define DEMO_FRAME_INTERVAL_MS 50U
#define DEMO_INTENSITY_MIN 10
#define DEMO_INTENSITY_STEP 2

/* Application demonstration colors; lighting itself only accepts RGB. */
static const struct led_rgb demo_colors[] = {
	{.r = 255, .g = 0, .b = 0},
	{.r = 255, .g = 255, .b = 0},
	{.r = 0, .g = 255, .b = 0},
	{.r = 0, .g = 255, .b = 255},
	{.r = 0, .g = 0, .b = 255},
	{.r = 255, .g = 0, .b = 255},
};

/*
 * ============================================================================
 * DEFINITIONS
 * ============================================================================
 */

LOG_MODULE_REGISTER(meshlight);

/*
 * ============================================================================
 * PUBLIC FUNCTION DEFINITIONS
 * ============================================================================
 */

/** Initialize, enable, and continuously update every configured strip pixel. */
int main(void)
{
	struct lighting_led_state state = {
		.rgb = demo_colors[0],
		.intensity = DEMO_INTENSITY_MIN,
	};
	/* Signed arithmetic lets the same accumulator dim down without wrapping. */
	int intensity_step = DEMO_INTENSITY_STEP;
	size_t color_index = 0;
	int err;

	LOG_INF("Meshlight %s (%s)", MESHLIGHT_VERSION_STRING, MESHLIGHT_GIT_SHA);
	err = lighting_init();

	if (err != 0) {
		LOG_ERR("Lighting initialization failed (%d)", err);
		return err;
	}

	/* Select dim red before enabling output, avoiding a full-brightness flash. */
	err = lighting_set_all(&state);
	if (err == 0) {
		err = lighting_set_on(true);
	}
	if (err != 0) {
		LOG_ERR("Lighting demo startup failed (%d)", err);
		return err;
	}

	LOG_INF("Lighting demo started");

	while (true) {
		int next_intensity;

		/* Sleep instead of busy-waiting so other Zephyr threads can run. */
		k_sleep(K_MSEC(DEMO_FRAME_INTERVAL_MS));
		next_intensity = state.intensity + intensity_step;

		/* Reverse at 10% and 100%; clamp before narrowing back to uint8_t. */
		if (next_intensity >= (int)LIGHTING_INTENSITY_MAX) {
			next_intensity = LIGHTING_INTENSITY_MAX;
			intensity_step = -DEMO_INTENSITY_STEP;
		} else if (next_intensity <= DEMO_INTENSITY_MIN) {
			next_intensity = DEMO_INTENSITY_MIN;
			intensity_step = DEMO_INTENSITY_STEP;
			color_index = (color_index + 1U) % ARRAY_SIZE(demo_colors);
			state.rgb = demo_colors[color_index];
		}
		state.intensity = (uint8_t)next_intensity;

		/* The independent lighting worker displays this request on its next frame. */
		err = lighting_set_all(&state);
		if (err != 0) {
			LOG_ERR("Lighting demo update failed (%d)", err);
			/* Limit retries if a state request fails; output errors are logged by lighting. */
			k_sleep(K_SECONDS(1));
		}
	}
}
