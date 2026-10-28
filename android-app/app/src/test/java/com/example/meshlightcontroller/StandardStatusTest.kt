package com.example.meshlightcontroller

import com.example.meshlightcontroller.core.protocol.MeshlightProtocol as Protocol
import org.junit.Assert.*
import org.junit.Test

class StandardStatusTest {
    @Test fun shortStatusesDoNotInventTransitionTargets() {
        val power=Protocol.decodeOnOffStatus(byteArrayOf(1))
        assertTrue(power.present)
        assertNull(power.target)
        assertNull(power.remainingMillis)
        val level=Protocol.decodeLevelStatus(byteArrayOf(0,0x80.toByte()))
        assertEquals(-32768,level.present)
        assertNull(level.target)
    }
    @Test fun extendedStatusesDecodeSignedLevelsAndAllTimeResolutions() {
        val power=Protocol.decodeOnOffStatus(byteArrayOf(0,1,0x42))
        assertEquals(false,power.present)
        assertEquals(true,power.target)
        assertEquals(2000L,power.remainingMillis)
        val level=Protocol.decodeLevelStatus(byteArrayOf(0,0x80.toByte(),0xFF.toByte(),0x7F,0x81.toByte()))
        assertEquals(-32768,level.present)
        assertEquals(32767,level.target)
        assertEquals(10000L,level.remainingMillis)
        assertNull(Protocol.decodeLevelStatus(byteArrayOf(0,0,0,0,0x3F)).remainingMillis)
        assertEquals(600000L,Protocol.decodeOnOffStatus(byteArrayOf(1,0,0xC1.toByte())).remainingMillis)
    }
    @Test fun invalidLengthsAndPowerValuesAreRejected() {
        listOf(byteArrayOf(),byteArrayOf(1,1),byteArrayOf(2),byteArrayOf(1,2,0)).forEach {bytes->
            assertThrows(IllegalArgumentException::class.java) {Protocol.decodeOnOffStatus(bytes)}
        }
        listOf(byteArrayOf(0),byteArrayOf(0,0,0),byteArrayOf(0,0,0,0)).forEach {bytes->
            assertThrows(IllegalArgumentException::class.java) {Protocol.decodeLevelStatus(bytes)}
        }
    }
}
