// SPDX-License-Identifier: MIT

/** @file Restore lighting and leave control to the shell or future transports. */
#include <zephyr/logging/log.h>

#include "lighting/lighting.h"
#include "meshlight_version.h"

LOG_MODULE_REGISTER(meshlight);

int main(void)
{
	int err;

	LOG_INF("Meshlight %s (%s)", MESHLIGHT_VERSION_STRING, MESHLIGHT_GIT_SHA);
	err = lighting_init();
	if (err != 0) {
		LOG_ERR("Lighting initialization failed (%d)", err);
		return err;
	}
	LOG_INF("Lighting ready");
	return 0;
}
