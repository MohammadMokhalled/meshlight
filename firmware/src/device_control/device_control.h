/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_DEVICE_CONTROL_H_
#define MESHLIGHT_DEVICE_CONTROL_H_
#include <stdbool.h>

/** Before lighting/Bluetooth: complete journaled cleanup, then reboot if needed. */
int device_control_recover(void);
/** Initialize sw0 hold detection and the independent reset worker. */
int device_control_init(void);
/** Nonblocking and idempotent. Safe from mesh callbacks; never writes flash inline. */
void device_control_request_reset(void);
bool device_control_resetting(void);
#endif
