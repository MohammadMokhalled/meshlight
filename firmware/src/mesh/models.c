// SPDX-License-Identifier: MIT
/** Bluetooth adapters. Parsing, transactions and transitions live in control.c. */
#include "internal.h"
#include "control.h"
#include <errno.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/byteorder.h>

LOG_MODULE_REGISTER(mesh_models);

#define ONOFF_STATUS BT_MESH_MODEL_OP_2(0x82, 0x04)
#define LEVEL_STATUS BT_MESH_MODEL_OP_2(0x82, 0x08)
static atomic_t stopped;
static uint16_t published_target = LIGHTING_TARGET_ALL;
K_MUTEX_DEFINE(publication_mutex);

static int update_onoff(const struct bt_mesh_model *model);
static int update_level(const struct bt_mesh_model *model);
static int update_vendor(const struct bt_mesh_model *model);
/* Exported publication objects are referenced by the static composition. */
NET_BUF_SIMPLE_DEFINE_STATIC(onoff_buffer, BT_MESH_MODEL_BUF_LEN(ONOFF_STATUS, 3));
NET_BUF_SIMPLE_DEFINE_STATIC(level_buffer, BT_MESH_MODEL_BUF_LEN(LEVEL_STATUS, 5));
NET_BUF_SIMPLE_DEFINE_STATIC(vendor_buffer, BT_MESH_MODEL_BUF_LEN(VENDOR_OP(0xc5), 8));
struct bt_mesh_model_pub mesh_onoff_pub = {.msg = &onoff_buffer, .update = update_onoff};
struct bt_mesh_model_pub mesh_level_pub = {.msg = &level_buffer, .update = update_level};
struct bt_mesh_model_pub mesh_vendor_pub = {.msg = &vendor_buffer, .update = update_vendor};

bool mesh_models_stopped(void)
{
	return atomic_get(&stopped);
}

static int update_standard(const struct bt_mesh_model *model, bool level)
{
	uint8_t bytes[5];
	size_t length;
	int err = mesh_control_status(level, bytes, &length);

	if (!err) {
		bt_mesh_model_msg_init(model->pub->msg, level ? LEVEL_STATUS : ONOFF_STATUS);
		net_buf_simple_add_mem(model->pub->msg, bytes, length);
	}
	return err;
}

static int update_onoff(const struct bt_mesh_model *model)
{
	k_mutex_lock(&publication_mutex, K_FOREVER);
	int err = update_standard(model, false);

	k_mutex_unlock(&publication_mutex);
	return err;
}

static int update_level(const struct bt_mesh_model *model)
{
	k_mutex_lock(&publication_mutex, K_FOREVER);
	int err = update_standard(model, true);

	k_mutex_unlock(&publication_mutex);
	return err;
}

static int update_vendor(const struct bt_mesh_model *model)
{
	struct lighting_status status;
	uint8_t bytes[8];
	int err;

	if (mesh_models_stopped()) {
		return -ESHUTDOWN;
	}
	k_mutex_lock(&publication_mutex, K_FOREVER);
	err = lighting_get_status(published_target, &status);
	mesh_protocol_status(bytes, published_target, err, &status);
	bt_mesh_model_msg_init(model->pub->msg, VENDOR_OP(MESHLIGHT_OP_STATE_STATUS));
	net_buf_simple_add_mem(model->pub->msg, bytes, sizeof(bytes));
	k_mutex_unlock(&publication_mutex);
	return 0;
}

static void publish_changed(void)
{
	if (mesh_models_stopped()) {
		return;
	}
	k_mutex_lock(&publication_mutex, K_FOREVER);
	for (size_t e = 0; e < mesh_composition.elem_count; e++) {
		const struct bt_mesh_elem *element = &mesh_composition.elem[e];

		for (size_t i = 0; i < element->model_count + element->vnd_model_count; i++) {
			const struct bt_mesh_model *model = i < element->model_count ?
				&element->models[i] : &element->vnd_models[i - element->model_count];

			if (model->pub && model->pub->update &&
			    model->pub->addr != BT_MESH_ADDR_UNASSIGNED &&
			    !model->pub->update(model)) {
				(void)bt_mesh_model_publish(model);
			}
		}
	}
	k_mutex_unlock(&publication_mutex);
}

void mesh_models_init(void)
{
	atomic_clear(&stopped);
	mesh_control_init(publish_changed);
}

void mesh_models_stop(void)
{
	atomic_set(&stopped, 1);
	mesh_control_stop();
}

static int standard(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		    struct net_buf_simple *buf, enum mesh_control_op op, bool acknowledge)
{
	uint8_t response[5];
	size_t length;
	int err;
	bool level = op != MESH_ONOFF_GET && op != MESH_ONOFF_SET;

	BT_MESH_MODEL_BUF_DEFINE(message, LEVEL_STATUS, 5);

	if (mesh_models_stopped()) {
		return -ESHUTDOWN;
	}
	err = mesh_control_standard(op, ctx->addr, ctx->recv_dst, buf->data, buf->len,
		response, &length);
	if (err) {
		LOG_WRN("Rejected SIG command %u from 0x%04x to 0x%04x (%d)",
			op, ctx->addr, ctx->recv_dst, err);
		return err;
	}
	if (acknowledge) {
		bt_mesh_model_msg_init(&message, level ? LEVEL_STATUS : ONOFF_STATUS);
		net_buf_simple_add_mem(&message, response, length);
		err = bt_mesh_model_send(model, ctx, &message, NULL, NULL);
	}
	if (op != MESH_ONOFF_GET && op != MESH_LEVEL_GET) {
		publish_changed();
	}
	return err;
}

static int onoff_get(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_ONOFF_GET, true);
}

static int onoff_set(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_ONOFF_SET, true);
}

static int onoff_unack(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_ONOFF_SET, false);
}

static int level_get(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_LEVEL_GET, true);
}

static int level_set(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_LEVEL_SET, true);
}

static int level_unack(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_LEVEL_SET, false);
}

static int delta_set(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_LEVEL_DELTA, true);
}

static int delta_unack(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_LEVEL_DELTA, false);
}

static int move_set(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_LEVEL_MOVE, true);
}

static int move_unack(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		     struct net_buf_simple *buf)
{
	return standard(model, ctx, buf, MESH_LEVEL_MOVE, false);
}

const struct bt_mesh_model_op mesh_onoff_ops[] = {
	{BT_MESH_MODEL_OP_2(0x82, 0x01), BT_MESH_LEN_EXACT(0), onoff_get},
	{BT_MESH_MODEL_OP_2(0x82, 0x02), BT_MESH_LEN_MIN(2), onoff_set},
	{BT_MESH_MODEL_OP_2(0x82, 0x03), BT_MESH_LEN_MIN(2), onoff_unack},
	BT_MESH_MODEL_OP_END,
};
const struct bt_mesh_model_op mesh_level_ops[] = {
	{BT_MESH_MODEL_OP_2(0x82, 0x05), BT_MESH_LEN_EXACT(0), level_get},
	{BT_MESH_MODEL_OP_2(0x82, 0x06), BT_MESH_LEN_MIN(3), level_set},
	{BT_MESH_MODEL_OP_2(0x82, 0x07), BT_MESH_LEN_MIN(3), level_unack},
	{BT_MESH_MODEL_OP_2(0x82, 0x09), BT_MESH_LEN_MIN(5), delta_set},
	{BT_MESH_MODEL_OP_2(0x82, 0x0a), BT_MESH_LEN_MIN(5), delta_unack},
	{BT_MESH_MODEL_OP_2(0x82, 0x0b), BT_MESH_LEN_MIN(3), move_set},
	{BT_MESH_MODEL_OP_2(0x82, 0x0c), BT_MESH_LEN_MIN(3), move_unack},
	BT_MESH_MODEL_OP_END,
};

static int capabilities(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
			struct net_buf_simple *buf)
{
	uint8_t bytes[6];

	BT_MESH_MODEL_BUF_DEFINE(message, VENDOR_OP(MESHLIGHT_OP_CAP_STATUS), 6);

	ARG_UNUSED(buf);
	if (mesh_models_stopped()) {
		return -ESHUTDOWN;
	}
	mesh_protocol_capabilities(bytes, lighting_get_led_count());
	bt_mesh_model_msg_init(&message, VENDOR_OP(MESHLIGHT_OP_CAP_STATUS));
	net_buf_simple_add_mem(&message, bytes, sizeof(bytes));
	return bt_mesh_model_send(model, ctx, &message, NULL, NULL);
}

static int vendor(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		  struct net_buf_simple *buf, bool set, bool acknowledge)
{
	uint16_t target = buf->len >= 2 ? sys_get_le16(buf->data) : LIGHTING_TARGET_ALL;
	struct lighting_status status;
	uint8_t bytes[8];
	int err = 0;

	BT_MESH_MODEL_BUF_DEFINE(message, VENDOR_OP(MESHLIGHT_OP_STATE_STATUS), 8);

	if (mesh_models_stopped()) {
		return -ESHUTDOWN;
	}
	if (set) {
		err = mesh_control_vendor(ctx->addr, ctx->recv_dst, buf->data, buf->len, &target);
	}
	if (!err) {
		err = lighting_get_status(target, &status);
	}
	if (acknowledge) {
		mesh_protocol_status(bytes, target, err, &status);
		bt_mesh_model_msg_init(&message, VENDOR_OP(MESHLIGHT_OP_STATE_STATUS));
		net_buf_simple_add_mem(&message, bytes, sizeof(bytes));
		(void)bt_mesh_model_send(model, ctx, &message, NULL, NULL);
	}
	if (set && !err) {
		k_mutex_lock(&publication_mutex, K_FOREVER);
		published_target = target;
		publish_changed();
		k_mutex_unlock(&publication_mutex);
	}
	return err;
}

static int vendor_get(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		      struct net_buf_simple *buf)
{
	return vendor(model, ctx, buf, false, true);
}

static int vendor_set(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
		      struct net_buf_simple *buf)
{
	return vendor(model, ctx, buf, true, true);
}

static int vendor_unack(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
			struct net_buf_simple *buf)
{
	return vendor(model, ctx, buf, true, false);
}

const struct bt_mesh_model_op mesh_vendor_ops[] = {
	{VENDOR_OP(MESHLIGHT_OP_CAP_GET), BT_MESH_LEN_EXACT(0), capabilities},
	{VENDOR_OP(MESHLIGHT_OP_STATE_GET), BT_MESH_LEN_EXACT(2), vendor_get},
	{VENDOR_OP(MESHLIGHT_OP_STATE_SET), BT_MESH_LEN_MIN(4), vendor_set},
	{VENDOR_OP(MESHLIGHT_OP_STATE_SET_UNACK), BT_MESH_LEN_MIN(4), vendor_unack},
	BT_MESH_MODEL_OP_END,
};
