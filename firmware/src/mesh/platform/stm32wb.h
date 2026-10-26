/* SPDX-License-Identifier: Apache-2.0 */
#ifndef MESHLIGHT_STM32WB_H_
#define MESHLIGHT_STM32WB_H_
#include "shci.h"

/* Linker's --wrap target, supplied by the pinned STM32 HAL library. */
SHCI_CmdStatus_t __real_SHCI_C2_BLE_Init(SHCI_C2_Ble_Init_Cmd_Packet_t *packet);
int mesh_stm32wb_configure_radio(void);
#endif
