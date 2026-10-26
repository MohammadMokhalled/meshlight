/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_STORAGE_H_
#define MESHLIGHT_STORAGE_H_

#include <stddef.h>
#include <stdbool.h>

/** Initialize Zephyr Settings, without loading any subsystem. Retryable/idempotent. */
int storage_init(void);

/** Synchronously commit one nonempty binary value. Requires successful init. */
int storage_store(const char *key, const void *data, size_t length);

/**
 * Restore exactly key, reading the complete value to verify backend data CRC.
 * Returns -ENOENT if absent, -ENOSPC with required actual_length if too large.
 * On other errors actual_length is zero and data must not be used.
 * All three APIs require thread context; ISR calls return -EWOULDBLOCK.
 */
int storage_restore(const char *key, void *data, size_t capacity, size_t *actual_length);

/** Load registered handlers only in this subtree, after their subsystem is ready. */
int storage_load_subtree(const char *subtree);
/** Delete one key. Requires init and thread context. */
int storage_delete(const char *key);

/** Durable reset journal. Finish only after all Settings producers are stopped. */
int storage_reset_begin(void);
int storage_reset_pending(bool *pending);
/** Delete all Settings records; remove the reset marker last. Retryable. */
int storage_reset_finish(void);

#endif
