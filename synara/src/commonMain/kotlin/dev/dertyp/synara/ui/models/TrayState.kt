package dev.dertyp.synara.ui.models

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrayState {
    private val _badgeColor = MutableStateFlow<Color?>(null)
    val badgeColor: StateFlow<Color?> = _badgeColor.asStateFlow()

    fun setBadgeColor(color: Color?) {
        _badgeColor.value = color
    }
}
