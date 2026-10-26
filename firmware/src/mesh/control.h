/* SPDX-License-Identifier: MIT */
/* Private model engine: no Bluetooth dependencies, also used by emulator tests. */
#ifndef MESHLIGHT_MESH_CONTROL_H_
#define MESHLIGHT_MESH_CONTROL_H_
#include "protocol.h"

enum mesh_control_op {
	MESH_ONOFF_GET, MESH_ONOFF_SET, MESH_LEVEL_GET, MESH_LEVEL_SET,
	MESH_LEVEL_DELTA, MESH_LEVEL_MOVE
};
void mesh_control_init(void (*publish)(void));
void mesh_control_stop(void);
int mesh_control_standard(enum mesh_control_op op, uint16_t src, uint16_t dst,
	const uint8_t *data, size_t length, uint8_t response[5], size_t *response_length);
int mesh_control_vendor(uint16_t src, uint16_t dst, const uint8_t *data, size_t length,
			uint16_t *target);
int mesh_control_status(bool level, uint8_t response[5], size_t *length);
uint8_t mesh_control_remaining(int64_t milliseconds);
#endif
