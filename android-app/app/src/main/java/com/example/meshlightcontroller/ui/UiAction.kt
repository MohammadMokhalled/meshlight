package com.example.meshlightcontroller.ui

import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.data.ThemeChoice

sealed interface UiAction {
    data class CreateNetwork(val name: String) : UiAction
    data class RenameNetwork(val name: String) : UiAction
    data object EnterDemo : UiAction
    data object LeaveDemo : UiAction
    data class SwitchNetwork(val id: String) : UiAction
    data object Connect : UiAction
    data object Scan : UiAction
    data object StopScan : UiAction
    data class AddLight(val light: NearbyLight, val name: String, val replace: Boolean = false) : UiAction
    data class FinishSetup(val uuid: String) : UiAction
    data class EditLight(val uuid: String, val targets: Set<Int>, val patch: LightingPatch, val final: Boolean = true, val fadeMillis: Long = 0) : UiAction
    data class ApplyDrafts(val uuid: String) : UiAction
    data class DiscardDrafts(val uuid: String) : UiAction
    data class Refresh(val uuid: String) : UiAction
    data class Visible(val uuid: String, val indices: List<Int>) : UiAction
    data object CancelVisible : UiAction
    data class RefreshStrip(val uuid: String) : UiAction
    data class Favorite(val uuid: String) : UiAction
    data class RenameLight(val uuid: String, val name: String) : UiAction
    data class Reverse(val uuid: String) : UiAction
    data class FavoriteColor(val rgb: Rgb) : UiAction
    data class SaveRoom(val name: String, val members: Set<String>, val address: Int? = null) : UiAction
    data class EditRoom(val address: Int, val patch: LightingPatch, val final: Boolean = true) : UiAction
    data class ApplyRoomDraft(val address: Int) : UiAction
    data class DeleteRoom(val address: Int) : UiAction
    data class RetryRoom(val address: Int) : UiAction
    data class SaveZone(val name: String, val refs: List<LedRef>, val id: String? = null) : UiAction
    data class EditZone(val id: String, val patch: LightingPatch) : UiAction
    data class DeleteZone(val id: String) : UiAction
    data class CapturePreset(val name: String, val refs: List<LedRef>) : UiAction
    data class SavePattern(val name: String, val refs: List<LedRef>, val first: Rgb, val second: Rgb, val alternating: Boolean) : UiAction
    data class ApplyPreset(val id: String) : UiAction
    data class RenamePreset(val id: String, val name: String) : UiAction
    data class DuplicatePreset(val id: String, val name: String) : UiAction
    data class DeletePreset(val id: String) : UiAction
    data object RetryDrafts : UiAction
    data object CancelOperation : UiAction
    data object DismissOperation : UiAction
    data object DismissError : UiAction
    data class Identify(val uuid: String) : UiAction
    data class LoadSettings(val uuid: String) : UiAction
    data class SaveSettings(val uuid: String, val ttl: Int, val relay: Boolean, val proxy: Boolean) : UiAction
    data class Reset(val uuid: String) : UiAction
    data class Forget(val uuid: String) : UiAction
    data class Appearance(val theme: ThemeChoice, val dynamic: Boolean) : UiAction
    data class Vendor(val company: Int, val model: Int) : UiAction
}
enum class ExportType { BACKUP, CDB, TRANSFER, DIAGNOSTICS }
