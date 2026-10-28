# SPDX-License-Identifier: MIT
# Preserve transport progress when GATT wins the start-callback race and CPU2
# subsequently rejects advertising. Generate the correction outside the SDK.
set(proxy_tx_original "${ZEPHYR_BASE}/subsys/bluetooth/mesh/adv_ext.c")
file(READ "${proxy_tx_original}" proxy_tx_source)
string(REPLACE "\r\n" "\n" proxy_tx_source "${proxy_tx_source}")
set(proxy_tx_old [=[	bt_mesh_adv_send_start(duration, err, &adv->ctx);

	return err;
}]=])
set(proxy_tx_new [=[	bt_mesh_adv_send_start(duration, err, &adv->ctx);

	/* GATT may already have reported a successful start for this packet.
	 * Then send_start() suppresses the advertising failure callback, and
	 * no advertising-complete event will arrive. Complete the failed
	 * bearer with its actual error so segmented transport can progress.
	 * A fresh failed start clears ctx.cb and already schedules a retry,
	 * so send_end() is a no-op in that case.
	 */
	if (err) {
		bt_mesh_adv_send_end(err, &adv->ctx);
	}

	return err;
}]=])
string(FIND "${proxy_tx_source}" "${proxy_tx_old}" proxy_tx_match)
if(proxy_tx_match EQUAL -1)
  message(FATAL_ERROR "STM32WB proxy TX fix: unexpected SDK advertiser; review the backport")
endif()
string(REPLACE "${proxy_tx_old}" "${proxy_tx_new}" proxy_tx_source "${proxy_tx_source}")
set(proxy_tx_generated "${CMAKE_CURRENT_BINARY_DIR}/adv_ext_wb55.c")
file(CONFIGURE OUTPUT "${proxy_tx_generated}" CONTENT "${proxy_tx_source}" @ONLY NEWLINE_STYLE LF)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${proxy_tx_original}")

get_target_property(proxy_tx_sources subsys__bluetooth__mesh SOURCES)
set(proxy_tx_replacements 0)
set(proxy_tx_corrected_sources)
foreach(proxy_tx_item IN LISTS proxy_tx_sources)
  if(proxy_tx_item MATCHES "(^|/)adv_ext[.]c$")
    list(APPEND proxy_tx_corrected_sources "${proxy_tx_generated}")
    math(EXPR proxy_tx_replacements "${proxy_tx_replacements} + 1")
  else()
    list(APPEND proxy_tx_corrected_sources "${proxy_tx_item}")
  endif()
endforeach()
if(NOT proxy_tx_replacements EQUAL 1)
  message(FATAL_ERROR "STM32WB proxy TX fix: expected exactly one adv_ext.c source")
endif()
set_property(TARGET subsys__bluetooth__mesh PROPERTY SOURCES "${proxy_tx_corrected_sources}")
target_include_directories(subsys__bluetooth__mesh PRIVATE
  "${ZEPHYR_BASE}/subsys/bluetooth/mesh")
message(STATUS "STM32WB proxy TX fix: finish failed advertising after a GATT start")
