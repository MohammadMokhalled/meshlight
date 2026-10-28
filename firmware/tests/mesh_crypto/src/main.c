// SPDX-License-Identifier: MIT
/** Real PSA and encrypted Settings storage, without a Bluetooth controller. */
#include "storage/storage.h"
#include <psa/crypto.h>
#include <zephyr/psa/key_ids.h>
#include <zephyr/ztest.h>

static psa_key_id_t held[32];
static size_t held_count;
static size_t persistent_count;

static void import_aes(bool persistent, bool raw)
{
	static const uint8_t bytes[16] = {1};
	psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
	psa_key_id_t key;
	psa_status_t status;

	psa_set_key_type(&attributes, PSA_KEY_TYPE_AES);
	psa_set_key_bits(&attributes, 128);
	psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_EXPORT |
		(raw ? 0 : PSA_KEY_USAGE_ENCRYPT | PSA_KEY_USAGE_DECRYPT));
	if (!raw) {
		psa_set_key_algorithm(&attributes,
			PSA_ALG_AEAD_WITH_AT_LEAST_THIS_LENGTH_TAG(PSA_ALG_CCM, 4));
	}
	if (persistent) {
		psa_set_key_lifetime(&attributes, PSA_KEY_LIFETIME_PERSISTENT);
		psa_set_key_id(&attributes, ZEPHYR_PSA_BT_MESH_KEY_ID_RANGE_BEGIN + persistent_count);
	}
	status = psa_import_key(&attributes, bytes, sizeof(bytes), &key);
	TC_PRINT("Key import: held=%u persistent=%u PSA status=%d\n",
	       (unsigned int)held_count, persistent, (int)status);
	zassert_equal(status, PSA_SUCCESS, "PSA key import failed (%d)", (int)status);
	held[held_count++] = key;
	if (persistent) {
		persistent_count++;
	}
	psa_reset_key_attributes(&attributes);
}

static void hold_provisioning_pair(void)
{
	static const uint8_t private_key[32] = {[31] = 1};
	psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
	psa_key_id_t key;

	/* Mesh retains its provisioning ECDH pair after completing provisioning. */
	psa_set_key_type(&attributes, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
	psa_set_key_bits(&attributes, 256);
	psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_DERIVE);
	psa_set_key_algorithm(&attributes, PSA_ALG_ECDH);
	zassert_equal(psa_import_key(&attributes, private_key, sizeof(private_key), &key),
		      PSA_SUCCESS);
	held[held_count++] = key;
	psa_reset_key_attributes(&attributes);

	/* Provisioning must also perform P-256 with the smaller nRF52832 heap. */
	uint8_t public_key[65], secret[32];
	size_t public_length, secret_length;

	zassert_equal(psa_export_public_key(key, public_key, sizeof(public_key), &public_length),
		      PSA_SUCCESS);
	zassert_equal(psa_raw_key_agreement(PSA_ALG_ECDH, key, public_key, public_length,
					   secret, sizeof(secret), &secret_length), PSA_SUCCESS);
	zassert_equal(secret_length, sizeof(secret));
	/* Private scalar 1 times its own public point yields that point's X coordinate. */
	zassert_mem_equal(secret, &public_key[1], sizeof(secret));
}

ZTEST(mesh_crypto, test_provisioned_node_accepts_both_appkeys)
{
	zassert_ok(storage_init());
	zassert_equal(psa_crypto_init(), PSA_SUCCESS);
	static const uint8_t metadata[16];

	zassert_ok(storage_store("bt/hash", metadata, sizeof(metadata)));
	hold_provisioning_pair();
	/* Device key, raw NetKey and five derived network keys for one subnet. */
	import_aes(true, false);
	import_aes(true, true);
	for (size_t i = 0; i < 5; i++) {
		import_aes(false, false);
	}
	/* Include the inline-sized records that the real stack writes before the
	 * phone installs an AppKey. ITS lookup must survive these adjacent entries.
	 */
	zassert_ok(storage_store("bt/mesh/NetKey/0", metadata, 9));
	zassert_ok(storage_store("bt/mesh/Net", metadata, 6));
	zassert_ok(storage_store("bt/mesh/IV", metadata, 5));
	zassert_ok(storage_store("bt/mesh/Seq", metadata, 3));
	zassert_ok(storage_store("bt/mesh/RPL/1", metadata, 4));
	/* Both configured AppKeys must import while the network keys remain live. */
	import_aes(true, false);
	import_aes(true, false);
	/* Exercise the separate AppKey configuration record as well as the
	 * encrypted ITS key. A successful import alone does not prove persistence.
	 */
	static const uint8_t appkey[11] = {0, 0, 0, 2, 0, 0, 0x20, 0, 0, 0, 0};
	uint8_t restored[sizeof(appkey)];
	size_t length;

	zassert_ok(storage_store("bt/mesh/AppKey/0", appkey, sizeof(appkey)));
	zassert_ok(storage_restore("bt/mesh/AppKey/0", restored, sizeof(restored), &length));
	zassert_equal(length, sizeof(appkey));
	zassert_mem_equal(restored, appkey, length);
}

ZTEST(mesh_crypto, test_both_subnets_and_key_refresh_fit)
{
	zassert_ok(storage_init());
	zassert_equal(psa_crypto_init(), PSA_SUCCESS);
	hold_provisioning_pair();
	/* Device key plus a device-key candidate, then both generations of each
	 * subnet: raw NetKey, encryption, privacy, beacon, identity, private beacon.
	 */
	import_aes(true, false);
	import_aes(true, false);
	for (size_t subnet = 0; subnet < 2; subnet++) {
		for (size_t generation = 0; generation < 2; generation++) {
			import_aes(true, true);
			for (size_t derived = 0; derived < 5; derived++) {
				import_aes(false, false);
			}
		}
	}
	/* Old and updated values of each of the two application keys. */
	for (size_t app = 0; app < 4; app++) {
		import_aes(true, false);
	}
}

static void after(void *fixture)
{
	ARG_UNUSED(fixture);
	while (held_count) {
		zassert_equal(psa_destroy_key(held[--held_count]), PSA_SUCCESS);
	}
	persistent_count = 0;
}

ZTEST_SUITE(mesh_crypto, NULL, NULL, NULL, after, NULL);
