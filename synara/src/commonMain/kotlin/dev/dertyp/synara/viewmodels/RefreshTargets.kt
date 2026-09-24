package dev.dertyp.synara.viewmodels

import androidx.compose.ui.input.key.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

fun isRefreshShortcut(
    key: Key,
    isCtrlPressed: Boolean,
    isMetaPressed: Boolean,
    isAltPressed: Boolean,
    isShiftPressed: Boolean,
): Boolean {
    if (isAltPressed || isShiftPressed) return false
    return when (key) {
        Key.F5 -> !isCtrlPressed && !isMetaPressed
        Key.R -> isCtrlPressed != isMetaPressed
        else -> false
    }
}

class RefreshTargets {
    private val _targets = MutableStateFlow<List<Refreshable>>(emptyList())
    val targets: StateFlow<List<Refreshable>> = _targets.asStateFlow()

    val current: Refreshable? get() = _targets.value.lastOrNull()

    fun register(target: Refreshable) {
        _targets.update { targets -> targets.filterNot { it === target } + target }
    }

    fun unregister(target: Refreshable) {
        _targets.update { targets -> targets.filterNot { it === target } }
    }

    fun refreshCurrent(): Boolean {
        val target = current ?: return false
        target.refresh()
        return true
    }
}

class GlobalShortcuts(
    private val refreshTargets: RefreshTargets,
    private val globalState: GlobalStateModel,
) {
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (!isRefreshShortcut(event.key, event.isCtrlPressed, event.isMetaPressed, event.isAltPressed, event.isShiftPressed)) {
            return false
        }
        if (globalState.isPlayerExpanded.value || globalState.isAnyOverlayOpen.value) return false
        return refreshTargets.refreshCurrent()
    }
}
