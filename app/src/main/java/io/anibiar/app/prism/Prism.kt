package io.anibiar.app.prism

import org.json.JSONObject

/**
 * Prism: the streaming engine that lives inside the app. It finds an anime on each source site, lists the episodes
 * (numbered the way AniList numbers them) and opens the video servers of an episode, all on the phone, so no
 * streaming API of ours has to be online. AniKoto, AniWaves and AnimeGG are ported from the Anivexa providers.
 */
object Prism {
    const val NAME = "Prism"
    val providers = listOf("anikoto", "aniwaves", "animegg")

    /** `{meta, episodes:{sub,dub}}` for one provider. */
    suspend fun episodes(provider: String, id: Int): JSONObject = when (provider) {
        "anikoto" -> AniKoto.episodes(id)
        "aniwaves" -> AniWaves.episodes(id)
        "animegg" -> AnimeGG.episodes(id)
        else -> throw PrismException("Unknown source $provider")
    }

    /** `{streams, intro, outro, ...}` for one provider, one episode. */
    suspend fun watch(provider: String, id: Int, audio: String, episode: Int): JSONObject = when (provider) {
        "anikoto" -> AniKoto.watch(id, audio, episode)
        "aniwaves" -> AniWaves.watch(id, audio, episode)
        "animegg" -> AnimeGG.watch(id, audio, episode)
        else -> throw PrismException("Unknown source $provider")
    }
}
