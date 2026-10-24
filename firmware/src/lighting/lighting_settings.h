/* SPDX-License-Identifier: MIT */
/* Private lighting wire format. Never store struct lighting_led_state directly. */
#ifndef MESHLIGHT_LIGHTING_SETTINGS_H_
#define MESHLIGHT_LIGHTING_SETTINGS_H_

#include "lighting.h"

#define LIGHTING_SETTINGS_KEY "meshlight/lighting"
#define LIGHTING_SETTINGS_MAX_SIZE 3072U
#define LIGHTING_DEFAULT_STATE \
	((struct lighting_led_state){.rgb = {.r = 255, .g = 255, .b = 255}, .intensity = 50})

int lighting_settings_encode(uint8_t *buffer, size_t capacity, size_t *length,
			     const struct lighting_led_state *leds, size_t count);
int lighting_settings_decode(const uint8_t *buffer, size_t length,
			     struct lighting_led_state *leds, size_t count);

#endif
