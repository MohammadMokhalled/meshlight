// SPDX-License-Identifier: MIT

/** @file Initialize durable state before exposing any mesh control. */
#include <zephyr/logging/log.h>

#include "lighting/lighting.h"
#include "meshlight_version.h"
#include "storage/storage.h"
#if defined(CONFIG_MESHLIGHT_MESH)
#include "device_control/device_control.h"
#include "mesh/mesh.h"
#endif

LOG_MODULE_REGISTER(meshlight);

int main(void)
{
	int err;

	LOG_INF("Meshlight %s (%s)", MESHLIGHT_VERSION_STRING, MESHLIGHT_GIT_SHA);
#if defined(CONFIG_MESHLIGHT_MESH)
	err = storage_init();
	if (!err) {
		err = device_control_recover();
	}
	if (err) {
		LOG_ERR("Storage/reset recovery failed (%d)", err);
		/* Keep the recovery button available, but never provision with lost keys. */
		(void)device_control_init();
		return err;
	}
	err = device_control_init();
	if (err) {
		LOG_ERR("Reset button initialization failed (%d)", err);
		return err;
	}
#endif
	err = lighting_init();
	if (err != 0) {
		LOG_ERR("Lighting initialization failed (%d)", err);
		return err;
	}
	LOG_INF("Lighting ready");
#if defined(CONFIG_MESHLIGHT_MESH)
	if (!device_control_resetting()) {
		return mesh_init();
	}
#endif
	return 0;
}
