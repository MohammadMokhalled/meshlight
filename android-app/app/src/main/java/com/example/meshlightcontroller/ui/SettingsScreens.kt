package com.example.meshlightcontroller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.mesh.NodeSettings
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.data.ThemeChoice
import com.example.meshlightcontroller.data.UiPreferences

@Composable
fun SettingsScreen(state: ControllerState, preferences: UiPreferences, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onBack: ()->Unit, onExport: (ExportType)->Unit, onImport: ()->Unit) {
    var create by rememberSaveable {mutableStateOf(false)}
    var rename by rememberSaveable {mutableStateOf(false)}
    var networks by remember {mutableStateOf(false)}
    var advanced by rememberSaveable {mutableStateOf(false)}
    var company by rememberSaveable(preferences.companyId) {mutableStateOf("%04X".format(preferences.companyId))}
    var model by rememberSaveable(preferences.modelId) {mutableStateOf("%04X".format(preferences.modelId))}
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {DetailTopBar(stringResource(R.string.settings),onBack=onBack)}
        item {
            Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SectionLabel(stringResource(R.string.appearance))
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    ThemeChoice.entries.forEach {choice->
                        FilterChip(preferences.theme==choice,{onAction(UiAction.Appearance(choice,preferences.dynamicColor))},label={Text(stringResource(when(choice){ThemeChoice.SYSTEM->R.string.theme_system;ThemeChoice.LIGHT->R.string.theme_light;ThemeChoice.DARK->R.string.theme_dark}))})
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.dynamic_color),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                    Switch(preferences.dynamicColor,{onAction(UiAction.Appearance(preferences.theme,it))})
                }
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                SectionLabel(stringResource(R.string.network))
                Text(state.installation?.name ?: stringResource(R.string.no_network),style=MaterialTheme.typography.headlineSmall)
                if(state.demo) InfoCard(stringResource(R.string.transfer_disabled))
                else InfoCard(stringResource(R.string.network_security))
                if(state.readOnly) InfoCard(stringResource(R.string.transferred_body),error=true)
                SettingsAction(stringResource(R.string.rename_network),Glyph.EDIT,enabled=!state.readOnly&&state.installation!=null) {rename=true}
                SettingsAction(stringResource(R.string.switch_network),Glyph.GROUP,enabled=!state.demo&&state.networks.isNotEmpty()) {networks=true}
                SettingsAction(stringResource(R.string.create_network),Glyph.ADD,enabled=!state.demo) {create=true}
                SettingsAction(stringResource(R.string.import_network),Glyph.IMPORT,enabled=!state.demo,onClick=onImport)
                SettingsAction(stringResource(R.string.backup_network),Glyph.EXPORT,enabled=!state.demo&&!state.readOnly&&state.installation!=null) {onExport(ExportType.BACKUP)}
                SettingsAction(stringResource(R.string.transfer_network),Glyph.EXPORT,enabled=!state.demo&&!state.readOnly&&state.installation!=null) {onExport(ExportType.TRANSFER)}
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                SettingsAction(stringResource(R.string.advanced),Glyph.SETTINGS) {advanced=!advanced}
                if(advanced) {
                    SectionLabel(stringResource(R.string.vendor_identifiers))
                    Text(stringResource(R.string.vendor_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(company,{company=it.take(4)},Modifier.weight(1f),label={Text(stringResource(R.string.company_id))},singleLine=true)
                        OutlinedTextField(model,{model=it.take(4)},Modifier.weight(1f),label={Text(stringResource(R.string.model_id))},singleLine=true)
                    }
                    val companyValue=company.toIntOrNull(16)
                    val modelValue=model.toIntOrNull(16)
                    OutlinedButton({onAction(UiAction.Vendor(requireNotNull(companyValue),requireNotNull(modelValue)))},enabled=companyValue in 0..65535&&modelValue in 0..65535) {Text(stringResource(R.string.save))}
                    SettingsAction(stringResource(R.string.export_cdb),Glyph.EXPORT,enabled=!state.demo&&!state.readOnly&&state.installation!=null) {onExport(ExportType.CDB)}
                    SettingsAction(stringResource(R.string.export_diagnostics),Glyph.EXPORT) {onExport(ExportType.DIAGNOSTICS)}
                    Text(stringResource(R.string.diagnostics_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.physical_reset),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(state.demo) OutlinedButton({onAction(UiAction.LeaveDemo)},Modifier.fillMaxWidth()) {Text(stringResource(R.string.leave_demo))}
            }
        }
    }
    if(rename) NameDialog(stringResource(R.string.rename_network),stringResource(R.string.network_name),state.installation?.name.orEmpty(),{rename=false}) {onAction(UiAction.RenameNetwork(it));rename=false}
    if(create) NameDialog(stringResource(R.string.create_network),stringResource(R.string.home_name),stringResource(R.string.default_home),{create=false}) {onAction(UiAction.CreateNetwork(it));create=false}
    if(networks) AlertDialog(onDismissRequest={networks=false},title={Text(stringResource(R.string.switch_network))},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {state.networks.forEach {network->
            TextButton({onAction(UiAction.SwitchNetwork(network.id));networks=false},Modifier.fillMaxWidth()) {
                Text(network.name,Modifier.weight(1f))
                if(network.id==state.installation?.networkId) MeshIcon(Glyph.CHECK)
            }
        }}
    },confirmButton={TextButton({networks=false}) {Text(stringResource(R.string.close))}})
}

@Composable
private fun SettingsAction(label: String, glyph: Glyph, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: ()->Unit) {
    OutlinedButton(onClick,modifier.fillMaxWidth().heightIn(min=52.dp),enabled=enabled,shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)) {
        MeshIcon(glyph,Modifier.size(20.dp));Spacer(Modifier.width(12.dp));Text(label,Modifier.weight(1f));MeshIcon(Glyph.ARROW,Modifier.size(16.dp))
    }
}

@Composable
fun DeviceScreen(node: NodeInfo, state: ControllerState, settings: NodeSettings?, modifier: Modifier = Modifier, onAction: (UiAction)->Unit, onBack: ()->Unit) {
    var ttl by rememberSaveable(node.uuid,settings?.ttl) {mutableStateOf(settings?.ttl?.toString().orEmpty())}
    var relay by rememberSaveable(node.uuid,settings?.relay) {mutableStateOf(settings?.relay ?: false)}
    var proxy by rememberSaveable(node.uuid,settings?.proxy) {mutableStateOf(settings?.proxy ?: true)}
    var confirm by rememberSaveable {mutableStateOf<String?>(null)}
    val ttlValue=ttl.toIntOrNull()
    val valid=ttlValue!=null&&(ttlValue==0||ttlValue in 2..127)&&settings!=null
    LaunchedEffect(node.uuid,state.connection) {if(state.connection==ConnectionStatus.CONNECTED) onAction(UiAction.LoadSettings(node.uuid))}
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {DetailTopBar(stringResource(R.string.device_info),onBack=onBack)}
        item {
            Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(node.name,style=MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.address_label,node.address),style=MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.stage_label,node.stage.name),style=MaterialTheme.typography.bodySmall)
                node.capabilities?.let {Text(stringResource(R.string.version_label,it.version,it.ledCount),style=MaterialTheme.typography.bodySmall)}
                node.lastError?.let {InfoCard(it,error=true)}
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                SectionLabel(stringResource(R.string.device_settings))
                if(settings==null) InfoCard(stringResource(R.string.read_first)) {
                    Button({onAction(UiAction.LoadSettings(node.uuid))},enabled=state.connection==ConnectionStatus.CONNECTED) {Text(stringResource(R.string.read_settings))}
                }
                OutlinedTextField(ttl,{ttl=it.take(3)},label={Text(stringResource(R.string.ttl))},enabled=settings!=null&&!state.readOnly,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,isError=ttl.isNotEmpty()&&!valid,modifier=Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.relay),Modifier.weight(1f))
                    Switch(relay,{relay=it},enabled=settings!=null&&!state.readOnly)
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.proxy),Modifier.weight(1f))
                    Switch(proxy,{proxy=it},enabled=settings!=null&&!state.readOnly)
                }
                Button({
                    if(!proxy&&settings?.currentProxy==true) confirm="proxy"
                    else onAction(UiAction.SaveSettings(node.uuid,requireNotNull(ttlValue),relay,proxy))
                },enabled=valid&&!state.readOnly) {Text(stringResource(R.string.save_settings))}
                Text(stringResource(R.string.settings_saved),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                SectionLabel(stringResource(R.string.advanced))
                OutlinedButton({confirm="reset"},Modifier.fillMaxWidth(),enabled=!state.readOnly) {Text(stringResource(R.string.reset_light),color=MaterialTheme.colorScheme.error)}
                OutlinedButton({confirm="forget"},Modifier.fillMaxWidth(),enabled=!state.readOnly) {Text(stringResource(R.string.forget_light))}
                InfoCard(stringResource(R.string.physical_reset))
                Text(stringResource(R.string.lighting_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    confirm?.let {type->
        ConfirmDialog(stringResource(when(type){"proxy"->R.string.proxy_warning_title;"reset"->R.string.reset_title;else->R.string.forget_title}),
            stringResource(when(type){"proxy"->R.string.proxy_warning;"reset"->R.string.reset_body;else->R.string.forget_body}),
            stringResource(when(type){"proxy"->R.string.save;"reset"->R.string.reset;else->R.string.forget}),{confirm=null}) {
            when(type) {
                "proxy" -> onAction(UiAction.SaveSettings(node.uuid,requireNotNull(ttlValue),relay,proxy))
                "reset" -> {onAction(UiAction.Reset(node.uuid));onBack()}
                else -> {onAction(UiAction.Forget(node.uuid));onBack()}
            }
            confirm=null
        }
    }
}
