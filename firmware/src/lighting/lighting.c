// SPDX-License-Identifier: MIT

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
#include "lighting_settings.h"
#include "storage/storage.h"

#include <errno.h>
#include <string.h>

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

LOG_MODULE_REGISTER(lighting);

/* Serialize init/retry; only the worker uses staging buffers after startup. */
K_MUTEX_DEFINE(init_mutex);
static struct lighting_led_state staged_states[STRIP_LENGTH];
static uint8_t settings_buffer[LIGHTING_SETTINGS_MAX_SIZE];
static bool save_pending;
static int64_t save_due_ms;
static struct k_work_q persistence_queue;
static struct k_work_delayable save_work;
static K_KERNEL_STACK_DEFINE(persistence_stack, CONFIG_LIGHTING_PERSISTENCE_STACK_SIZE);

/* Worst case: four two-byte unsigned values and an array header per pixel. */
BUILD_ASSERT(9U * STRIP_LENGTH + 16U <= LIGHTING_SETTINGS_MAX_SIZE,
	     "Lighting snapshot exceeds the single-record storage limit");
BUILD_ASSERT(8 < CONFIG_NUM_PREEMPT_PRIORITIES, "Persistence requires priority 8");

#if defined(CONFIG_LIGHTING_AUTO_REFRESH)

/* Created once after the restored/default first frame succeeds; no heap or timer. */
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
static int set_selection(size_t first, size_t count, const struct led_rgb *rgb,
			 const uint8_t *intensity);
static uint8_t render_channel(uint8_t channel, uint8_t intensity);
static int render_frame(void);
static void save_entry(struct k_work *work);
static void request_save_locked(void);
static int restore_settings(void);
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
	int err;
	int restore_err;

	if (k_is_in_isr()) {
		return -EWOULDBLOCK;
	}
	k_mutex_lock(&init_mutex, K_FOREVER);
	k_mutex_lock(&lighting_mutex, K_FOREVER);
	if (initialized) {
		k_mutex_unlock(&lighting_mutex);
		k_mutex_unlock(&init_mutex);
		return 0;
	}
	k_mutex_unlock(&lighting_mutex);
	if (!device_is_ready(strip)) {
		k_mutex_unlock(&init_mutex);
		return -ENODEV;
	}

	/* No flash I/O holds lighting_mutex, including during initialization. */
	restore_err = restore_settings();
	if (restore_err != 0) {
		for (size_t i = 0; i < STRIP_LENGTH; i++) {
			staged_states[i] = LIGHTING_DEFAULT_STATE;
		}
		if (restore_err != -ENOENT) {
			LOG_WRN("Lighting restore failed (%d); using white at 50%%", restore_err);
		}
	}
	k_mutex_lock(&lighting_mutex, K_FOREVER);
	memcpy(led_states, staged_states, sizeof(led_states));
	strip_on = true;
	err = render_frame();
	if (err == 0) {
		k_work_init_delayable(&save_work, save_entry);
		k_work_queue_start(&persistence_queue, persistence_stack,
				   K_KERNEL_STACK_SIZEOF(persistence_stack),
				   K_PRIO_PREEMPT(8), NULL);
		initialized = true;
#if defined(CONFIG_LIGHTING_AUTO_REFRESH)
		first_refresh_ms = k_uptime_get() + CONFIG_LIGHTING_REFRESH_INTERVAL_MS;
		k_thread_create(&refresh_thread, refresh_stack,
				K_KERNEL_STACK_SIZEOF(refresh_stack), refresh_entry,
				NULL, NULL, NULL, K_PRIO_PREEMPT(REFRESH_THREAD_PRIORITY),
				0, K_NO_WAIT);
#endif
	}
	k_mutex_unlock(&lighting_mutex);
	k_mutex_unlock(&init_mutex);
	return err;
}

size_t lighting_get_led_count(void)
{
	return STRIP_LENGTH;
}

int lighting_set_led(size_t index, const struct lighting_led_state *state)
{
	if (state == NULL) {
		return -EINVAL;
	}
	if (index >= STRIP_LENGTH || state->intensity > LIGHTING_INTENSITY_MAX) {
		return -ERANGE;
	}
	return set_selection(index, 1, &state->rgb, &state->intensity);
}

int lighting_set_all(const struct lighting_led_state *state)
{
	if (state == NULL) {
		return -EINVAL;
	}
	if (state->intensity > LIGHTING_INTENSITY_MAX) {
		return -ERANGE;
	}
	return set_selection(0, STRIP_LENGTH, &state->rgb, &state->intensity);
}

int lighting_set_led_color(size_t index, const struct led_rgb *rgb)
{
	if (rgb == NULL) {
		return -EINVAL;
	}
	if (index >= STRIP_LENGTH) {
		return -ERANGE;
	}
	return set_selection(index, 1, rgb, NULL);
}

int lighting_set_all_color(const struct led_rgb *rgb)
{
	if (rgb == NULL) {
		return -EINVAL;
	}
	return set_selection(0, STRIP_LENGTH, rgb, NULL);
}

int lighting_set_led_intensity(size_t index, uint8_t intensity)
{
	if (index >= STRIP_LENGTH || intensity > LIGHTING_INTENSITY_MAX) {
		return -ERANGE;
	}
	return set_selection(index, 1, NULL, &intensity);
}

int lighting_set_all_intensity(uint8_t intensity)
{
	if (intensity > LIGHTING_INTENSITY_MAX) {
		return -ERANGE;
	}
	return set_selection(0, STRIP_LENGTH, NULL, &intensity);
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

/* Optional fields preserve the other selection under the same lock as updates. */
static int set_selection(size_t first, size_t count, const struct led_rgb *rgb,
			 const uint8_t *intensity)
{
	bool changed = false;
	int err = lock_initialized();

	if (err != 0) {
		return err;
	}
	for (size_t i = first; i < first + count; i++) {
		if (rgb != NULL) {
			changed |= led_states[i].rgb.r != rgb->r ||
				   led_states[i].rgb.g != rgb->g || led_states[i].rgb.b != rgb->b;
			led_states[i].rgb = *rgb;
		}
		if (intensity != NULL) {
			changed |= led_states[i].intensity != *intensity;
			led_states[i].intensity = *intensity;
		}
	}
	if (changed) {
		request_save_locked();
	}
	k_mutex_unlock(&lighting_mutex);
	return 0;
}

/* Startup only, before either worker can access staging buffers. */
static int restore_settings(void)
{
	size_t length;
	int err = storage_init();

	if (err == 0) {
		err = storage_restore(LIGHTING_SETTINGS_KEY, settings_buffer,
				      sizeof(settings_buffer), &length);
	}
	if (err == 0) {
		err = lighting_settings_decode(settings_buffer, length, staged_states, STRIP_LENGTH);
	}
	return err;
}

/* All actual RGB/intensity changes arrive here with lighting_mutex held. */
static void request_save_locked(void)
{
	save_pending = true;
	save_due_ms = k_uptime_get() + CONFIG_LIGHTING_SAVE_DELAY_MS;
	/* The queue remains active for the lifetime of the lighting module. */
	(void)k_work_reschedule_for_queue(&persistence_queue, &save_work,
					K_MSEC(CONFIG_LIGHTING_SAVE_DELAY_MS));
}

static void save_entry(struct k_work *work)
{
	int64_t remaining_ms;
	size_t length;
	int err;

	ARG_UNUSED(work);
	k_mutex_lock(&lighting_mutex, K_FOREVER);
	if (!save_pending) {
		k_mutex_unlock(&lighting_mutex);
		return;
	}
	remaining_ms = save_due_ms - k_uptime_get();
	if (remaining_ms > 0) {
		/* Rescheduling a timer does not remove an already queued callback. */
		(void)k_work_reschedule_for_queue(&persistence_queue, &save_work,
						K_MSEC(remaining_ms));
		k_mutex_unlock(&lighting_mutex);
		return;
	}
	memcpy(staged_states, led_states, sizeof(staged_states));
	save_pending = false;
	k_mutex_unlock(&lighting_mutex);

	/* No flash I/O holds the lighting mutex; later edits queue their own save. */
	err = lighting_settings_encode(settings_buffer, sizeof(settings_buffer), &length,
				       staged_states, STRIP_LENGTH);
	if (err == 0) {
		err = storage_init();
	}
	if (err == 0) {
		err = storage_store(LIGHTING_SETTINGS_KEY, settings_buffer, length);
	}
	if (err != 0) {
		k_mutex_lock(&lighting_mutex, K_FOREVER);
		save_pending = true;
		save_due_ms = k_uptime_get() + CONFIG_LIGHTING_SAVE_RETRY_MS;
		(void)k_work_reschedule_for_queue(&persistence_queue, &save_work,
						K_MSEC(CONFIG_LIGHTING_SAVE_RETRY_MS));
		k_mutex_unlock(&lighting_mutex);
		LOG_ERR_RATELIMIT_RATE(CONFIG_LIGHTING_SAVE_RETRY_MS,
				      "Lighting save failed (%d); retrying", err);
	}
	/* Success must not clear a save requested while this snapshot was written. */
}

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
