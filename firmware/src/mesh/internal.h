/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_MESH_INTERNAL_H_
#define MESHLIGHT_MESH_INTERNAL_H_
#include <zephyr/bluetooth/mesh.h>

#define VENDOR_OP(byte) BT_MESH_MODEL_OP_3(byte, CONFIG_MESHLIGHT_COMPANY_ID)
extern const struct bt_mesh_model_op mesh_onoff_ops[], mesh_level_ops[], mesh_vendor_ops[];
extern struct bt_mesh_model_pub mesh_onoff_pub, mesh_level_pub, mesh_vendor_pub;
extern struct bt_mesh_health_srv mesh_health;
extern const struct bt_mesh_comp mesh_composition;
const struct bt_mesh_prov *mesh_provisioning(void);
int mesh_provisioning_init(void);
void mesh_models_init(void);
void mesh_models_stop(void);
bool mesh_models_stopped(void);
#endif
