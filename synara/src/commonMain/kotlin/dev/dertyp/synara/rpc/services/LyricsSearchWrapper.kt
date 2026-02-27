package dev.dertyp.synara.rpc.services

import dev.dertyp.services.ILyricsSearch
import dev.dertyp.synara.rpc.RpcServiceManager

class LyricsSearchWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ILyricsSearch {
    override suspend fun searchLyrics(artist: String, title: String, syncedOnly: Boolean): List<String> {
        return manager.getService<ILyricsSearch>().searchLyrics(artist, title, syncedOnly)
    }
}
