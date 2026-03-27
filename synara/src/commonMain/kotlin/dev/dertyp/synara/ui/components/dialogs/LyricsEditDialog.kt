package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.ILyricsSearch
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.SynaraIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

data class LyricLineData(
    val minutes: Int,
    val seconds: Int,
    val hundredths: Int,
    val content: String
)

@Composable
fun LyricsEditDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    initialLyrics: List<String>,
    onSave: (List<String>) -> Unit,
    artist: String,
    title: String,
    lyricsSearch: ILyricsSearch = koinInject(),
    logger: Logger = koinInject()
) {
    val scope = rememberCoroutineScope()
    
    val lines = remember(isOpen) {
        mutableStateListOf<LyricLineData>().apply {
            if (isOpen) {
                if (initialLyrics.isEmpty()) {
                    add(LyricLineData(0, 0, 0, ""))
                } else {
                    addAll(initialLyrics.map { parseLineToData(it) })
                }
            }
        }
    }
    
    var isSearching by remember { mutableStateOf(false) }

    SynaraDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxHeight(0.9f)
                .fillMaxWidth(0.9f)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.lyrics),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                        Text(
                            text = "$artist - $title",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSearching) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                scope.launch {
                                    isSearching = true
                                    try {
                                        val results = lyricsSearch.searchLyrics(artist, title)
                                        if (results.isNotEmpty()) {
                                            lines.clear()
                                            lines.addAll(results.map { parseLineToData(it) })
                                        }
                                    } catch (e: Exception) {
                                        logger.error(LogTag.RPC, e.message ?: "", e)
                                    } finally {
                                        isSearching = false
                                    }
                                }
                            }) {
                                Icon(
                                    SynaraIcons.Search.get(),
                                    contentDescription = stringResource(Res.string.search_lyrics)
                                )
                            }
                        }
                        IconButton(onClick = {
                            lines.add(LyricLineData(0, 0, 0, ""))
                        }) {
                            Icon(SynaraIcons.Add.get(), contentDescription = stringResource(Res.string.add_line))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(lines) { index, line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val timestampDigits =
                                "%02d%02d%02d".format(line.minutes, line.seconds, line.hundredths)

                            InternalTextField(
                                value = timestampDigits,
                                onValueChange = { newValue ->
                                    val digits = newValue.filter { it.isDigit() }
                                    val finalDigits = if (digits.length > 6) {
                                        digits.substring(digits.length - 6)
                                    } else {
                                        digits
                                    }
                                    val padded = finalDigits.padStart(6, '0')

                                    val m = padded.substring(0, 2).toInt().coerceIn(0, 99)
                                    val s = padded.substring(2, 4).toInt().coerceIn(0, 59)
                                    val h = padded.substring(4, 6).toInt().coerceIn(0, 99)
                                    lines[index] = line.copy(minutes = m, seconds = s, hundredths = h)
                                },
                                modifier = Modifier.width(110.dp),
                                visualTransformation = TimeTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.primary
                                ),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.5f
                                    ),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.3f
                                    ),
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )

                            InternalTextField(
                                value = line.content,
                                onValueChange = { lines[index] = line.copy(content = it) },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(Res.string.lyrics_hint)) },
                                textStyle = MaterialTheme.typography.bodyMedium,
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color.Transparent,
                                )
                            )

                            IconButton(
                                onClick = { lines.removeAt(index) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    SynaraIcons.Delete.get(),
                                    contentDescription = stringResource(Res.string.delete_line),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                )
                            }
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(lines.map { formatDataToLine(it) })
                            onDismissRequest()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(Res.string.save))
                    }
                }
            }
        }
    }
}

class TimeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val trimmed = if (text.text.length > 6) text.text.substring(0, 6) else text.text
        var out = ""
        for (i in trimmed.indices) {
            out += trimmed[i]
            if (i == 1 && trimmed.length > 2) out += ":"
            if (i == 3 && trimmed.length > 4) out += "."
        }

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 1) return offset
                if (offset <= 3) return offset + 1
                return offset + 2
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 2) return offset
                if (offset <= 5) return offset - 1
                return offset - 2
            }
        }

        return TransformedText(AnnotatedString(out), offsetMapping)
    }
}

private fun parseLineToData(line: String): LyricLineData {
    val timeRegex = Regex("""^\[(\d{2}):(\d{2})\.(\d{2,3})](.*)$""")
    val match = timeRegex.find(line)
    return if (match != null) {
        val mm = match.groupValues[1]
        val ss = match.groupValues[2]
        var hh = match.groupValues[3]
        if (hh.length > 2) hh = hh.substring(0, 2)
        else if (hh.length == 1) hh = "0$hh"
        
        LyricLineData(
            minutes = mm.toInt(),
            seconds = ss.toInt(),
            hundredths = hh.toInt(),
            content = match.groupValues[4].trim()
        )
    } else {
        LyricLineData(0, 0, 0, line.trim())
    }
}

private fun formatDataToLine(data: LyricLineData): String {
    return "[%02d:%02d.%02d] %s".format(data.minutes, data.seconds, data.hundredths, data.content)
}
