package dev.dertyp.synara.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.PlatformUUID
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IUiService
import dev.dertyp.synara.screens.AlbumScreen
import dev.dertyp.synara.screens.ArtistScreen
import dev.dertyp.synara.screens.PlaylistScreen
import dev.dertyp.synara.screens.SearchScreen
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiButtonStyle
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiEntityType
import dev.dertyp.ui.UiHookHandler
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIntakeStatus
import dev.dertyp.ui.UiInvokePayload
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiPortals
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

val ServerUiLogTag = LogTag("server-ui")

typealias UiPortalContent = @Composable (params: Map<String, String>) -> Unit

fun interface UiPortalRegistry {
    fun portal(name: String): UiPortalContent?

    companion object {
        val Empty = UiPortalRegistry { null }
    }
}

data class UiFormBinding(
    val formId: String,
    val values: () -> Map<String, UiValue>,
    val onFieldErrors: (Map<String, String>) -> Unit = {},
    val onBusy: (Boolean) -> Unit = {},
)

data class UiEntry(
    val title: String,
    val subtitle: String? = null,
    val icon: UiIcon?,
    val action: UiAction?,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

fun UiComponent.asEntry(): UiEntry? = when (this) {
    is UiComponent.ListItem -> UiEntry(title, subtitle, icon, action)
    is UiComponent.Tile -> UiEntry(title, subtitle, icon, action)
    is UiComponent.Button -> UiEntry(label, icon = icon, action = action, enabled = enabled, destructive = style == UiButtonStyle.DESTRUCTIVE)
    else -> null
}

interface UiHost {
    val contributionId: String
    val context: UiContext
    val entityId: PlatformUUID? get() = context.entityId

    fun dispatch(action: UiAction, form: UiFormBinding? = null)
    fun portal(name: String): UiPortalContent?
    fun message(text: String)
    fun refresh()
}

val LocalUiHost = staticCompositionLocalOf<UiHost?> { null }

class DefaultUiHost(
    override val contributionId: String,
    override val context: UiContext,
    private val scope: CoroutineScope,
    private val uiService: IUiService,
    private val snackbarManager: SnackbarManager,
    private val logger: Logger,
    private val portals: UiPortalRegistry = UiPortalRegistry.Empty,
    private val onRefresh: () -> Unit = {},
    private val onNavigate: (Screen) -> Unit = {},
    private val onOpenUrl: (String) -> Unit = {},
    private val onSearchQuery: (String) -> Unit = {},
) : UiHost {

    internal class PendingConfirm(val text: String, val onConfirm: () -> Unit)
    internal class PendingChoice(val handlers: List<UiHookHandler>)

    internal var pendingConfirm by mutableStateOf<PendingConfirm?>(null)
    internal var pendingChoice by mutableStateOf<PendingChoice?>(null)
    internal var pendingModalPage by mutableStateOf<UiAction.OpenPage?>(null)
    internal var pendingSong by mutableStateOf<PlatformUUID?>(null)

    override fun portal(name: String): UiPortalContent? = portals.portal(name)

    override fun message(text: String) {
        if (text.isNotBlank()) snackbarManager.showSnackbar(text)
    }

    override fun refresh() = onRefresh()

    override fun dispatch(action: UiAction, form: UiFormBinding?) {
        when (action) {
            is UiAction.Invoke -> confirmed(action.confirmText) { invoke(action, form) }
            is UiAction.Intake -> confirmed(action.confirmText) { intake(action.items, action.resolverId) }
            is UiAction.OpenMenu -> logger.info(ServerUiLogTag, "OpenMenu dispatched without an anchor")
            is UiAction.OpenEntity -> openEntity(action)
            is UiAction.OpenPage ->
                if (action.modal) pendingModalPage = action
                else onNavigate(UiPageScreen(action.pageId, action.params))

            is UiAction.OpenUrl -> onOpenUrl(action.url)
            is UiAction.OpenNative -> openNative(action)
            UiAction.DismissKeyboard -> Unit
            UiAction.Refresh -> refresh()
        }
    }

    private fun confirmed(confirmText: String?, block: () -> Unit) {
        if (confirmText.isNullOrBlank()) block() else pendingConfirm = PendingConfirm(confirmText, block)
    }

    private fun invoke(action: UiAction.Invoke, form: UiFormBinding?) {
        val formValues = if (form != null && action.formId == form.formId) form.values() else emptyMap()
        scope.launch {
            form?.onBusy(true)
            try {
                val result = uiService.invoke(
                    action.contributionId,
                    action.actionId,
                    UiInvokePayload(values = action.params + formValues, context = context),
                )
                result.message?.let { message(it) }
                if (result.status == UiInvokeStatus.VALIDATION_ERROR) {
                    form?.onFieldErrors(result.fieldErrors)
                } else {
                    form?.onFieldErrors(emptyMap())
                }
                if (result.refresh) refresh()
                result.next?.let { dispatch(it, form) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(ServerUiLogTag, "invoke ${action.actionId} failed", e)
                message(e.message ?: e.toString())
            } finally {
                form?.onBusy(false)
            }
        }
    }

    internal fun intake(items: List<IntakeItem>, resolverId: String?) {
        scope.launch {
            try {
                val result = uiService.intake(items, resolverId)
                result.message?.let { message(it) }
                if (result.status == UiIntakeStatus.NEEDS_CHOICE && result.handlers.isNotEmpty()) {
                    pendingChoice = PendingChoice(result.handlers)
                }
                result.next?.let { dispatch(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(ServerUiLogTag, "intake failed", e)
                message(e.message ?: e.toString())
            }
        }
    }

    private fun openEntity(action: UiAction.OpenEntity) {
        when (action.entityType) {
            UiEntityType.ALBUM -> onNavigate(AlbumScreen(action.entityId))
            UiEntityType.ARTIST -> onNavigate(ArtistScreen(action.entityId))
            UiEntityType.PLAYLIST -> onNavigate(PlaylistScreen(action.entityId, isUserPlaylist = true))
            UiEntityType.SONG -> pendingSong = action.entityId
            UiEntityType.USER -> logger.info(ServerUiLogTag, "OpenEntity USER is not supported on desktop")
        }
    }

    private fun openNative(action: UiAction.OpenNative) {
        when (action.name) {
            UiPortals.EXTERNAL_SEARCH -> {
                onSearchQuery(action.params["query"].orEmpty())
                onNavigate(SearchScreen())
            }

            UiPortals.BARCODE_SCANNER ->
                logger.info(ServerUiLogTag, "barcodeScanner portal is not supported on desktop")

            else -> logger.info(ServerUiLogTag, "unknown native portal ${action.name}")
        }
    }
}

@Composable
fun rememberUiHost(
    contributionId: String,
    context: UiContext = UiContext(),
    portals: UiPortalRegistry = SynaraUiPortals.default(),
    onRefresh: () -> Unit = {},
): DefaultUiHost {
    val uiService = koinInject<IUiService>()
    val snackbarManager = koinInject<SnackbarManager>()
    val logger = koinInject<Logger>()
    val globalState = koinInject<GlobalStateModel>()
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val currentRefresh by rememberUpdatedState(onRefresh)
    val currentNavigator by rememberUpdatedState(navigator)
    val currentUriHandler by rememberUpdatedState(uriHandler)

    return remember(contributionId, context, portals) {
        DefaultUiHost(
            contributionId = contributionId,
            context = context,
            scope = scope,
            uiService = uiService,
            snackbarManager = snackbarManager,
            logger = logger,
            portals = portals,
            onRefresh = { currentRefresh() },
            onNavigate = { screen -> currentNavigator?.push(screen) },
            onOpenUrl = { url -> runCatching { currentUriHandler.openUri(url) } },
            onSearchQuery = { query -> globalState.setSearchQuery(query) },
        )
    }
}
