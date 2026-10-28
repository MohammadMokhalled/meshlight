package com.example.meshlightcontroller.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.model.*
import kotlin.math.roundToInt

enum class Glyph { LIGHT, GROUP, PRESET, SETTINGS, BACK, ADD, CLOSE, CHECK, POWER, HEART, LEDS, BLUETOOTH, EDIT, DELETE, REFRESH, EXPORT, IMPORT, ARROW, COPY, SEARCH, MORE, SUN, ZONE }

@Composable
fun MeshIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, description: String? = null) {
    val accessibility = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Canvas(modifier.size(24.dp).then(accessibility)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x,y), Offset(x2,y2), 1.7f, StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>) {
                drawPath(Path().apply { points.forEachIndexed { i, p -> if(i==0) moveTo(p.first,p.second) else lineTo(p.first,p.second) } }, tint, style = stroke)
            }
            when(glyph) {
                Glyph.LIGHT -> {
                    drawPath(Path().apply { moveTo(9f,17f); cubicTo(9f,14f,5f,13f,5f,9f); cubicTo(5f,0f,19f,0f,19f,9f); cubicTo(19f,13f,15f,14f,15f,17f); close() },tint,style=stroke)
                    line(9f,20f,15f,20f); line(11f,23f,13f,23f)
                }
                Glyph.POWER -> { drawArc(tint, -46f, 272f, false, Offset(4f,4f),Size(16f,16f), style=stroke); line(12f,2f,12f,11f) }
                Glyph.BACK -> path(15f to 5f,8f to 12f,15f to 19f)
                Glyph.ARROW -> path(9f to 5f,16f to 12f,9f to 19f)
                Glyph.ADD -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
                Glyph.CLOSE -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                Glyph.CHECK -> path(5f to 12f,10f to 17f,20f to 6f)
                Glyph.HEART -> drawPath(Path().apply { moveTo(12f,20f); cubicTo(8f,17f,2f,12f,3f,7f); cubicTo(4f,2f,10f,2f,12f,7f); cubicTo(14f,2f,20f,2f,21f,7f); cubicTo(22f,12f,16f,17f,12f,20f); close() },tint,style=stroke)
                Glyph.GROUP -> { drawRoundRect(tint,Offset(3f,4f),Size(7f,7f),androidx.compose.ui.geometry.CornerRadius(2f),style=stroke); drawRoundRect(tint,Offset(14f,4f),Size(7f,7f),androidx.compose.ui.geometry.CornerRadius(2f),style=stroke); drawRoundRect(tint,Offset(3f,15f),Size(18f,6f),androidx.compose.ui.geometry.CornerRadius(2f),style=stroke) }
                Glyph.LEDS, Glyph.ZONE -> { for(y in 0..1) for(x in 0..2) drawRoundRect(tint,Offset(3f+x*7,5f+y*9),Size(4f,5f),androidx.compose.ui.geometry.CornerRadius(1f),style=stroke) }
                Glyph.PRESET -> { path(12f to 2f,15f to 9f,22f to 12f,15f to 15f,12f to 22f,9f to 15f,2f to 12f,9f to 9f,12f to 2f) }
                Glyph.SETTINGS -> { drawCircle(tint,8f,Offset(12f,12f),style=stroke); drawCircle(tint,3f,Offset(12f,12f),style=stroke); for(i in 0..7) { val a=i*Math.PI/4; line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat()) } }
                Glyph.BLUETOOTH -> { path(12f to 2f,18f to 7f,6f to 17f); path(6f to 7f,18f to 17f,12f to 22f,12f to 2f) }
                Glyph.EDIT -> { path(4f to 20f,5f to 15f,16f to 4f,20f to 8f,9f to 19f,4f to 20f); line(13f,7f,17f,11f) }
                Glyph.DELETE -> { path(5f to 6f,6f to 21f,18f to 21f,19f to 6f); line(3f,6f,21f,6f); path(8f to 6f,8f to 3f,16f to 3f,16f to 6f); line(10f,10f,10f,17f); line(14f,10f,14f,17f) }
                Glyph.REFRESH -> { drawArc(tint,35f,290f,false,Offset(4f,4f),Size(16f,16f),style=stroke); path(15f to 3f,20f to 5f,20f to 10f) }
                Glyph.EXPORT, Glyph.IMPORT -> { path(4f to 16f,4f to 21f,20f to 21f,20f to 16f); line(12f,3f,12f,16f); if(glyph==Glyph.EXPORT) path(7f to 8f,12f to 3f,17f to 8f) else path(7f to 11f,12f to 16f,17f to 11f) }
                Glyph.COPY -> { drawRoundRect(tint,Offset(8f,8f),Size(12f,13f),androidx.compose.ui.geometry.CornerRadius(2f),style=stroke); path(15f to 4f,4f to 4f,4f to 16f) }
                Glyph.SEARCH -> { drawCircle(tint,7f,Offset(10f,10f),style=stroke); line(15f,15f,21f,21f) }
                Glyph.MORE -> for(y in listOf(5f,12f,19f)) drawCircle(tint,1.4f,Offset(12f,y))
                Glyph.SUN -> { drawCircle(tint,4f,Offset(12f,12f),style=stroke); for(i in 0..7) { val a=i*Math.PI/4; line((12+7*kotlin.math.cos(a)).toFloat(),(12+7*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat()) } }
            }
        }
    }
}

@Composable
fun ActionIcon(glyph: Glyph, @StringRes label: Int, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true) {
    IconButton(onClick, modifier.size(48.dp), enabled = enabled) {
        MeshIcon(glyph, tint = if(selected) MaterialTheme.colorScheme.primary else LocalContentColor.current, description=stringResource(label))
    }
}

@Composable
fun PageHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null, action: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style=MaterialTheme.typography.headlineLarge)
            if(subtitle!=null) Text(subtitle,Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}

@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier, error: Boolean = false, action: @Composable (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(text,style=MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, glyph: Glyph = Glyph.LIGHT, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical=36.dp,horizontal=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.secondaryContainer,CircleShape),contentAlignment=Alignment.Center) {
            MeshIcon(glyph,Modifier.size(36.dp),MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title,style=MaterialTheme.typography.headlineSmall)
        Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

fun Rgb.composeColor() = Color(red, green, blue)
fun Color.rgb() = Rgb((red*255).roundToInt(),(green*255).roundToInt(),(blue*255).roundToInt())

@Composable
fun LightOrb(rgb: Rgb?, modifier: Modifier = Modifier, powered: Boolean? = true) {
    val color = rgb?.composeColor() ?: MaterialTheme.colorScheme.outline
    Box(modifier,contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha=if(powered==false) .07f else .25f),color.copy(alpha=0f))))
            drawCircle(color.copy(alpha=if(powered==false) .20f else .9f),radius=size.minDimension*.20f)
            drawCircle(Color.White.copy(alpha=.5f),radius=size.minDimension*.20f,style=Stroke(1.dp.toPx()))
        }
        MeshIcon(Glyph.LIGHT,Modifier.size(32.dp),if(powered==false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun ConnectionBadge(status: ConnectionStatus, modifier: Modifier = Modifier, demo: Boolean = false, pendingCount: Int = 0, onCancel: () -> Unit = {}, onConnect: () -> Unit = {}) {
    val label = when {
        pendingCount>0 -> R.string.sending_changes
        demo -> R.string.demo
        status==ConnectionStatus.CONNECTED -> R.string.connected
        status==ConnectionStatus.CONNECTING -> R.string.connecting
        status==ConnectionStatus.SCANNING -> R.string.scanning
        status==ConnectionStatus.BLUETOOTH_OFF -> R.string.bluetooth_off
        status==ConnectionStatus.PERMISSION_REQUIRED -> R.string.permission_required
        else -> R.string.offline
    }
    val pendingDescription=stringResource(R.string.pending_changes_count,pendingCount)
    val cancelDescription=stringResource(R.string.cancel_pending)
    val feedback=if(pendingCount>0) Modifier.semantics {stateDescription=pendingDescription;contentDescription=cancelDescription} else Modifier
    Surface(onClick=if(pendingCount>0) onCancel else onConnect,modifier=modifier.heightIn(min=48.dp).then(feedback),shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min=48.dp).padding(horizontal=12.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(if(pendingCount>0) 16.dp else 6.dp),contentAlignment=Alignment.Center) {
                if(pendingCount>0) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
                else Box(Modifier.size(6.dp).background(if(status==ConnectionStatus.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,CircleShape))
            }
            Text(stringResource(label),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(pendingCount>0) MeshIcon(Glyph.CLOSE,Modifier.size(16.dp))
        }
    }
}

@Composable
fun OperationCard(operation: OperationProgress, modifier: Modifier = Modifier, onAction: (UiAction) -> Unit) {
    val title = when(operation.kind) {
        OperationKind.PROVISION -> R.string.operation_provision
        OperationKind.CONFIGURE -> R.string.operation_configure
        OperationKind.GROUP -> R.string.operation_group
        OperationKind.REFRESH -> R.string.operation_refresh
        OperationKind.APPLY -> R.string.operation_apply
        OperationKind.CAPTURE -> R.string.operation_capture
        OperationKind.IMPORT -> R.string.operation_import
        OperationKind.EXPORT -> R.string.operation_export
        OperationKind.RESET -> R.string.operation_reset
        OperationKind.TRANSFER -> R.string.operation_transfer
    }
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.large) {
        Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title),style=MaterialTheme.typography.titleSmall)
                    Text(if(operation.cancelled) stringResource(R.string.operation_cancelled) else if(!operation.running && operation.failures.isEmpty()) stringResource(R.string.operation_complete) else stringResource(R.string.operation_count,operation.completed,operation.total),style=MaterialTheme.typography.bodySmall)
                }
                if(operation.running) TextButton({onAction(UiAction.CancelOperation)}) { Text(stringResource(R.string.cancel)) }
                else ActionIcon(Glyph.CLOSE,R.string.dismiss,{onAction(UiAction.DismissOperation)})
            }
            if(operation.running) LinearProgressIndicator(progress={ operation.completed.toFloat()/operation.total.coerceAtLeast(1) },modifier=Modifier.fillMaxWidth().padding(bottom=8.dp))
            if(operation.failures.isNotEmpty()) {
                TextButton({expanded=!expanded}) { Text(stringResource(R.string.operation_failed,operation.failures.size)) }
                if(expanded) operation.failures.values.distinct().forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

fun previewObservation(node: NodeInfo, targets: Set<Int>): Observation? {
    fun at(target: Int): Observation? {
        val observed = if(target==LedTarget.All) node.aggregate else node.leds[target]
        val patch = (node.drafts[LedTarget.All] ?: LightingPatch()).merge(node.drafts[target] ?: LightingPatch())
        val base = observed ?: if(patch.rgb!=null && patch.intensity!=null && patch.power!=null) Observation(LedState(patch.rgb,patch.intensity,patch.power)) else return null
        return base.copy(state=patch.applyTo(base.state),mixedRgb=base.mixedRgb && patch.rgb==null,mixedIntensity=base.mixedIntensity && patch.intensity==null,mixedPower=base.mixedPower && patch.power==null)
    }
    val values=targets.map { at(it) ?: return null }
    return combineObservations(values)
}

@Composable
fun TransitionNotice(value: TransitionInfo, modifier: Modifier = Modifier) {
    var remaining by remember(value) { mutableStateOf(value.remainingMillis) }
    LaunchedEffect(value) {
        val duration = value.remainingMillis ?: return@LaunchedEffect
        do {
            remaining = (duration - (System.currentTimeMillis() - value.receivedAt)).coerceAtLeast(0)
            if (remaining == 0L) break
            kotlinx.coroutines.delay(250)
        } while (true)
    }
    if (remaining == null || remaining!! > 0L) {
        InfoCard(if(remaining==null) stringResource(R.string.transition_pending)
            else stringResource(R.string.transition_remaining,((remaining!!+999)/1000).toString()),modifier)
    }
}

fun sharedCapabilities(nodes: List<NodeInfo>): Capabilities? {
    val values = nodes.mapNotNull { it.capabilities }
    if (values.isEmpty()) return null
    return Capabilities(1,1,values.map { it.features }.reduce { a,b -> a and b },values.minOf { it.maxIntensity })
}

fun combineObservations(values: List<Observation>): Observation? {
    if(values.isEmpty()) return null
    val first=values.first()
    return Observation(first.state.copy(intensity=values.map { it.state.intensity }.average().roundToInt(),power=values.any { it.state.power }),
        mixedRgb=values.any { it.mixedRgb || it.state.rgb!=first.state.rgb },
        mixedIntensity=values.any { it.mixedIntensity || it.state.intensity!=first.state.intensity },
        mixedPower=values.any { it.mixedPower || it.state.power!=first.state.power },
        receivedAt=values.minOf { it.receivedAt })
}
