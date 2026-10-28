package com.example.meshlightcontroller.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.data.ThemeChoice
import com.example.meshlightcontroller.ui.theme.MeshLightControllerTheme

@Composable
fun MeshLightApp(viewModel: MeshViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val activity=context as Activity
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val permissions=remember {if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)}
    var pending by remember {mutableStateOf<UiAction?>(null)}
    var permissionDialog by rememberSaveable {mutableStateOf(false)}
    var denied by rememberSaveable {mutableStateOf(false)}
    var locationDialog by rememberSaveable {mutableStateOf(false)}
    var unsupported by rememberSaveable {mutableStateOf(false)}
    var exportType by remember {mutableStateOf<ExportType?>(null)}
    var importDialog by rememberSaveable {mutableStateOf(false)}
    fun granted()=permissions.all {ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED}
    fun locationEnabled(): Boolean {
        if(Build.VERSION.SDK_INT>=31) return true
        val manager=context.getSystemService(LocationManager::class.java)
        return if(Build.VERSION.SDK_INT>=28) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER)||manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    fun runPending() {
        val action=pending ?: return
        pending=null
        viewModel.dispatch(action)
    }
    val enableBluetooth=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->if(result.resultCode==Activity.RESULT_OK) runPending() else pending=null}
    fun continueConnection() {
        if(!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {unsupported=true;pending=null;return}
        if(!granted()) {permissionDialog=true;return}
        if(!locationEnabled()) {locationDialog=true;return}
        val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
        if(adapter?.isEnabled!=true) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) else runPending()
    }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {result->
        if(result.values.all {it}&&granted()) {denied=false;continueConnection()} else {denied=true;permissionDialog=true}
    }
    val settingsLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {if(pending!=null&&granted()) continueConnection()}
    val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"),viewModel::completeExport)
    val importLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(),viewModel::completeImport)
    val dispatch: (UiAction)->Unit = {action->
        val needsBluetooth=action is UiAction.Connect || action is UiAction.Scan || action is UiAction.AddLight || action is UiAction.FinishSetup ||
            action is UiAction.LoadSettings || action is UiAction.SaveSettings || action is UiAction.Reset || action is UiAction.Identify ||
            action is UiAction.ApplyDrafts || action is UiAction.ApplyRoomDraft || action is UiAction.SaveRoom || action is UiAction.RetryRoom ||
            action is UiAction.DeleteRoom || action is UiAction.EditZone || action is UiAction.ApplyPreset || action is UiAction.CapturePreset || action is UiAction.RetryDrafts
        if(needsBluetooth&&!state.demo) {pending=action;continueConnection()} else viewModel.dispatch(action)
    }
    DisposableEffect(lifecycle,viewModel) {
        val observer=LifecycleEventObserver {_,event->when(event) {
            Lifecycle.Event.ON_START -> viewModel.foreground(true)
            Lifecycle.Event.ON_STOP -> viewModel.foreground(false)
            else -> Unit
        }}
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.foreground(true)
        onDispose {lifecycle.removeObserver(observer)}
    }
    val dark=when(preferences.theme){ThemeChoice.SYSTEM->isSystemInDarkTheme();ThemeChoice.DARK->true;ThemeChoice.LIGHT->false}
    MeshLightControllerTheme(darkTheme=dark,dynamicColor=preferences.dynamicColor) {
        MeshLightScreen(state,preferences=preferences,settings=settings,onAction=dispatch,onExport={exportType=it},onImport={importDialog=true})
        exportType?.let {type->ExportDialog(type,{exportType=null}) {kind,password->
            if(viewModel.prepareExport(kind,password)) exportLauncher.launch(when(kind){ExportType.BACKUP->"MeshLight-backup.meshlight";ExportType.TRANSFER->"MeshLight-transfer.meshlight";ExportType.CDB->"MeshLight-network.json";ExportType.DIAGNOSTICS->"MeshLight-diagnostics.txt"})
        }}
        if(importDialog) ImportDialog({importDialog=false}) {password->viewModel.prepareImport(password);importLauncher.launch(arrayOf("*/*"))}
        if(permissionDialog) AlertDialog(onDismissRequest={permissionDialog=false;pending=null},title={Text(stringResource(R.string.permission_title))},text={Text(stringResource(if(denied) R.string.permission_denied else R.string.permission_body))},
            confirmButton={TextButton({
                permissionDialog=false
                if(denied&&permissions.none {activity.shouldShowRequestPermissionRationale(it)}) settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName)))
                else permissionLauncher.launch(permissions)
            }) {Text(stringResource(if(denied&&permissions.none {activity.shouldShowRequestPermissionRationale(it)}) R.string.open_app_settings else R.string.grant_permission))}},
            dismissButton={TextButton({permissionDialog=false;pending=null}) {Text(stringResource(R.string.cancel))}})
        if(locationDialog) ConfirmDialog(stringResource(R.string.permission_title),stringResource(R.string.location_required),stringResource(R.string.open_location_settings),{locationDialog=false;pending=null}) {locationDialog=false;settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))}
        if(unsupported) AlertDialog(onDismissRequest={unsupported=false},text={Text(stringResource(R.string.bluetooth_unavailable))},confirmButton={TextButton({unsupported=false}) {Text(stringResource(R.string.close))}})
    }
}
