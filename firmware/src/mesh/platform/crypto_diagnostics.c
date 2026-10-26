// SPDX-License-Identifier: MIT
/** Preserve PSA error codes otherwise collapsed to -EIO by the mesh stack. */
#include "crypto_diagnostics.h"
#include <zephyr/logging/log.h>

LOG_MODULE_REGISTER(mesh_crypto);

psa_status_t __wrap_psa_import_key(const psa_key_attributes_t *attributes,
				 const uint8_t *data, size_t length, mbedtls_svc_key_id_t *key)
{
	psa_status_t status = __real_psa_import_key(attributes, data, length, key);

	/* The stack already handles stale persistent IDs by deleting and retrying. */
	if (status != PSA_SUCCESS && status != PSA_ERROR_ALREADY_EXISTS) {
		LOG_ERR("PSA key import failed: status=%d id=0x%08x lifetime=0x%08x",
			(int)status, (unsigned int)MBEDTLS_SVC_KEY_ID_GET_KEY_ID(
				psa_get_key_id(attributes)),
			(unsigned int)psa_get_key_lifetime(attributes));
	}
	return status;
}
