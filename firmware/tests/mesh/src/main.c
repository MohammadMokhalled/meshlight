// SPDX-License-Identifier: MIT
#include "lighting/lighting.h"
#include "lighting/lighting_settings.h"
#include "mesh/control.h"
#include "mesh/transaction.h"
#include "device_control/button_hold.h"
#include "fake_storage.h"
#include <errno.h>
#include <string.h>
#include <zephyr/device.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/ztest.h>

#define COUNT DT_PROP(DT_ALIAS(led_strip), chain_length)
static struct led_rgb frame[COUNT];
static unsigned int publications;

static int update_rgb(const struct device *dev, struct led_rgb *pixels, size_t count)
{
	ARG_UNUSED(dev);
	zassert_equal(count, COUNT);
	memcpy(frame, pixels, sizeof(frame));
	return 0;
}
static size_t strip_length(const struct device *dev) { ARG_UNUSED(dev); return COUNT; }
static DEVICE_API(led_strip, strip_api) = {.update_rgb = update_rgb, .length = strip_length};
DEVICE_DT_DEFINE(DT_ALIAS(led_strip), NULL, NULL, NULL, NULL, POST_KERNEL,
	CONFIG_LED_STRIP_INIT_PRIORITY, &strip_api);

static void publish(void) { publications++; }
static struct lighting_status snapshot(uint16_t target)
{
	struct lighting_status status;

	zassert_ok(lighting_get_status(target, &status));
	return status;
}
static void *setup(void) { zassert_ok(lighting_init()); return NULL; }
static void before(void *unused)
{
	ARG_UNUSED(unused);
	mesh_control_stop();
	const struct lighting_update reset = {.fields = 7, .rgb = {.r = 255, .g = 255, .b = 255},
		.intensity = 50, .on = true};

	zassert_ok(lighting_update(LIGHTING_TARGET_ALL, &reset));
	mesh_control_init(publish);
	publications = 0;
}

ZTEST(mesh, test_power_is_individual_and_preserves_color)
{
	const struct lighting_update off = {.fields = LIGHTING_UPDATE_POWER, .on = false};

	zassert_ok(lighting_update(0, &off));
	zassert_false(snapshot(0).on);
	zassert_ok(lighting_apply());
	zassert_equal(frame[0].r, 0);
	zassert_equal(snapshot(0).rgb.r, 255);
	if (COUNT > 1) {
		zassert_true(snapshot(1).on);
		zassert_true(frame[1].r > 0);
		zassert_true(snapshot(LIGHTING_TARGET_ALL).mixed & LIGHTING_UPDATE_POWER);
	}
	zassert_ok(lighting_set_on(true));
	zassert_true(snapshot(0).on);
}

ZTEST(mesh, test_atomic_validation_and_mixed_status)
{
	struct lighting_update update = {.fields = 7, .rgb = {.r = 7}, .intensity = 101};

	zassert_equal(lighting_update(0, &update), -ERANGE);
	zassert_equal(snapshot(0).rgb.r, 255);
	update.intensity = 0;
	zassert_ok(lighting_update(0, &update));
	struct lighting_status all = snapshot(LIGHTING_TARGET_ALL);

	zassert_equal(all.rgb.r, 7);
	zassert_equal(all.intensity, (50 * (COUNT - 1) + COUNT / 2) / COUNT);
	zassert_equal(all.mixed, COUNT > 1 ? 7 : 0);
	zassert_equal(lighting_update(COUNT, &update), -ERANGE);
	update.fields = 0x40;
	zassert_equal(lighting_update(0, &update), -EINVAL);
}

ZTEST(mesh, test_wire_decode_exact_lengths_and_flags)
{
	uint8_t packet[] = {0xff, 0xff, 1, 0x0f, 255, 12, 34, 100};
	struct lighting_update update = {0};
	uint16_t target;
	uint8_t tid;

	zassert_ok(mesh_protocol_decode(packet, sizeof(packet), &target, &tid, &update));
	zassert_equal(target, LIGHTING_TARGET_ALL);
	zassert_equal(update.fields, 7);
	zassert_true(update.on);
	zassert_equal(update.rgb.b, 34);
	for (size_t i = 0; i < sizeof(packet); i++) {
		zassert_equal(mesh_protocol_decode(packet, i, &target, &tid, &update), -EINVAL);
	}
	packet[7] = 101;
	zassert_equal(mesh_protocol_decode(packet, 8, &target, &tid, &update), -EINVAL);
	packet[3] = 8; /* Power value without a power property. */
	zassert_equal(mesh_protocol_decode(packet, 4, &target, &tid, &update), -EINVAL);
	packet[3] = 0x14;
	zassert_equal(mesh_protocol_decode(packet, 4, &target, &tid, &update), -EINVAL);
}

ZTEST(mesh, test_capability_and_error_status_encoding)
{
	uint8_t caps[6], status[8];
	struct lighting_status current = snapshot(0);

	mesh_protocol_capabilities(caps, lighting_get_led_count());
	zassert_equal(caps[0], 1);
	zassert_equal(sys_get_le16(caps + 1), COUNT);
	zassert_equal(sys_get_le16(caps + 3), 0x3f);
	mesh_protocol_status(status, COUNT, -ERANGE, &current);
	zassert_equal(status[2], MESHLIGHT_BAD_TARGET);
	zassert_equal(status[3], 0);
	mesh_protocol_status(status, 0, 0, &current);
	zassert_equal(status[3], 1);
	zassert_equal(status[7], 50);
}

ZTEST(mesh, test_vendor_preserves_properties_and_deduplicates)
{
	uint8_t packet[] = {0, 0, 1, 1, 255, 0, 0};
	uint16_t target;

	zassert_ok(mesh_control_vendor(1, 2, packet, sizeof(packet), &target));
	zassert_equal(snapshot(0).rgb.g, 0);
	zassert_equal(snapshot(0).intensity, 50);
	packet[4] = 0;
	zassert_ok(mesh_control_vendor(1, 2, packet, sizeof(packet), &target));
	zassert_equal(snapshot(0).rgb.r, 255);
	zassert_ok(mesh_control_vendor(3, 2, packet, sizeof(packet), &target));
	zassert_equal(snapshot(0).rgb.r, 0);
	sys_put_le16(COUNT, packet);
	zassert_equal(mesh_control_vendor(1, 2, packet, sizeof(packet), &target), -ERANGE);
}

ZTEST(mesh, test_transaction_expiry_and_destination_isolation)
{
	struct mesh_transactions cache = {0};
	bool duplicate;

	(void)mesh_transaction_get(&cache, 1, 2, 0, 7, 1, 0, &duplicate);
	zassert_false(duplicate);
	(void)mesh_transaction_get(&cache, 1, 2, 0, 7, 1, 5999, &duplicate);
	zassert_true(duplicate);
	(void)mesh_transaction_get(&cache, 1, 2, 0, 7, 1, 6000, &duplicate);
	zassert_false(duplicate);
	(void)mesh_transaction_get(&cache, 1, 3, 0, 7, 1, 6001, &duplicate);
	zassert_false(duplicate);
}

static int standard(enum mesh_control_op op, uint8_t *request, size_t length)
{
	uint8_t response[5];
	size_t response_length;

	return mesh_control_standard(op, 1, 2, request, length, response, &response_length);
}

ZTEST(mesh, test_standard_intensity_extremes_and_power_preservation)
{
	uint8_t minimum[] = {0, 0x80, 1};
	uint8_t maximum[] = {0xff, 0x7f, 2};

	zassert_ok(standard(MESH_LEVEL_SET, minimum, sizeof(minimum)));
	zassert_equal(snapshot(0).intensity, 0);
	zassert_true(snapshot(0).on);
	zassert_ok(standard(MESH_LEVEL_SET, maximum, sizeof(maximum)));
	zassert_equal(snapshot(0).intensity, 100);
	zassert_equal(snapshot(0).rgb.r, 255);
}

ZTEST(mesh, test_standard_delta_uses_transaction_baseline)
{
	uint8_t delta[5] = {0};

	delta[4] = 1;
	sys_put_le32(6553, delta);
	zassert_ok(standard(MESH_LEVEL_DELTA, delta, sizeof(delta)));
	zassert_equal(snapshot(0).intensity, 60);
	zassert_ok(standard(MESH_LEVEL_DELTA, delta, sizeof(delta)));
	zassert_equal(snapshot(0).intensity, 60);
	sys_put_le32(13107, delta);
	zassert_ok(standard(MESH_LEVEL_DELTA, delta, sizeof(delta)));
	zassert_equal(snapshot(0).intensity, 70);
}

ZTEST(mesh, test_delay_and_transition_publish_completion)
{
	uint8_t request[] = {0xff, 0x7f, 1, 1, 20}; /* 100 ms delay + 100 ms transition. */
	uint8_t response[5];
	size_t length;

	zassert_ok(standard(MESH_LEVEL_SET, request, sizeof(request)));
	zassert_equal(snapshot(0).intensity, 50);
	zassert_ok(mesh_control_status(true, response, &length));
	zassert_equal(length, 5);
	zassert_equal(sys_get_le16(response + 2), 0x7fff);
	zassert_true(response[4] > 0);
	k_sleep(K_MSEC(70));
	zassert_equal(snapshot(0).intensity, 50);
	k_sleep(K_MSEC(180));
	zassert_equal(snapshot(0).intensity, 100);
	zassert_true(publications > 0);
}

ZTEST(mesh, test_move_stop_and_omitted_interval)
{
	uint8_t move[] = {0xff, 0x7f, 1};

	zassert_ok(standard(MESH_LEVEL_MOVE, move, sizeof(move)));
	k_sleep(K_MSEC(30));
	zassert_equal(snapshot(0).intensity, 50);
	uint8_t moving[] = {0xff, 0x7f, 2, 2, 0};

	zassert_ok(standard(MESH_LEVEL_MOVE, moving, sizeof(moving)));
	k_sleep(K_MSEC(80));
	zassert_true(snapshot(0).intensity > 50);
	uint8_t stop[] = {0, 0, 3};

	zassert_ok(standard(MESH_LEVEL_MOVE, stop, sizeof(stop)));
	uint8_t intensity = snapshot(0).intensity;

	k_sleep(K_MSEC(100));
	zassert_equal(snapshot(0).intensity, intensity);
}

ZTEST(mesh, test_malformed_standard_messages_leave_state_unchanged)
{
	uint8_t invalid_power[] = {2, 1};
	uint8_t invalid_transition[] = {0xff, 0x7f, 1, 0x3f, 0};

	zassert_equal(standard(MESH_ONOFF_SET, invalid_power, 2), -EINVAL);
	zassert_equal(standard(MESH_LEVEL_SET, invalid_transition, 5), -EINVAL);
	zassert_equal(standard(MESH_LEVEL_SET, invalid_transition, 4), -EINVAL);
	zassert_equal(snapshot(0).intensity, 50);
	zassert_true(snapshot(0).on);
}

ZTEST(mesh, test_vendor_interrupts_transition_and_final_state_is_saved)
{
	uint8_t request[] = {0xff, 0x7f, 1, 5, 0};
	uint8_t packet[] = {0, 0, 1, 2, 30};
	uint16_t target;

	zassert_ok(standard(MESH_LEVEL_SET, request, 5));
	k_sleep(K_MSEC(70));
	zassert_ok(mesh_control_vendor(1, 2, packet, sizeof(packet), &target));
	k_sleep(K_MSEC(180));
	zassert_equal(snapshot(0).intensity, 30);
	struct lighting_led_state saved[COUNT];

	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	zassert_ok(lighting_settings_decode(fake_storage.data, fake_storage.length, saved, COUNT));
	k_mutex_unlock(&fake_storage_mutex);
	zassert_equal(saved[0].intensity, 30);
}

ZTEST(mesh, test_intermediate_frames_never_enter_persistent_snapshot)
{
	struct lighting_update transient = {.fields = LIGHTING_UPDATE_INTENSITY |
		LIGHTING_UPDATE_TRANSIENT, .intensity = 90};
	const struct led_rgb red = {.r = 255};
	struct lighting_led_state saved[COUNT];

	zassert_ok(lighting_update(LIGHTING_TARGET_ALL, &transient));
	zassert_ok(lighting_set_all_color(&red));
	k_sleep(K_MSEC(150));
	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	zassert_ok(lighting_settings_decode(fake_storage.data, fake_storage.length, saved, COUNT));
	k_mutex_unlock(&fake_storage_mutex);
	zassert_equal(saved[0].intensity, 50);
	zassert_equal(saved[0].rgb.g, 0);
}

ZTEST(mesh, test_button_threshold_release_and_boot_hold)
{
	struct button_hold button = {0};

	zassert_false(button_hold_update(&button, true, 0));
	zassert_false(button_hold_update(&button, true, 30000));
	zassert_true(button_hold_update(&button, true, 30001));
	zassert_false(button_hold_update(&button, true, 40000));
	zassert_false(button_hold_update(&button, false, 41000));
	zassert_false(button_hold_update(&button, true, 50000));
	zassert_false(button_hold_update(&button, false, 79999));
	zassert_false(button_hold_update(&button, true, 90000));
	/* Even if the worker is delayed, a long release still requests reset. */
	zassert_true(button_hold_update(&button, false, 120001));
}

ZTEST(mesh, test_remaining_time_rounds_up)
{
	zassert_equal(mesh_control_remaining(0), 0);
	zassert_equal(mesh_control_remaining(1), 1);
	zassert_equal(mesh_control_remaining(6200), 62);
	zassert_equal(mesh_control_remaining(6201), 0x47);
	zassert_equal(mesh_control_remaining(40000000), 0x3f);
}
ZTEST_SUITE(mesh, NULL, setup, before, NULL, NULL);
