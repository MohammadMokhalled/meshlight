// SPDX-License-Identifier: MIT
/** Button IRQs only capture state. Reset flash I/O runs on a private queue. */
#include "device_control.h"
#include "button_hold.h"
#include "lighting/lighting.h"
#include "mesh/mesh.h"
#include "storage/storage.h"
#include <errno.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/reboot.h>

LOG_MODULE_REGISTER(device_control);
static const struct gpio_dt_spec button = GPIO_DT_SPEC_GET(DT_ALIAS(sw0), gpios);
static struct gpio_callback button_callback;
static struct k_spinlock button_lock;
static bool raw_pressed;
static int64_t edge_time;
static struct button_hold hold;
static atomic_t resetting;
static struct k_work_q reset_queue;
static K_KERNEL_STACK_DEFINE(reset_stack, 4096);

static void reset_worker(struct k_work *work);
static void button_worker(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(reset_work, reset_worker);
K_WORK_DELAYABLE_DEFINE(button_work, button_worker);

bool device_control_resetting(void)
{
	return atomic_get(&resetting);
}

void device_control_request_reset(void)
{
	if (atomic_cas(&resetting, 0, 1)) {
		(void)k_work_reschedule_for_queue(&reset_queue, &reset_work, K_NO_WAIT);
	}
}

static void reset_worker(struct k_work *work)
{
	int err = storage_init();

	ARG_UNUSED(work);
	if (!err) {
		err = storage_reset_begin();
	}
	/* Once requested, controls remain closed even if the marker needs retrying. */
	(void)lighting_quiesce();
	(void)mesh_stop();
	if (err) {
		LOG_ERR("Cannot journal reset (%d); retrying", err);
		(void)k_work_reschedule_for_queue(&reset_queue, &reset_work, K_SECONDS(5));
		return;
	}
	LOG_INF("Reset journal committed; rebooting into cleanup");
	/* A reboot is the barrier for all SDK-owned asynchronous Settings producers. */
	sys_reboot(SYS_REBOOT_COLD);
}

int device_control_recover(void)
{
	bool pending;
	int err = storage_reset_pending(&pending);

	if (err || !pending) {
		return err;
	}
	LOG_INF("Completing interrupted/requested factory reset");
	/* No lighting, Bluetooth or mesh workers exist at this point. */
	while ((err = storage_reset_finish()) != 0) {
		LOG_ERR("Reset cleanup failed (%d); retrying", err);
		k_sleep(K_SECONDS(5));
	}
	/* Discard any crypto/backend RAM caches populated by early SDK init hooks. */
	sys_reboot(SYS_REBOOT_COLD);
	return 0;
}

static void button_worker(struct k_work *work)
{
	bool pressed;
	int64_t timestamp, now = k_uptime_get();
	k_spinlock_key_t key = k_spin_lock(&button_lock);

	ARG_UNUSED(work);
	pressed = raw_pressed;
	timestamp = edge_time;
	k_spin_unlock(&button_lock, key);
	if (now - timestamp < 30) {
		(void)k_work_reschedule(&button_work, K_MSEC(30 - (now - timestamp)));
		return;
	}
	/* A release uses its edge timestamp, never the delayed worker timestamp. */
	if (button_hold_update(&hold, pressed, pressed != hold.pressed ? timestamp : now)) {
		device_control_request_reset();
	}
	if (pressed && !hold.fired) {
		(void)k_work_reschedule(&button_work,
			K_MSEC(MAX(1, hold.since + 30001 - now)));
	}
}

static void button_edge(const struct device *dev, struct gpio_callback *callback, uint32_t pins)
{
	int pressed = gpio_pin_get_dt(&button);
	k_spinlock_key_t key;

	ARG_UNUSED(dev);
	ARG_UNUSED(callback);
	ARG_UNUSED(pins);
	if (pressed < 0) {
		return;
	}
	key = k_spin_lock(&button_lock);
	raw_pressed = pressed;
	edge_time = k_uptime_get();
	k_spin_unlock(&button_lock, key);
	(void)k_work_reschedule(&button_work, K_MSEC(30));
}

int device_control_init(void)
{
	int err;

	k_work_queue_start(&reset_queue, reset_stack, K_KERNEL_STACK_SIZEOF(reset_stack),
		K_PRIO_PREEMPT(6), NULL);
	if (!gpio_is_ready_dt(&button)) {
		return -ENODEV;
	}
	err = gpio_pin_configure_dt(&button, GPIO_INPUT);
	if (err) {
		return err;
	}
	gpio_init_callback(&button_callback, button_edge, BIT(button.pin));
	err = gpio_add_callback(button.port, &button_callback);
	if (!err) {
		err = gpio_pin_interrupt_configure_dt(&button, GPIO_INT_EDGE_BOTH);
	}
	if (!err) {
		/* Treat an already-held button as a press starting at initialization. */
		button_edge(button.port, &button_callback, BIT(button.pin));
	}
	return err;
}
