/** @file Automatic refresh tests with timestamped, synchronized fake transfers. */

#include <errno.h>
#include <string.h>

#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/util.h>
#include <zephyr/ztest.h>

#include "lighting.h"

#define STRIP_NODE DT_ALIAS(led_strip)
#define PIXEL_COUNT DT_PROP(STRIP_NODE, chain_length)
#define PERIOD_MS CONFIG_LIGHTING_REFRESH_INTERVAL_MS
#define HISTORY_SIZE 128U
/* Bounds tolerate tick rounding and scheduling, but reject accumulated drift. */
#define TIMING_TOLERANCE_MS MAX(5, PERIOD_MS / 5)
#define FRAME_TIMEOUT_MS MAX(1000, 8 * PERIOD_MS)

/** Controls are copied at transfer entry, so a test can safely change them. */
struct fake_settings {
	int error;
	uint32_t delay_ms;
	uint32_t once_delay_ms;
	bool overwrite;
	bool check_uniform;
};

/** Completed frames retain the input, result, caller, and transfer timing. */
struct frame_record {
	struct led_rgb pixels[PIXEL_COUNT];
	k_tid_t caller;
	int64_t start_ms;
	int64_t finish_ms;
	uint32_t number;
	uint32_t delay_ms;
	int error;
};

/* This lock protects only the fake; never hold it while calling lighting APIs. */
K_MUTEX_DEFINE(fake_mutex);
K_SEM_DEFINE(frame_ready, 0, 1);
static atomic_t active_transfers;
static struct {
	struct fake_settings settings;
	struct frame_record history[HISTORY_SIZE];
	uint32_t started;
	uint32_t completed;
	bool overlap;
	bool wrong_length;
	bool mutated_input;
	bool nonuniform;
} fake;
static k_tid_t refresh_tid;

/** Record transfers without holding the fake lock during injected delays. */
static int fake_update(const struct device *dev, struct led_rgb *pixels, size_t count)
{
	struct frame_record record = {.caller = k_current_get(), .start_ms = k_uptime_get()};
	struct fake_settings settings;
	bool overlap = atomic_inc(&active_transfers) != 0;

	ARG_UNUSED(dev);
	k_mutex_lock(&fake_mutex, K_FOREVER);
	fake.overlap |= overlap;
	settings = fake.settings;
	record.number = ++fake.started;
	record.delay_ms = settings.once_delay_ms != 0U ? settings.once_delay_ms :
						       settings.delay_ms;
	fake.settings.once_delay_ms = 0;
	if (count != PIXEL_COUNT) {
		fake.wrong_length = true;
	}
	k_mutex_unlock(&fake_mutex);

	record.error = count == PIXEL_COUNT ? settings.error : -EINVAL;
	if (count == PIXEL_COUNT) {
		memcpy(record.pixels, pixels, sizeof(record.pixels));
	}
	k_yield();
	if (record.delay_ms != 0U) {
		k_msleep(record.delay_ms);
	}

	k_mutex_lock(&fake_mutex, K_FOREVER);
	if (count == PIXEL_COUNT) {
		/* A second unlocked renderer would change input while this call sleeps. */
		fake.mutated_input |= memcmp(record.pixels, pixels, sizeof(record.pixels)) != 0;
		if (settings.check_uniform) {
			for (size_t i = 1; i < count; i++) {
				fake.nonuniform |= pixels[i].r != pixels[0].r ||
						   pixels[i].g != pixels[0].g ||
						   pixels[i].b != pixels[0].b;
			}
		}
		if (settings.overwrite) {
			memset(pixels, 0xa5, count * sizeof(*pixels));
		}
	}
	record.finish_ms = k_uptime_get();
	fake.history[record.number % HISTORY_SIZE] = record;
	fake.completed++;
	k_mutex_unlock(&fake_mutex);
	atomic_dec(&active_transfers);
	k_sem_give(&frame_ready);
	return record.error;
}

/** Use the same configured length as production's requested and output arrays. */
static size_t fake_length(const struct device *dev)
{
	ARG_UNUSED(dev);
	return PIXEL_COUNT;
}

static DEVICE_API(led_strip, fake_api) = {
	.update_rgb = fake_update,
	.length = fake_length,
};

DEVICE_DT_DEFINE(STRIP_NODE, NULL, NULL, NULL, NULL, POST_KERNEL,
		 CONFIG_LED_STRIP_INIT_PRIORITY, &fake_api);

/** Replace injection controls without racing a worker or manual caller. */
static void configure_fake(const struct fake_settings *settings)
{
	k_mutex_lock(&fake_mutex, K_FOREVER);
	fake.settings = *settings;
	k_mutex_unlock(&fake_mutex);
}

/** Return a synchronized completion sequence, not a racy global counter. */
static uint32_t completed_count(void)
{
	uint32_t count;

	k_mutex_lock(&fake_mutex, K_FOREVER);
	count = fake.completed;
	k_mutex_unlock(&fake_mutex);
	return count;
}

/** Wait for the next numbered record with a bounded absolute timeout. */
static struct frame_record wait_frame(uint32_t after)
{
	int64_t deadline_ms = k_uptime_get() + FRAME_TIMEOUT_MS;
	struct frame_record record;

	while (true) {
		k_mutex_lock(&fake_mutex, K_FOREVER);
		if (fake.completed > after) {
			record = fake.history[(after + 1U) % HISTORY_SIZE];
			k_mutex_unlock(&fake_mutex);
			zassert_equal(record.number, after + 1U, "Frame history was overwritten");
			return record;
		}
		k_mutex_unlock(&fake_mutex);
		zassert_ok(k_sem_take(&frame_ready, K_TIMEOUT_ABS_MS(deadline_ms)),
			   "Timed out waiting for an automatic refresh");
	}
}

/** Read the first future frame after all earlier API calls returned. */
static struct frame_record next_frame(void)
{
	return wait_frame(completed_count());
}

/** Compare channels only; optional driver scratch is not part of RGB selection. */
static void assert_rgb(const struct led_rgb *rgb, uint8_t red, uint8_t green, uint8_t blue)
{
	zassert_equal(rgb->r, red);
	zassert_equal(rgb->g, green);
	zassert_equal(rgb->b, blue);
}

/** Validate every pixel rather than just the first one. */
static void assert_uniform(const struct frame_record *record,
			   uint8_t red, uint8_t green, uint8_t blue)
{
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		assert_rgb(&record->pixels[i], red, green, blue);
	}
}

/** Fetch diagnostics under the fake lock, then assert without holding it. */
static void assert_serialized(void)
{
	bool okay;

	k_mutex_lock(&fake_mutex, K_FOREVER);
	okay = !fake.overlap && !fake.wrong_length && !fake.mutated_input && !fake.nonuniform;
	k_mutex_unlock(&fake_mutex);
	zassert_true(okay, "Overlapping transfers, modified input, or a torn frame");
}

/** Initialization errors must never start refresh; success starts exactly once. */
static void *refresh_setup(void)
{
	const struct device *dev = DEVICE_DT_GET(STRIP_NODE);
	struct fake_settings settings = {.error = -EIO, .overwrite = true};
	struct lighting_led_state state;
	struct frame_record record;
	int64_t initialized_ms;
	bool on = true;

	zassert_equal(lighting_apply(), -ENODEV);
	dev->state->init_res = EIO;
	zassert_equal(lighting_init(), -ENODEV);
	k_msleep(2 * PERIOD_MS);
	zassert_equal(completed_count(), 0);
	dev->state->init_res = 0;

	configure_fake(&settings);
	zassert_equal(lighting_init(), -EIO);
	k_msleep(2 * PERIOD_MS);
	zassert_equal(completed_count(), 1);
	zassert_equal(lighting_get_on(&on), -ENODEV);
	zassert_true(on);

	settings.error = 0;
	configure_fake(&settings);
	zassert_ok(lighting_init());
	initialized_ms = k_uptime_get();
	zassert_equal(completed_count(), 2);
	record = wait_frame(1);
	assert_uniform(&record, 0, 0, 0);
	zassert_ok(lighting_get_on(&on));
	zassert_false(on);
	for (size_t i = 0; i < PIXEL_COUNT; i++) {
		zassert_ok(lighting_get_led(i, &state));
		assert_rgb(&state.rgb, 0, 0, 0);
		zassert_equal(state.intensity, 100);
	}

	/* Initial clear is synchronous; the worker must wait its first interval. */
	k_msleep(PERIOD_MS / 2);
	zassert_equal(completed_count(), 2);
	record = wait_frame(2);
	zassert_true(record.start_ms >= initialized_ms + PERIOD_MS - 2);
	zassert_not_equal(record.caller, k_current_get());
	assert_uniform(&record, 0, 0, 0);
	refresh_tid = record.caller;
	return NULL;
}

/** Restore a known state through automatic refresh without stopping the worker. */
static void refresh_before(void *fixture)
{
	const struct fake_settings settings = {0};
	const struct lighting_led_state black = {.intensity = 100};
	struct frame_record record;

	ARG_UNUSED(fixture);
	configure_fake(&settings);
	zassert_ok(lighting_set_all(&black));
	zassert_ok(lighting_set_on(false));
	record = next_frame();
	assert_uniform(&record, 0, 0, 0);
	assert_serialized();
}

/** Even unchanged off-state selections are refreshed on every periodic slot. */
ZTEST(refresh, test_unchanged_off_frames)
{
	struct frame_record previous = next_frame();

	for (unsigned int i = 0; i < 4U; i++) {
		struct frame_record record = wait_frame(previous.number);

		zassert_equal(record.caller, refresh_tid);
		zassert_true(record.start_ms - previous.start_ms >= PERIOD_MS - TIMING_TOLERANCE_MS);
		zassert_true(record.start_ms - previous.start_ms <= PERIOD_MS + TIMING_TOLERANCE_MS);
		assert_uniform(&record, 0, 0, 0);
		previous = record;
	}
}

/** Per-LED selections appear automatically with their individual gamma intensity. */
ZTEST(refresh, test_individual_and_uniform_output)
{
	const struct lighting_led_state red = {.rgb = {.r = 255}, .intensity = 100};
	const struct lighting_led_state blue = {.rgb = {.b = 255}, .intensity = 50};
	struct frame_record record;

	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_on(true));
	record = next_frame();
	assert_uniform(&record, 255, 0, 0);
	zassert_ok(lighting_set_led(PIXEL_COUNT - 1U, &blue));
	record = next_frame();
	for (size_t i = 0; i + 1U < PIXEL_COUNT; i++) {
		assert_rgb(&record.pixels[i], 255, 0, 0);
	}
	assert_rgb(&record.pixels[PIXEL_COUNT - 1U], 0, 0, 56);
}

/** Automatic off/on retains changes made while dark, including zero intensity. */
ZTEST(refresh, test_power_restoration)
{
	struct lighting_led_state request = {.rgb = {.g = 255}, .intensity = 50};
	struct lighting_led_state actual;
	struct frame_record record;

	zassert_ok(lighting_set_all(&request));
	record = next_frame();
	assert_uniform(&record, 0, 0, 0);
	zassert_ok(lighting_set_on(true));
	record = next_frame();
	assert_uniform(&record, 0, 56, 0);
	zassert_ok(lighting_set_on(false));
	record = next_frame();
	assert_uniform(&record, 0, 0, 0);
	request.rgb = (struct led_rgb){.b = 255};
	zassert_ok(lighting_set_all(&request));
	record = next_frame();
	assert_uniform(&record, 0, 0, 0);
	zassert_ok(lighting_set_on(true));
	record = next_frame();
	assert_uniform(&record, 0, 0, 56);
	request.intensity = 0;
	zassert_ok(lighting_set_all(&request));
	zassert_ok(lighting_set_on(false));
	zassert_ok(lighting_set_on(true));
	record = next_frame();
	assert_uniform(&record, 0, 0, 0);
	zassert_ok(lighting_get_led(0, &actual));
	zassert_equal(actual.intensity, 0);
	assert_rgb(&actual.rgb, 0, 0, 255);
}

/** Injected transfer work must not accumulate into the frame-start interval. */
ZTEST(refresh, test_absolute_cadence_under_transfer_delay)
{
	const struct fake_settings settings = {.delay_ms = PERIOD_MS / 4};
	struct frame_record first;
	struct frame_record last;
	int64_t elapsed_ms;

	configure_fake(&settings);
	first = next_frame();
	last = first;
	for (unsigned int i = 0; i < 8U; i++) {
		last = wait_frame(last.number);
		zassert_equal(last.delay_ms, settings.delay_ms);
	}
	elapsed_ms = last.start_ms - first.start_ms;
	zassert_true(elapsed_ms >= 8 * PERIOD_MS - TIMING_TOLERANCE_MS);
	zassert_true(elapsed_ms <= 8 * PERIOD_MS + TIMING_TOLERANCE_MS,
		     "Transfer time drifted the refresh cadence: %lld ms", (long long)elapsed_ms);
}

/** A 2.5-period transfer skips two slots and waits for the next future deadline. */
ZTEST(refresh, test_overrun_skips_missed_slots)
{
	const struct fake_settings settings = {.once_delay_ms = 2 * PERIOD_MS + PERIOD_MS / 2};
	struct frame_record slow;
	struct frame_record next;
	int64_t elapsed_ms;

	configure_fake(&settings);
	slow = next_frame();
	zassert_equal(slow.delay_ms, settings.once_delay_ms);
	next = wait_frame(slow.number);
	elapsed_ms = next.start_ms - slow.start_ms;
	zassert_true(elapsed_ms >= 3 * PERIOD_MS - TIMING_TOLERANCE_MS);
	zassert_true(elapsed_ms <= 3 * PERIOD_MS + TIMING_TOLERANCE_MS);
	zassert_true(next.start_ms - slow.finish_ms >= PERIOD_MS / 2 - TIMING_TOLERANCE_MS,
		     "Late refresh caused an immediate catch-up transfer");
	zassert_equal(next.delay_ms, 0);
}

/** Failures keep raw selections and recover automatically after input corruption. */
ZTEST(refresh, test_failure_retry_and_framebuffer_recovery)
{
	struct fake_settings settings = {.error = -EIO, .overwrite = true};
	const struct lighting_led_state request = {
		.rgb = {.r = 255, .g = 128, .b = 64}, .intensity = 50,
	};
	struct lighting_led_state actual;
	struct frame_record record;

	configure_fake(&settings);
	zassert_ok(lighting_set_all(&request));
	zassert_ok(lighting_set_on(true));
	record = next_frame();
	for (unsigned int i = 0; i < 3U; i++) {
		zassert_equal(record.error, -EIO);
		assert_uniform(&record, 56, 12, 3);
		record = wait_frame(record.number);
	}
	zassert_ok(lighting_get_led(0, &actual));
	assert_rgb(&actual.rgb, 255, 128, 64);
	zassert_equal(actual.intensity, 50);
	settings.error = 0;
	configure_fake(&settings);
	record = next_frame();
	zassert_ok(record.error);
	assert_uniform(&record, 56, 12, 3);
	record = wait_frame(record.number);
	zassert_ok(record.error);
	assert_uniform(&record, 56, 12, 3);
}

/** Repeated init preserves requested values, worker identity, and its schedule. */
ZTEST(refresh, test_repeated_init_keeps_one_worker)
{
	const struct lighting_led_state red = {.rgb = {.r = 255}, .intensity = 100};
	struct frame_record previous;

	zassert_ok(lighting_set_all(&red));
	zassert_ok(lighting_set_on(true));
	previous = next_frame();
	for (unsigned int i = 0; i < 5U; i++) {
		struct frame_record record;

		k_msleep(PERIOD_MS / 3);
		zassert_ok(lighting_init());
		zassert_ok(lighting_init());
		record = wait_frame(previous.number);
		zassert_equal(record.caller, refresh_tid);
		zassert_true(record.start_ms - previous.start_ms >= PERIOD_MS - TIMING_TOLERANCE_MS);
		zassert_true(record.start_ms - previous.start_ms <= PERIOD_MS + TIMING_TOLERANCE_MS);
		assert_uniform(&record, 255, 0, 0);
		previous = record;
	}
}

/** Manual producer thread results are checked after both threads have joined. */
struct producer_request {
	struct lighting_led_state state;
	int result;
};

K_THREAD_STACK_DEFINE(producer_a_stack, 1024);
K_THREAD_STACK_DEFINE(producer_b_stack, 1024);
static struct k_thread producer_a;
static struct k_thread producer_b;

/** Compete with the automatic worker using the public synchronous flush path. */
static void producer_entry(void *request, void *unused_b, void *unused_c)
{
	struct producer_request *producer = request;

	ARG_UNUSED(unused_b);
	ARG_UNUSED(unused_c);
	for (unsigned int i = 0; i < 8U; i++) {
		producer->result = lighting_set_all(&producer->state);
		if (producer->result == 0) {
			producer->result = lighting_apply();
		}
		if (producer->result != 0) {
			break;
		}
	}
}

/** Manual apply, automatic refresh, and concurrent setters share one owner lock. */
ZTEST(refresh, test_manual_apply_and_concurrent_setters)
{
	const struct fake_settings settings = {
		.delay_ms = PERIOD_MS / 4, .overwrite = true, .check_uniform = true,
	};
	struct producer_request a = {.state = {.rgb = {.r = 255}, .intensity = 100}};
	struct producer_request b = {.state = {.rgb = {.b = 255}, .intensity = 100}};
	struct frame_record record;
	uint32_t before;

	configure_fake(&settings);
	zassert_ok(lighting_set_on(true));
	before = completed_count();
	k_thread_create(&producer_a, producer_a_stack, K_THREAD_STACK_SIZEOF(producer_a_stack),
			producer_entry, &a, NULL, NULL, K_PRIO_PREEMPT(1), 0, K_NO_WAIT);
	k_thread_create(&producer_b, producer_b_stack, K_THREAD_STACK_SIZEOF(producer_b_stack),
			producer_entry, &b, NULL, NULL, K_PRIO_PREEMPT(1), 0, K_NO_WAIT);
	zassert_ok(k_thread_join(&producer_a, K_SECONDS(5)));
	zassert_ok(k_thread_join(&producer_b, K_SECONDS(5)));
	zassert_ok(a.result);
	zassert_ok(b.result);
	zassert_true(completed_count() >= before + 16U);
	record = next_frame();
	zassert_equal(record.caller, refresh_tid);
	assert_serialized();
}

ZTEST_SUITE(refresh, NULL, refresh_setup, refresh_before, NULL, NULL);
