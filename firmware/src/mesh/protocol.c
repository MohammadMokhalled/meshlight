// SPDX-License-Identifier: MIT
#include "protocol.h"
#include <errno.h>
#include <string.h>
#include <zephyr/sys/byteorder.h>

int mesh_protocol_decode(const uint8_t *data, size_t length, uint16_t *target,
			 uint8_t *tid, struct lighting_update *update)
{
	struct lighting_update value = {0};
	size_t offset = 4;
	uint8_t flags;

	if (!data || !target || !tid || !update || length < 4) {
		return -EINVAL;
	}
	flags = data[3];
	if (!(flags & 7) || (flags & 0xf0) ||
	    ((flags & MESHLIGHT_WIRE_ON) && !(flags & LIGHTING_UPDATE_POWER))) {
		return -EINVAL;
	}
	if (length != 4U + ((flags & LIGHTING_UPDATE_RGB) ? 3U : 0U) +
			  ((flags & LIGHTING_UPDATE_INTENSITY) ? 1U : 0U)) {
		return -EINVAL;
	}
	value.fields = flags & 7;
	value.on = flags & MESHLIGHT_WIRE_ON;
	if (flags & LIGHTING_UPDATE_RGB) {
		value.rgb = (struct led_rgb){.r = data[offset], .g = data[offset + 1],
			.b = data[offset + 2]};
		offset += 3;
	}
	if (flags & LIGHTING_UPDATE_INTENSITY) {
		value.intensity = data[offset];
		if (value.intensity > LIGHTING_INTENSITY_MAX) {
			return -EINVAL;
		}
	}
	*target = sys_get_le16(data);
	*tid = data[2];
	*update = value;
	return 0;
}

void mesh_protocol_capabilities(uint8_t out[6], uint16_t led_count)
{
	out[0] = MESHLIGHT_PROTOCOL_VERSION;
	sys_put_le16(led_count, out + 1);
	/* RGB, intensity, power, indexed target, all target, mixed-state reporting. */
	sys_put_le16(0x003f, out + 3);
	out[5] = LIGHTING_INTENSITY_MAX;
}

void mesh_protocol_status(uint8_t out[8], uint16_t target, int error,
			  const struct lighting_status *status)
{
	memset(out, 0, 8);
	sys_put_le16(target, out);
	out[2] = error == 0 ? MESHLIGHT_OK : error == -ERANGE ? MESHLIGHT_BAD_TARGET :
		 error == -EINVAL ? MESHLIGHT_BAD_REQUEST : MESHLIGHT_UNAVAILABLE;
	if (error == 0 && status) {
		out[3] = (status->on ? 1 : 0) | (status->mixed << 1);
		out[4] = status->rgb.r;
		out[5] = status->rgb.g;
		out[6] = status->rgb.b;
		out[7] = status->intensity;
	}
}
