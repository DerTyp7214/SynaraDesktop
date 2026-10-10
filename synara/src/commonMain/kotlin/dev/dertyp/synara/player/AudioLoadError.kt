package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID

sealed class AudioLoadError {
    data object UnsupportedFormat : AudioLoadError()
    data object Unavailable : AudioLoadError()
    data class Failed(val message: String?) : AudioLoadError()
}

data class AudioLoadFailure(val songId: PlatformUUID?, val error: AudioLoadError)
