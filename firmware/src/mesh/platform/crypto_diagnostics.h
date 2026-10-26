/* SPDX-License-Identifier: MIT */
#ifndef MESHLIGHT_CRYPTO_DIAGNOSTICS_H
#define MESHLIGHT_CRYPTO_DIAGNOSTICS_H

#include <psa/crypto.h>

/* GNU ld --wrap supplies __real_psa_import_key; the signature must match PSA. */
psa_status_t __real_psa_import_key(const psa_key_attributes_t *attributes,
				 const uint8_t *data, size_t length, mbedtls_svc_key_id_t *key);
psa_status_t __wrap_psa_import_key(const psa_key_attributes_t *attributes,
				 const uint8_t *data, size_t length, mbedtls_svc_key_id_t *key);

#endif
