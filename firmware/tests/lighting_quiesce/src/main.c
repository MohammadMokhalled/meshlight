// SPDX-License-Identifier: MIT
#include "lighting/lighting.h"
#include "fake_storage.h"
#include <errno.h>
#include <zephyr/device.h>
#include <zephyr/ztest.h>

static int update_rgb(const struct device *dev, struct led_rgb *pixels, size_t count)
{
	ARG_UNUSED(dev);
	ARG_UNUSED(pixels);
	ARG_UNUSED(count);
	return 0;
}
static size_t strip_length(const struct device *dev)
{
	ARG_UNUSED(dev);
	return DT_PROP(DT_ALIAS(led_strip), chain_length);
}
static DEVICE_API(led_strip, strip_api) = {.update_rgb = update_rgb, .length = strip_length};
DEVICE_DT_DEFINE(DT_ALIAS(led_strip), NULL, NULL, NULL, NULL, POST_KERNEL,
	CONFIG_LED_STRIP_INIT_PRIORITY, &strip_api);

static void release_save(struct k_work *work)
{
	ARG_UNUSED(work);
	k_sem_give(&fake_io_release);
}
K_WORK_DELAYABLE_DEFINE(release_save_work, release_save);

ZTEST(quiesce, test_drain_inflight_save_and_close_control)
{
	zassert_ok(lighting_init());
	fake_storage.block_store = true;
	zassert_ok(lighting_set_all_intensity(70));
	zassert_ok(k_sem_take(&fake_io_started, K_SECONDS(1)));
	(void)k_work_reschedule(&release_save_work, K_MSEC(30));
	zassert_ok(lighting_quiesce());
	unsigned int writes = fake_storage.writes;

	zassert_true(writes > 0);
	k_sleep(K_MSEC(200));
	zassert_equal(fake_storage.writes, writes);
	zassert_equal(lighting_set_on(false), -ESHUTDOWN);
	zassert_equal(lighting_set_all_intensity(10), -ESHUTDOWN);
	zassert_equal(lighting_init(), -ESHUTDOWN);
	zassert_equal(lighting_apply(), -ESHUTDOWN);
}
ZTEST_SUITE(quiesce, NULL, NULL, NULL, NULL, NULL);
