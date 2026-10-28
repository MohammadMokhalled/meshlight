// SPDX-License-Identifier: MIT
#include "storage/storage.h"

#include <errno.h>
#include <string.h>
#include <zephyr/drivers/flash/flash_simulator.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/fs/zms.h>
#include <zephyr/irq_offload.h>
#include <zephyr/settings/settings.h>
#include <zephyr/ztest.h>
#if defined(CONFIG_SETTINGS_ZMS)
#include <settings/settings_zms.h>
#include <zephyr/sys/hash_function.h>
#include <zephyr/sys/crc.h>
#include "zms_priv.h"
#endif

static const uint8_t value[] = {0x31, 0x74, 0xc9, 0x52, 0x88, 0x2a, 0xf6, 0x19,
				0x63, 0xdb, 0x40, 0x95, 0x0a, 0xe1, 0xb7, 0x28};

static void *setup(void)
{
	uint8_t buf[32];
	size_t length;

	zassert_equal(storage_store("before", value, sizeof(value)), -ENODEV);
	zassert_equal(storage_restore("before", buf, sizeof(buf), &length), -ENODEV);
	zassert_ok(storage_init());
	zassert_ok(storage_init());
	return NULL;
}

ZTEST(storage, test_roundtrip_and_exact_keys)
{
	uint8_t buf[32];
	size_t length;

	zassert_ok(storage_store("test/child/value", value, sizeof(value)));
	zassert_equal(storage_restore("test/child", buf, sizeof(buf), &length), -ENOENT);
	zassert_equal(length, 0);
	zassert_ok(storage_store("test/child", value, sizeof(value)));
	zassert_ok(storage_restore("test/child", buf, sizeof(buf), &length));
	zassert_equal(length, sizeof(value));
	zassert_mem_equal(buf, value, length);
	zassert_equal(storage_restore("absent", buf, sizeof(buf), &length), -ENOENT);
}

ZTEST(storage, test_capacity_and_invalid_arguments)
{
	uint8_t buf[32];
	size_t length = 999;

	zassert_ok(storage_store("capacity", value, sizeof(value)));
	memset(buf, 0xaa, sizeof(buf));
	zassert_equal(storage_restore("capacity", buf, 1, &length), -ENOSPC);
	zassert_equal(length, sizeof(value));
	zassert_equal(buf[0], 0xaa);
	zassert_equal(storage_restore("capacity", buf, 0, &length), -EINVAL);
	zassert_equal(storage_restore("capacity", NULL, 32, &length), -EINVAL);
	zassert_equal(storage_restore("capacity", buf, 32, NULL), -EINVAL);
	zassert_equal(storage_store(NULL, value, sizeof(value)), -EINVAL);
	zassert_equal(storage_store("", value, sizeof(value)), -EINVAL);
	zassert_equal(storage_store("key", NULL, 1), -EINVAL);
	zassert_equal(storage_store("key", value, 0), -EINVAL);
}

ZTEST(storage, test_backend_remount_retains_data)
{
	void *backend;
	uint8_t buf[32];
	size_t length;

	zassert_ok(storage_store("retained", value, sizeof(value)));
	zassert_ok(settings_storage_get(&backend));
#if defined(CONFIG_SETTINGS_NVS)
	struct nvs_fs *live = backend;
	struct nvs_fs parameters = {
		.flash_device = live->flash_device, .offset = live->offset,
		.sector_size = live->sector_size, .sector_count = live->sector_count,
	};
	/* Discard volatile append pointers and reconstruct the actual live backend. */
	*live = parameters;
	zassert_ok(nvs_mount(live));
#else
	struct zms_fs *live = backend;
	struct zms_fs parameters = {
		.flash_device = live->flash_device, .offset = live->offset,
		.sector_size = live->sector_size, .sector_count = live->sector_count,
	};
	*live = parameters;
	zassert_ok(zms_mount(live));
#endif
	zassert_ok(storage_restore("retained", buf, sizeof(buf), &length));
	zassert_mem_equal(buf, value, sizeof(value));
}

ZTEST(storage, test_payload_crc_detects_bit_flip)
{
	const struct device *dev = DEVICE_DT_GET(DT_NODELABEL(flashcontroller_test));
	size_t size;
	uint8_t *memory = flash_simulator_get_memory(dev, &size);
	uint8_t buf[32];
	size_t length;
	size_t found = SIZE_MAX;

	zassert_ok(storage_store("crc", value, sizeof(value)));
	/* Corrupt all copies of this payload, leaving allocation metadata intact. */
	for (size_t i = 0; i + sizeof(value) <= size; i++) {
		if (memcmp(memory + i, value, sizeof(value)) == 0) {
			memory[i] ^= 1;
			found = i;
		}
	}
	zassert_not_equal(found, SIZE_MAX);
	zassert_true(storage_restore("crc", buf, sizeof(buf), &length) < 0);
	zassert_equal(length, 0);
	for (size_t i = 0; i + sizeof(value) <= size; i++) {
		if (memory[i] == (value[0] ^ 1) &&
		    memcmp(memory + i + 1, value + 1, sizeof(value) - 1) == 0) {
			memory[i] ^= 1;
		}
	}
}

ZTEST(storage, test_updates_survive_sector_rotation)
{
	uint8_t expected[64];
	uint8_t actual[64];
	size_t length;

	/* More than a whole backend's capacity: exercise reclamation and wraparound. */
	for (unsigned int i = 0; i < 300; i++) {
		memset(expected, i & 0xff, sizeof(expected));
		expected[0] = i >> 8;
		zassert_ok(storage_store("rotating", expected, sizeof(expected)));
	}
	zassert_ok(storage_restore("rotating", actual, sizeof(actual), &length));
	zassert_equal(length, sizeof(actual));
	zassert_mem_equal(actual, expected, sizeof(actual));
}

static unsigned int written_bytes;

static int count_write(const struct device *dev, off_t offset, uint8_t data)
{
	ARG_UNUSED(dev);
	ARG_UNUSED(offset);
	written_bytes++;
	return data;
}

ZTEST(storage, test_identical_values_do_not_write_flash)
{
	const struct device *dev = DEVICE_DT_GET(DT_NODELABEL(flashcontroller_test));
	const struct flash_simulator_cb callbacks = {.write_byte = count_write};
	uint8_t changed[sizeof(value)];
	unsigned int duplicate_bytes;
	int same_result;
	int changed_result;

	memcpy(changed, value, sizeof(value));
	changed[0] ^= 0x42;
	zassert_ok(storage_store("duplicate", value, sizeof(value)));
	written_bytes = 0;
	flash_simulator_set_callbacks(dev, &callbacks);
	same_result = storage_store("duplicate", value, sizeof(value));
	duplicate_bytes = written_bytes;
	changed_result = storage_store("duplicate", changed, sizeof(changed));
	flash_simulator_set_callbacks(dev, NULL);
	zassert_ok(same_result);
	zassert_ok(changed_result);
	zassert_equal(duplicate_bytes, 0);
	zassert_true(written_bytes > 0, "Callback must observe actual changed writes");
}

static int fail_write(const struct device *dev, off_t offset, uint8_t data)
{
	ARG_UNUSED(dev);
	ARG_UNUSED(offset);
	ARG_UNUSED(data);
	return -EIO;
}

ZTEST(storage, test_failed_write_preserves_previous_value)
{
	const struct device *dev = DEVICE_DT_GET(DT_NODELABEL(flashcontroller_test));
	const struct flash_simulator_cb callbacks = {.write_byte = fail_write};
	uint8_t changed[sizeof(value)];
	uint8_t buf[sizeof(value)];
	size_t length;
	int err;

	memcpy(changed, value, sizeof(value));
	changed[0] ^= 0x42;
	zassert_ok(storage_store("interrupted", value, sizeof(value)));
	flash_simulator_set_callbacks(dev, &callbacks);
	err = storage_store("interrupted", changed, sizeof(changed));
	flash_simulator_set_callbacks(dev, NULL);
	zassert_true(err < 0);
	zassert_ok(storage_restore("interrupted", buf, sizeof(buf), &length));
	zassert_mem_equal(buf, value, sizeof(value));
}

static void isr(const void *unused)
{
	uint8_t buf[32];
	size_t length;

	ARG_UNUSED(unused);
	zassert_equal(storage_init(), -EWOULDBLOCK);
	zassert_equal(storage_store("key", value, sizeof(value)), -EWOULDBLOCK);
	zassert_equal(storage_restore("key", buf, sizeof(buf), &length), -EWOULDBLOCK);
	zassert_equal(storage_delete("key"), -EWOULDBLOCK);
	zassert_equal(storage_load_subtree("bt"), -EWOULDBLOCK);
	zassert_equal(storage_reset_begin(), -EWOULDBLOCK);
}

ZTEST(storage, test_reset_marker_survives_failed_cleanup_and_retry)
{
	const struct device *dev = DEVICE_DT_GET(DT_NODELABEL(flashcontroller_test));
	const struct flash_simulator_cb callbacks = {.write_byte = fail_write};
	bool pending;
	uint8_t buf[32];
	size_t length;

	zassert_ok(storage_store("meshlight/lighting", value, sizeof(value)));
	zassert_ok(storage_store("bt/mesh/Net", value, sizeof(value)));
	zassert_ok(storage_store("its/test-key", value, sizeof(value)));
	zassert_ok(storage_reset_begin());
	zassert_ok(storage_reset_pending(&pending));
	zassert_true(pending);
	flash_simulator_set_callbacks(dev, &callbacks);
	int err = storage_reset_finish();

	flash_simulator_set_callbacks(dev, NULL);
	zassert_true(err < 0);
	zassert_ok(storage_reset_pending(&pending));
	zassert_true(pending);
	zassert_ok(storage_reset_finish());
	zassert_ok(storage_reset_pending(&pending));
	zassert_false(pending);
	zassert_equal(storage_restore("meshlight/lighting", buf, sizeof(buf), &length), -ENOENT);
	zassert_equal(storage_restore("bt/mesh/Net", buf, sizeof(buf), &length), -ENOENT);
	zassert_equal(storage_restore("its/test-key", buf, sizeof(buf), &length), -ENOENT);
	zassert_ok(storage_reset_finish());
}

static unsigned int subtree_loads;
static int subtree_set(const char *name, size_t len, settings_read_cb read, void *arg)
{
	uint8_t buf[32];

	if (strcmp(name, "value") || len != sizeof(value)) {
		return -EINVAL;
	}
	if (read(arg, buf, len) != len || memcmp(buf, value, len)) {
		return -EIO;
	}
	subtree_loads++;
	return 0;
}
SETTINGS_STATIC_HANDLER_DEFINE(load_probe, "load_probe", NULL, subtree_set, NULL, NULL);

ZTEST(storage, test_subtree_load_and_delete)
{
	uint8_t buf[32];
	size_t length;

	zassert_ok(storage_store("load_probe/value", value, sizeof(value)));
	subtree_loads = 0;
	zassert_ok(storage_load_subtree("unrelated"));
	zassert_equal(subtree_loads, 0);
	zassert_ok(storage_load_subtree("load_probe"));
	zassert_equal(subtree_loads, 1);
	zassert_ok(storage_delete("load_probe/value"));
	zassert_equal(storage_restore("load_probe/value", buf, sizeof(buf), &length), -ENOENT);
	zassert_equal(storage_delete(NULL), -EINVAL);
	zassert_equal(storage_load_subtree(""), -EINVAL);
}

ZTEST(storage, test_interrupt_rejection)
{
	irq_offload(isr, NULL);
}

ZTEST(storage, test_appkey_record_crc_and_legacy_name_rejection)
{
	const char *key = "bt/mesh/AppKey/0";
	/* SDK app_key_val: NetKey index, updated flag, and two opaque PSA IDs. */
	static const uint8_t record[11] = {0, 0, 0, 2, 0, 0, 0x20, 0, 0, 0, 0};
	uint8_t buf[sizeof(record)];
	size_t length;

	zassert_ok(storage_store(key, record, sizeof(record)));
	zassert_ok(storage_restore(key, buf, sizeof(buf), &length));
	zassert_equal(length, sizeof(record));
	zassert_mem_equal(buf, record, length);
#if defined(CONFIG_SETTINGS_ZMS)
	const struct device *dev = DEVICE_DT_GET(DT_NODELABEL(flashcontroller_test));
	size_t size;
	uint8_t *memory = flash_simulator_get_memory(dev, &size);
	uint32_t id = ZMS_NAME_ID_FROM_HASH(sys_hash32(key, strlen(key)) & ZMS_HASH_MASK);
	bool found = false;

	/* Reproduce onoff-app's CRC-disabled ATE format: its metadata CRC8 is
	 * valid, but the payload CRC32 is zero. Names cannot be silently reused
	 * by the CRC-enabled backend, even if their actual bytes are unchanged.
	 */
	for (size_t i = 0; i + sizeof(struct zms_ate) <= size; i += sizeof(struct zms_ate)) {
		struct zms_ate *entry = (struct zms_ate *)(memory + i);
		struct zms_ate saved;

		if (entry->id != id || entry->len != strlen(key) ||
		    entry->data_crc != crc32_ieee(key, strlen(key))) {
			continue;
		}
		saved = *entry;
		entry->data_crc = 0;
		entry->crc8 = crc8_ccitt(0xff, (uint8_t *)entry + 1, sizeof(*entry) - 1);
		int err = storage_store(key, record, sizeof(record));

		*entry = saved;
		zassert_true(err < 0, "Legacy name must fail CRC, not report a successful save");
		found = true;
		break;
	}
	zassert_true(found, "Must inject a valid, CRC-disabled name record");
#endif
	zassert_ok(storage_store(key, record, sizeof(record)));
	zassert_ok(storage_restore(key, buf, sizeof(buf), &length));
	zassert_mem_equal(buf, record, sizeof(record));
}

ZTEST_SUITE(storage, NULL, setup, NULL, NULL, NULL);
