// SPDX-License-Identifier: MIT
/** Shared control state. Flash and SPI never run in the Bluetooth receive path. */
#include "control.h"
#include "transaction.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/sys/util.h>

struct transition {
	bool active;
	int32_t from, to;
	int64_t start, duration;
};

K_MUTEX_DEFINE(control_mutex);
static struct mesh_transactions transactions;
static struct transition level_transition, power_transition;
static uint32_t own_revision;
static bool stopped, notify;
static void (*publish_changed)(void);
static void transition_worker(struct k_work *work);
K_WORK_DELAYABLE_DEFINE(transition_work, transition_worker);

static int32_t intensity_to_level(uint8_t intensity)
{
	return ((uint32_t)intensity * 65535U + 50U) / 100U - 32768;
}

static uint8_t level_to_intensity(int32_t level)
{
	return ((uint32_t)(level + 32768) * 100U + 32767U) / 65535U;
}

uint8_t mesh_control_remaining(int64_t ms)
{
	static const int32_t units[] = {100, 1000, 10000, 600000};

	if (ms <= 0) {
		return 0;
	}
	for (size_t i = 0; i < ARRAY_SIZE(units); i++) {
		int64_t steps = DIV_ROUND_UP(ms, units[i]);

		if (steps <= 62) {
			return (i << 6) | steps;
		}
	}
	return 0x3f; /* Unknown remaining time (e.g. a very slow Move). */
}

static void remember_revision(void)
{
	struct lighting_status status;

	if (lighting_get_status(LIGHTING_TARGET_ALL, &status) == 0) {
		own_revision = status.revision;
	}
}

/* All engine edits, including intermediate frames, run under control_mutex. */
static int write_all(uint8_t fields, int32_t value, bool transient)
{
	struct lighting_update update = {.fields = fields |
		(transient ? LIGHTING_UPDATE_TRANSIENT : 0)};

	if (fields == LIGHTING_UPDATE_INTENSITY) {
		update.intensity = level_to_intensity(value);
	} else {
		update.on = value;
	}
	int err = lighting_update(LIGHTING_TARGET_ALL, &update);

	remember_revision();
	return err;
}

static void advance(int64_t now)
{
	struct lighting_status status;
	int err = lighting_get_status(LIGHTING_TARGET_ALL, &status);

	if (err) {
		return;
	}
	if (status.revision != own_revision) {
		/* A local shell edit supersedes any in-flight mesh transition. */
		level_transition.active = power_transition.active = false;
		own_revision = status.revision;
	}
	if (power_transition.active && now >= power_transition.start) {
		/* On takes effect at the start; Off takes effect at the end. */
		if (power_transition.to || now >= power_transition.start + power_transition.duration) {
			(void)write_all(LIGHTING_UPDATE_POWER, power_transition.to, false);
		}
		if (now >= power_transition.start + power_transition.duration) {
			power_transition.active = false;
			notify = true;
		}
	}
	if (level_transition.active && now >= level_transition.start) {
		int64_t elapsed = MIN(now - level_transition.start, level_transition.duration);
		bool done = elapsed == level_transition.duration;
		int32_t value = level_transition.to;

		if (!done) {
			value = level_transition.from +
				(int64_t)(level_transition.to - level_transition.from) * elapsed /
				level_transition.duration;
		}
		(void)write_all(LIGHTING_UPDATE_INTENSITY, value, !done);
		if (done) {
			level_transition.active = false;
			notify = true;
		}
	}
}

static void schedule(void)
{
	if (level_transition.active || power_transition.active || notify) {
		(void)k_work_reschedule(&transition_work, K_MSEC(20));
	}
}

static void transition_worker(struct k_work *work)
{
	bool changed;

	ARG_UNUSED(work);
	k_mutex_lock(&control_mutex, K_FOREVER);
	if (stopped) {
		k_mutex_unlock(&control_mutex);
		return;
	}
	advance(k_uptime_get());
	changed = notify;
	notify = false;
	schedule();
	k_mutex_unlock(&control_mutex);
	if (changed && publish_changed) {
		publish_changed();
	}
}

void mesh_control_init(void (*publish)(void))
{
	k_mutex_lock(&control_mutex, K_FOREVER);
	publish_changed = publish;
	stopped = false;
	level_transition.active = power_transition.active = notify = false;
	memset(&transactions, 0, sizeof(transactions));
	remember_revision();
	k_mutex_unlock(&control_mutex);
}

void mesh_control_stop(void)
{
	struct k_work_sync sync;

	k_mutex_lock(&control_mutex, K_FOREVER);
	stopped = true;
	level_transition.active = power_transition.active = false;
	k_mutex_unlock(&control_mutex);
	(void)k_work_cancel_delayable_sync(&transition_work, &sync);
}

static int status_locked(bool level, uint8_t response[5], size_t *length)
{
	struct lighting_status status;
	struct transition *transition = level ? &level_transition : &power_transition;
	int err = lighting_get_status(LIGHTING_TARGET_ALL, &status);

	if (err) {
		return err;
	}
	if (level) {
		sys_put_le16((uint16_t)intensity_to_level(status.intensity), response);
		*length = 2;
		if (transition->active) {
			sys_put_le16((uint16_t)transition->to, response + 2);
			*length = 4;
		}
	} else {
		response[0] = status.on;
		*length = 1;
		if (transition->active) {
			response[1] = transition->to;
			*length = 2;
		}
	}
	if (transition->active) {
		response[(*length)++] = mesh_control_remaining(
			transition->start + transition->duration - k_uptime_get());
	}
	return 0;
}

int mesh_control_status(bool level, uint8_t response[5], size_t *length)
{
	int err;

	k_mutex_lock(&control_mutex, K_FOREVER);
	if (stopped) {
		err = -ESHUTDOWN;
	} else {
		advance(k_uptime_get());
		err = status_locked(level, response, length);
		schedule();
	}
	k_mutex_unlock(&control_mutex);
	return err;
}

int mesh_control_standard(enum mesh_control_op op, uint16_t src, uint16_t dst,
	const uint8_t *data, size_t length, uint8_t response[5], size_t *response_length)
{
	static const int32_t units[] = {100, 1000, 10000, 600000};
	bool level = op != MESH_ONOFF_GET && op != MESH_ONOFF_SET;
	bool get = op == MESH_ONOFF_GET || op == MESH_LEVEL_GET;
	size_t minimum = op == MESH_ONOFF_SET ? 2 : op == MESH_LEVEL_DELTA ? 5 : 3;
	int64_t duration = 0, delay = 0, now = k_uptime_get();
	int32_t value = 0;
	struct lighting_status status;
	struct mesh_transaction *transaction;
	struct transition *transition = level ? &level_transition : &power_transition;
	bool duplicate;
	int err = 0;

	if (get) {
		return length ? -EINVAL : mesh_control_status(level, response, response_length);
	}
	if (!data || (length != minimum && length != minimum + 2) ||
	    (!level && data[0] > 1)) {
		return -EINVAL;
	}
	if (length > minimum) {
		uint8_t tt = data[minimum];

		if ((tt & 0x3f) == 0x3f) {
			return -EINVAL;
		}
		duration = (tt & 0x3f) * (int64_t)units[tt >> 6];
		delay = data[minimum + 1] * 5;
	}
	value = !level ? data[0] : op == MESH_LEVEL_DELTA ?
		(int32_t)sys_get_le32(data) : (int16_t)sys_get_le16(data);
	k_mutex_lock(&control_mutex, K_FOREVER);
	if (stopped) {
		err = -ESHUTDOWN;
		goto out;
	}
	advance(now);
	err = lighting_get_status(LIGHTING_TARGET_ALL, &status);
	if (err) {
		goto out;
	}
	transaction = mesh_transaction_get(&transactions, src, dst, LIGHTING_TARGET_ALL,
		data[minimum - 1], op, now, &duplicate);
	if (op == MESH_LEVEL_DELTA) {
		if (!duplicate) {
			transaction->baseline = intensity_to_level(status.intensity);
		} else if (transaction->delta == value) {
			goto respond;
		}
		transaction->delta = value;
		value = CLAMP((int64_t)transaction->baseline + value, -32768, 32767);
	} else if (duplicate) {
		goto respond;
	}
	if (op == MESH_LEVEL_MOVE) {
		if (value == 0) {
			transition->active = false;
			err = write_all(LIGHTING_UPDATE_INTENSITY,
				intensity_to_level(status.intensity), false);
			goto respond;
		}
		/* Without a nonzero rate interval a Move must not change the state. */
		if (!duration) {
			goto respond;
		}
		int32_t bound = value > 0 ? 32767 : -32768;

		duration = DIV_ROUND_UP(llabs((int64_t)bound -
			intensity_to_level(status.intensity)) * duration, abs(value));
		value = bound;
	}
	*transition = (struct transition){.active = duration || delay,
		.from = level ? intensity_to_level(status.intensity) : status.on,
		.to = value, .start = now + delay, .duration = duration};
	if (transition->active) {
		advance(now);
		schedule();
	} else {
		err = write_all(level ? LIGHTING_UPDATE_INTENSITY : LIGHTING_UPDATE_POWER,
			value, false);
	}
respond:
	if (!err) {
		err = status_locked(level, response, response_length);
	}
out:
	k_mutex_unlock(&control_mutex);
	return err;
}

int mesh_control_vendor(uint16_t src, uint16_t dst, const uint8_t *data, size_t length,
			uint16_t *target)
{
	struct lighting_update update;
	uint8_t tid;
	bool duplicate;
	int err = mesh_protocol_decode(data, length, target, &tid, &update);

	if (err || (*target != LIGHTING_TARGET_ALL && *target >= lighting_get_led_count())) {
		return err ? err : -ERANGE;
	}
	k_mutex_lock(&control_mutex, K_FOREVER);
	if (stopped) {
		err = -ESHUTDOWN;
		goto out;
	}
	(void)mesh_transaction_get(&transactions, src, dst, *target, tid, 0xff,
		k_uptime_get(), &duplicate);
	if (duplicate) {
		goto out;
	}
	advance(k_uptime_get());
	if ((update.fields & LIGHTING_UPDATE_INTENSITY) && level_transition.active) {
		struct lighting_status status;

		/* Preserve the displayed intermediate level for LEDs outside the target. */
		if (!lighting_get_status(LIGHTING_TARGET_ALL, &status)) {
			(void)write_all(LIGHTING_UPDATE_INTENSITY,
				intensity_to_level(status.intensity), false);
		}
		level_transition.active = false;
	}
	if (update.fields & LIGHTING_UPDATE_POWER) {
		power_transition.active = false;
	}
	err = lighting_update(*target, &update);
	remember_revision();
out:
	k_mutex_unlock(&control_mutex);
	return err;
}
