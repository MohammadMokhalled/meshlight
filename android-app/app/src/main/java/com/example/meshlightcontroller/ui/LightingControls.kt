package com.example.meshlightcontroller.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.meshlightcontroller.R
import com.example.meshlightcontroller.core.model.*
import kotlin.math.roundToInt

@Composable
fun LightingControls(
    observation: Observation?,
    modifier: Modifier = Modifier,
    draft: LightingPatch = LightingPatch(),
    capabilities: Capabilities? = null,
    favoriteColors: List<Rgb> = emptyList(),
    recentColors: List<Rgb> = emptyList(),
    enabled: Boolean = true,
    onFavorite: (Rgb) -> Unit = {},
    onEdit: (LightingPatch, Boolean) -> Unit
) {
    val rgb = draft.rgb ?: observation?.state?.rgb
    val power = draft.power ?: observation?.state?.power
    val intensity = draft.intensity ?: observation?.state?.intensity
    val mixedPower = observation?.mixedPower == true && draft.power == null
    val mixedRgb = observation?.mixedRgb == true && draft.rgb == null
    val mixedIntensity = observation?.mixedIntensity == true && draft.intensity == null
    Column(modifier,verticalArrangement=Arrangement.spacedBy(24.dp)) {
        if(capabilities?.supportsPower!=false) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.power),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(if(mixedPower) R.string.mixed else if(power==null) R.string.unknown else if(power) R.string.on else R.string.off),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(power==null || mixedPower) {
                    OutlinedButton({onEdit(LightingPatch(power=false),true)},enabled=enabled) { Text(stringResource(R.string.off)) }
                    Spacer(Modifier.width(8.dp))
                    Button({onEdit(LightingPatch(power=true),true)},enabled=enabled) { Text(stringResource(R.string.on)) }
                } else {
                    FilledIconToggleButton(checked=power,onCheckedChange={onEdit(LightingPatch(power=it),true)},enabled=enabled,modifier=Modifier.size(56.dp)) {
                        MeshIcon(Glyph.POWER,description=stringResource(if(power) R.string.turn_off else R.string.turn_on))
                    }
                }
            }
        }
        if(capabilities?.supportsIntensity!=false) {
            var slider by remember(intensity) { mutableFloatStateOf(intensity?.toFloat() ?: 50f) }
            val label=if(mixedIntensity) stringResource(R.string.mixed) else intensity?.let { stringResource(R.string.brightness_value,it) } ?: stringResource(R.string.unknown)
            Column {
                SectionLabel(stringResource(R.string.brightness)) { Text(label,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary) }
                val brightnessDescription=stringResource(R.string.brightness)
                Slider(value=slider,onValueChange={ slider=it; onEdit(LightingPatch(intensity=it.roundToInt()),false) },
                    onValueChangeFinished={onEdit(LightingPatch(intensity=slider.roundToInt()),true)},
                    valueRange=0f..(capabilities?.maxIntensity?.toFloat() ?: 100f),enabled=enabled,modifier=Modifier.fillMaxWidth().semantics { contentDescription=brightnessDescription })
                if(mixedIntensity) Text(stringResource(R.string.mixed_brightness_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(capabilities?.supportsRgb!=false) {
            ColorEditor(rgb=rgb,mixed=mixedRgb,favoriteColors=favoriteColors,recentColors=recentColors,enabled=enabled,onFavorite=onFavorite,
                onChange={color,final -> onEdit(LightingPatch(rgb=color),final)})
        }
    }
}

@Composable
fun ColorEditor(
    rgb: Rgb?,
    modifier: Modifier = Modifier,
    mixed: Boolean = false,
    favoriteColors: List<Rgb> = emptyList(),
    recentColors: List<Rgb> = emptyList(),
    enabled: Boolean = true,
    onFavorite: (Rgb) -> Unit = {},
    onChange: (Rgb, Boolean) -> Unit
) {
    val hsv=remember(rgb) { FloatArray(3).also { AndroidColor.RGBToHSV(rgb?.red ?: 255,rgb?.green ?: 185,rgb?.blue ?: 100,it) } }
    var hue by remember(rgb) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(rgb) { mutableFloatStateOf(hsv[1]) }
    var value by remember(rgb) { mutableFloatStateOf(hsv[2]) }
    fun selected() = AndroidColor.HSVToColor(floatArrayOf(hue,saturation,value)).let { Rgb(AndroidColor.red(it),AndroidColor.green(it),AndroidColor.blue(it)) }
    var exact by rememberSaveable { mutableStateOf(false) }
    var hex by remember(rgb) { mutableStateOf(rgb?.hex.orEmpty()) }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.color)) {
            ActionIcon(Glyph.HEART,if(rgb in favoriteColors) R.string.remove_color else R.string.save_color,{rgb?.let(onFavorite)},selected=rgb in favoriteColors,enabled=enabled&&rgb!=null)
        }
        val hues=remember { (0..6).map { Color.hsv(it*60f,1f,1f) } }
        ColorSlider(stringResource(R.string.color_hue),hue,0f..360f,hues,enabled,{hue=it;onChange(selected(),false)},{onChange(selected(),true)})
        ColorSlider(stringResource(R.string.color_saturation),saturation,0f..1f,listOf(Color.hsv(hue,0f,value),Color.hsv(hue,1f,value)),enabled,{saturation=it;onChange(selected(),false)},{onChange(selected(),true)})
        ColorSlider(stringResource(R.string.color_value),value,0f..1f,listOf(Color.Black,Color.hsv(hue,saturation,1f)),enabled,{value=it;onChange(selected(),false)},{onChange(selected(),true)})
        if(mixed) Text(stringResource(R.string.mixed_color_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(rgb==null) Text(stringResource(R.string.unknown),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        val essentials=remember { listOf(Rgb(255,188,119),Rgb(255,115,84),Rgb(233,115,171),Rgb(159,139,233),Rgb(115,180,244),Rgb(126,207,167),Rgb.White) }
        ColorSwatches(essentials,enabled=enabled,onColor={onChange(it,true)})
        if(favoriteColors.isNotEmpty()) { Text(stringResource(R.string.favorite_colors),style=MaterialTheme.typography.labelLarge); ColorSwatches(favoriteColors,enabled=enabled,onColor={onChange(it,true)}) }
        if(recentColors.isNotEmpty()) { Text(stringResource(R.string.recent_colors),style=MaterialTheme.typography.labelLarge); ColorSwatches(recentColors,enabled=enabled,onColor={onChange(it,true)}) }
        TextButton({exact=!exact},enabled=enabled,contentPadding=PaddingValues(0.dp)) { Text(stringResource(R.string.exact_color)); MeshIcon(if(exact) Glyph.CLOSE else Glyph.ADD,Modifier.padding(start=8.dp).size(18.dp)) }
        if(exact) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                val valid=hex.length==6 && runCatching { Rgb.fromHex(hex) }.isSuccess
                OutlinedTextField(value=hex,onValueChange={hex=it.removePrefix("#").take(6)},label={Text(stringResource(R.string.rgb_hex))},prefix={Text("#")},singleLine=true,isError=hex.isNotEmpty()&&!valid,enabled=enabled,modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
                Button({onChange(Rgb.fromHex(hex),true)},enabled=valid&&enabled) { Text(stringResource(R.string.apply)) }
            }
        }
    }
}

@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, colors: List<Color>, enabled: Boolean, onChange: (Float)->Unit, onFinished: ()->Unit) {
    Column {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box(contentAlignment=Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal=10.dp).height(12.dp).background(Brush.horizontalGradient(colors),CircleShape))
            Slider(value=value,onValueChange=onChange,onValueChangeFinished=onFinished,valueRange=range,enabled=enabled,
                colors=SliderDefaults.colors(activeTrackColor=Color.Transparent,inactiveTrackColor=Color.Transparent,disabledActiveTrackColor=Color.Transparent,disabledInactiveTrackColor=Color.Transparent),
                modifier=Modifier.fillMaxWidth().semantics { contentDescription=label })
        }
    }
}

@Composable
fun ColorSwatches(colors: List<Rgb>, modifier: Modifier = Modifier, enabled: Boolean = true, onColor: (Rgb)->Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        colors.forEach { rgb ->
            val label=stringResource(R.string.color_swatch,rgb.hex)
            Surface(onClick={onColor(rgb)},enabled=enabled,modifier=Modifier.size(48.dp).semantics { contentDescription=label },shape=CircleShape,color=Color.Transparent) {
                Box(Modifier.padding(6.dp).background(rgb.composeColor(),CircleShape))
            }
        }
    }
}
