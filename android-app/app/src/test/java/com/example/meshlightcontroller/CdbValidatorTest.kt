package com.example.meshlightcontroller

import com.example.meshlightcontroller.data.CdbValidator
import org.junit.Assert.*
import org.junit.Test

const val FixtureNetworkId="01234567-89AB-CDEF-0123-456789ABCDEF"
fun fixtureCdb(): String = """
{
  "meshUUID":"$FixtureNetworkId",
  "netKeys":[{"index":0,"key":"00000000000000000000000000000000","phase":0}],
  "appKeys":[{"index":0,"boundNetKey":0,"key":"11111111111111111111111111111111"}],
  "nodes":[{"UUID":"11111111-2222-3333-4444-555555555555","unicastAddress":"0002",
    "deviceKey":"22222222222222222222222222222222","elements":[{"index":0}]}],
  "groups":[{"name":"Room","address":"C000"}]
}
""".trimIndent()

class CdbValidatorTest {
    @Test fun acceptsCanonicalCredentialsAndAddressRanges() {
        assertEquals(FixtureNetworkId,CdbValidator.validate(fixtureCdb()))
    }
    @Test fun rejectsMissingCredentialsAndInvalidKeyBindingsBeforeImport() {
        listOf(
            fixtureCdb().replace("00000000000000000000000000000000","short"),
            fixtureCdb().replace("\"boundNetKey\":0","\"boundNetKey\":2"),
            fixtureCdb().replace("22222222222222222222222222222222",""),
            fixtureCdb().replace("\"phase\":0","\"phase\":3")
        ).forEach { assertThrows(IllegalArgumentException::class.java) {CdbValidator.validate(it)} }
    }
    @Test fun rejectsOverlappingNodesAndFixedGroupDestinations() {
        val duplicate=fixtureCdb()
            .replace("\"elements\":[{\"index\":0}]}]", "\"elements\":[{\"index\":0}]},{\"UUID\":\"99999999-2222-3333-4444-555555555555\",\"unicastAddress\":\"0002\",\"deviceKey\":\"33333333333333333333333333333333\",\"elements\":[{}]}]")
        assertThrows(IllegalArgumentException::class.java) {CdbValidator.validate(duplicate)}
        assertThrows(IllegalArgumentException::class.java) {CdbValidator.validate(fixtureCdb().replace("C000","FFFF"))}
    }
}
