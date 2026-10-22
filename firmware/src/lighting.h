/* SPDX-License-Identifier: MIT */

/**
 * @file
 * @brief Staged per-LED RGB and intensity control for a single strip.
 *
 * Setters only change the requested state. With CONFIG_LIGHTING_AUTO_REFRESH,
 * a private thread displays it at the next refresh (50 ms by default), including
 * on/off changes. lighting_apply() remains an optional synchronous flush; it
 * is required when automatic refresh is disabled. Getters return requested
 * values, not hardware readback. Each call is serialized, but separate setters
 * are not an atomic batch: a refresh may display intermediate selections.
 * Except for lighting_get_led_count(), all calls require thread context.
 */

#ifndef MESHLIGHT_LIGHTING_H_
#define MESHLIGHT_LIGHTING_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <zephyr/drivers/led_strip.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Maximum per-LED intensity, in percent. */
#define LIGHTING_INTENSITY_MAX 100U

/** Requested color and intensity of one LED, before output processing. */
struct lighting_led_state {
	/** RGB channels, each 0..255; optional Zephyr scratch space is ignored. */
	struct led_rgb rgb;
	/** Intensity in percent, inclusive range 0..100. */
	uint8_t intensity;
};

/*
 * ============================================================================
 * PUBLIC FUNCTION DECLARATIONS
 * ============================================================================
 */

/**
 * @brief Initialize the strip and transmit black to every configured LED.
 *
 * Initial requested values are black RGB, 100% intensity, and power off.
 * Successful repeated calls preserve all requested values and do not transmit.
 * Initialization failures can be retried; other controls remain unavailable
 * until clearing the strip succeeds.
 *
 * With automatic refresh enabled, starts one private preemptible thread after
 * successful initialization. Its first deadline is one configured interval
 * later. Repeated calls do not create another thread or restart its schedule.
 *
 * @retval 0 Successfully initialized, or already initialized.
 * @retval -ENODEV The configured strip device is not ready.
 * @retval -EWOULDBLOCK Called from interrupt context.
 * @return A negative driver error if clearing the strip fails.
 */
int lighting_init(void);

/**
 * @brief Return the immutable LED count from devicetree chain-length.
 *
 * Does not access hardware or acquire a mutex. Available before initialization
 * and from any context. Indices follow strip order, starting at the DIN end.
 *
 * @return Number of LEDs; always greater than zero.
 */
size_t lighting_get_led_count(void);

/**
 * @brief Stage RGB and intensity for one LED without transmitting.
 *
 * Copies the supplied value; the caller retains ownership. Other LEDs and the
 * strip-wide power selection are preserved. Automatic refresh displays the
 * request at the next frame; lighting_apply() can flush it immediately. Success
 * means the request was stored, not that a hardware transfer succeeded.
 *
 * @param index Zero-based LED index, less than lighting_get_led_count().
 * @param state Requested RGB and intensity.
 * @retval 0 Requested values stored.
 * @retval -EINVAL The state pointer is NULL.
 * @retval -ERANGE The index is out of range or intensity exceeds 100%.
 * @retval -ENODEV Initialization has not succeeded.
 * @retval -EWOULDBLOCK Called from interrupt context.
 */
int lighting_set_led(size_t index, const struct lighting_led_state *state);

/**
 * @brief Stage the same RGB and intensity for every LED without transmitting.
 *
 * Copies the supplied value to every LED under one lock. Preserves the
 * strip-wide power selection. Automatic refresh displays the request at the
 * next frame; lighting_apply() can flush it immediately. Success means the
 * request was stored, not that a hardware transfer succeeded.
 *
 * @param state Requested RGB and intensity for all LEDs.
 * @retval 0 Requested values stored.
 * @retval -EINVAL The state pointer is NULL.
 * @retval -ERANGE Intensity exceeds 100%.
 * @retval -ENODEV Initialization has not succeeded.
 * @retval -EWOULDBLOCK Called from interrupt context.
 */
int lighting_set_all(const struct lighting_led_state *state);

/**
 * @brief Copy one LED's requested RGB and intensity into caller-owned storage.
 *
 * Reports staged values even before apply or after a failed transfer. The
 * caller's output is unchanged on error. Does not transmit.
 *
 * @param index Zero-based LED index, less than lighting_get_led_count().
 * @param state Destination snapshot.
 * @retval 0 Snapshot copied.
 * @retval -EINVAL The destination pointer is NULL.
 * @retval -ERANGE The index is out of range.
 * @retval -ENODEV Initialization has not succeeded.
 * @retval -EWOULDBLOCK Called from interrupt context.
 */
int lighting_get_led(size_t index, struct lighting_led_state *state);

/**
 * @brief Stage the strip-wide power setting without transmitting.
 *
 * Off makes the next apply transmit black while retaining every LED's RGB and
 * intensity. On makes the next apply render those selections, including zero
 * intensity. No color or intensity is changed by this call.
 * Automatic refresh applies the selection at the next frame; a synchronous
 * lighting_apply() can apply it immediately. This call does not confirm output.
 *
 * @param on True to render selections; false to render black.
 * @retval 0 Requested power setting stored.
 * @retval -ENODEV Initialization has not succeeded.
 * @retval -EWOULDBLOCK Called from interrupt context.
 */
int lighting_set_on(bool on);

/**
 * @brief Copy the requested strip-wide power setting.
 *
 * Does not transmit. The caller's output is unchanged on error.
 *
 * @param on Destination snapshot, owned by the caller.
 * @retval 0 Snapshot copied.
 * @retval -EINVAL The destination pointer is NULL.
 * @retval -ENODEV Initialization has not succeeded.
 * @retval -EWOULDBLOCK Called from interrupt context.
 */
int lighting_get_on(bool *on);

/**
 * @brief Render and synchronously transmit the complete requested strip state.
 *
 * While on, each channel is first scaled with half-up rounding:
 * scaled = (channel * intensity + 50) / 100. A fixed gamma-2.2 lookup table
 * then maps scaled to round(255 * (scaled / 255)^2.2). Thus RGB components
 * below 255 are gamma-corrected even at 100% intensity. Zero stays black;
 * small nonzero values may also round to black. While off, all output is black.
 *
 * Always transmits, even if nothing changed. Requested values survive driver
 * failures so another apply can retry. A failed transfer may partially change
 * the physical strip; there is no hardware readback or rollback guarantee.
 * The background worker uses this same serialized path and retries at its
 * next scheduled refresh, logging failures at most once per second. Manual
 * calls do not change the worker's schedule. Timing is subject to scheduler
 * latency and transfer duration; missed slots are skipped without catch-up.
 *
 * @retval 0 The driver accepted the complete frame.
 * @retval -ENODEV Initialization has not succeeded.
 * @retval -EWOULDBLOCK Called from interrupt context.
 * @return A negative driver error on transfer failure.
 */
int lighting_apply(void);

#ifdef __cplusplus
}
#endif

#endif /* MESHLIGHT_LIGHTING_H_ */
