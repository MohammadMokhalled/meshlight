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
#define RESET_KEY "meshlight/reset"

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

int storage_load_subtree(const char *subtree)
{
	int err;

	if (k_is_in_isr()) {
		return -EWOULDBLOCK;
	}
	if (!valid_key(subtree)) {
		return -EINVAL;
	}
	k_mutex_lock(&storage_mutex, K_FOREVER);
	err = ready ? settings_load_subtree(subtree) : -ENODEV;
	k_mutex_unlock(&storage_mutex);
	return err;
}

int storage_delete(const char *key)
{
	int err;

	if (k_is_in_isr()) {
		return -EWOULDBLOCK;
	}
	if (!valid_key(key)) {
		return -EINVAL;
	}
	k_mutex_lock(&storage_mutex, K_FOREVER);
	err = ready ? settings_delete(key) : -ENODEV;
	k_mutex_unlock(&storage_mutex);
	return err;
}

int storage_reset_begin(void)
{
	const uint8_t marker = 1;

	return storage_store(RESET_KEY, &marker, sizeof(marker));
}

int storage_reset_pending(bool *pending)
{
	uint8_t marker;
	size_t length;
	int err;

	if (pending == NULL) {
		return -EINVAL;
	}
	err = storage_restore(RESET_KEY, &marker, sizeof(marker), &length);
	if (err == -ENOENT) {
		*pending = false;
		return 0;
	}
	if (err == 0) {
		/* Any present marker requests recovery, including unknown versions. */
		*pending = true;
	}
	return err;
}

struct reset_search {
	char key[SETTINGS_MAX_NAME_LEN + 1];
	int error;
};

static int find_reset_key(const char *key, size_t length, settings_read_cb read,
			  void *arg, void *param)
{
	struct reset_search *search = param;

	ARG_UNUSED(read);
	ARG_UNUSED(arg);
	if (length && strcmp(key, RESET_KEY) != 0) {
		if (strnlen(key, sizeof(search->key)) >= sizeof(search->key)) {
			search->error = -E2BIG;
		} else {
			strcpy(search->key, key);
		}
		return 1;
	}
	return 0;
}

int storage_reset_finish(void)
{
	struct reset_search search;
	bool pending;
	int err = storage_reset_pending(&pending);

	if (err || !pending) {
		return err;
	}
	k_mutex_lock(&storage_mutex, K_FOREVER);
	for (;;) {
		memset(&search, 0, sizeof(search));
		/* Delete outside enumeration: backends may compact on each deletion. */
		err = settings_load_subtree_direct(NULL, find_reset_key, &search);
		if (search.error) {
			err = search.error;
		}
		if (err || search.key[0] == '\0') {
			break;
		}
		err = settings_delete(search.key);
		if (err) {
			break;
		}
	}
	if (!err) {
		err = settings_delete(RESET_KEY);
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
