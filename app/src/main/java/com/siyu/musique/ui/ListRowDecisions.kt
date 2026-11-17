package com.siyu.musique.ui

import com.siyu.musique.model.Track

/**
 * The pure decisions behind every song list in the app: which tap
 * expands a collection, which plays, which waits for a parts load,
 * which rows grow the parts tree, and which rows light up as the
 * current track.
 *
 * These lived inline in the composables, where no unit test could
 * reach them, and the same rule drifted apart across the three
 * lists: a playlist entry naming one part expanded its collection
 * in the playlist detail, then did the same in the queue sheet,
 * and both shipped before a device caught them. Every rule lives
 * here now, once, with tests, and the screens only apply the
 * answer.
 *
 * The one rule everything else follows: an entry that names a
 * specific part (a non-empty cid) is that song, never the
 * collection. Only a whole-video entry (an empty cid) expands,
 * grows the parts tree, or toggles it.
 */

/** What tapping a list row does. */
sealed interface RowTapDecision {
    /** A collection with known parts: flip its expanded state. */
    data object ToggleExpansion : RowTapDecision

    /** Play the row: a single video, or an entry naming one part. */
    data object Play : RowTapDecision

    /** The parts are unknown: load them, and let the pending tap decide when they land. */
    data object LoadParts : RowTapDecision
}

/**
 * The tap on a search row or a playlist entry. A part entry plays
 * at once, without waiting for any load. A whole-video entry with
 * known parts toggles its expansion when the video has more than
 * one part and plays when it has one or none; with unknown parts
 * the tap waits on the load.
 */
fun decideListRowTap(
    track: Track,
    knownParts: List<Track>?,
    expanded: Boolean,
): RowTapDecision {
    if (track.cid.isNotEmpty()) return RowTapDecision.Play
    if (knownParts == null) return RowTapDecision.LoadParts
    if (knownParts.size > 1) return RowTapDecision.ToggleExpansion
    return RowTapDecision.Play
}

/** What a pending tap does once its parts load lands. */
sealed interface PendingTapDecision {
    /** A collection: expand it under its row. */
    data object Expand : PendingTapDecision

    /** A single video, or an entry naming one part: play it. */
    data object Play : PendingTapDecision

    /** The tap is stale: drop it without expanding or playing. */
    data object Drop : PendingTapDecision
}

/**
 * The tap that waited on a parts load, now that [parts] landed.
 * A tap drops when the row is gone ([stillPresent] false: the
 * search moved on, or the entry left the playlist) or when
 * selection mode took over meanwhile, in both cases before any
 * expand or play. Otherwise a part entry plays, a multi-part
 * whole video expands, and anything else plays.
 */
fun resolvePendingListRowTap(
    pending: Track,
    parts: List<Track>,
    selecting: Boolean,
    stillPresent: Boolean,
): PendingTapDecision {
    if (!stillPresent || selecting) return PendingTapDecision.Drop
    if (pending.cid.isNotEmpty()) return PendingTapDecision.Play
    if (parts.size > 1) return PendingTapDecision.Expand
    return PendingTapDecision.Play
}

/** What tapping a queue sheet row does. */
sealed interface QueueRowTapDecision {
    /** The playing video's whole-video row: flip its parts tree. */
    data object ToggleParts : QueueRowTapDecision

    /** Every other row: play it, leaving the queue untouched. */
    data object Play : QueueRowTapDecision
}

/**
 * The tap on a queue sheet row. Only the playing video's
 * whole-video row toggles the parts tree, and only while the
 * video is known to have more than one part. A row naming a
 * specific part plays that part instead of expanding, and so
 * does any row for another video.
 */
fun decideQueueRowTap(
    row: Track,
    currentTrack: Track?,
    currentParts: List<Track>,
): QueueRowTapDecision {
    if (row.cid.isEmpty() &&
        row.id == currentTrack?.id &&
        currentParts.size > 1
    ) {
        return QueueRowTapDecision.ToggleParts
    }
    return QueueRowTapDecision.Play
}

/**
 * Whether a search row or playlist entry grows the parts tree
 * under it: whole-video entries only, expanded, with more than
 * one known part. A part entry never grows the tree.
 */
fun listRowShowsParts(
    track: Track,
    knownParts: List<Track>?,
    expanded: Boolean,
): Boolean =
    track.cid.isEmpty() &&
        expanded &&
        knownParts != null &&
        knownParts.size > 1

/**
 * Whether a queue sheet row grows the parts tree under it: the
 * playing video's whole-video row only, expanded, with more
 * than one known part. A part entry never grows the tree, so
 * the sheet shows the queue itself rather than the collection.
 */
fun queueRowShowsParts(
    row: Track,
    currentTrack: Track?,
    currentParts: List<Track>,
    expanded: Boolean,
): Boolean =
    row.cid.isEmpty() &&
        expanded &&
        row.id == currentTrack?.id &&
        currentParts.size > 1

/**
 * Whether a list row is the current track. A whole-video row
 * matches by video id, so it stays lit while any of its parts
 * plays; a row naming a specific part matches by key, so it
 * only lights up when that part itself plays.
 */
fun isCurrentListRow(row: Track, currentTrack: Track?): Boolean {
    if (currentTrack == null) return false
    return if (row.cid.isEmpty()) {
        row.id == currentTrack.id
    } else {
        row.key == currentTrack.key
    }
}
