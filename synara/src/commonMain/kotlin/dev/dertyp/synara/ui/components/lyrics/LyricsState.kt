package dev.dertyp.synara.ui.components.lyrics

import dev.dertyp.services.models.LyricsCandidate

sealed interface LyricsState {
    data object Loading : LyricsState
    data class Loaded(val candidate: LyricsCandidate, val display: LyricsDisplay) : LyricsState
    data object Empty : LyricsState
    data object Failed : LyricsState
}
