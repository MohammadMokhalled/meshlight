// SPDX-License-Identifier: Apache-2.0
/* Adapted from the working onoff-app STM32WB HCI adapter. */

#include <zephyr/logging/log.h>
#include <zephyr/bluetooth/hci.h>
#include "stm32wb.h"

LOG_MODULE_REGISTER(mesh_stm32wb);

/*
 * NCS 3.3.0's IPM driver replaces CFG_BLE_OPTIONS (which selects LL_ONLY)
 * with LL_HOST, which is inappropriate for our HCI-only controller image.
 * Correct the command at the HAL boundary without
 * modifying the installed SDK or the pinned HAL checkout.
 */
SHCI_CmdStatus_t __wrap_SHCI_C2_BLE_Init(SHCI_C2_Ble_Init_Cmd_Packet_t *packet)
{
	SHCI_CmdStatus_t status;

	packet->Param.Options |= SHCI_C2_BLE_INIT_OPTIONS_LL_ONLY;
	status = __real_SHCI_C2_BLE_Init(packet);
	LOG_INF("CPU2 HCI-only initialization status: 0x%02x", status);
	return status;
}

int mesh_stm32wb_configure_radio(void)
{
	/* ST PM0271: offset 0xc1 enables background scanning. Mesh scans
	 * continuously; connection and advertising slots must take priority.
	 */
	const uint8_t parameters[] = { 0xc1, 1, 1 };
	struct net_buf *command = bt_hci_cmd_alloc(K_FOREVER);
	int err;

	if (!command) {
		return -ENOMEM;
	}
	net_buf_add_mem(command, parameters, sizeof(parameters));
	err = bt_hci_cmd_send_sync(BT_OP(BT_OGF_VS, 0x000c), command, NULL);
	LOG_INF("CPU2 background scanning configuration: %d", err);
	return err;
}
