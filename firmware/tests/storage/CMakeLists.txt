cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(meshlight_storage_tests)
target_include_directories(app PRIVATE ../../src)
# Only the fault-injection test needs the SDK's ATE layout; production uses
# public Settings APIs exclusively.
target_include_directories(app PRIVATE ${ZEPHYR_BASE}/subsys/fs/zms)
add_subdirectory(../../src/storage storage)
target_sources(app PRIVATE src/main.c)
