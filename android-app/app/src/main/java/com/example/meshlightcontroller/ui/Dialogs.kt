package com.example.meshlightcontroller.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.model.*

@Composable
fun NameDialog(title: String, label: String, initial: String, onDismiss: ()->Unit, onSave: (String)->Unit) {
    var value by rememberSaveable(initial) {mutableStateOf(initial)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={OutlinedTextField(value,{value=it.take(80)},label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())},
        confirmButton={TextButton({onSave(value.trim())},enabled=value.isNotBlank()){Text(stringResource(R.string.save))}},
        dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: ()->Unit, onConfirm: ()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Text(body)},confirmButton={TextButton(onConfirm){Text(confirm)}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable
fun LedRangeDialog(count: Int, onDismiss: ()->Unit, onSelect: (Int,Int)->Unit) {
    var from by rememberSaveable {mutableStateOf("1")}
    var to by rememberSaveable {mutableStateOf(count.toString())}
    val first=from.toIntOrNull()
    val last=to.toIntOrNull()
    val valid=first!=null&&last!=null&&first in 1..count&&last in first..count
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.select_range))},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(from,{from=it.take(3)},Modifier.weight(1f),label={Text(stringResource(R.string.led_range_start))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                OutlinedTextField(to,{to=it.take(3)},Modifier.weight(1f),label={Text(stringResource(R.string.led_range_end))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
            }
            if(!valid) Text(stringResource(R.string.range_invalid),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton({onSelect(requireNotNull(first)-1,requireNotNull(last)-1)},enabled=valid){Text(stringResource(R.string.select_range))}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable
fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean)->Unit) {
    Row(modifier.fillMaxWidth().heightIn(min=52.dp).toggleable(checked,enabled=enabled,role=androidx.compose.ui.semantics.Role.Checkbox,onValueChange=onChange),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked,onCheckedChange=null,enabled=enabled)
        Text(label,Modifier.padding(start=12.dp),style=MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun CopyPropertiesDialog(observation: Observation, onDismiss: ()->Unit, onCopy: (LightingPatch)->Unit) {
    var rgb by rememberSaveable {mutableStateOf(true)}
    var intensity by rememberSaveable {mutableStateOf(true)}
    var power by rememberSaveable {mutableStateOf(true)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.copy_properties))},text={
        Column {
            CheckRow(stringResource(R.string.color),rgb) {rgb=it}
            CheckRow(stringResource(R.string.brightness),intensity) {intensity=it}
            CheckRow(stringResource(R.string.power),power) {power=it}
        }
    },confirmButton={TextButton({onCopy(LightingPatch(observation.state.rgb.takeIf {rgb},observation.state.intensity.takeIf {intensity},observation.state.power.takeIf {power}))},enabled=rgb||intensity||power){Text(stringResource(R.string.copy))}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable
fun PatternDialog(refs: List<LedRef>, onDismiss: ()->Unit, onAction: (UiAction)->Unit) {
    var name by rememberSaveable {mutableStateOf("")}
    var first by rememberSaveable {mutableStateOf("FFB875")}
    var second by rememberSaveable {mutableStateOf("A28BEC")}
    var alternating by rememberSaveable {mutableStateOf(false)}
    val a=runCatching {Rgb.fromHex(first)}.getOrNull()
    val b=runCatching {Rgb.fromHex(second)}.getOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.create_pattern))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name,{name=it.take(80)},label={Text(stringResource(R.string.pattern_name))},singleLine=true)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!alternating,{alternating=false},label={Text(stringResource(R.string.gradient))})
                FilterChip(alternating,{alternating=true},label={Text(stringResource(R.string.alternating))})
            }
            OutlinedTextField(first,{first=it.removePrefix("#").take(6)},label={Text(stringResource(R.string.first_color))},singleLine=true,isError=a==null)
            OutlinedTextField(second,{second=it.removePrefix("#").take(6)},label={Text(stringResource(R.string.second_color))},singleLine=true,isError=b==null)
            if(a!=null&&b!=null) {
                if(alternating) Row(Modifier.fillMaxWidth().height(36.dp)) {repeat(10){Box(Modifier.weight(1f).fillMaxHeight().background(if(it%2==0) a.composeColor() else b.composeColor()))}}
                else Box(Modifier.fillMaxWidth().height(36.dp).background(Brush.horizontalGradient(listOf(a.composeColor(),b.composeColor())),MaterialTheme.shapes.small))
            }
            Text(stringResource(R.string.selected_count,refs.distinct().size),style=MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.pattern_rgb_only),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton({onAction(UiAction.SavePattern(name,refs,requireNotNull(a),requireNotNull(b),alternating));onDismiss()},enabled=name.isNotBlank()&&refs.isNotEmpty()&&a!=null&&b!=null){Text(stringResource(R.string.save_pattern))}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable
fun ExportDialog(type: ExportType, onDismiss: ()->Unit, onExport: (ExportType,String)->Unit) {
    // Passwords deliberately stay out of saved instance state.
    var password by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    val encrypted=type==ExportType.BACKUP||type==ExportType.TRANSFER
    val title=when(type) {ExportType.BACKUP->R.string.backup_network;ExportType.TRANSFER->R.string.transfer_network;ExportType.CDB->R.string.export_cdb;ExportType.DIAGNOSTICS->R.string.export_diagnostics}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(title))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(when(type){ExportType.BACKUP->R.string.backup_help;ExportType.TRANSFER->R.string.transfer_warning;ExportType.CDB->R.string.cdb_warning;ExportType.DIAGNOSTICS->R.string.diagnostics_help}))
            if(encrypted) {
                OutlinedTextField(password,{password=it},label={Text(stringResource(R.string.password))},supportingText={Text(stringResource(R.string.password_hint))},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                OutlinedTextField(confirmation,{confirmation=it},label={Text(stringResource(R.string.confirm_password))},visualTransformation=PasswordVisualTransformation(),singleLine=true,isError=confirmation.isNotEmpty()&&password!=confirmation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
            }
        }
    },confirmButton={TextButton({onExport(type,password);password="";confirmation="";onDismiss()},enabled=!encrypted||(password.length>=8&&password==confirmation)){Text(stringResource(R.string.export))}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable
fun ImportDialog(onDismiss: ()->Unit, onImport: (String)->Unit) {
    var password by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.import_network))},text={
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.import_help))
            OutlinedTextField(password,{password=it},label={Text(stringResource(R.string.password))},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
            Text(stringResource(R.string.two_phone_warning),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton({onImport(password);password="";onDismiss()}){Text(stringResource(R.string.select_file))}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}
