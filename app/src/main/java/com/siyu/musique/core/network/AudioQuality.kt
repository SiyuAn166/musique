package com.siyu.musique.core.network

/**
 * The audio quality preference for play URL picking. The ids are the
 * DASH audio stream ids from /x/player/playurl: 30216 is 64K, 30232
 * is 132K, 30280 is 192K, 30250 is Dolby, 30251 is Hi-Res. Dolby and
 * Hi-Res need a premium membership and are normally absent from
 * anonymous playurl responses; trying them first costs nothing,
 * because absent ids are simply skipped.
 *
 * [BEST] is the default (ordered Nov 14, 2026: the old 192K default
 * sounded too low): it tries every id from highest to lowest.
 * Explicit choices fall back DOWNWARD only, never upward: picking
 * 192K never silently upgrades to Dolby or Hi-Res, and picking 64K
 * never upgrades at all. [tryOrder] is the exact order
 * [pickAudioStream] walks; selection is by id, never by array index.
 *
 * The preference is persisted by name (see
 * [com.siyu.musique.data.QualityPreferenceStore]); unknown stored
 * names map back to [BEST] so a renamed or corrupt value can never
 * break playback.
 */
enum class AudioQuality(
    /** The label shown in the quality selector. */
    val label: String,

    /** Stream ids to try, in order. */
    val tryOrder: List<Int>,
) {
    BEST("Best", listOf(30251, 30250, 30280, 30232, 30216)),
    Q192("192K", listOf(30280, 30232, 30216)),
    Q132("132K", listOf(30232, 30216)),
    Q64("64K", listOf(30216));

    companion object {
        /** The stored enum name back to a preference; null or unknown names mean [BEST]. */
        fun fromStoredName(name: String?): AudioQuality =
            entries.firstOrNull { it.name == name } ?: BEST

        /** The badge label for a resolved DASH stream id, or null when the id is unknown. */
        fun labelForStreamId(id: Int): String? = when (id) {
            30216 -> "64K"
            30232 -> "132K"
            30280 -> "192K"
            30250 -> "Dolby"
            30251 -> "Hi-Res"
            else -> null
        }
    }
}
