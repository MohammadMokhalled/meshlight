/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_BUTTON_HOLD_H_
#define MESHLIGHT_BUTTON_HOLD_H_
#include <stdbool.h>
#include <stdint.h>

struct button_hold {
	int64_t since;
	bool pressed, fired;
};
/** Feed debounced edges with their original timestamps, or a held-state timer. */
bool button_hold_update(struct button_hold *hold, bool pressed, int64_t time_ms);
#endif
