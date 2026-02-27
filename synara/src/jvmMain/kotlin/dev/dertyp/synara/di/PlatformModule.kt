package dev.dertyp.synara.di

import dev.dertyp.synara.player.AudioPlayer
import dev.dertyp.synara.player.JvmAudioPlayer
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    singleOf(::JvmAudioPlayer) bind AudioPlayer::class
}
