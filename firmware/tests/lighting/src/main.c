// SPDX-License-Identifier: MIT

/** @file Production lighting tests using a fake strip and fixed gamma vectors. */

#include <errno.h>
#include <string.h>

#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/irq_offload.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/util.h>
#include <zephyr/ztest.h>

#include "lighting/lighting.h"

#define STRIP_NODE DT_ALIAS(led_strip)
#define PIXEL_COUNT DT_PROP(STRIP_NODE, chain_length)

/** Observations and injected failures belong to the fake, not the module. */
static struct {
	struct led_rgb frame[PIXEL_COUNT];
	size_t count;
	unsigned int calls;
	int transfer_error;
	bool overwrite;
	bool overlap;
	bool wrong_length;
	bool check_uniform;
	bool nonuniform;
	atomic_t active;
} fake;

/** Capture a complete frame and optionally corrupt the driver's input. */
static int fake_update(const struct device *dev, struct led_rgb *pixels, size_t count)
{
	ARG_UNUSED(dev);
	if (atomic_inc(&fake.active) != 0) {
		fake.overlap = true;
	}

	/* Expose unlocked frame rebuilding or overlapping driver calls. */
	k_yield();
	fake.calls++;
	fake.count = count;
	if (count != ARRAY_SIZE(fake.frame)) {
		fake.wrong_length = true;
		atomic_dec(&fake.active);
		return -EINVAL;
	}
	memcpy(fake.frame, pixels, sizeof(fake.frame));
	if (fake.check_uniform) {
		for (size_t i = 1; i < count; i++) {
			if (pixels[i].r != pixels[0].r || pixels[i].g != pixels[0].g ||
			    pixels[i].b != pixels[0].b) {
				fake.nonuniform = true;
			}
		}
	}
	if (fake.overwrite) {
		memset(pixels, 0xa5, count * sizeof(*pixels));
	}
	atomic_dec(&fake.active);
	return fake.transfer_error;
}

/** Advertise the configured length through the real Zephyr driver API. */
static size_t fake_length(const struct device *dev)
{
	ARG_UNUSED(dev);
	return PIXEL_COUNT;
}

static DEVICE_API(led_strip, fake_api) = {
	.update_rgb = fake_update,
	.length = fake_length,
};

/* Register a normal device so production code needs no test-only hooks. */
DEVICE_DT_DEFINE(STRIP_NODE, NULL, NULL, NULL, NULL, POST_KERNEL,
		 CONFIG_LED_STRIP_INIT_PRIORITY, &fake_api);

/** Check semantic channels without assuming struct padding or scratch layout. */
static void assert_rgb(const struct led_rgb *rgb, uint8_t red, uint8_t green, uint8_t blue)
{
	zassert_equal(rgb->r, red, "red: got %u expected %u", rgb->r, red);
	zassert_equal(rgb->g, green, "green: got %u expected %u", rgb->g, green);
	zassert_equal(rgb->b, blue, "blue: got %u expected %u", rgb->b, blue);
}

/** Compare raw requested values, which must never be gamma-corrected in place. */
static void assert_state(const struct lighting_led_state *actual,
			 const struct lighting_led_state *expected)
{
	assert_rgb(&actual->rgb, expected->rgb.r, expected->rgb.g, expected->rgb.b);
	zassert_equal(actual->intensity, expected->intensity);
}

/** Check every transmitted pixel and the complete devicetree-derived length. */
static void assert_uniform(uint8_t red, uint8_t green, uint8_t blue)
{
	zassert_equal(fake.count, PIXEL_COUNT);
	zassert_false(fake.wrong_length);
	for (size_t i = 0; i < ARRAY_SIZE(fake.frame); i++) {
		assert_rgb(&fake.frame[i], red, green, blue);
	}
}

/** Verify pre-init contracts and failure recovery once, before control tests. */
static void *controller_setup(void)
{
	const struct device *dev = DEVICE_DT_GET(STRIP_NODE);
	const struct lighting_led_state sentinel = {
		.rgb = {.r = 12, .g = 34, .b = 56}, .intensity = 7,
	};
	struct lighting_led_state state = sentinel;
	bool on = true;

	zassert_equal(lighting_get_led_count(), PIXEL_COUNT);
	zassert_equal(lighting_get_led(0, &state), -ENODEV);
	assert_state(&state, &sentinel);
	zassert_equal(lighting_get_on(&on), -ENODEV);
	zassert_true(on);
	zassert_equal(lighting_set_led(0, &sentinel), -ENODEV);
	zassert_equal(lighting_set_all(&sentinel), -ENODEV);
	zassert_equal(lighting_set_led_color(0, &sentinel.rgb), -ENODEV);
	zassert_equal(lighting_set_all_color(&sentinel.rgb), -ENODEV);
	zassert_equal(lighting_set_led_intensity(0, 50), -ENODEV);
	zassert_equal(lighting_set_all_intensity(50), -ENODEV);
	zassert_equal(lighting_set_on(true), -ENODEV);
	zassert_equal(lighting_apply(), -ENODEV);
	zassert_equal(fake.calls, 0);

	/* Emulate failed Zephyr device initialization, then restore readiness. */
	dev->state->init_res = EIO;
	zassert_equal(lighting_init(), -ENODEV);
	zassert_equal(fake.calls, 0);
	dev->state->init_res = 0;

	fake.transfer_error = -EIO;
	fake.overwrite = true;
	zassert_equal(lighting_init(), -EIO);
	zassert_equal(lighting_get_led(0, &state), -ENODEV);
	assert_state(&state, &sentinel);
	zassert_equal(lighting_get_on(&on), -ENODEV);
	zassert_true(on);

	fake.transfer_error = 0;
	zassert_ok(lighting_init());
	zassert_equal(fake.calls, 2);
	assert_uniform(56, 56, 56);
	zassert_ok(lighting_get_on(&on));
	zassert_true(on);
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &state));
		assert_rgb(&state.rgb, 255, 255, 255);
		zassert_equal(state.intensity, 50);
	}
	return NULL;
}

/** Reset requested and captured state through the public API between tests. */
static void controller_before(void *fixture)
{
	const struct lighting_led_state black = {.intensity = 100};

	ARG_UNUSED(fixture);
	fake.transfer_error = 0;
	fake.overwrite = false;
	fake.check_uniform = false;
	zassert_ok(lighting_set_all(&black));
	zassert_ok(lighting_set_on(false));
	zassert_ok(lighting_apply());
	memset(&fake, 0, sizeof(fake));
}

/** Successful repeated initialization must not reset pending, unapplied values. */
ZTEST(controller, test_idempotent_initialization)
{
	const struct lighting_led_state request = {
		.rgb = {.r = 255, .g = 128, .b = 64}, .intensity = 50,
	};
	struct lighting_led_state actual;
	bool on;

	zassert_ok(lighting_set_all(&request));
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_init());
	zassert_equal(fake.calls, 0);
	zassert_ok(lighting_get_on(&on));
	zassert_true(on);
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &actual));
		assert_state(&actual, &request);
	}
	zassert_ok(lighting_apply());
	assert_uniform(56, 12, 3);
}

/** All setters stage only, including while on and when switching power off. */
ZTEST(controller, test_all_updates_require_apply)
{
	const struct lighting_led_state red = {.rgb = {.r = 255}, .intensity = 100};
	const struct lighting_led_state blue = {.rgb = {.b = 255}, .intensity = 50};

	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_on(true));
	zassert_equal(fake.calls, 0);
	zassert_ok(lighting_apply());
	assert_uniform(255, 0, 0);
	zassert_ok(lighting_set_all(&blue));
	zassert_equal(fake.calls, 1);
	assert_uniform(255, 0, 0);
	zassert_ok(lighting_apply());
	assert_uniform(0, 0, 56);
	zassert_ok(lighting_set_on(false));
	zassert_equal(fake.calls, 2);
	assert_uniform(0, 0, 56);
	zassert_ok(lighting_apply());
	assert_uniform(0, 0, 0);
}

/** The last legal index changes independently of every other LED. */
ZTEST(controller, test_single_led_isolation)
{
	const struct lighting_led_state green = {.rgb = {.g = 255}, .intensity = 100};
	struct lighting_led_state actual;
	size_t last = lighting_get_led_count() - 1U;

	zassert_ok(lighting_set_led(last, &green));
	zassert_ok(lighting_set_on(true));
	zassert_equal(fake.calls, 0);
	zassert_ok(lighting_apply());
	zassert_equal(fake.count, PIXEL_COUNT);
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &actual));
		if (i == last) {
			assert_state(&actual, &green);
			assert_rgb(&fake.frame[i], 0, 255, 0);
		} else {
			assert_rgb(&actual.rgb, 0, 0, 0);
			zassert_equal(actual.intensity, 100);
			assert_rgb(&fake.frame[i], 0, 0, 0);
		}
	}
}

/** A single apply must preserve distinct colors and intensities across a frame. */
ZTEST(controller, test_mixed_led_frame)
{
	static const struct lighting_led_state requests[] = {
		{.rgb = {.r = 255}, .intensity = 100},
		{.rgb = {.g = 255}, .intensity = 50},
		{.rgb = {.b = 255}, .intensity = 10},
	};
	static const struct led_rgb expected[] = {
		{.r = 255}, {.g = 56}, {.b = 2},
	};

	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_set_led(i, &requests[i % ARRAY_SIZE(requests)]));
	}
	zassert_ok(lighting_set_on(true));
	zassert_equal(fake.calls, 0);
	zassert_ok(lighting_apply());
	zassert_equal(fake.calls, 1);
	zassert_equal(fake.count, PIXEL_COUNT);
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		const struct led_rgb *rgb = &expected[i % ARRAY_SIZE(expected)];

		assert_rgb(&fake.frame[i], rgb->r, rgb->g, rgb->b);
	}
}

/** Set-all replaces every previous per-LED color and intensity in one request. */
ZTEST(controller, test_all_overwrites_individual_selections)
{
	const struct lighting_led_state red = {.rgb = {.r = 255}, .intensity = 10};
	const struct lighting_led_state white = {
		.rgb = {.r = 255, .g = 255, .b = 255}, .intensity = 50,
	};
	struct lighting_led_state actual;

	zassert_ok(lighting_set_led(0, &red));
	zassert_ok(lighting_set_all(&white));
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_apply());
	assert_uniform(56, 56, 56);
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &actual));
		assert_state(&actual, &white);
	}
}

/** A partial update preserves the other field, other LEDs, and power. */
ZTEST(controller, test_single_led_partial_updates_preserve_other_fields)
{
	struct led_rgb color = {.r = 12, .g = 34, .b = 56};
	struct lighting_led_state actual;
	bool on;

	zassert_ok(lighting_set_led_color(PIXEL_COUNT - 1, &color));
	memset(&color, 0, sizeof(color));
	zassert_ok(lighting_get_led(PIXEL_COUNT - 1, &actual));
	assert_rgb(&actual.rgb, 12, 34, 56);
	zassert_equal(actual.intensity, 100);
	zassert_ok(lighting_set_led_intensity(PIXEL_COUNT - 1, 0));
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &actual));
		if (i == PIXEL_COUNT - 1) {
			assert_rgb(&actual.rgb, 12, 34, 56);
			zassert_equal(actual.intensity, 0);
		} else {
			assert_rgb(&actual.rgb, 0, 0, 0);
			zassert_equal(actual.intensity, 100);
		}
	}
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
	zassert_equal(fake.calls, 0);
}

/** All-target partial updates retain each LED's distinct unselected field. */
ZTEST(controller, test_all_partial_updates_preserve_individual_selections)
{
	const struct led_rgb color = {.r = 255, .g = 128, .b = 64};
	struct lighting_led_state actual;
	bool on;

	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		struct lighting_led_state request = {.rgb = {.r = i}, .intensity = i};

		zassert_ok(lighting_set_led(i, &request));
	}
	zassert_ok(lighting_set_all_intensity(50));
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &actual));
		assert_rgb(&actual.rgb, i, 0, 0);
		zassert_equal(actual.intensity, 50);
		zassert_ok(lighting_set_led_intensity(i, i));
	}
	zassert_ok(lighting_set_all_color(&color));
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &actual));
		assert_rgb(&actual.rgb, 255, 128, 64);
		zassert_equal(actual.intensity, i);
	}
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
	zassert_equal(fake.calls, 0);
}

/** Fixed reference vectors are independent of the production lookup table. */
ZTEST(controller, test_gamma_reference_vectors)
{
	static const struct {
		struct lighting_led_state request;
		struct led_rgb expected;
	} vectors[] = {
		{{{.r = 255, .g = 128, .b = 64}, 100}, {.r = 255, .g = 56, .b = 12}},
		{{{.r = 255, .g = 128, .b = 64}, 50}, {.r = 56, .g = 12, .b = 3}},
		{{{.r = 200, .g = 100, .b = 40}, 50}, {.r = 33, .g = 7, .b = 1}},
		{{{.r = 255, .g = 128, .b = 64}, 10}, {.r = 2, .g = 0, .b = 0}},
		{{{.r = 255, .g = 255, .b = 255}, 0}, {0}},
		{{{.r = 0, .g = 0, .b = 0}, 100}, {0}},
	};

	zassert_ok(lighting_set_on(true));
	for (size_t i = 0; i < ARRAY_SIZE(vectors); i++) {
		const struct led_rgb *rgb = &vectors[i].expected;

		zassert_ok(lighting_set_all(&vectors[i].request));
		zassert_ok(lighting_apply());
		assert_uniform(rgb->r, rgb->g, rgb->b);
	}
}

/** Pin half-up intensity rounding before gamma and the low-end quantization. */
ZTEST(controller, test_rounding_and_low_intensity)
{
	struct lighting_led_state request = {
		.rgb = {.r = 255, .g = 253, .b = 200}, .intensity = 50,
	};

	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_set_all(&request));
	zassert_ok(lighting_apply());
	assert_uniform(56, 55, 33);
	request.intensity = 1;
	zassert_ok(lighting_set_all(&request));
	zassert_ok(lighting_apply());
	assert_uniform(0, 0, 0);
}

/** Visit every eight-bit channel input: output is monotone with exact endpoints. */
ZTEST(controller, test_gamma_channel_monotonicity)
{
	struct lighting_led_state request = {.intensity = 100};
	uint8_t previous = 0;

	zassert_ok(lighting_set_on(true));
	for (unsigned int value = 0; value <= UINT8_MAX; value++) {
		request.rgb = (struct led_rgb){.r = value, .g = value, .b = value};
		zassert_ok(lighting_set_all(&request));
		zassert_ok(lighting_apply());
		zassert_true(fake.frame[0].r >= previous);
		assert_uniform(fake.frame[0].r, fake.frame[0].r, fake.frame[0].r);
		if (value == 0U) {
			zassert_equal(fake.frame[0].r, 0);
		}
		previous = fake.frame[0].r;
	}
	zassert_equal(previous, 255);
}

/** All accepted intensity levels are monotone for unequal RGB components. */
ZTEST(controller, test_intensity_monotonicity)
{
	struct lighting_led_state request = {.rgb = {.r = 255, .g = 128, .b = 64}};
	struct led_rgb previous = {0};

	zassert_ok(lighting_set_on(true));
	for (unsigned int intensity = 0; intensity <= 100U; intensity++) {
		request.intensity = intensity;
		zassert_ok(lighting_set_all(&request));
		zassert_ok(lighting_apply());
		zassert_true(fake.frame[0].r >= previous.r);
		zassert_true(fake.frame[0].g >= previous.g);
		zassert_true(fake.frame[0].b >= previous.b);
		if (intensity == 0U) {
			assert_uniform(0, 0, 0);
		}
		previous = fake.frame[0];
	}
	assert_uniform(255, 56, 12);
}

/** Off applies black; changes while off become visible only after on and apply. */
ZTEST(controller, test_off_updates_and_restoration)
{
	const struct lighting_led_state red = {.rgb = {.r = 255}, .intensity = 100};
	const struct lighting_led_state blue = {.rgb = {.b = 255}, .intensity = 50};
	struct lighting_led_state actual;
	bool on;

	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_apply());
	zassert_ok(lighting_set_on(false));
	zassert_ok(lighting_apply());
	assert_uniform(0, 0, 0);
	zassert_ok(lighting_set_led(0, &blue));
	zassert_ok(lighting_apply());
	assert_uniform(0, 0, 0);
	zassert_ok(lighting_get_led(0, &actual));
	assert_state(&actual, &blue);
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_apply());
	assert_rgb(&fake.frame[0], 0, 0, 56);
	for (size_t i = 1; i < PIXEL_COUNT; i++) {
		assert_rgb(&fake.frame[i], 255, 0, 0);
	}
}

/** Toggling power never restores a nonzero intensity for a dark selection. */
ZTEST(controller, test_zero_intensity_survives_power_changes)
{
	const struct lighting_led_state request = {
		.rgb = {.r = 255, .g = 128, .b = 64}, .intensity = 0,
	};
	struct lighting_led_state actual;

	zassert_ok(lighting_set_all(&request));
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_apply());
	assert_uniform(0, 0, 0);
	zassert_ok(lighting_set_on(false));
	zassert_ok(lighting_apply());
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_apply());
	assert_uniform(0, 0, 0);
	zassert_ok(lighting_get_led(0, &actual));
	assert_state(&actual, &request);
}

/** Neither input storage nor getter snapshots expose private mutable state. */
ZTEST(controller, test_caller_owned_values_and_snapshots)
{
	const struct lighting_led_state expected = {
		.rgb = {.r = 255, .g = 128, .b = 64}, .intensity = 50,
	};
	struct lighting_led_state request = expected;
	struct lighting_led_state actual;
	bool on = false;

	zassert_ok(lighting_set_all(&request));
	memset(&request, 0, sizeof(request));
	zassert_ok(lighting_get_led(0, &actual));
	assert_state(&actual, &expected);
	memset(&actual, 0, sizeof(actual));
	zassert_ok(lighting_get_led(0, &actual));
	assert_state(&actual, &expected);
	zassert_ok(lighting_set_led(0, &actual));
	memset(&actual, 0, sizeof(actual));
	zassert_ok(lighting_get_led(0, &actual));
	assert_state(&actual, &expected);
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_get_on(&on));
	zassert_true(on);
	on = false;
	zassert_ok(lighting_get_on(&on));
	zassert_true(on);
	zassert_equal(fake.calls, 0);
}

/** All rejected requests preserve every LED, the power selection, and outputs. */
ZTEST(controller, test_invalid_requests_are_atomic)
{
	const struct lighting_led_state valid = {
		.rgb = {.r = 255, .g = 128, .b = 64}, .intensity = 50,
	};
	struct lighting_led_state invalid = valid;
	struct lighting_led_state actual = valid;
	bool on;

	invalid.intensity = 101;
	zassert_ok(lighting_set_all(&valid));
	zassert_ok(lighting_set_on(true));
	zassert_equal(lighting_set_led(0, NULL), -EINVAL);
	zassert_equal(lighting_set_all(NULL), -EINVAL);
	zassert_equal(lighting_set_led_color(0, NULL), -EINVAL);
	zassert_equal(lighting_set_all_color(NULL), -EINVAL);
	zassert_equal(lighting_set_led_color(PIXEL_COUNT, &valid.rgb), -ERANGE);
	zassert_equal(lighting_set_led_color(SIZE_MAX, &valid.rgb), -ERANGE);
	zassert_equal(lighting_set_led_intensity(PIXEL_COUNT, 50), -ERANGE);
	zassert_equal(lighting_set_led_intensity(SIZE_MAX, 50), -ERANGE);
	zassert_equal(lighting_set_led_intensity(0, 101), -ERANGE);
	zassert_equal(lighting_set_all_intensity(UINT8_MAX), -ERANGE);
	zassert_equal(lighting_get_led(0, NULL), -EINVAL);
	zassert_equal(lighting_get_on(NULL), -EINVAL);
	zassert_equal(lighting_set_led(PIXEL_COUNT, &valid), -ERANGE);
	zassert_equal(lighting_set_led(SIZE_MAX, &valid), -ERANGE);
	zassert_equal(lighting_get_led(PIXEL_COUNT, &actual), -ERANGE);
	assert_state(&actual, &valid);
	zassert_equal(lighting_get_led(SIZE_MAX, &actual), -ERANGE);
	assert_state(&actual, &valid);
	zassert_equal(lighting_set_led(0, &invalid), -ERANGE);
	zassert_equal(lighting_set_all(&invalid), -ERANGE);
	invalid.intensity = UINT8_MAX;
	zassert_equal(lighting_set_led(0, &invalid), -ERANGE);
	zassert_equal(lighting_set_all(&invalid), -ERANGE);
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &actual));
		assert_state(&actual, &valid);
	}
	zassert_ok(lighting_get_on(&on));
	zassert_true(on);
	zassert_equal(fake.calls, 0);
}

/** Apply must retransmit unchanged selections, without cumulative gamma loss. */
ZTEST(controller, test_repeated_apply_rebuilds_frame)
{
	const struct lighting_led_state request = {
		.rgb = {.r = 255, .g = 128, .b = 64}, .intensity = 50,
	};
	struct lighting_led_state actual;

	zassert_ok(lighting_set_all(&request));
	zassert_ok(lighting_set_on(true));
	fake.overwrite = true;
	for (unsigned int attempt = 0; attempt < 3U; attempt++) {
		zassert_ok(lighting_apply());
		assert_uniform(56, 12, 3);
		zassert_ok(lighting_get_led(0, &actual));
		assert_state(&actual, &request);
	}
	zassert_equal(fake.calls, 3);
}

/** A failed transfer keeps the newest staged values for an undamaged retry. */
ZTEST(controller, test_transfer_failure_and_buffer_recovery)
{
	const struct lighting_led_state red = {.rgb = {.r = 255}, .intensity = 100};
	const struct lighting_led_state blue = {.rgb = {.b = 255}, .intensity = 50};
	struct lighting_led_state actual;
	bool on;

	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_apply());
	zassert_ok(lighting_set_led(0, &blue));
	fake.transfer_error = -EIO;
	fake.overwrite = true;
	zassert_equal(lighting_apply(), -EIO);
	zassert_ok(lighting_get_led(0, &actual));
	assert_state(&actual, &blue);
	zassert_ok(lighting_get_on(&on));
	zassert_true(on);
	fake.transfer_error = 0;
	zassert_ok(lighting_apply());
	assert_rgb(&fake.frame[0], 0, 0, 56);
	for (size_t i = 1; i < PIXEL_COUNT; i++) {
		assert_rgb(&fake.frame[i], 255, 0, 0);
	}
	zassert_false(fake.wrong_length);
}

/** Failed off/on transfers do not discard the requested power or LED values. */
ZTEST(controller, test_power_transfer_failure_retains_request)
{
	const struct lighting_led_state red = {.rgb = {.r = 255}, .intensity = 100};
	bool on;

	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_on(true));
	fake.transfer_error = -ETIMEDOUT;
	zassert_equal(lighting_apply(), -ETIMEDOUT);
	zassert_ok(lighting_get_on(&on));
	zassert_true(on);
	zassert_ok(lighting_set_on(false));
	zassert_equal(lighting_apply(), -ETIMEDOUT);
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
	fake.transfer_error = 0;
	zassert_ok(lighting_apply());
	assert_uniform(0, 0, 0);
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_apply());
	assert_uniform(255, 0, 0);
}

/** Count is immutable and independent of requested colors, power, or transfers. */
ZTEST(controller, test_count_and_getters_do_not_transmit)
{
	struct lighting_led_state actual;
	bool on;

	zassert_equal(lighting_get_led_count(), PIXEL_COUNT);
	for (size_t i = 0; i < lighting_get_led_count(); i++) {
		zassert_ok(lighting_get_led(i, &actual));
		assert_rgb(&actual.rgb, 0, 0, 0);
		zassert_equal(actual.intensity, 100);
	}
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
	zassert_equal(fake.calls, 0);
}

/** Run valid controls in a real ISR; gather results without asserting there. */
static void controls_in_isr(const void *parameter)
{
	int *results = (int *)parameter;
	const struct lighting_led_state request = {.rgb = {.r = 255}, .intensity = 50};
	struct lighting_led_state state = {.rgb = {.r = 42}, .intensity = 7};
	bool on = true;

	results[0] = lighting_init();
	results[1] = lighting_set_led(0, &request);
	results[2] = lighting_set_all(&request);
	results[3] = lighting_get_led(0, &state);
	results[4] = lighting_set_on(true);
	results[5] = lighting_get_on(&on);
	results[6] = lighting_apply();
	results[7] = lighting_set_led_color(0, &request.rgb);
	results[8] = lighting_set_all_color(&request.rgb);
	results[9] = lighting_set_led_intensity(0, 50);
	results[10] = lighting_set_all_intensity(50);
	results[11] = lighting_get_led_count() == PIXEL_COUNT;
	results[12] = state.rgb.r == 42 && state.intensity == 7 && on;
}

/** Only the immutable count getter is allowed from interrupt context. */
ZTEST(controller, test_isr_rejection)
{
	int results[13] = {0};

	irq_offload(controls_in_isr, results);
	for (size_t i = 0; i < 11U; i++) {
		zassert_equal(results[i], -EWOULDBLOCK);
	}
	zassert_equal(results[11], 1);
	zassert_equal(results[12], 1);
	zassert_equal(fake.calls, 0);
}

/** Worker results are inspected after join, outside the concurrent threads. */
struct worker_request {
	struct lighting_led_state state;
	size_t index;
	bool all;
	enum { UPDATE_BOTH, UPDATE_COLOR, UPDATE_INTENSITY } fields;
	int result;
};

K_THREAD_STACK_DEFINE(worker_a_stack, 1024);
K_THREAD_STACK_DEFINE(worker_b_stack, 1024);
static struct k_thread worker_a;
static struct k_thread worker_b;

/** Compete on setters and transfers while the fake deliberately yields. */
static void update_worker(void *request, void *unused_a, void *unused_b)
{
	struct worker_request *worker = request;

	ARG_UNUSED(unused_a);
	ARG_UNUSED(unused_b);
	for (unsigned int i = 0; i < 16U; i++) {
		switch (worker->fields) {
		case UPDATE_COLOR:
			worker->result = worker->all ? lighting_set_all_color(&worker->state.rgb) :
				lighting_set_led_color(worker->index, &worker->state.rgb);
			break;
		case UPDATE_INTENSITY:
			worker->result = worker->all ?
				lighting_set_all_intensity(worker->state.intensity) :
				lighting_set_led_intensity(worker->index, worker->state.intensity);
			break;
		default:
			worker->result = worker->all ? lighting_set_all(&worker->state) :
				lighting_set_led(worker->index, &worker->state);
			break;
		}
		if (worker->result == 0) {
			worker->result = lighting_apply();
		}
		if (worker->result != 0) {
			break;
		}
	}
}

/** Join both workers before checking transfer serialization or final state. */
static void run_workers(struct worker_request *a, struct worker_request *b)
{
	zassert_ok(lighting_set_on(true));
	fake.overwrite = true;
	k_thread_create(&worker_a, worker_a_stack, K_THREAD_STACK_SIZEOF(worker_a_stack),
			update_worker, a, NULL, NULL, K_PRIO_PREEMPT(1), 0, K_NO_WAIT);
	k_thread_create(&worker_b, worker_b_stack, K_THREAD_STACK_SIZEOF(worker_b_stack),
			update_worker, b, NULL, NULL, K_PRIO_PREEMPT(1), 0, K_NO_WAIT);
	zassert_ok(k_thread_join(&worker_a, K_SECONDS(5)));
	zassert_ok(k_thread_join(&worker_b, K_SECONDS(5)));
	zassert_ok(a->result);
	zassert_ok(b->result);
	zassert_equal(fake.calls, 32);
	zassert_false(fake.overlap);
	zassert_false(fake.wrong_length);
}

/** Set-all and apply must each be atomic, with no mixed or overlapping frames. */
ZTEST(controller, test_concurrent_all_updates)
{
	struct worker_request a = {
		.state = {.rgb = {.r = 255}, .intensity = 100}, .all = true,
	};
	struct worker_request b = {
		.state = {.rgb = {.b = 255}, .intensity = 100}, .all = true,
	};

	fake.check_uniform = true;
	run_workers(&a, &b);
	zassert_false(fake.nonuniform);
}

/** Concurrent per-LED writes retain both selections when indices differ. */
ZTEST(controller, test_concurrent_individual_updates)
{
	struct worker_request a = {.state = {.rgb = {.r = 255}, .intensity = 100}};
	struct worker_request b = {
		.state = {.rgb = {.b = 255}, .intensity = 100}, .index = PIXEL_COUNT - 1U,
	};
	struct lighting_led_state actual;

	run_workers(&a, &b);
	zassert_ok(lighting_apply());
	if (PIXEL_COUNT == 1U) {
		zassert_ok(lighting_get_led(0, &actual));
		zassert_true((actual.rgb.r == 255 && actual.rgb.b == 0) ||
			     (actual.rgb.r == 0 && actual.rgb.b == 255));
		assert_rgb(&fake.frame[0], actual.rgb.r, 0, actual.rgb.b);
	} else {
		zassert_ok(lighting_get_led(0, &actual));
		assert_state(&actual, &a.state);
		zassert_ok(lighting_get_led(PIXEL_COUNT - 1U, &actual));
		assert_state(&actual, &b.state);
		assert_rgb(&fake.frame[0], 255, 0, 0);
		assert_rgb(&fake.frame[PIXEL_COUNT - 1U], 0, 0, 255);
		for (size_t i = 1; i + 1U < PIXEL_COUNT; i++) {
			assert_rgb(&fake.frame[i], 0, 0, 0);
		}
	}
}

/** Concurrent partial setters must retain both writers, for one or all LEDs. */
ZTEST(controller, test_concurrent_color_and_intensity_updates)
{
	struct worker_request a = {
		.state = {.rgb = {.g = 255}}, .fields = UPDATE_COLOR,
	};
	struct worker_request b = {.state = {.intensity = 50}, .fields = UPDATE_INTENSITY};
	struct lighting_led_state actual;

	for (unsigned int all = 0; all < 2; all++) {
		a.all = all;
		b.all = all;
		fake.calls = 0;
		run_workers(&a, &b);
		for (size_t i = 0; i < (all ? PIXEL_COUNT : 1); i++) {
			zassert_ok(lighting_get_led(i, &actual));
			assert_rgb(&actual.rgb, 0, 255, 0);
			zassert_equal(actual.intensity, 50);
			assert_rgb(&fake.frame[i], 0, 56, 0);
		}
	}
}

ZTEST_SUITE(controller, NULL, controller_setup, controller_before, NULL, NULL);
