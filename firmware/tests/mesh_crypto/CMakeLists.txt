cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(meshlight_mesh_crypto_tests)

target_include_directories(app PRIVATE ../../src)
add_subdirectory(../../src/storage storage)
target_sources(app PRIVATE src/main.c)
