package com.example.meshlightcontroller.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.meshlightcontroller.core.mesh.NodeSettings
import com.example.meshlightcontroller.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeshViewModel @Inject constructor(
    private val repository: LightingRepository,
    private val backup: BackupService,
    private val preferencesStore: AppPreferences
) : ViewModel() {
    val state = repository.state
    val preferences = repository.appearance
    private val mutableSettings = MutableStateFlow<Pair<String, NodeSettings>?>(null)
    val settings = mutableSettings.asStateFlow()
    private data class PreparedExport(val type: ExportType, val password: CharArray?)
    private var preparedExport: PreparedExport? = null

    fun foreground(active: Boolean) = repository.setForeground(active)
    fun dispatch(action: UiAction) {
        try {
            when (action) {
                is UiAction.CreateNetwork -> repository.createNetwork(action.name)
                is UiAction.RenameNetwork -> repository.renameNetwork(action.name)
                UiAction.EnterDemo -> repository.enterDemo()
                UiAction.LeaveDemo -> repository.leaveDemo()
                is UiAction.SwitchNetwork -> repository.switchNetwork(action.id)
                UiAction.Connect -> repository.connect()
                UiAction.Scan -> repository.discover()
                UiAction.StopScan -> repository.stopDiscovery()
                is UiAction.AddLight -> repository.addLight(action.light, action.name, action.replace)
                is UiAction.FinishSetup -> repository.finishSetup(action.uuid)
                is UiAction.EditLight -> repository.edit(action.uuid, action.targets, action.patch, action.final, action.fadeMillis)
                is UiAction.ApplyDrafts -> repository.applyDrafts(action.uuid)
                is UiAction.DiscardDrafts -> repository.discardDrafts(action.uuid)
                is UiAction.Refresh -> repository.refreshNode(action.uuid)
                is UiAction.Visible -> repository.visibleLeds(action.uuid, action.indices)
                UiAction.CancelVisible -> repository.cancelVisible()
                is UiAction.RefreshStrip -> repository.refreshStrip(action.uuid)
                is UiAction.Favorite -> repository.favorite(action.uuid)
                is UiAction.RenameLight -> repository.renameNode(action.uuid, action.name)
                is UiAction.Reverse -> repository.reverse(action.uuid)
                is UiAction.FavoriteColor -> repository.favoriteColor(action.rgb)
                is UiAction.SaveRoom -> repository.saveRoom(action.name, action.members, action.address)
                is UiAction.EditRoom -> repository.editRoom(action.address, action.patch, action.final)
                is UiAction.ApplyRoomDraft -> repository.applyRoomDraft(action.address)
                is UiAction.DeleteRoom -> repository.deleteRoom(action.address)
                is UiAction.RetryRoom -> repository.retryRoom(action.address)
                is UiAction.SaveZone -> repository.saveZone(action.name, action.refs, action.id)
                is UiAction.EditZone -> repository.editZone(action.id, action.patch)
                is UiAction.DeleteZone -> repository.deleteZone(action.id)
                is UiAction.CapturePreset -> repository.capturePreset(action.name, action.refs)
                is UiAction.SavePattern -> repository.savePattern(action.name, action.refs, action.first, action.second, action.alternating)
                is UiAction.ApplyPreset -> repository.applyPreset(action.id)
                is UiAction.RenamePreset -> repository.renamePreset(action.id, action.name)
                is UiAction.DuplicatePreset -> repository.duplicatePreset(action.id, action.name)
                is UiAction.DeletePreset -> repository.deletePreset(action.id)
                UiAction.RetryDrafts -> repository.retryDrafts()
                UiAction.CancelOperation -> repository.cancelOperation()
                UiAction.DismissOperation -> repository.dismissOperation()
                UiAction.DismissError -> repository.dismissError()
                is UiAction.Identify -> repository.identify(action.uuid)
                is UiAction.LoadSettings -> {
                    mutableSettings.value = null
                    viewModelScope.launch {
                        try { mutableSettings.value = action.uuid to repository.readSettings(action.uuid) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { repository.message(error) }
                    }
                }
                is UiAction.SaveSettings -> repository.writeSettings(action.uuid, action.ttl, action.relay, action.proxy)
                is UiAction.Reset -> repository.reset(action.uuid)
                is UiAction.Forget -> repository.forget(action.uuid)
                is UiAction.Appearance -> viewModelScope.launch { preferencesStore.appearance(action.theme, action.dynamic) }
                is UiAction.Vendor -> viewModelScope.launch { preferencesStore.vendor(action.company, action.model) }
            }
        } catch (error: Exception) { repository.message(error) }
    }

    fun prepareExport(type: ExportType, password: String): Boolean {
        if (type in setOf(ExportType.BACKUP, ExportType.TRANSFER) && password.length < 8) return false
        preparedExport?.password?.fill('\u0000')
        preparedExport = PreparedExport(type, password.takeIf { type in setOf(ExportType.BACKUP, ExportType.TRANSFER) }?.toCharArray())
        return true
    }

    fun completeExport(uri: Uri?) {
        val prepared = preparedExport
        preparedExport = null
        if (uri == null) { prepared?.password?.fill('\u0000'); return }
        if (prepared == null) { repository.message(IllegalStateException("Start the export again after restoring the app")); return }
        if (prepared.type == ExportType.DIAGNOSTICS) backup.diagnostics(uri)
        else backup.export(uri, prepared.password, prepared.type == ExportType.TRANSFER)
    }

    private var importPassword: CharArray? = null
    fun prepareImport(password: String) {
        importPassword?.fill('\u0000')
        importPassword = password.toCharArray()
    }
    fun completeImport(uri: Uri?) {
        val password = importPassword
        importPassword = null
        if (uri == null) { password?.fill('\u0000'); return }
        if (password == null) { repository.message(IllegalStateException("Start the import again after restoring the app")); return }
        backup.import(uri, password)
    }
    override fun onCleared() {
        preparedExport?.password?.fill('\u0000')
        importPassword?.fill('\u0000')
        repository.cancelVisible()
        super.onCleared()
    }
}
