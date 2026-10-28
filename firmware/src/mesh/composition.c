// SPDX-License-Identifier: MIT
#include "internal.h"
#include <errno.h>
#include <zephyr/logging/log.h>
#include <zephyr/drivers/gpio.h>

LOG_MODULE_REGISTER(mesh_composition);
static const struct gpio_dt_spec attention_led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static bool attention, attention_lit;
K_MUTEX_DEFINE(attention_mutex);
static void attention_worker(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(attention_work, attention_worker);

static void attention_worker(struct k_work *work)
{
	ARG_UNUSED(work);
	k_mutex_lock(&attention_mutex, K_FOREVER);
	if (attention) {
		attention_lit = !attention_lit;
		(void)gpio_pin_set_dt(&attention_led, attention_lit);
		(void)k_work_reschedule(&attention_work, K_MSEC(200));
	}
	k_mutex_unlock(&attention_mutex);
}

static int faults(const struct bt_mesh_model *model, uint8_t *test_id,
		  uint16_t *company, uint8_t *values, uint8_t *count)
{
	ARG_UNUSED(model);
	ARG_UNUSED(values);
	*test_id = 0;
	*company = CONFIG_MESHLIGHT_COMPANY_ID;
	*count = 0;
	return 0;
}

static int registered_faults(const struct bt_mesh_model *model, uint16_t company,
			     uint8_t *test_id, uint8_t *values, uint8_t *count)
{
	if (company != CONFIG_MESHLIGHT_COMPANY_ID) {
		return -EINVAL;
	}
	return faults(model, test_id, &company, values, count);
}

static int clear_faults(const struct bt_mesh_model *model, uint16_t company)
{
	ARG_UNUSED(model);
	return company == CONFIG_MESHLIGHT_COMPANY_ID ? 0 : -EINVAL;
}

static int test_faults(const struct bt_mesh_model *model, uint8_t test, uint16_t company)
{
	return test == 0 ? clear_faults(model, company) : -EINVAL;
}

static void attention_on(const struct bt_mesh_model *model)
{
	ARG_UNUSED(model);
	k_mutex_lock(&attention_mutex, K_FOREVER);
	if (gpio_is_ready_dt(&attention_led) &&
	    !gpio_pin_configure_dt(&attention_led, GPIO_OUTPUT_INACTIVE)) {
		attention = true;
		(void)k_work_reschedule(&attention_work, K_NO_WAIT);
	}
	k_mutex_unlock(&attention_mutex);
	LOG_INF("Attention requested (node identification)");
}

static void attention_off(const struct bt_mesh_model *model)
{
	ARG_UNUSED(model);
	k_mutex_lock(&attention_mutex, K_FOREVER);
	attention = false;
	(void)k_work_cancel_delayable(&attention_work);
	(void)gpio_pin_set_dt(&attention_led, 0);
	k_mutex_unlock(&attention_mutex);
	LOG_INF("Attention ended");
}

static const struct bt_mesh_health_srv_cb health_callbacks = {
	.fault_get_cur = faults, .fault_get_reg = registered_faults,
	.fault_clear = clear_faults, .fault_test = test_faults,
	.attn_on = attention_on, .attn_off = attention_off,
};
struct bt_mesh_health_srv mesh_health = {.cb = &health_callbacks};
BT_MESH_HEALTH_PUB_DEFINE(health_pub, 0);

static const struct bt_mesh_model models[] = {
	BT_MESH_MODEL_CFG_SRV,
	BT_MESH_MODEL_HEALTH_SRV(&mesh_health, &health_pub),
	BT_MESH_MODEL(BT_MESH_MODEL_ID_GEN_ONOFF_SRV, mesh_onoff_ops, &mesh_onoff_pub, NULL),
	BT_MESH_MODEL(BT_MESH_MODEL_ID_GEN_LEVEL_SRV, mesh_level_ops, &mesh_level_pub, NULL),
};
static const struct bt_mesh_model vendor_models[] = {
	BT_MESH_MODEL_VND(CONFIG_MESHLIGHT_COMPANY_ID, CONFIG_MESHLIGHT_MODEL_ID,
		mesh_vendor_ops, &mesh_vendor_pub, NULL),
};
static const struct bt_mesh_elem elements[] = {BT_MESH_ELEM(0, models, vendor_models)};
const struct bt_mesh_comp mesh_composition = {
	.cid = CONFIG_MESHLIGHT_COMPANY_ID, .pid = 1, .vid = 1,
	.elem = elements, .elem_count = ARRAY_SIZE(elements),
};
