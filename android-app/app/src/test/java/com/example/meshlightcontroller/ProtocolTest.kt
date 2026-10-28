package com.example.meshlightcontroller

import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.core.protocol.MeshlightProtocol as Wire
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun goldenVectors() {
        assertArrayEquals(hex("0000"), Wire.encodeGet(0))
        assertArrayEquals(hex("FFFF"), Wire.encodeGet(LedTarget.All))
        assertArrayEquals(hex("0001"), Wire.encodeGet(256))
        assertArrayEquals(hex("00000101FF0000"), Wire.encodeSet(0, 1, LightingPatch(rgb = Rgb(255, 0, 0))))
        assertArrayEquals(hex("FFFF020F0000FF19"), Wire.encodeSet(LedTarget.All, 2, LightingPatch(Rgb(0, 0, 255), 25, true)))
        assertArrayEquals(hex("00000304"), Wire.encodeSet(0, 3, LightingPatch(power = false)))
        assertArrayEquals(hex("0000040C"), Wire.encodeSet(0, 4, LightingPatch(power = true)))
        assertArrayEquals(hex("FFFF050232"), Wire.encodeSet(LedTarget.All, 5, LightingPatch(intensity = 50)))
        assertEquals(Capabilities(1, 6, 0x3F, 100), Wire.decodeCapabilities(hex("0106003F0064")))
        val status = Wire.decodeStatus(hex("FFFF00010000FF19"), 123)
        assertEquals(LedState(Rgb(0, 0, 255), 25, true), status.observation!!.state)
        assertEquals(123L, status.observation.receivedAt)
    }
    @Test fun malformedAndErrorsNeverBecomeBlackState() {
        listOf("", "0000", "FFFF00010000FF", "FFFF00010000FF65", "FFFF00F10000FF19", "0000000300000032")
            .forEach { assertThrows(IllegalArgumentException::class.java) { Wire.decodeStatus(hex(it), 0) } }
        assertNull(Wire.decodeStatus(hex("FFFF010000000000"), 0).observation)
        assertThrows(IllegalArgumentException::class.java) { Wire.encodeSet(0, 0, LightingPatch()) }
        assertThrows(IllegalArgumentException::class.java) { Wire.encodeGet(339) }
        assertThrows(IllegalArgumentException::class.java) { Wire.decodeCapabilities(hex("0206003F0064")) }
        assertThrows(IllegalArgumentException::class.java) { Wire.decodeCapabilities(hex("0100003F0064")) }
        assertThrows(IllegalArgumentException::class.java) { Wire.decodeCapabilities(hex("0154013F0064")) }
    }
    @Test fun propertiesAndMixedStatus() {
        val state = LedState(Rgb(1, 2, 255), 40, false)
        assertEquals(LedState(Rgb(20, 30, 40), 40, false), LightingPatch(rgb = Rgb(20, 30, 40)).applyTo(state))
        val mixed = Wire.decodeStatus(hex("FFFF000FFF80FE32"), 0).observation!!
        assertTrue(mixed.mixedRgb && mixed.mixedPower && mixed.mixedIntensity)
        assertEquals(Rgb(255, 128, 254), mixed.state.rgb)
        assertFalse(mixed.matches(LightingPatch(intensity = 50)))
        assertEquals(Rgb(170, 187, 204), Rgb.fromHex("#AaBBcc"))
    }
    @Test fun levelMappingAndTransitions() {
        assertEquals(-32768, Wire.intensityToLevel(0))
        assertEquals(0, Wire.intensityToLevel(50))
        assertEquals(32767, Wire.intensityToLevel(100))
        for (intensity in 0..100) assertEquals(intensity, Wire.levelToIntensity(Wire.intensityToLevel(intensity)))
        assertEquals(1100L, Wire.transition(1001).actualMillis)
        assertEquals(7000L, Wire.transition(6201).actualMillis)
        assertNull(Wire.remainingMillis(0x3F))
        assertEquals(6000L, Wire.remainingMillis(0x46))
    }
}
