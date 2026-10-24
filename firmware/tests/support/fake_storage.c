// SPDX-License-Identifier: MIT
#include "storage/storage.h"
#include "fake_storage.h"
#include <errno.h>
#include <string.h>

K_MUTEX_DEFINE(fake_storage_mutex);
K_SEM_DEFINE(fake_io_started, 0, 1);
K_SEM_DEFINE(fake_io_release, 0, 1);
struct fake_storage_state fake_storage;

int storage_init(void)
{
	return fake_storage.init_error;
}

int storage_store(const char *key, const void *data, size_t length)
{
	int err;
	bool block;

	ARG_UNUSED(key);
	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	fake_storage.attempts++;
	fake_storage.last_attempt_ms = k_uptime_get();
	err = fake_storage.store_error;
	block = fake_storage.block_store;
	fake_storage.block_store = false;
	k_mutex_unlock(&fake_storage_mutex);
	if (block) {
		k_sem_give(&fake_io_started);
		k_sem_take(&fake_io_release, K_FOREVER);
	}
	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	if (length > sizeof(fake_storage.data)) {
		err = -ENOSPC;
	}
	if (err == 0) {
		memcpy(fake_storage.data, data, length);
		fake_storage.length = length;
		fake_storage.writes++;
	}
	k_mutex_unlock(&fake_storage_mutex);
	return err;
}

int storage_restore(const char *key, void *data, size_t capacity, size_t *length)
{
	int err;

	ARG_UNUSED(key);
	k_mutex_lock(&fake_storage_mutex, K_FOREVER);
	err = fake_storage.restore_error;
	*length = fake_storage.length;
	if (err == 0) {
		if (*length == 0) {
			err = -ENOENT;
		} else if (*length > capacity) {
			err = -ENOSPC;
		} else {
			memcpy(data, fake_storage.data, *length);
		}
	}
	k_mutex_unlock(&fake_storage_mutex);
	return err;
}
