// SPDX-License-Identifier: MIT
#include "lighting/lighting.h"
#include "lighting/lighting_settings.h"
#include "fake_storage.h"

#include <errno.h>
#include <string.h>
#include <zephyr/device.h>
#include <zephyr/shell/shell.h>
#include <zephyr/shell/shell_dummy.h>
#include <zephyr/ztest.h>

#define STRIP_NODE DT_ALIAS(led_strip)
#define PIXEL_COUNT DT_PROP(STRIP_NODE, chain_length)

static struct led_rgb first_frame[PIXEL_COUNT];
static unsigned int frames;
static const struct lighting_led_state red = {.rgb = {.r = 255}, .intensity = 100};
static const struct lighting_led_state blue = {.rgb = {.b = 255}, .intensity = 40};

static int fake_update(const struct device *dev, struct led_rgb *pixels, size_t count)
{
	ARG_UNUSED(dev);
	zassert_equal(count, PIXEL_COUNT);
	if (frames++ == 0) {
		memcpy(first_frame, pixels, sizeof(first_frame));
	}
	return 0;
}

static size_t fake_length(const struct device *dev)
{
	ARG_UNUSED(dev);
	return PIXEL_COUNT;
}

static DEVICE_API(led_strip, fake_api) = {
	.update_rgb = fake_update, .length = fake_length,
};
DEVICE_DT_DEFINE(STRIP_NODE, NULL, NULL, NULL, NULL, POST_KERNEL,
		 CONFIG_LED_STRIP_INIT_PRIORITY, &fake_api);

static unsigned int count_io(bool successful)
{
	unsigned int count;

	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	count = successful ? fake_storage.writes : fake_storage.attempts;
	k_mutex_unlock(&fake_storage_mutex);
	return count;
}

static void wait_io(unsigned int count, bool successful)
{
	int64_t deadline = k_uptime_get() + 1000;

	while (count_io(successful) < count && k_uptime_get() < deadline) {
		k_msleep(2);
	}
	zassert_true(count_io(successful) >= count, "Storage did not progress");
}

static int64_t last_attempt_ms(void)
{
	int64_t time;

	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	time = fake_storage.last_attempt_ms;
	k_mutex_unlock(&fake_storage_mutex);
	return time;
}

static void assert_selected(const struct lighting_led_state *expected)
{
	struct lighting_led_state actual;

	zassert_ok(lighting_get_led(0, &actual));
	zassert_equal(actual.rgb.r, expected->rgb.r);
	zassert_equal(actual.rgb.g, expected->rgb.g);
	zassert_equal(actual.rgb.b, expected->rgb.b);
	zassert_equal(actual.intensity, expected->intensity);
}

static void assert_saved(const struct lighting_led_state *expected)
{
	struct lighting_led_state decoded[PIXEL_COUNT];

	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	zassert_ok(lighting_settings_decode(fake_storage.data, fake_storage.length,
					    decoded, PIXEL_COUNT));
	zassert_equal(fake_storage.data[0], 0xa2);
	zassert_equal(fake_storage.data[2], 2);
	zassert_equal(decoded[0].rgb.r, expected->rgb.r);
	zassert_equal(decoded[0].rgb.g, expected->rgb.g);
	zassert_equal(decoded[0].rgb.b, expected->rgb.b);
	zassert_equal(decoded[0].intensity, expected->intensity);
	k_mutex_unlock(&fake_storage_mutex);
}

static void *setup(void)
{
	static const uint8_t legacy[] = {
		0xa3, 0, 1, 1, 0xf4, 2, 0x81, 0x84, 0, 0x18, 255, 0, 0x18, 100,
	};
	/* First entry is valid; a malformed second entry must reject the whole snapshot. */
	static const uint8_t corrupt[] = {
		0xa2, 0, 2, 2, 0x82, 0x84, 0, 0x18, 255, 0, 0x18, 100, 0x84, 0,
	};
	struct lighting_led_state saved = {.rgb = {.g = 255}, .intensity = 100};
	bool saved_boot = TEST_BOOT_MODE == 1 || TEST_BOOT_MODE == 2 ||
			  TEST_BOOT_MODE == 6 || TEST_BOOT_MODE == 7;
	uint8_t original[32];
	size_t original_length;
	bool on;

	if (TEST_BOOT_MODE == 2 || TEST_BOOT_MODE == 6) {
		memcpy(fake_storage.data, legacy, sizeof(legacy));
		fake_storage.data[4] = TEST_BOOT_MODE == 2 ? 0xf4 : 0xf5;
		fake_storage.length = sizeof(legacy);
	} else if (TEST_BOOT_MODE == 3) {
		memcpy(fake_storage.data, corrupt, sizeof(corrupt));
		fake_storage.length = sizeof(corrupt);
	} else if (TEST_BOOT_MODE == 1 || TEST_BOOT_MODE == 4 || TEST_BOOT_MODE == 7) {
		if (TEST_BOOT_MODE == 7) {
			saved.intensity = 0;
		}
		zassert_ok(lighting_settings_encode(fake_storage.data, sizeof(fake_storage.data),
						   &fake_storage.length, &saved, 1));
		if (TEST_BOOT_MODE == 4) {
			fake_storage.data[2] = 3;
		}
	} else if (TEST_BOOT_MODE == 5) {
		fake_storage.init_error = -EIO;
	} else if (TEST_BOOT_MODE == 8) {
		fake_storage.restore_error = -EIO;
	} else if (TEST_BOOT_MODE == 9) {
		fake_storage.length = sizeof(fake_storage.data) + 1;
	}
	original_length = fake_storage.length;
	memcpy(original, fake_storage.data, sizeof(original));
	zassert_ok(lighting_init());
	zassert_equal(frames, 1);
	zassert_equal(first_frame[0].r, saved_boot ? 0 : 56);
	zassert_equal(first_frame[0].g, saved_boot ? (saved.intensity == 0 ? 0 : 255) : 56);
	zassert_equal(first_frame[0].b, saved_boot ? 0 : 56);
	zassert_ok(lighting_get_on(&on));
	zassert_true(on, "Boot must ignore legacy power-off selections");
	if (saved_boot) {
		assert_selected(&saved);
	}
	for (size_t i = 1; i < PIXEL_COUNT; i++) {
		zassert_equal(first_frame[i].r, 56);
		zassert_equal(first_frame[i].g, 56);
		zassert_equal(first_frame[i].b, 56);
	}
	/* Restoring or falling back must not rewrite or migrate the saved record. */
	k_msleep(120);
	zassert_equal(count_io(false), 0);
	zassert_equal(fake_storage.length, original_length);
	zassert_mem_equal(fake_storage.data, original, sizeof(original));
	zassert_ok(lighting_init());
	zassert_equal(frames, 1);
	fake_storage.init_error = 0;
	fake_storage.restore_error = 0;
	return NULL;
}

static void before(void *fixture)
{
	const struct lighting_led_state white = LIGHTING_DEFAULT_STATE;
	unsigned int target = count_io(true) + 1;

	ARG_UNUSED(fixture);
	fake_storage.store_error = 0;
	/* Force a real change, then wait for the ordinary worker to establish a baseline. */
	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_all(&white));
	zassert_ok(lighting_set_on(true));
	wait_io(target, true);
	assert_saved(&white);
	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	fake_storage.attempts = 0;
	fake_storage.writes = 0;
	fake_storage.last_attempt_ms = 0;
	k_mutex_unlock(&fake_storage_mutex);
}

ZTEST(persistence, test_quiet_period_and_latest_state)
{
	zassert_ok(lighting_set_all(&red));
	k_msleep(30);
	zassert_ok(lighting_set_all(&blue));
	k_msleep(40);
	zassert_equal(count_io(false), 0);
	wait_io(1, true);
	assert_saved(&blue);
}

ZTEST(persistence, test_continuous_changes_wait_until_quiet)
{
	struct lighting_led_state state = red;
	int64_t start = k_uptime_get();

	while (k_uptime_get() - start < 260) {
		state.intensity = state.intensity == 100 ? 99 : 100;
		zassert_ok(lighting_set_led(0, &state));
		k_msleep(20);
	}
	zassert_equal(count_io(false), 0);
	wait_io(1, true);
	assert_saved(&state);
}

ZTEST(persistence, test_unchanged_setters_do_not_save_or_extend_debounce)
{
	const struct lighting_led_state white = LIGHTING_DEFAULT_STATE;
	int64_t start;

	zassert_ok(lighting_set_all(&white));
	k_msleep(120);
	zassert_equal(count_io(false), 0);
	start = k_uptime_get();
	zassert_ok(lighting_set_all(&red));
	k_msleep(40);
	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_led(0, &red));
	zassert_ok(lighting_set_all_color(&red.rgb));
	zassert_ok(lighting_set_all_intensity(100));
	wait_io(1, true);
	zassert_true(last_attempt_ms() - start < 90);
	assert_saved(&red);
}

ZTEST(persistence, test_power_only_changes_do_not_save)
{
	bool on;

	zassert_ok(lighting_set_on(false));
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_set_on(false));
	k_msleep(120);
	zassert_equal(count_io(false), 0);
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
}

ZTEST(persistence, test_power_changes_do_not_extend_debounce)
{
	int64_t start = k_uptime_get();
	bool on;

	zassert_ok(lighting_set_all(&red));
	k_msleep(40);
	zassert_ok(lighting_set_on(false));
	wait_io(1, true);
	zassert_true(last_attempt_ms() - start < 90);
	assert_saved(&red);
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
}

ZTEST(persistence, test_failed_save_retries_without_another_edit)
{
	int64_t first_attempt;

	fake_storage.store_error = -EIO;
	zassert_ok(lighting_set_all(&red));
	wait_io(1, false);
	first_attempt = last_attempt_ms();
	fake_storage.store_error = 0;
	wait_io(1, true);
	zassert_true(last_attempt_ms() - first_attempt >= CONFIG_LIGHTING_SAVE_RETRY_MS);
	assert_saved(&red);
}

ZTEST(persistence, test_new_edit_restarts_debounce_during_retry)
{
	fake_storage.store_error = -EIO;
	zassert_ok(lighting_set_all(&red));
	wait_io(1, false);
	/* Allow failure handling to arm its retry before a newer selection arrives. */
	k_msleep(10);
	fake_storage.store_error = 0;
	zassert_ok(lighting_set_all(&blue));
	k_msleep(40);
	zassert_equal(count_io(false), 1);
	wait_io(1, true);
	assert_saved(&blue);
}

ZTEST(persistence, test_setter_during_write_is_saved_later)
{
	fake_storage.block_store = true;
	zassert_ok(lighting_set_all(&red));
	zassert_ok(k_sem_take(&fake_io_started, K_SECONDS(1)));
	/* Would deadlock if flash I/O held the lighting mutex. */
	zassert_ok(lighting_set_all(&blue));
	k_sem_give(&fake_io_release);
	wait_io(2, true);
	assert_saved(&blue);
}

ZTEST(persistence, test_queued_callback_during_write_obeys_latest_deadline)
{
	const struct lighting_led_state green = {.rgb = {.g = 255}, .intensity = 75};

	fake_storage.block_store = true;
	zassert_ok(lighting_set_all(&red));
	zassert_ok(k_sem_take(&fake_io_started, K_SECONDS(1)));
	zassert_ok(lighting_set_all(&blue));
	k_msleep(80); /* Queue another callback while the first write is blocked. */
	zassert_ok(lighting_set_all(&green));
	k_sem_give(&fake_io_release);
	wait_io(1, true);
	k_msleep(40);
	zassert_equal(count_io(false), 1);
	wait_io(2, true);
	assert_saved(&green);
}

ZTEST(persistence, test_queued_callback_does_not_bypass_failure_retry)
{
	fake_storage.store_error = -EIO;
	fake_storage.block_store = true;
	zassert_ok(lighting_set_all(&red));
	zassert_ok(k_sem_take(&fake_io_started, K_SECONDS(1)));
	zassert_ok(lighting_set_all(&blue));
	k_msleep(80);
	k_sem_give(&fake_io_release);
	k_msleep(40);
	zassert_equal(count_io(false), 1);
	fake_storage.store_error = 0;
	wait_io(1, true);
	assert_saved(&blue);
}

ZTEST(persistence, test_already_queued_callback_obeys_latest_deadline)
{
	int err;

	/* Timer IRQs still run, but the worker cannot consume the queued callback. */
	k_sched_lock();
	err = lighting_set_all(&red);
	k_busy_wait((CONFIG_LIGHTING_SAVE_DELAY_MS + 10) * 1000);
	err |= lighting_set_all(&blue);
	k_sched_unlock();
	zassert_ok(err);
	k_msleep(40);
	zassert_equal(count_io(false), 0);
	wait_io(1, true);
	assert_saved(&blue);
}

ZTEST(persistence, test_repeated_init_preserves_pending_state_and_power)
{
	unsigned int old_frames = frames;
	bool on;

	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_on(false));
	zassert_ok(lighting_init());
	zassert_equal(frames, old_frames);
	assert_selected(&red);
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
	wait_io(1, true);
	assert_saved(&red);
}

ZTEST(persistence, test_codec_writes_v2_without_power)
{
	static const uint8_t expected[] = {
		0xa2, 0, 2, 2, 0x81, 0x84, 0x18, 255, 0, 0, 0x18, 100,
	};
	uint8_t buffer[32];
	size_t length;

	zassert_ok(lighting_settings_encode(buffer, sizeof(buffer), &length, &red, 1));
	zassert_equal(length, sizeof(expected));
	zassert_mem_equal(buffer, expected, length);
}

ZTEST(persistence, test_codec_reads_legacy_power_values)
{
	uint8_t legacy[] = {0xa3, 0, 1, 1, 0xf4, 2, 0x81, 0x84, 1, 2, 3, 4};
	struct lighting_led_state output[PIXEL_COUNT];

	for (unsigned int on = 0; on < 2; on++) {
		legacy[4] = on ? 0xf5 : 0xf4;
		zassert_ok(lighting_settings_decode(legacy, sizeof(legacy), output, PIXEL_COUNT));
		zassert_equal(output[0].rgb.r, 1);
		zassert_equal(output[0].rgb.g, 2);
		zassert_equal(output[0].rgb.b, 3);
		zassert_equal(output[0].intensity, 4);
	}
}

ZTEST(persistence, test_codec_rejects_malformed_and_unsupported_records)
{
	static const struct {
		uint8_t data[24];
		size_t length;
		int error;
	} invalid[] = {
		{{0xa2, 0, 3, 2, 0x81, 0x84, 1, 2, 3, 4}, 10, -ENOTSUP},
		{{0xa2, 0, 2, 2, 0x81, 0x84, 1, 2, 3, 0x18, 101}, 11, -EINVAL},
		{{0xa2, 0, 2, 2, 0x81, 0x84, 0x19, 1, 0, 2, 3, 4}, 12, -EINVAL},
		{{0xa1, 2, 0x81, 0x84, 1, 2, 3, 4}, 8, -EINVAL},
		{{0xa1, 0, 2}, 3, -EINVAL},
		{{0xa2, 0, 2, 2, 0x80}, 5, -EINVAL},
		{{0xa3, 0, 2, 0, 2, 2, 0x81, 0x84, 1, 2, 3, 4}, 12, -EINVAL},
		{{0xa3, 0, 2, 2, 0x81, 0x84, 1, 2, 3, 4,
			2, 0x81, 0x84, 1, 2, 3, 4}, 17, -EINVAL},
		{{0xa2, 0, 2, 2, 0x81, 0x83, 1, 2, 3}, 9, -EINVAL},
		{{0xa2, 0, 2, 2, 0x81, 0x84, 1, 2, 3}, 9, -EINVAL},
		{{0xa2, 0, 1, 2, 0x81, 0x84, 1, 2, 3, 4}, 10, -EINVAL},
		{{0xa3, 0, 1, 1, 0, 2, 0x81, 0x84, 1, 2, 3, 4}, 12, -EINVAL},
	};
	struct lighting_led_state output[PIXEL_COUNT];

	for (size_t i = 0; i < ARRAY_SIZE(invalid); i++) {
		zassert_equal(lighting_settings_decode(invalid[i].data, invalid[i].length,
						       output, PIXEL_COUNT), invalid[i].error,
			      "Invalid fixture %zu accepted", i);
	}
}

ZTEST(persistence, test_codec_resize_unknown_fields_and_bounds)
{
	uint8_t buffer[128];
	size_t length;
	struct lighting_led_state input[PIXEL_COUNT + 1];
	struct lighting_led_state output[PIXEL_COUNT];

	for (size_t i = 0; i < ARRAY_SIZE(input); i++) {
		input[i] = red;
	}
	zassert_ok(lighting_settings_encode(buffer, sizeof(buffer), &length, input, 1));
	/* Add an unknown nested field; readers must skip its entire value. */
	buffer[0] = 0xa3;
	buffer[length++] = 3;
	buffer[length++] = 0x81;
	buffer[length++] = 0x81;
	buffer[length++] = 1;
	zassert_ok(lighting_settings_decode(buffer, length, output, PIXEL_COUNT));
	zassert_equal(output[0].rgb.r, 255);
	zassert_equal(output[1].rgb.g, 255);
	zassert_equal(output[1].intensity, 50);
	zassert_ok(lighting_settings_encode(buffer, sizeof(buffer), &length, input,
					   ARRAY_SIZE(input)));
	zassert_ok(lighting_settings_decode(buffer, length, output, PIXEL_COUNT));
	zassert_equal(output[PIXEL_COUNT - 1].intensity, 100);
	zassert_equal(lighting_settings_decode(buffer, length - 1, output, PIXEL_COUNT), -EINVAL);
	buffer[length] = 0;
	zassert_equal(lighting_settings_decode(buffer, length + 1, output, PIXEL_COUNT), -EINVAL);
	/* Even ignored surplus LEDs must be valid. */
	buffer[length - 1] = 101;
	zassert_equal(lighting_settings_decode(buffer, length, output, PIXEL_COUNT), -EINVAL);
	zassert_equal(lighting_settings_encode(buffer, 2, &length, input, 1), -ENOSPC);
}

ZTEST(persistence, test_partial_changes_automatically_persist)
{
	struct lighting_led_state expected = LIGHTING_DEFAULT_STATE;
	const struct led_rgb color = {.r = 12, .g = 34, .b = 56};

	zassert_ok(lighting_set_led_color(0, &color));
	expected.rgb = color;
	wait_io(1, true);
	assert_saved(&expected);
	zassert_ok(lighting_set_led_intensity(0, 0));
	expected.intensity = 0;
	wait_io(2, true);
	assert_saved(&expected);
	zassert_ok(lighting_set_all_color(&blue.rgb));
	expected.rgb = blue.rgb;
	wait_io(3, true);
	assert_saved(&expected);
	zassert_ok(lighting_set_all_intensity(100));
	expected.intensity = 100;
	wait_io(4, true);
	assert_saved(&expected);
	/* All four partial setters must suppress unchanged selections. */
	zassert_ok(lighting_set_led_color(0, &blue.rgb));
	zassert_ok(lighting_set_led_intensity(0, 100));
	zassert_ok(lighting_set_all_color(&blue.rgb));
	zassert_ok(lighting_set_all_intensity(100));
	k_msleep(120);
	zassert_equal(count_io(false), 4);
}

ZTEST(persistence, test_shell_changes_automatically_save_and_show)
{
	const struct shell *shell = shell_backend_dummy_get_ptr();
	struct lighting_led_state state;
	size_t output_length;
	const char *output;
	bool on;
	unsigned int old_frames = frames;

	zassert_ok(shell_execute_cmd(shell, "lighting set all 12 34 56 50"));
	zassert_true(frames > old_frames);
	zassert_ok(shell_execute_cmd(shell, "lighting set 0 255 0 0 100"));
	zassert_ok(lighting_get_led(0, &state));
	zassert_equal(state.rgb.r, 255);
	zassert_ok(shell_execute_cmd(shell, "lighting color 0 0 255 128"));
	zassert_ok(lighting_get_led(0, &state));
	zassert_equal(state.rgb.r, 0);
	zassert_equal(state.rgb.g, 255);
	zassert_equal(state.rgb.b, 128);
	zassert_equal(state.intensity, 100);
	zassert_ok(shell_execute_cmd(shell, "lighting intensity 0 0"));
	zassert_ok(lighting_get_led(0, &state));
	zassert_equal(state.rgb.g, 255);
	zassert_equal(state.rgb.b, 128);
	zassert_equal(state.intensity, 0);
	zassert_ok(shell_execute_cmd(shell, "lighting color all 255 0 0"));
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &state));
		zassert_equal(state.rgb.r, 255);
		zassert_equal(state.rgb.g, 0);
		zassert_equal(state.rgb.b, 0);
		zassert_equal(state.intensity, i == 0 ? 0 : 50);
	}
	zassert_ok(shell_execute_cmd(shell, "lighting intensity all 100"));
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &state));
		zassert_equal(state.rgb.r, 255);
		zassert_equal(state.intensity, 100);
	}
	zassert_ok(shell_execute_cmd(shell, "lighting set 0 0 0 255 40"));
	zassert_ok(shell_execute_cmd(shell, "lighting power off"));
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
	zassert_equal(frames, old_frames + 8);
	wait_io(1, true);
	assert_saved(&blue);
	shell_backend_dummy_clear_output(shell);
	zassert_ok(shell_execute_cmd(shell, "lighting show"));
	output = shell_backend_dummy_get_output(shell, &output_length);
	zassert_not_null(strstr(output, "Power off"));
	zassert_not_null(strstr(output, "0: RGB 0 0 255; intensity 40%"));
}

ZTEST(persistence, test_shell_rejects_invalid_commands_atomically)
{
	const struct shell *shell = shell_backend_dummy_get_ptr();
	const struct lighting_led_state white = LIGHTING_DEFAULT_STATE;
	static const char *const invalid[] = {
		"lighting set all 256 0 0 50", "lighting set all 1 2 3 101",
		"lighting set -1 1 2 3 4", "lighting set 999 1 2 3 4",
		"lighting set all 1 2 3 junk", "lighting set all 1 2 3",
		"lighting set all 999999999999999999999999999 1 2 3",
		"lighting set all 1 2 3 4 extra",
		"lighting color all 256 0 0", "lighting color all 0 0 -1",
		"lighting color all 0 0 junk", "lighting color all 1 2",
		"lighting color all 1 2 3 extra", "lighting color -1 1 2 3",
		"lighting color 999 1 2 3", "lighting color All 1 2 3",
		"lighting color all 1 2 999999999999999999999999999",
		"lighting intensity all 101", "lighting intensity all -1",
		"lighting intensity all junk", "lighting intensity all",
		"lighting intensity all 50 extra", "lighting intensity -1 50",
		"lighting intensity 999 50", "lighting intensity All 50",
		"lighting intensity all 999999999999999999999999999",
		"lighting power yes", "lighting power on extra",
		"lighting save", "lighting restore",
	};

	for (size_t i = 0; i < ARRAY_SIZE(invalid); i++) {
		zassert_not_equal(shell_execute_cmd(shell, invalid[i]), 0);
		assert_selected(&white);
	}
	k_msleep(100);
	zassert_equal(count_io(false), 0);
}

ZTEST_SUITE(persistence, NULL, setup, before, NULL, NULL);
