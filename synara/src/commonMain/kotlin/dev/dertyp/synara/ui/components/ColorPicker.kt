package dev.dertyp.synara.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.material.Hct
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

@Composable
fun ColorPicker(
    isOpen: Boolean,
    title: String,
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismissRequest: () -> Unit
) {
    val hct = remember(initialColor) { Hct.fromInt(initialColor.toArgb()) }
    var hue by remember { mutableStateOf(hct.hue.toFloat()) }
    var chroma by remember { mutableStateOf(hct.chroma.toFloat()) }
    var tone by remember { mutableStateOf(hct.tone.toFloat()) }

    val currentColor = remember(hue, chroma, tone) {
        Color(Hct.from(hue.toDouble(), chroma.toDouble(), tone.toDouble()).toInt())
    }

    SynaraDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(currentColor)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                )

                Spacer(modifier = Modifier.height(24.dp))

                ColorSlider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    label = stringResource(Res.string.hue),
                    trackBrush = Brush.horizontalGradient(
                        colors = List(36) { Color(Hct.from(it * 10.0, chroma.toDouble(), tone.toDouble()).toInt()) }
                    )
                )

                ColorSlider(
                    value = chroma,
                    onValueChange = { chroma = it },
                    valueRange = 0f..120f,
                    label = stringResource(Res.string.chroma),
                    trackBrush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(Hct.from(hue.toDouble(), 0.0, tone.toDouble()).toInt()),
                            Color(Hct.from(hue.toDouble(), 120.0, tone.toDouble()).toInt())
                        )
                    )
                )

                ColorSlider(
                    value = tone,
                    onValueChange = { tone = it },
                    valueRange = 0f..100f,
                    label = stringResource(Res.string.tone),
                    trackBrush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(Hct.from(hue.toDouble(), chroma.toDouble(), 0.0).toInt()),
                            Color(Hct.from(hue.toDouble(), chroma.toDouble(), 100.0).toInt())
                        )
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onColorSelected(currentColor)
                        onDismissRequest()
                    }) {
                        Text(stringResource(Res.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    trackBrush: Brush
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape)
                .background(trackBrush)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
