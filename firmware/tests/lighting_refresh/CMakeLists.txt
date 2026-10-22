cmake_minimum_required(VERSION 3.20.0)

# Share production Kconfig and the existing fake strip's binding and overlay.
set(KCONFIG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../Kconfig")
get_filename_component(LIGHTING_TEST_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../lighting" ABSOLUTE)
list(APPEND DTS_ROOT "${LIGHTING_TEST_ROOT}")
set(DTC_OVERLAY_FILE "${LIGHTING_TEST_ROOT}/app.overlay")
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(meshlight_lighting_refresh_tests)

target_include_directories(app PRIVATE ../../src)
target_sources(app PRIVATE ../../src/lighting.c src/main.c)
