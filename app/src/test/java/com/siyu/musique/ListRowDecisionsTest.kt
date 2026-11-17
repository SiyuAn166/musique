package com.siyu.musique

import com.siyu.musique.model.Track
import com.siyu.musique.ui.PendingTapDecision
import com.siyu.musique.ui.QueueRowTapDecision
import com.siyu.musique.ui.RowTapDecision
import com.siyu.musique.ui.decideListRowTap
import com.siyu.musique.ui.decideQueueRowTap
import com.siyu.musique.ui.isCurrentListRow
import com.siyu.musique.ui.listRowShowsParts
import com.siyu.musique.ui.queueRowShowsParts
import com.siyu.musique.ui.resolvePendingListRowTap
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap, expansion, and highlight rules shared by the search
 * list, the playlist detail, and the queue sheet. The one rule
 * everything else follows: an entry naming a specific part is
 * that song, never the collection.
 */
class ListRowDecisionsTest {

    private fun track(id: String, cid: String, title: String = "T") = Track(
        id = id,
        cid = cid,
        title = title,
        artist = "A",
        cover = "",
        duration = 200.seconds,
    )

    private val video = track("BV1multi", "")
    private val partOne = track("BV1multi", "101", "001. Part One")
    private val partTwo = track("BV1multi", "102", "002. Part Two")
    private val other = track("BV1other", "111", "Other")
    private val twoParts = listOf(partOne, partTwo)

    @Test
    fun `a part entry plays at once even with known parts`() {
        assertEquals(
            RowTapDecision.Play,
            decideListRowTap(partOne, twoParts, expanded = false),
        )
    }

    @Test
    fun `a part entry plays at once without waiting for a load`() {
        assertEquals(
            RowTapDecision.Play,
            decideListRowTap(partOne, knownParts = null, expanded = false),
        )
    }

    @Test
    fun `a whole video with known parts toggles its expansion`() {
        assertEquals(
            RowTapDecision.ToggleExpansion,
            decideListRowTap(video, twoParts, expanded = false),
        )
        assertEquals(
            RowTapDecision.ToggleExpansion,
            decideListRowTap(video, twoParts, expanded = true),
        )
    }

    @Test
    fun `a whole video with one known part plays`() {
        assertEquals(
            RowTapDecision.Play,
            decideListRowTap(video, listOf(partOne), expanded = false),
        )
    }

    @Test
    fun `a whole video with no known parts plays`() {
        assertEquals(
            RowTapDecision.Play,
            decideListRowTap(video, emptyList(), expanded = false),
        )
    }

    @Test
    fun `a whole video with unknown parts waits for the load`() {
        assertEquals(
            RowTapDecision.LoadParts,
            decideListRowTap(video, knownParts = null, expanded = false),
        )
    }

    @Test
    fun `a pending tap drops when its row left the list`() {
        assertEquals(
            PendingTapDecision.Drop,
            resolvePendingListRowTap(video, twoParts, selecting = false, stillPresent = false),
        )
    }

    @Test
    fun `a pending tap drops when selection mode took over`() {
        assertEquals(
            PendingTapDecision.Drop,
            resolvePendingListRowTap(video, twoParts, selecting = true, stillPresent = true),
        )
        assertEquals(
            PendingTapDecision.Drop,
            resolvePendingListRowTap(
                video,
                listOf(partOne),
                selecting = true,
                stillPresent = true,
            ),
        )
    }

    @Test
    fun `a pending part entry plays`() {
        assertEquals(
            PendingTapDecision.Play,
            resolvePendingListRowTap(partOne, twoParts, selecting = false, stillPresent = true),
        )
    }

    @Test
    fun `a pending whole video expands when it has parts`() {
        assertEquals(
            PendingTapDecision.Expand,
            resolvePendingListRowTap(video, twoParts, selecting = false, stillPresent = true),
        )
    }

    @Test
    fun `a pending whole video plays when it has one part or none`() {
        assertEquals(
            PendingTapDecision.Play,
            resolvePendingListRowTap(
                video,
                listOf(partOne),
                selecting = false,
                stillPresent = true,
            ),
        )
        assertEquals(
            PendingTapDecision.Play,
            resolvePendingListRowTap(video, emptyList(), selecting = false, stillPresent = true),
        )
    }

    @Test
    fun `the playing videos whole-video row toggles its parts tree`() {
        assertEquals(
            QueueRowTapDecision.ToggleParts,
            decideQueueRowTap(video, partOne, twoParts),
        )
    }

    @Test
    fun `a part-entry row in the queue plays instead of toggling`() {
        assertEquals(
            QueueRowTapDecision.Play,
            decideQueueRowTap(partOne, partOne, twoParts),
        )
    }

    @Test
    fun `a row for another video plays`() {
        assertEquals(
            QueueRowTapDecision.Play,
            decideQueueRowTap(other, partOne, twoParts),
        )
    }

    @Test
    fun `the playing video row plays when it has one part or none`() {
        assertEquals(
            QueueRowTapDecision.Play,
            decideQueueRowTap(video, partOne, listOf(partOne)),
        )
        assertEquals(
            QueueRowTapDecision.Play,
            decideQueueRowTap(video, partOne, emptyList()),
        )
    }

    @Test
    fun `a queue row plays when nothing is loaded`() {
        assertEquals(
            QueueRowTapDecision.Play,
            decideQueueRowTap(video, currentTrack = null, twoParts),
        )
    }

    @Test
    fun `a whole-video row grows the tree only expanded with several parts`() {
        assertTrue(listRowShowsParts(video, twoParts, expanded = true))
        assertFalse(listRowShowsParts(video, twoParts, expanded = false))
        assertFalse(listRowShowsParts(video, listOf(partOne), expanded = true))
        assertFalse(listRowShowsParts(video, emptyList(), expanded = true))
        assertFalse(listRowShowsParts(video, knownParts = null, expanded = true))
    }

    @Test
    fun `a part entry never grows the tree`() {
        assertFalse(listRowShowsParts(partOne, twoParts, expanded = true))
    }

    @Test
    fun `the queue tree grows only under the playing videos whole-video row`() {
        assertTrue(queueRowShowsParts(video, partOne, twoParts, expanded = true))
        assertFalse(queueRowShowsParts(partOne, partOne, twoParts, expanded = true))
        assertFalse(queueRowShowsParts(other, partOne, twoParts, expanded = true))
        assertFalse(queueRowShowsParts(video, partOne, twoParts, expanded = false))
        assertFalse(queueRowShowsParts(video, partOne, listOf(partOne), expanded = true))
        assertFalse(queueRowShowsParts(video, partOne, emptyList(), expanded = true))
        assertFalse(queueRowShowsParts(video, currentTrack = null, twoParts, expanded = true))
    }

    @Test
    fun `a whole-video row stays lit while any part plays`() {
        assertTrue(isCurrentListRow(video, partOne))
        assertTrue(isCurrentListRow(video, partTwo))
        assertFalse(isCurrentListRow(video, other))
        assertFalse(isCurrentListRow(video, currentTrack = null))
    }

    @Test
    fun `a part row lights only when that part plays`() {
        assertTrue(isCurrentListRow(partOne, partOne))
        assertFalse(isCurrentListRow(partOne, partTwo))
        assertFalse(isCurrentListRow(partOne, other))
        assertFalse(isCurrentListRow(partOne, currentTrack = null))
    }
}
