// SPDX-License-Identifier: MIT
/** Exercise real opcode handlers with a capturing transport, without a radio. */
#include "mesh/internal.h"
#include "mesh/control.h"
#include <string.h>
#include <errno.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/ztest.h>

static unsigned int sends, publishes;
static uint8_t sent[16];
static size_t sent_length;
static const struct bt_mesh_model sig[] = {
	{.op = mesh_onoff_ops, .pub = &mesh_onoff_pub},
	{.op = mesh_level_ops, .pub = &mesh_level_pub},
};
static const struct bt_mesh_model vendor[] = {{.op = mesh_vendor_ops, .pub = &mesh_vendor_pub}};
static const struct bt_mesh_elem element[] = {{.model_count = 2, .models = sig,
	.vnd_model_count = 1, .vnd_models = vendor}};
const struct bt_mesh_comp mesh_composition = {.elem = element, .elem_count = 1};

/* Encode only the transport header, just as the stack does before queueing it. */
void bt_mesh_model_msg_init(struct net_buf_simple *msg, uint32_t opcode)
{
	net_buf_simple_reset(msg);
	if (opcode > 0xffff) {
		net_buf_simple_add_u8(msg, opcode >> 16);
		net_buf_simple_add_le16(msg, opcode & 0xffff);
	} else {
		net_buf_simple_add_be16(msg, opcode);
	}
}

int bt_mesh_model_send(const struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx,
	struct net_buf_simple *msg, const struct bt_mesh_send_cb *callback, void *data)
{
	ARG_UNUSED(model); ARG_UNUSED(ctx); ARG_UNUSED(callback); ARG_UNUSED(data);
	sends++;
	sent_length = msg->len;
	zassert_true(sent_length <= sizeof(sent));
	memcpy(sent, msg->data, sent_length);
	return 0;
}

int bt_mesh_model_publish(const struct bt_mesh_model *model)
{
	zassert_true(model->pub->msg->len > 0);
	publishes++;
	return 0;
}

static int invoke(const struct bt_mesh_model *model, size_t operation,
		  const uint8_t *bytes, size_t length)
{
	struct bt_mesh_msg_ctx context = {.addr = 1, .recv_dst = 2};
	NET_BUF_SIMPLE_DEFINE(buffer, 16);

	if (length) {
		net_buf_simple_add_mem(&buffer, bytes, length);
	}
	return model->op[operation].func(model, &context, &buffer);
}

static void before(void *unused)
{
	ARG_UNUSED(unused);
	zassert_ok(lighting_init());
	mesh_models_stop();
	zassert_ok(lighting_set_on(true));
	zassert_ok(lighting_set_all_intensity(50));
	mesh_onoff_pub.addr = mesh_level_pub.addr = mesh_vendor_pub.addr = BT_MESH_ADDR_UNASSIGNED;
	mesh_models_init();
	sends = publishes = 0;
}

ZTEST(adapters, test_standard_acknowledged_and_unacknowledged)
{
	uint8_t off[] = {0, 1};

	zassert_ok(invoke(&sig[0], 1, off, sizeof(off)));
	zassert_equal(sends, 1);
	zassert_equal(sent_length, 3);
	zassert_equal(sent[0], 0x82);
	zassert_equal(sent[1], 4);
	zassert_equal(sent[2], 0);
	off[0] = 1; off[1] = 2;
	zassert_ok(invoke(&sig[0], 2, off, sizeof(off)));
	zassert_equal(sends, 1);
}

ZTEST(adapters, test_vendor_ack_error_and_no_ack)
{
	uint8_t packet[] = {0, 0, 1, 2, 25};

	zassert_ok(invoke(&vendor[0], 2, packet, sizeof(packet)));
	zassert_equal(sends, 1);
	zassert_equal(sent[0], MESHLIGHT_OP_STATE_STATUS);
	zassert_equal(sent[10], 25);
	packet[2] = 2; packet[4] = 30;
	zassert_ok(invoke(&vendor[0], 3, packet, sizeof(packet)));
	zassert_equal(sends, 1);
	sys_put_le16(lighting_get_led_count(), packet);
	zassert_equal(invoke(&vendor[0], 2, packet, sizeof(packet)), -ERANGE);
	zassert_equal(sends, 2);
	zassert_equal(sent[5], MESHLIGHT_BAD_TARGET);
}

ZTEST(adapters, test_publications_and_periodic_snapshot)
{
	uint8_t request[] = {0, 0x80, 1};

	mesh_level_pub.addr = 0xc000;
	zassert_ok(invoke(&sig[1], 1, request, sizeof(request)));
	zassert_equal(publishes, 1);
	zassert_ok(mesh_level_pub.update(&sig[1]));
	zassert_equal(sys_get_le16(mesh_level_pub.msg->data + 2), 0x8000);
	mesh_level_pub.addr = BT_MESH_ADDR_UNASSIGNED;
}

ZTEST(adapters, test_capabilities_are_queryable)
{
	zassert_ok(invoke(&vendor[0], 0, NULL, 0));
	zassert_equal(sends, 1);
	zassert_equal(sent[0], MESHLIGHT_OP_CAP_STATUS);
	zassert_equal(sys_get_le16(sent + 4), lighting_get_led_count());
}

ZTEST(adapters, test_stopped_models_do_not_accept_requests)
{
	mesh_models_stop();
	zassert_equal(invoke(&sig[0], 0, NULL, 0), -ESHUTDOWN);
	zassert_equal(sends, 0);
}
ZTEST_SUITE(adapters, NULL, NULL, before, NULL, NULL);
