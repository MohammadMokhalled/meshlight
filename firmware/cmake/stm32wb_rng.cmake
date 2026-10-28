# SPDX-License-Identifier: MIT
# NCS 3.3.0 touches RNG before taking Sem0. AN5289 section 4.3 requires
# ownership before clock/register access; CPU2 may have stopped CLK48.
# Generate a board-local correction, leaving the SDK checkout untouched.
set(rng_original "${ZEPHYR_BASE}/drivers/entropy/entropy_stm32.c")
file(READ "${rng_original}" rng_source)
string(REPLACE "\r\n" "\n" rng_source "${rng_source}")
set(rng_acquire_old [=[static void acquire_rng(void)
{
	entropy_stm32_resume();
#if defined(CONFIG_SOC_SERIES_STM32WBX) || defined(CONFIG_STM32H7_DUAL_CORE)
	/* Lock the RNG to prevent concurrent access */
	z_stm32_hsem_lock(CFG_HW_RNG_SEMID, HSEM_LOCK_WAIT_FOREVER);
	/* RNG configuration could have been changed by the other core */
	configure_rng();
#endif /* CONFIG_SOC_SERIES_STM32WBX || CONFIG_STM32H7_DUAL_CORE */
}]=])
set(rng_acquire_new [=[static void acquire_rng(void)
{
	/* AN5289: Sem0 must cover the clock setup as well as RNG register access. */
	z_stm32_hsem_lock(CFG_HW_RNG_SEMID, HSEM_LOCK_WAIT_FOREVER);
	/* CPU2 may stop CLK48 when releasing RNG. Restore the WB55 board's
	 * HSI48 source on every acquisition, under Sem0 and the RCC clock lock.
	 */
	z_stm32_hsem_lock(CFG_HW_CLK48_CONFIG_SEMID, HSEM_LOCK_WAIT_FOREVER);
	LL_RCC_HSI48_Enable();
	while (!LL_RCC_HSI48_IsReady()) {
	}
	LL_RCC_SetCLK48ClockSource(LL_RCC_CLK48_CLKSOURCE_HSI48);
	LL_RCC_SetRNGClockSource(LL_RCC_RNG_CLKSOURCE_CLK48);
	z_stm32_hsem_unlock(CFG_HW_CLK48_CONFIG_SEMID);
	entropy_stm32_resume();
	configure_rng();
}]=])

string(FIND "${rng_source}" "${rng_acquire_old}" rng_match)
if(rng_match EQUAL -1)
  message(FATAL_ERROR "STM32WB RNG fix: unexpected SDK driver; review the backport for this SDK")
endif()
string(REPLACE "${rng_acquire_old}" "${rng_acquire_new}" rng_source "${rng_source}")
set(rng_generated "${CMAKE_CURRENT_BINARY_DIR}/entropy_stm32_wb55.c")
file(CONFIGURE OUTPUT "${rng_generated}" CONTENT "${rng_source}" @ONLY)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${rng_original}")

get_target_property(rng_sources drivers__entropy SOURCES)
set(rng_replacements 0)
set(rng_corrected_sources)
foreach(rng_item IN LISTS rng_sources)
  if(rng_item MATCHES "(^|/)entropy_stm32[.]c$")
    list(APPEND rng_corrected_sources "${rng_generated}")
    math(EXPR rng_replacements "${rng_replacements} + 1")
  else()
    list(APPEND rng_corrected_sources "${rng_item}")
  endif()
endforeach()
if(NOT rng_replacements EQUAL 1)
  message(FATAL_ERROR "STM32WB RNG fix: expected exactly one entropy_stm32.c driver source")
endif()
set_property(TARGET drivers__entropy PROPERTY SOURCES "${rng_corrected_sources}")
target_include_directories(drivers__entropy PRIVATE "${ZEPHYR_BASE}/drivers/entropy")
message(STATUS "STM32WB RNG fix: Sem0 before clock/register access; restore HSI48 on acquire")
