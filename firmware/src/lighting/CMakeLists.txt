# SPDX-License-Identifier: MIT

target_sources(app PRIVATE lighting.c lighting_settings.c)
target_sources_ifdef(CONFIG_MESHLIGHT_DEV_SHELL app PRIVATE lighting_shell.c)
