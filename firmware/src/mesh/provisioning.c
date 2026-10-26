// SPDX-License-Identifier: MIT
/** Authentication boundary. Future OOB callbacks belong here, not in models. */
#include "internal.h"
#include "device_control/device_control.h"
#include <errno.h>
#include <string.h>
#include <psa/crypto.h>
#include <zephyr/drivers/hwinfo.h>
#include <zephyr/logging/log.h>

LOG_MODULE_REGISTER(mesh_provisioning);
static uint8_t uuid[16];

static void complete(uint16_t net_idx, uint16_t address)
{
	LOG_INF("Provisioned: subnet 0x%04x, address 0x%04x", net_idx, address);
}

static void link_open(bt_mesh_prov_bearer_t bearer)
{
	LOG_INF("Provisioning opened (%s)", bearer == BT_MESH_PROV_GATT ? "GATT" : "ADV");
}

static void link_close(bt_mesh_prov_bearer_t bearer)
{
	LOG_INF("Provisioning closed (%u); provisioned=%u", bearer, bt_mesh_is_provisioned());
}

static void reset(void)
{
	/* The Configuration Server invokes this after sending Node Reset Status. */
	device_control_request_reset();
}

static const struct bt_mesh_prov provisioning = {
	.uuid = uuid, .output_size = 0, .output_actions = 0,
	.input_size = 0, .input_actions = 0,
	.complete = complete, .link_open = link_open, .link_close = link_close, .reset = reset,
};

int mesh_provisioning_init(void)
{
	/* A fixed namespace prevents accidental reuse by unrelated firmware. */
	static const uint8_t namespace[] = "Meshlight UUID v1";
	uint8_t input[sizeof(namespace) - 1 + 16];
	uint8_t digest[32];
	size_t written;
	const size_t prefix_length = sizeof(namespace) - 1;
	ssize_t length = hwinfo_get_device_id(input + prefix_length, 16);

	if (length <= 0) {
		return length < 0 ? length : -ENODEV;
	}
	memcpy(input, namespace, prefix_length);
	if (psa_crypto_init() != PSA_SUCCESS ||
	    psa_hash_compute(PSA_ALG_SHA_256, input, prefix_length + length, digest,
			     sizeof(digest), &written) != PSA_SUCCESS) {
		return -EIO;
	}
	memcpy(uuid, digest, sizeof(uuid));
	return 0;
}

const struct bt_mesh_prov *mesh_provisioning(void)
{
	return &provisioning;
}
