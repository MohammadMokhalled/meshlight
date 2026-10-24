// SPDX-License-Identifier: MIT

#include "lighting_settings.h"

#include <errno.h>
#include <zcbor_decode.h>
#include <zcbor_encode.h>

int lighting_settings_encode(uint8_t *buffer, size_t capacity, size_t *length,
			     const struct lighting_led_state *leds, size_t count)
{
	ZCBOR_STATE_E(state, 3, buffer, capacity, 1);
	bool ok = zcbor_map_start_encode(state, 2) && zcbor_uint32_put(state, 0) &&
		  zcbor_uint32_put(state, 2) && zcbor_uint32_put(state, 2) &&
		  zcbor_list_start_encode(state, count);

	for (size_t i = 0; ok && i < count; i++) {
		ok = zcbor_list_start_encode(state, 4) &&
		     zcbor_uint32_put(state, leds[i].rgb.r) &&
		     zcbor_uint32_put(state, leds[i].rgb.g) &&
		     zcbor_uint32_put(state, leds[i].rgb.b) &&
		     zcbor_uint32_put(state, leds[i].intensity) &&
		     zcbor_list_end_encode(state, 4);
	}
	ok = ok && zcbor_list_end_encode(state, count) && zcbor_map_end_encode(state, 2);
	if (!ok) {
		return -ENOSPC;
	}
	*length = state->payload - buffer;
	return 0;
}

static bool decode_leds(zcbor_state_t *state, struct lighting_led_state *leds, size_t count)
{
	size_t index = 0;

	if (!zcbor_list_start_decode(state)) {
		return false;
	}
	while (!zcbor_array_at_end(state)) {
		uint32_t r, g, b, intensity;

		if (!zcbor_list_start_decode(state) || !zcbor_uint32_decode(state, &r) ||
		    !zcbor_uint32_decode(state, &g) || !zcbor_uint32_decode(state, &b) ||
		    !zcbor_uint32_decode(state, &intensity) || !zcbor_list_end_decode(state) ||
		    r > 255 || g > 255 || b > 255 || intensity > LIGHTING_INTENSITY_MAX) {
			return false;
		}
		if (index < count) {
			leds[index] = (struct lighting_led_state){
				.rgb = {.r = r, .g = g, .b = b}, .intensity = intensity,
			};
		}
		index++;
	}
	return index != 0 && zcbor_list_end_decode(state);
}

int lighting_settings_decode(const uint8_t *buffer, size_t length,
			     struct lighting_led_state *leds, size_t count)
{
	ZCBOR_STATE_D(state, 8, buffer, length, 1, 0);
	uint32_t version = 0;
	unsigned int seen = 0;
	bool legacy_power;

	for (size_t i = 0; i < count; i++) {
		leds[i] = LIGHTING_DEFAULT_STATE;
	}
	if (!zcbor_map_start_decode(state)) {
		return -EINVAL;
	}
	while (!zcbor_array_at_end(state)) {
		uint32_t key;
		bool ok;

		if (!zcbor_uint32_decode(state, &key)) {
			return -EINVAL;
		}
		if (key < 3) {
			if ((seen & (1U << key)) != 0) {
				return -EINVAL;
			}
			seen |= 1U << key;
		}
		switch (key) {
		case 0:
			ok = zcbor_uint32_decode(state, &version);
			break;
		case 1:
			/* Retired v1 field: validate it, but never restore its value. */
			ok = zcbor_bool_decode(state, &legacy_power);
			break;
		case 2:
			ok = decode_leds(state, leds, count);
			break;
		default:
			ok = zcbor_any_skip(state, NULL);
			break;
		}
		if (!ok) {
			return -EINVAL;
		}
	}
	if ((seen & 5U) != 5U || !zcbor_map_end_decode(state) || !zcbor_payload_at_end(state)) {
		return -EINVAL;
	}
	if (version == 1) {
		return seen == 7U ? 0 : -EINVAL;
	}
	return version == 2 ? 0 : -ENOTSUP;
}
