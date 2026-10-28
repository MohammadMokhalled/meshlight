// SPDX-License-Identifier: MIT
#include "transaction.h"
#include <stddef.h>

struct mesh_transaction *mesh_transaction_get(struct mesh_transactions *cache,
	uint16_t src, uint16_t dst, uint16_t target, uint8_t tid, uint8_t family,
	int64_t now, bool *duplicate)
{
	struct mesh_transaction *oldest = &cache->entries[0];

	for (size_t i = 0; i < 16; i++) {
		struct mesh_transaction *entry = &cache->entries[i];

		if (entry->valid && now - entry->time < 6000 && entry->src == src &&
		    entry->dst == dst && entry->target == target && entry->tid == tid &&
		    entry->family == family) {
			*duplicate = true;
			return entry;
		}
		if (!entry->valid || entry->time < oldest->time) {
			oldest = entry;
		}
	}
	*oldest = (struct mesh_transaction){.valid = true, .time = now, .src = src,
		.dst = dst, .target = target, .tid = tid, .family = family};
	*duplicate = false;
	return oldest;
}
