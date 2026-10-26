/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_PROTOCOL_H_
#define MESHLIGHT_PROTOCOL_H_
#include "lighting/lighting.h"

#define MESHLIGHT_PROTOCOL_VERSION 1U
#define MESHLIGHT_OP_CAP_GET 0xc0
#define MESHLIGHT_OP_CAP_STATUS 0xc1
#define MESHLIGHT_OP_STATE_GET 0xc2
#define MESHLIGHT_OP_STATE_SET 0xc3
#define MESHLIGHT_OP_STATE_SET_UNACK 0xc4
#define MESHLIGHT_OP_STATE_STATUS 0xc5
#define MESHLIGHT_WIRE_ON 0x08U

enum meshlight_result {
	MESHLIGHT_OK, MESHLIGHT_BAD_TARGET, MESHLIGHT_BAD_REQUEST, MESHLIGHT_UNAVAILABLE
};

/** Pure wire decoder: never mutates output arguments on failure. */
int mesh_protocol_decode(const uint8_t *data, size_t length, uint16_t *target,
			 uint8_t *tid, struct lighting_update *update);
/** Fixed eight-byte State Status; failed snapshots have zero state fields. */
void mesh_protocol_status(uint8_t out[8], uint16_t target, int error,
			  const struct lighting_status *status);
void mesh_protocol_capabilities(uint8_t out[6], uint16_t led_count);
#endif
