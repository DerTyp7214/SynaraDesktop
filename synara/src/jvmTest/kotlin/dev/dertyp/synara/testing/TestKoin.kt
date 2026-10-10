package dev.dertyp.synara.testing

import com.russhwolf.settings.Settings
import dev.dertyp.logging.Logger
import dev.dertyp.synara.Config
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.mockk
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module

fun startTestKoin(vararg modules: Module): Koin {
    stopTestKoin()
    return startKoin {
        allowOverride(true)
        modules(
            module {
                single<Settings> { InMemorySettings() }
                single<Logger> { mockk(relaxed = true) }
                single<SynaraDispatchers> { UnconfinedDispatchers }
            },
            *modules
        )
    }.koin
}

fun stopTestKoin() {
    if (GlobalContext.getOrNull() != null) stopKoin()
}

object ConfigFixture {
    fun resetToDefaults() {
        Config.setIsServerScrobblingEnabled(true)
        Config.setIsListenBrainzEnabled(false)
        Config.setIsLastFmEnabled(false)
        Config.setIsDiscordRpcEnabled(false)
        Config.setIsRemoteControlEnabled(false)
        Config.setIsQueueSyncEnabled(false)
        Config.setQueueSyncDeviceName("")
        Config.setPodcastsEnabled(false)
        Config.setPodcastPlaybackSpeed(1f)
        Config.setIsSettingsSyncEnabled(false)
        Config.setIsSecretsSyncEnabled(false)
        Config.setSyncSetupShown(false)
        Config.setRemoteControlOptInShown(false)
        Config.setPodcastOptInShown(false)
        Config.setShowTitleTagsInText(true)
    }
}
