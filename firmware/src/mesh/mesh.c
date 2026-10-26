// SPDX-License-Identifier: MIT
#include "mesh.h"
#include "internal.h"
#include "storage/storage.h"
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/logging/log.h>
#if defined(CONFIG_BOARD_NUCLEO_WB55RG)
#include "platform/stm32wb.h"
#endif

LOG_MODULE_REGISTER(meshlight_mesh);
static bool bluetooth_ready, mesh_ready;

static void connected(struct bt_conn *conn, uint8_t error)
{
	ARG_UNUSED(conn);
	LOG_INF("BLE connection opened (status 0x%02x)", error);
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	ARG_UNUSED(conn);
	LOG_INF("BLE connection closed (reason 0x%02x)", reason);
}
BT_CONN_CB_DEFINE(mesh_connection_callbacks) = {.connected = connected, .disconnected = disconnected};

int mesh_init(void)
{
	int err = storage_init();

	if (!err) {
		LOG_INF("Crypto resources: heap=%u bytes, PSA key slots=%u",
			CONFIG_MBEDTLS_HEAP_SIZE, CONFIG_MBEDTLS_PSA_KEY_SLOT_COUNT);
		err = mesh_provisioning_init();
	}
	if (!err) {
		err = bt_enable(NULL);
		bluetooth_ready = !err;
	}
#if defined(CONFIG_BOARD_NUCLEO_WB55RG)
	if (!err) {
		err = mesh_stm32wb_configure_radio();
	}
#endif
	if (!err) {
		mesh_models_init();
		err = bt_mesh_init(mesh_provisioning(), &mesh_composition);
		mesh_ready = !err;
	}
	if (!err) {
		err = storage_load_subtree("bt");
	}
	if (!err && !bt_mesh_is_provisioned()) {
		err = bt_mesh_prov_enable(BT_MESH_PROV_ADV | BT_MESH_PROV_GATT);
	}
	if (err) {
		/* A failed restore must never turn into a destructive Node Reset. */
		mesh_models_stop();
		if (bluetooth_ready) {
			(void)bt_disable();
			bluetooth_ready = false;
		}
		mesh_ready = false;
		LOG_ERR("Mesh startup failed (%d)", err);
	} else {
		LOG_INF("Mesh ready; provisioned=%u", bt_mesh_is_provisioned());
	}
	return err;
}

int mesh_stop(void)
{
	mesh_models_stop();
	if (mesh_ready) {
		(void)bt_mesh_prov_disable(BT_MESH_PROV_ADV | BT_MESH_PROV_GATT);
		/* The reset journal must be durable before destroying persistent keys. */
		bool pending = false;

		(void)storage_reset_pending(&pending);
		if (bt_mesh_is_provisioned()) {
			if (pending) {
				bt_mesh_reset();
			} else {
				(void)bt_mesh_suspend();
			}
		}
		mesh_ready = false;
	}
	if (bluetooth_ready) {
		int err = bt_disable();

		if (err) {
			return err;
		}
		bluetooth_ready = false;
	}
	return 0;
}
