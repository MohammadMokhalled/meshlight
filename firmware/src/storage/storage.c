// SPDX-License-Identifier: MIT

#include "storage.h"

#include <errno.h>
#include <string.h>
#include <zephyr/devicetree/fixed-partitions.h>
#include <zephyr/kernel.h>
#include <zephyr/settings/settings.h>

#if DT_HAS_CHOSEN(zephyr_settings_partition)
#define SETTINGS_NODE DT_CHOSEN(zephyr_settings_partition)
#else
#define SETTINGS_NODE DT_NODELABEL(storage_partition)
#endif
#define SETTINGS_END (DT_REG_ADDR(SETTINGS_NODE) + DT_REG_SIZE(SETTINGS_NODE))
BUILD_ASSERT(SETTINGS_END <= DT_REG_SIZE(DT_MEM_FROM_FIXED_PARTITION(SETTINGS_NODE)),
	     "Settings partition is outside the declared memory device");
#if DT_HAS_CHOSEN(zephyr_code_partition)
#define CODE_NODE DT_CHOSEN(zephyr_code_partition)
BUILD_ASSERT(!DT_SAME_NODE(DT_MEM_FROM_FIXED_PARTITION(CODE_NODE),
			  DT_MEM_FROM_FIXED_PARTITION(SETTINGS_NODE)) ||
	     DT_REG_ADDR(CODE_NODE) + DT_REG_SIZE(CODE_NODE) <= DT_REG_ADDR(SETTINGS_NODE) ||
	     SETTINGS_END <= DT_REG_ADDR(CODE_NODE), "Code and Settings partitions overlap");
#endif

K_MUTEX_DEFINE(storage_mutex);
static bool ready;

static bool valid_key(const char *key)
{
	return key != NULL && key[0] != '\0' &&
	       strnlen(key, SETTINGS_MAX_NAME_LEN + 1) <= SETTINGS_MAX_NAME_LEN;
}

int storage_init(void)
{
	int err = 0;

	if (k_is_in_isr()) {
		return -EWOULDBLOCK;
	}
	k_mutex_lock(&storage_mutex, K_FOREVER);
	if (!ready) {
		err = settings_subsys_init();
		ready = err == 0;
	}
	k_mutex_unlock(&storage_mutex);
	return err;
}

int storage_store(const char *key, const void *data, size_t length)
{
	int err;

	if (k_is_in_isr()) {
		return -EWOULDBLOCK;
	}
	if (!valid_key(key) || data == NULL || length == 0) {
		return -EINVAL;
	}
	k_mutex_lock(&storage_mutex, K_FOREVER);
	err = ready ? settings_save_one(key, data, length) : -ENODEV;
	k_mutex_unlock(&storage_mutex);
	return err;
}

int storage_restore(const char *key, void *data, size_t capacity, size_t *actual_length)
{
	ssize_t length;
	ssize_t read;
	int err = 0;

	if (k_is_in_isr()) {
		return -EWOULDBLOCK;
	}
	if (actual_length == NULL) {
		return -EINVAL;
	}
	*actual_length = 0;
	if (!valid_key(key) || data == NULL || capacity == 0) {
		return -EINVAL;
	}
	k_mutex_lock(&storage_mutex, K_FOREVER);
	if (!ready) {
		err = -ENODEV;
		goto out;
	}
	length = settings_get_val_len(key);
	if (length <= 0) {
		err = length < 0 ? (int)length : -ENOENT;
		goto out;
	}
	if ((size_t)length > capacity) {
		*actual_length = length;
		err = -ENOSPC;
		goto out;
	}
	read = settings_load_one(key, data, length);
	if (read != length) {
		err = read < 0 ? (int)read : -EIO;
	} else {
		*actual_length = length;
	}
out:
	k_mutex_unlock(&storage_mutex);
	return err;
}
