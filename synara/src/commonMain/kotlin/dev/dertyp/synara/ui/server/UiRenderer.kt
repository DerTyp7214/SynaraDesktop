package dev.dertyp.synara.ui.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.dertyp.services.IUiService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiAlign
import dev.dertyp.ui.UiButtonStyle
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiEmphasis
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiTextKind
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.ui_server_unsupported_component

@Composable
fun UiRenderer(
    component: UiComponent,
    host: UiHost,
    modifier: Modifier = Modifier,
) {
    when (component) {
        is UiComponent.Column -> UiColumn(component, host, modifier)
        is UiComponent.Row -> UiRow(component, host, modifier)
        is UiComponent.Grid -> UiGrid(component, host, modifier)
        is UiComponent.Card -> UiCard(component, host, modifier)
        is UiComponent.Section -> UiSection(component, host, modifier)
        is UiComponent.Form -> UiForm(component, host, modifier)
        is UiComponent.Text -> UiText(component, modifier)
        is UiComponent.Icon -> UiIconView(component.icon, modifier, 24.dp, component.tone.contentColor())
        is UiComponent.Image -> UiImage(component, modifier)
        is UiComponent.Badge -> UiBadge(component, modifier)
        is UiComponent.Stat -> UiStat(component, modifier)
        is UiComponent.Progress -> UiProgress(component, modifier)
        is UiComponent.Tile -> UiTile(component, host, modifier)
        is UiComponent.Button -> UiButton(component, host, modifier)
        is UiComponent.ListItem -> UiListItem(component, host, modifier)
        is UiComponent.Table -> UiTable(component, host, modifier)
        is UiComponent.Spacer -> Spacer(modifier.size(component.size.toDp()))
        is UiComponent.Divider -> HorizontalDivider(modifier)
        is UiComponent.EmptyState -> UiEmptyState(component, host, modifier)
        is UiComponent.Log -> UiLog(component, modifier)
        is UiComponent.Live -> UiLive(component, host, modifier)
        is UiComponent.Native -> UiNative(component, host, modifier)
        is UiComponent.Fallback -> UiFallback(component, modifier)
        is UiComponent.TextField -> UiTextField(component, host, modifier)
        is UiComponent.NumberField -> UiNumberField(component, modifier)
        is UiComponent.Switch -> UiSwitch(component, modifier)
        is UiComponent.Select -> UiSelect(component, modifier)
    }
}

private fun UiFormState?.bindingFor(action: UiAction): UiFormBinding? =
    if (this != null && action is UiAction.Invoke && action.formId == id) binding() else null

@Composable
private fun UiActionAnchor(
    action: UiAction?,
    host: UiHost,
    content: @Composable (onActivate: () -> Unit) -> Unit,
) {
    val form = LocalUiFormState.current
    if (action is UiAction.OpenMenu) {
        var open by remember { mutableStateOf(false) }
        Box {
            content { open = true }
            UiOpenMenuContent(
                menu = if (open) action else null,
                host = host,
                form = form,
                onDismiss = { open = false },
            )
        }
    } else {
        content { action?.let { host.dispatch(it, form.bindingFor(it)) } }
    }
}

@Composable
fun UiOpenMenuContent(
    menu: UiAction.OpenMenu?,
    host: UiHost,
    form: UiFormState? = null,
    onDismiss: () -> Unit,
    onItemDispatched: () -> Unit = onDismiss,
) {
    var stack by remember(menu) { mutableStateOf(listOfNotNull(menu)) }
    val current = stack.lastOrNull()
    SynaraMenu(
        expanded = current != null,
        onDismissRequest = {
            stack = emptyList()
            onDismiss()
        },
    ) {
        current?.title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        current?.items?.forEach { item ->
            key(item.id ?: item.label) {
                DropdownMenuItem(
                    text = { Text(item.label, color = item.tone.contentColor()) },
                    enabled = item.enabled,
                    leadingIcon = item.icon?.let { icon -> { UiIconView(icon, tint = item.tone.contentColor()) } },
                    onClick = {
                        val next = item.action
                        if (next is UiAction.OpenMenu) {
                            stack = stack + next
                        } else {
                            stack = emptyList()
                            onItemDispatched()
                            host.dispatch(next, form.bindingFor(next))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun UiColumn(component: UiComponent.Column, host: UiHost, modifier: Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = if (component.align == UiAlign.SPACE_BETWEEN) Arrangement.SpaceBetween
        else Arrangement.spacedBy(component.spacing.toDp()),
        horizontalAlignment = component.align.toHorizontal(),
    ) {
        component.children.forEachIndexed { index, child ->
            val weight = component.weights?.getOrNull(index)?.toFloat()
            var childModifier: Modifier = Modifier
            if (weight != null && weight > 0f) childModifier = childModifier.weight(weight)
            if (component.align == UiAlign.STRETCH) childModifier = childModifier.fillMaxWidth()
            UiRenderer(child, host, childModifier)
        }
    }
}

@Composable
private fun UiRow(component: UiComponent.Row, host: UiHost, modifier: Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (component.align == UiAlign.SPACE_BETWEEN) Arrangement.SpaceBetween
        else Arrangement.spacedBy(component.spacing.toDp()),
        verticalAlignment = component.align.toVertical(),
    ) {
        component.children.forEachIndexed { index, child ->
            val weight = component.weights?.getOrNull(index)?.toFloat()
            var childModifier: Modifier = Modifier
            if (weight != null && weight > 0f) childModifier = childModifier.weight(weight)
            UiRenderer(child, host, childModifier)
        }
    }
}

@Composable
private fun UiGrid(component: UiComponent.Grid, host: UiHost, modifier: Modifier) {
    val columns = component.columns.coerceAtLeast(1)
    val spacing = component.spacing.toDp()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        component.children.chunked(columns).forEach { rowChildren ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                rowChildren.forEach { child ->
                    Box(modifier = Modifier.weight(1f)) { UiRenderer(child, host, Modifier.fillMaxWidth()) }
                }
                repeat(columns - rowChildren.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun UiCard(component: UiComponent.Card, host: UiHost, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = component.tone.containerColor(),
            contentColor = component.tone.onContainerColor(),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (component.title != null || component.subtitle != null || component.icon != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    component.icon?.let { UiIconView(it, size = 24.dp) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        component.title?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        component.subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            component.children.forEach { UiRenderer(it, host, Modifier.fillMaxWidth()) }
            if (component.actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    component.actions.forEach { UiRenderer(it, host) }
                }
            }
        }
    }
}

@Composable
private fun UiSection(component: UiComponent.Section, host: UiHost, modifier: Modifier) {
    var collapsed by rememberSaveable(component.title) { mutableStateOf(component.collapsed) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (component.collapsible) Modifier.clickable { collapsed = !collapsed } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = component.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (component.collapsible) {
                Icon(
                    imageVector = if (collapsed) SynaraIcons.ExpandDown.get() else SynaraIcons.ExpandUp.get(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!component.collapsible || !collapsed) {
            component.children.forEach { UiRenderer(it, host, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun UiForm(component: UiComponent.Form, host: UiHost, modifier: Modifier) {
    val state = remember(component.id) { UiFormState(component.id) }
    CompositionLocalProvider(LocalUiFormState provides state) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            component.children.forEach { UiRenderer(it, host, Modifier.fillMaxWidth()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                component.cancelLabel?.let { label ->
                    TextButton(onClick = { state.reset() }, enabled = !state.busy) { Text(label) }
                }
                component.actions.forEach { UiRenderer(it, host) }
                Button(
                    onClick = { host.dispatch(component.submit, state.binding()) },
                    enabled = !state.busy,
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(component.submitLabel)
                }
            }
        }
    }
}

@Composable
private fun UiText(component: UiComponent.Text, modifier: Modifier) {
    Text(
        text = component.text,
        modifier = modifier,
        style = component.style.toTextStyle(),
        color = component.tone.contentColor().copy(alpha = component.emphasis.alpha()),
        fontWeight = if (component.emphasis == UiEmphasis.HIGH) FontWeight.Bold else null,
    )
}

@Composable
private fun UiImage(component: UiComponent.Image, modifier: Modifier) {
    val shape = if (component.rounded) CircleShape else MaterialTheme.shapes.small
    val imageId = component.imageId
    val url = component.url
    when {
        imageId != null -> SynaraImage(imageId = imageId, size = 160.dp, modifier = modifier, shape = shape)
        url != null -> AsyncImage(
            model = url,
            contentDescription = null,
            modifier = modifier.size(160.dp).clip(shape),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun UiBadge(component: UiComponent.Badge, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = component.tone.containerColor(),
        contentColor = component.tone.onContainerColor(),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            component.icon?.let { UiIconView(it, size = 14.dp) }
            Text(component.text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun UiStat(component: UiComponent.Stat, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            component.icon?.let { UiIconView(it, size = 16.dp, tint = component.tone.contentColor()) }
            Text(
                text = component.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = component.unit?.let { "${component.value} $it" } ?: component.value,
            style = MaterialTheme.typography.titleLarge,
            color = component.tone.contentColor(),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun UiProgress(component: UiComponent.Progress, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        component.label?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val value = component.value
        if (value == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { value.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun UiTile(component: UiComponent.Tile, host: UiHost, modifier: Modifier) {
    UiActionAnchor(component.action, host) { onActivate ->
        Card(
            modifier = modifier.then(
                if (component.action != null) Modifier.clickable(onClick = onActivate) else Modifier
            ),
            colors = CardDefaults.cardColors(
                containerColor = component.tone.containerColor(),
                contentColor = component.tone.onContainerColor(),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                component.icon?.let { UiIconView(it, size = 24.dp) }
                Text(component.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                component.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun UiButton(component: UiComponent.Button, host: UiHost, modifier: Modifier) {
    UiActionAnchor(component.action, host) { onActivate ->
        val content: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                component.icon?.let { UiIconView(it, size = 18.dp) }
                Text(component.label)
            }
        }
        when (component.style) {
            UiButtonStyle.PRIMARY -> Button(onClick = onActivate, modifier = modifier, enabled = component.enabled) { content() }
            UiButtonStyle.SECONDARY -> OutlinedButton(onClick = onActivate, modifier = modifier, enabled = component.enabled) { content() }
            UiButtonStyle.DESTRUCTIVE -> Button(
                onClick = onActivate,
                modifier = modifier,
                enabled = component.enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { content() }

            UiButtonStyle.TEXT -> TextButton(onClick = onActivate, modifier = modifier, enabled = component.enabled) { content() }
        }
    }
}

@Composable
private fun UiListItem(component: UiComponent.ListItem, host: UiHost, modifier: Modifier) {
    UiActionAnchor(component.action, host) { onActivate ->
        ListItem(
            modifier = modifier
                .fillMaxWidth()
                .then(if (component.action != null) Modifier.clickable(onClick = onActivate) else Modifier),
            headlineContent = { Text(component.title) },
            supportingContent = component.subtitle?.let { { Text(it) } },
            leadingContent = component.icon?.let { { UiIconView(it, size = 24.dp) } },
            trailingContent = component.trailing?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun UiTable(component: UiComponent.Table, host: UiHost, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            component.columns.forEach { column ->
                Text(
                    text = column,
                    modifier = Modifier.width(160.dp).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        component.rows.forEach { row ->
            UiActionAnchor(row.action, host) { onActivate ->
                Row(
                    modifier = Modifier
                        .then(if (row.action != null) Modifier.clickable(onClick = onActivate) else Modifier)
                        .padding(vertical = 8.dp),
                ) {
                    row.cells.forEach { cell ->
                        Text(
                            text = cell,
                            modifier = Modifier.width(160.dp).padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UiEmptyState(component: UiComponent.EmptyState, host: UiHost, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        component.icon?.let {
            UiIconView(it, size = 48.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
        Text(
            text = component.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        component.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (component.actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                component.actions.forEach { UiRenderer(it, host) }
            }
        }
    }
}

@Composable
private fun UiLog(component: UiComponent.Log, modifier: Modifier) {
    val lines = remember(component.lines, component.maxLines) { component.lines.takeLast(component.maxLines) }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp).padding(8.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UiLive(component: UiComponent.Live, host: UiHost, modifier: Modifier) {
    val uiService = koinInject<IUiService>()
    var child by remember(component.key, component.child) { mutableStateOf(component.child) }

    LaunchedEffect(host.contributionId, component.key, host.entityId) {
        uiService.subscribeLive(host.contributionId, component.key, host.entityId)
            .catch { if (it is CancellationException) throw it }
            .collect { update ->
                child = when (update) {
                    is UiLiveUpdate.Replace -> update.child
                    is UiLiveUpdate.AppendLines -> {
                        val log = child as? UiComponent.Log
                        if (log == null) child
                        else log.copy(lines = (log.lines + update.lines).takeLast(log.maxLines))
                    }
                }
            }
    }

    UiRenderer(child, host, modifier)
}

@Composable
private fun UiNative(component: UiComponent.Native, host: UiHost, modifier: Modifier) {
    val portal = host.portal(component.name)
    if (portal != null) {
        Box(modifier = modifier) { portal(component.params) }
    } else {
        component.fallback?.let { UiRenderer(it, host, modifier) }
    }
}

@Composable
private fun UiFallback(component: UiComponent.Fallback, modifier: Modifier) {
    Text(
        text = component.text ?: stringResource(Res.string.ui_server_unsupported_component),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.FieldSupport(error: String?, helper: String?) {
    val text = error ?: helper ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp),
    )
}

@Composable
private fun rememberFieldForm(): UiFormState {
    val orphan = remember { UiFormState("") }
    return LocalUiFormState.current ?: orphan
}

@Composable
private fun UiTextField(component: UiComponent.TextField, host: UiHost, modifier: Modifier) {
    val form = rememberFieldForm()
    remember(component.key, component.value) {
        form.seed(component.key, UiValue.of(component.value.orEmpty()))
        component.key
    }
    val value = form.text(component.key).orEmpty()
    val error = form.errors[component.key] ?: component.error
    val multiline = component.multiline || component.kind == UiTextKind.MULTILINE_URLS

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { form.set(component.key, UiValue.of(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (component.required) "${component.label} *" else component.label) },
            placeholder = component.placeholder?.let { { Text(it) } },
            enabled = component.enabled,
            isError = error != null,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            visualTransformation = if (component.secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (component.kind) {
                    UiTextKind.URL, UiTextKind.MULTILINE_URLS -> KeyboardType.Uri
                    UiTextKind.EMAIL -> KeyboardType.Email
                    UiTextKind.BARCODE -> KeyboardType.Number
                    UiTextKind.TEXT -> KeyboardType.Text
                }
            ),
        )
        FieldSupport(error, component.helper)
        if (component.toolbar.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                component.toolbar.forEach { UiRenderer(it, host) }
            }
        }
    }
}

@Composable
private fun UiNumberField(component: UiComponent.NumberField, modifier: Modifier) {
    val form = rememberFieldForm()
    remember(component.key, component.value) {
        form.seed(component.key, UiValue(number = component.value))
        component.key
    }
    val current = form.number(component.key)
    var text by remember(component.key) { mutableStateOf(component.value.formatNumber(component.step)) }
    val error = form.errors[component.key] ?: component.error

    fun commit(raw: String) {
        text = raw
        val parsed = raw.replace(',', '.').toDoubleOrNull()
        val clamped = parsed?.let {
            var v = it
            component.min?.let { min -> if (v < min) v = min }
            component.max?.let { max -> if (v > max) v = max }
            v
        }
        form.set(component.key, UiValue(number = clamped))
    }

    fun bump(delta: Double) {
        val base = current ?: component.min ?: 0.0
        var next = base + delta
        component.min?.let { if (next < it) next = it }
        component.max?.let { if (next > it) next = it }
        text = next.formatNumber(component.step)
        form.set(component.key, UiValue(number = next))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { commit(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (component.required) "${component.label} *" else component.label) },
            enabled = component.enabled,
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = component.step?.let { step ->
                {
                    Row {
                        IconButton(onClick = { bump(-step) }, enabled = component.enabled) {
                            Icon(SynaraIcons.ExpandDown.get(), contentDescription = null)
                        }
                        IconButton(onClick = { bump(step) }, enabled = component.enabled) {
                            Icon(SynaraIcons.ExpandUp.get(), contentDescription = null)
                        }
                    }
                }
            },
        )
        FieldSupport(error, component.helper)
    }
}

@Composable
private fun UiSwitch(component: UiComponent.Switch, modifier: Modifier) {
    val form = rememberFieldForm()
    remember(component.key, component.value) {
        form.seed(component.key, UiValue.of(component.value))
        component.key
    }
    val checked = form.flag(component.key) ?: component.value

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(component.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = checked,
                onCheckedChange = { form.set(component.key, UiValue.of(it)) },
                enabled = component.enabled,
            )
        }
        FieldSupport(null, component.helper)
    }
}

@Composable
private fun UiSelect(component: UiComponent.Select, modifier: Modifier) {
    val form = rememberFieldForm()
    remember(component.key, component.value) {
        form.seed(component.key, UiValue.of(component.value.orEmpty()))
        component.key
    }
    val selected = form.text(component.key)
    val selectedOption = component.options.firstOrNull { it.value == selected }
    val error = form.errors[component.key] ?: component.error
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (component.required) "${component.label} *" else component.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = component.enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                selectedOption?.icon?.let {
                    UiIconView(it, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = selectedOption?.label ?: selected.orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                Icon(SynaraIcons.ChevronDown.get(), contentDescription = null)
            }
            SynaraMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                component.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = option.icon?.let { icon -> { UiIconView(icon) } },
                        onClick = {
                            expanded = false
                            form.set(component.key, UiValue.of(option.value))
                        },
                    )
                }
            }
        }
        FieldSupport(error, component.helper)
    }
}

private fun Double?.formatNumber(step: Double?): String {
    if (this == null) return ""
    val whole = this % 1.0 == 0.0
    return if (whole && (step == null || step % 1.0 == 0.0)) toLong().toString() else toString()
}
