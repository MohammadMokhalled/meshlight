// SPDX-License-Identifier: MIT

#include "lighting.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <zephyr/shell/shell.h>

/* Deliberately accept decimal digits only, including for a zero-based index. */
static int parse_number(const char *text, unsigned long maximum, unsigned long *value)
{
	char *end;
	unsigned long parsed;

	if (text[0] == '\0' || strspn(text, "0123456789") != strlen(text)) {
		return -EINVAL;
	}
	errno = 0;
	parsed = strtoul(text, &end, 10);
	if (errno == ERANGE || *end != '\0' || parsed > maximum) {
		return -ERANGE;
	}
	*value = parsed;
	return 0;
}

static int parse_target(const char *text, bool *all, unsigned long *index)
{
	*all = strcmp(text, "all") == 0;
	return *all ? 0 : parse_number(text, lighting_get_led_count() - 1, index);
}

static int parse_color(char **arguments, struct led_rgb *rgb)
{
	unsigned long values[3];
	int err;

	for (size_t i = 0; i < ARRAY_SIZE(values); i++) {
		err = parse_number(arguments[i], UINT8_MAX, &values[i]);
		if (err != 0) {
			return err;
		}
	}
	*rgb = (struct led_rgb){.r = values[0], .g = values[1], .b = values[2]};
	return 0;
}

static int finish_change(const struct shell *shell, int err)
{
	if (err == 0 && !IS_ENABLED(CONFIG_LIGHTING_AUTO_REFRESH)) {
		err = lighting_apply();
	}
	if (err != 0) {
		shell_error(shell, "Lighting operation failed (%d)", err);
	}
	return err;
}

static int cmd_show(const struct shell *shell, size_t argc, char **argv)
{
	bool on;
	int err = lighting_get_on(&on);

	ARG_UNUSED(argc);
	ARG_UNUSED(argv);
	if (err != 0) {
		return finish_change(shell, err);
	}
	shell_print(shell, "Power %s; %zu LEDs (requested state)",
		    on ? "on" : "off", lighting_get_led_count());
	for (size_t i = 0; i < lighting_get_led_count(); i++) {
		struct lighting_led_state state;

		err = lighting_get_led(i, &state);
		if (err != 0) {
			return finish_change(shell, err);
		}
		shell_print(shell, "%zu: RGB %u %u %u; intensity %u%%", i,
			    state.rgb.r, state.rgb.g, state.rgb.b, state.intensity);
	}
	return 0;
}

static int cmd_set(const struct shell *shell, size_t argc, char **argv)
{
	struct lighting_led_state state;
	unsigned long intensity;
	unsigned long index = 0;
	bool all;
	int err;

	ARG_UNUSED(argc);
	err = parse_target(argv[1], &all, &index);
	if (err == 0) {
		err = parse_color(&argv[2], &state.rgb);
	}
	if (err == 0) {
		err = parse_number(argv[5], LIGHTING_INTENSITY_MAX, &intensity);
	}
	if (err != 0) {
		shell_error(shell, "Use all or a valid index, RGB 0..255, intensity 0..100");
		return err;
	}
	state.intensity = intensity;
	err = all ? lighting_set_all(&state) : lighting_set_led(index, &state);
	return finish_change(shell, err);
}

static int cmd_color(const struct shell *shell, size_t argc, char **argv)
{
	struct led_rgb rgb;
	unsigned long index = 0;
	bool all;
	int err;

	ARG_UNUSED(argc);
	err = parse_target(argv[1], &all, &index);
	if (err == 0) {
		err = parse_color(&argv[2], &rgb);
	}
	if (err != 0) {
		shell_error(shell, "Use all or a valid index, RGB 0..255");
		return err;
	}
	err = all ? lighting_set_all_color(&rgb) : lighting_set_led_color(index, &rgb);
	return finish_change(shell, err);
}

static int cmd_intensity(const struct shell *shell, size_t argc, char **argv)
{
	unsigned long intensity;
	unsigned long index = 0;
	bool all;
	int err;

	ARG_UNUSED(argc);
	err = parse_target(argv[1], &all, &index);
	if (err == 0) {
		err = parse_number(argv[2], LIGHTING_INTENSITY_MAX, &intensity);
	}
	if (err != 0) {
		shell_error(shell, "Use all or a valid index, intensity 0..100");
		return err;
	}
	err = all ? lighting_set_all_intensity(intensity) :
		    lighting_set_led_intensity(index, intensity);
	return finish_change(shell, err);
}

static int cmd_power(const struct shell *shell, size_t argc, char **argv)
{
	ARG_UNUSED(argc);
	if (strcmp(argv[1], "on") != 0 && strcmp(argv[1], "off") != 0) {
		shell_error(shell, "Use on or off");
		return -EINVAL;
	}
	return finish_change(shell, lighting_set_on(strcmp(argv[1], "on") == 0));
}

SHELL_STATIC_SUBCMD_SET_CREATE(lighting_commands,
	SHELL_CMD_ARG(show, NULL, "Show requested lighting state", cmd_show, 1, 0),
	SHELL_CMD_ARG(set, NULL, "<all|index> <r> <g> <b> <intensity>", cmd_set, 6, 0),
	SHELL_CMD_ARG(color, NULL, "<all|index> <r> <g> <b> (preserve intensity)",
		      cmd_color, 5, 0),
	SHELL_CMD_ARG(intensity, NULL, "<all|index> <0..100> (preserve color)",
		      cmd_intensity, 3, 0),
	SHELL_CMD_ARG(power, NULL, "<on|off>", cmd_power, 2, 0),
	SHELL_SUBCMD_SET_END
);
SHELL_CMD_REGISTER(lighting, &lighting_commands, "Lighting control (automatically saved)", NULL);
