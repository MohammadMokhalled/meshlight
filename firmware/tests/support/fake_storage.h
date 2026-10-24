/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_FAKE_STORAGE_H_
#define MESHLIGHT_FAKE_STORAGE_H_
#include <zephyr/kernel.h>
#include <stddef.h>
#include <stdint.h>

extern struct k_mutex fake_storage_mutex;
extern struct k_sem fake_io_started;
extern struct k_sem fake_io_release;
struct fake_storage_state {
	uint8_t data[3072];
	size_t length;
	unsigned int attempts;
	int64_t last_attempt_ms;
	unsigned int writes;
	int init_error;
	int store_error;
	int restore_error;
	bool block_store;
};
extern struct fake_storage_state fake_storage;
#endif
