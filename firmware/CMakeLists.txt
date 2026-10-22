cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})

project(meshlight)

# Keep staged per-LED state and gamma-corrected rendering behind the lighting API.
target_sources(app PRIVATE src/main.c src/lighting.c)
