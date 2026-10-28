/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_TRANSACTION_H_
#define MESHLIGHT_TRANSACTION_H_
#include <stdbool.h>
#include <stdint.h>

/** Private bounded cache. Callers serialize access. Expiry is not extended by retries. */
struct mesh_transaction {
	int64_t time;
	uint16_t src, dst, target;
	uint8_t tid, family;
	bool valid;
	int32_t baseline;
	int32_t delta;
};
struct mesh_transactions {
	struct mesh_transaction entries[16];
};
/** Find or allocate a transaction; baseline/delta are available for Level Delta Set. */
struct mesh_transaction *mesh_transaction_get(struct mesh_transactions *cache,
	uint16_t src, uint16_t dst, uint16_t target, uint8_t tid, uint8_t family,
	int64_t now, bool *duplicate);
#endif
