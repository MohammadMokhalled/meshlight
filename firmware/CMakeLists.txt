cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})

project(meshlight)

zephyr_get(MESHLIGHT_VERSION)
zephyr_get(MESHLIGHT_GIT_SHA)
set(MESHLIGHT_VERSION "${MESHLIGHT_VERSION}" CACHE STRING "Resolved firmware SemVer (CI override)")
set(MESHLIGHT_GIT_SHA "${MESHLIGHT_GIT_SHA}" CACHE STRING "Firmware source revision (CI override)")
find_package(Python3 REQUIRED COMPONENTS Interpreter)
find_package(Git REQUIRED)
if(NOT MESHLIGHT_VERSION)
  # Reconfigure the generated header when commits, refs, tags, or the resolver change.
  execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse --absolute-git-dir
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    OUTPUT_VARIABLE meshlight_git_dir OUTPUT_STRIP_TRAILING_WHITESPACE
    COMMAND_ERROR_IS_FATAL ANY)
  execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse --path-format=absolute --git-common-dir
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    OUTPUT_VARIABLE meshlight_git_common OUTPUT_STRIP_TRAILING_WHITESPACE
    COMMAND_ERROR_IS_FATAL ANY)
  file(GLOB_RECURSE meshlight_git_refs CONFIGURE_DEPENDS
    "${meshlight_git_common}/refs/heads/*" "${meshlight_git_common}/refs/tags/*")
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/../VERSION"
    "${CMAKE_CURRENT_SOURCE_DIR}/../scripts/version.py"
    "${meshlight_git_dir}/HEAD" ${meshlight_git_refs})
  if(EXISTS "${meshlight_git_common}/packed-refs")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
      "${meshlight_git_common}/packed-refs")
  endif()
  execute_process(
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/../scripts/version.py --format version
    OUTPUT_VARIABLE MESHLIGHT_VERSION OUTPUT_STRIP_TRAILING_WHITESPACE
    COMMAND_ERROR_IS_FATAL ANY)
endif()
if(NOT MESHLIGHT_VERSION MATCHES "^[0-9]+\\.[0-9]+\\.[0-9]+[-+a-zA-Z0-9.]*$")
  message(FATAL_ERROR "Invalid MESHLIGHT_VERSION: ${MESHLIGHT_VERSION}")
endif()
if(NOT MESHLIGHT_GIT_SHA)
  execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse HEAD
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    OUTPUT_VARIABLE MESHLIGHT_GIT_SHA OUTPUT_STRIP_TRAILING_WHITESPACE
    COMMAND_ERROR_IS_FATAL ANY)
endif()
if(NOT MESHLIGHT_GIT_SHA MATCHES "^[0-9a-f]+$")
  message(FATAL_ERROR "Invalid MESHLIGHT_GIT_SHA: ${MESHLIGHT_GIT_SHA}")
endif()
configure_file(cmake/meshlight_version.h.in generated/meshlight_version.h @ONLY)
target_include_directories(app PRIVATE src ${CMAKE_CURRENT_BINARY_DIR}/generated)
message(STATUS "Meshlight version: ${MESHLIGHT_VERSION} (${MESHLIGHT_GIT_SHA})")

target_sources(app PRIVATE src/main.c)
add_subdirectory(src/storage)
add_subdirectory(src/lighting)
if(CONFIG_MESHLIGHT_MESH)
  add_subdirectory(src/mesh)
  add_subdirectory(src/device_control)
endif()
