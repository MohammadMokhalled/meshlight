// SPDX-License-Identifier: MIT
#include "button_hold.h"

bool button_hold_update(struct button_hold *hold, bool pressed, int64_t time_ms)
{
	bool fire = hold->pressed && !hold->fired && time_ms - hold->since > 30000;

	if (!hold->pressed && pressed) {
		hold->since = time_ms;
		hold->fired = false;
	}
	if (fire) {
		hold->fired = true;
	}
	hold->pressed = pressed;
	return fire;
}
