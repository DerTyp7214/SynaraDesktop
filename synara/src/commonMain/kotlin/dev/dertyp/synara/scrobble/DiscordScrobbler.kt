package dev.dertyp.synara.scrobble

import com.russhwolf.settings.Settings
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.synara.player.PlayerModel

expect class DiscordScrobbler(
    settings: Settings,
    playerModel: PlayerModel,
    metadataService: IMetadataService
) : BaseScrobbler
