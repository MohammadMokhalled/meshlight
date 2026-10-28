package com.example.meshlightcontroller.core.model

data class Rgb(val red: Int, val green: Int, val blue: Int) {
    init { require(listOf(red, green, blue).all { it in 0..255 }) }
    val hex: String get() = "%02X%02X%02X".format(red, green, blue)
    companion object {
        val White = Rgb(255, 255, 255)
        fun fromHex(value: String): Rgb {
            val text = value.trim().removePrefix("#")
            require(text.length == 6 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
            return Rgb(text.substring(0, 2).toInt(16), text.substring(2, 4).toInt(16), text.substring(4, 6).toInt(16))
        }
    }
}

data class LightingPatch(val rgb: Rgb? = null, val intensity: Int? = null, val power: Boolean? = null) {
    init { require(intensity == null || intensity in 0..100) }
    val isEmpty: Boolean get() = rgb == null && intensity == null && power == null
    fun merge(newer: LightingPatch) = LightingPatch(newer.rgb ?: rgb, newer.intensity ?: intensity, newer.power ?: power)
    fun applyTo(state: LedState) = LedState(rgb ?: state.rgb, intensity ?: state.intensity, power ?: state.power)
    fun confirmedBy(other: LightingPatch) = LightingPatch(
        rgb.takeUnless { it == other.rgb }, intensity.takeUnless { it == other.intensity }, power.takeUnless { it == other.power })
    fun without(other: LightingPatch) = LightingPatch(
        rgb.takeIf { other.rgb == null }, intensity.takeIf { other.intensity == null }, power.takeIf { other.power == null })
}

data class LedState(val rgb: Rgb, val intensity: Int, val power: Boolean) {
    init { require(intensity in 0..100) }
}

data class Observation(
    val state: LedState,
    val mixedRgb: Boolean = false,
    val mixedIntensity: Boolean = false,
    val mixedPower: Boolean = false,
    val receivedAt: Long = 0
) {
    fun matches(patch: LightingPatch) =
        (patch.rgb == null || (!mixedRgb && patch.rgb == state.rgb)) &&
        (patch.intensity == null || (!mixedIntensity && patch.intensity == state.intensity)) &&
        (patch.power == null || (!mixedPower && patch.power == state.power))
}

data class Capabilities(val version: Int, val ledCount: Int, val features: Int, val maxIntensity: Int) {
    val supportsRgb get() = features and 1 != 0
    val supportsIntensity get() = features and 2 != 0
    val supportsPower get() = features and 4 != 0
    val supportsIndexed get() = features and 8 != 0
    val supportsAll get() = features and 16 != 0
}

data class TransitionInfo(val targetPower: Boolean? = null, val targetIntensity: Int? = null, val remainingMillis: Long? = null, val receivedAt: Long = 0)

enum class SetupStage { PROVISIONED, COMPOSITION, APP_KEY, BINDING, CAPABILITIES, READY, UNSUPPORTED }
data class NodeInfo(
    val uuid: String,
    val address: Int,
    val name: String,
    val companyId: Int = 0xFFFF,
    val modelId: Int = 1,
    val capabilities: Capabilities? = null,
    val stage: SetupStage = SetupStage.PROVISIONED,
    val favorite: Boolean = false,
    val lastError: String? = null,
    val aggregate: Observation? = null,
    val leds: Map<Int, Observation> = emptyMap(),
    val drafts: Map<Int, LightingPatch> = emptyMap(),
    val reverseDisplay: Boolean = false,
    val transition: TransitionInfo? = null
)
data class LedRef(val nodeUuid: String, val index: Int)
data class RoomInfo(
    val address: Int,
    val name: String,
    val members: Set<String> = emptySet(),
    val confirmed: Set<String> = emptySet(),
    val failures: Map<String, String> = emptyMap(),
    val pendingRemoval: Set<String> = emptySet(),
    val deleting: Boolean = false,
    val draft: LightingPatch = LightingPatch(),
    val controlFailures: Map<String, String> = emptyMap()
)
data class ZoneInfo(val id: String, val name: String, val leds: List<LedRef>, val invalidated: Boolean = false)
data class PresetEdit(val ref: LedRef, val patch: LightingPatch)
data class PresetInfo(val id: String, val name: String, val edits: List<PresetEdit>, val invalidated: Boolean = false)
data class Installation(
    val version: Int = 1,
    val networkId: String,
    val name: String,
    val nodes: List<NodeInfo> = emptyList(),
    val rooms: List<RoomInfo> = emptyList(),
    val zones: List<ZoneInfo> = emptyList(),
    val presets: List<PresetInfo> = emptyList(),
    val favoriteColors: List<Rgb> = emptyList(),
    val recentColors: List<Rgb> = emptyList()
)

enum class ConnectionStatus { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, BLUETOOTH_OFF, PERMISSION_REQUIRED }
enum class OperationKind { PROVISION, CONFIGURE, GROUP, REFRESH, APPLY, CAPTURE, IMPORT, EXPORT, RESET, TRANSFER }
data class OperationProgress(
    val kind: OperationKind,
    val completed: Int = 0,
    val total: Int = 1,
    val running: Boolean = true,
    val failures: Map<String, String> = emptyMap(),
    val detail: String? = null,
    val cancelled: Boolean = false
)
data class NearbyLight(val uuid: String, val address: String, val name: String?, val rssi: Int)
data class NetworkChoice(val id: String, val name: String)
data class ControllerState(
    val installation: Installation? = null,
    val connection: ConnectionStatus = ConnectionStatus.IDLE,
    val nearby: List<NearbyLight> = emptyList(),
    val operation: OperationProgress? = null,
    val demo: Boolean = false,
    val readOnly: Boolean = false,
    val pendingTargets: Set<String> = emptySet(),
    val networks: List<NetworkChoice> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val error: String? = null,
    val loading: Boolean = false
)

object LedTarget {
    const val All = 0xFFFF
    fun isValid(value: Int) = value == All || value in 0 until 339
}
