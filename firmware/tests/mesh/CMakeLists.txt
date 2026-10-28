cmake_minimum_required(VERSION 3.20.0)
set(KCONFIG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../Kconfig")
list(APPEND DTS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../lighting")
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(meshlight_mesh_tests)
target_include_directories(app PRIVATE ../../src ../support)
add_subdirectory(../../src/lighting lighting)
target_sources(app PRIVATE ../support/fake_storage.c src/main.c
  ../../src/mesh/control.c ../../src/mesh/protocol.c ../../src/mesh/transaction.c
  ../../src/device_control/button_hold.c)
# Mesh public types contain PSA key IDs. These adapter tests only need the
# headers; the capturing transport does not enable a controller or crypto.
target_include_directories(app PRIVATE ${ZEPHYR_MBEDTLS_MODULE_DIR}/include)
target_compile_definitions(app PRIVATE CONFIG_MESHLIGHT_COMPANY_ID=65535 CONFIG_MESHLIGHT_MODEL_ID=1)
target_sources(app PRIVATE ../../src/mesh/models.c src/adapters.c)
