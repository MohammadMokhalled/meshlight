package com.example.meshlightcontroller.core.protocol

import com.example.meshlightcontroller.core.model.*

object MeshlightProtocol {
    const val CapabilitiesGet = 0xC0
    const val CapabilitiesStatus = 0xC1
    const val StateGet = 0xC2
    const val StateSet = 0xC3
    const val StateSetUnacknowledged = 0xC4
    const val StateStatus = 0xC5

    data class Status(val target: Int, val result: Int, val observation: Observation?)

    fun encodeGet(target: Int): ByteArray {
        require(LedTarget.isValid(target))
        return byteArrayOf(target.toByte(), (target ushr 8).toByte())
    }

    fun encodeSet(target: Int, tid: Int, patch: LightingPatch): ByteArray {
        require(LedTarget.isValid(target) && tid in 0..255 && !patch.isEmpty)
        var flags = 0
        if (patch.rgb != null) flags = flags or 1
        if (patch.intensity != null) flags = flags or 2
        if (patch.power != null) flags = flags or 4 or if (patch.power) 8 else 0
        val bytes = mutableListOf(target.toByte(), (target ushr 8).toByte(), tid.toByte(), flags.toByte())
        patch.rgb?.let { bytes += listOf(it.red.toByte(), it.green.toByte(), it.blue.toByte()) }
        patch.intensity?.let { bytes += it.toByte() }
        return bytes.toByteArray()
    }

    fun decodeCapabilities(bytes: ByteArray): Capabilities {
        require(bytes.size == 6) { "Malformed capabilities length" }
        val value = Capabilities(u8(bytes[0]), u16(bytes, 1), u16(bytes, 3), u8(bytes[5]))
        require(value.version == 1) { "Unsupported Meshlight protocol version" }
        require(value.ledCount in 1..339 && value.maxIntensity in 1..100) { "Invalid capabilities" }
        require(value.supportsAll) { "All-LED target is required" }
        return value
    }

    fun decodeStatus(bytes: ByteArray, timestamp: Long): Status {
        require(bytes.size == 8) { "Malformed state status length" }
        val target = u16(bytes, 0)
        require(LedTarget.isValid(target))
        val result = u8(bytes[2])
        require(result in 0..3)
        if (result != 0) return Status(target, result, null)
        val flags = u8(bytes[3])
        require(flags and 0xF0 == 0 && u8(bytes[7]) in 0..100)
        require(target == LedTarget.All || flags and 0x0E == 0)
        return Status(target, 0, Observation(
            LedState(Rgb(u8(bytes[4]), u8(bytes[5]), u8(bytes[6])), u8(bytes[7]), flags and 1 != 0),
            flags and 2 != 0, flags and 4 != 0, flags and 8 != 0, timestamp))
    }

    fun intensityToLevel(intensity: Int): Int {
        require(intensity in 0..100)
        return (intensity * 65535 + 50) / 100 - 32768
    }

    fun levelToIntensity(level: Int): Int {
        require(level in -32768..32767)
        return ((level + 32768) * 100 + 32767) / 65535
    }

    data class Transition(val steps: Int, val resolution: Int, val actualMillis: Long) {
        val encoded: Int get() = (resolution shl 6) or steps
    }

    fun transition(millis: Long): Transition {
        require(millis in 0..37_200_000L)
        val units = longArrayOf(100, 1000, 10_000, 600_000)
        for ((resolution, unit) in units.withIndex()) {
            val steps = (millis + unit - 1) / unit
            if (steps <= 62) return Transition(steps.toInt(), resolution, steps * unit)
        }
        error("Transition is too long")
    }

    fun remainingMillis(encoded: Int): Long? {
        require(encoded in 0..255)
        if (encoded and 0x3F == 0x3F) return null
        return (encoded and 0x3F) * longArrayOf(100, 1000, 10_000, 600_000)[encoded ushr 6]
    }

    data class OnOffStatus(val present: Boolean, val target: Boolean? = null, val remainingMillis: Long? = null)
    data class LevelStatus(val present: Int, val target: Int? = null, val remainingMillis: Long? = null)

    fun decodeOnOffStatus(bytes: ByteArray): OnOffStatus {
        require(bytes.size == 1 || bytes.size == 3) { "Malformed OnOff status length" }
        require(u8(bytes[0]) in 0..1)
        if (bytes.size == 1) return OnOffStatus(bytes[0].toInt() == 1)
        require(u8(bytes[1]) in 0..1)
        return OnOffStatus(bytes[0].toInt() == 1, bytes[1].toInt() == 1, remainingMillis(u8(bytes[2])))
    }

    fun decodeLevelStatus(bytes: ByteArray): LevelStatus {
        require(bytes.size == 2 || bytes.size == 5) { "Malformed Level status length" }
        val present = u16(bytes, 0).toShort().toInt()
        if (bytes.size == 2) return LevelStatus(present)
        return LevelStatus(present, u16(bytes, 2).toShort().toInt(), remainingMillis(u8(bytes[4])))
    }

    private fun u8(value: Byte) = value.toInt() and 0xFF
    private fun u16(bytes: ByteArray, index: Int) = u8(bytes[index]) or (u8(bytes[index + 1]) shl 8)
}
