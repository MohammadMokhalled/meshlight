cmake_minimum_required(VERSION 3.20.0)
# Use the same lighting options as production, with automatic refresh disabled.
set(KCONFIG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../Kconfig")
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(meshlight_lighting_tests)

# Compile the production implementation against a test-only strip device.
target_include_directories(app PRIVATE ../../src ../support)
add_subdirectory(../../src/lighting lighting)
target_sources(app PRIVATE ../support/fake_storage.c src/main.c)
