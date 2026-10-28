/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_MESH_H_
#define MESHLIGHT_MESH_H_

/**
 * Start Bluetooth, mesh, and Settings restoration. Call once from thread context
 * after lighting and reset recovery. Never provision automatically with test keys.
 * Returns a negative error on failure; local lighting and reset remain available.
 */
int mesh_init(void);
/** Stop accepting requests and quiesce the radio before rebooting for reset. */
int mesh_stop(void);

#endif
