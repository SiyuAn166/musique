package com.siyu.musique.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Every icon the app uses, as custom [ImageVector]s built from the
 * standard 24x24 Material icon path data. They are drawn here
 * instead of using androidx.compose.material.icons so the app needs
 * no icons dependency at all: the material-icons-core artifact is
 * not on the dependency list, and whether material3 pulls it in
 * could not be verified from the sandbox. The vectors are filled
 * black like the Material originals, and [androidx.compose.material3.Icon]
 * tints them with its [androidx.compose.material3.Icon] tint color.
 * Path data is the published Material Icons set (Apache 2.0).
 */
object MusiqueIcons {

    val Search: ImageVector = icon(
        "Search",
        "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
    )

    /** The X that clears the search field and cancels dialogs. */
    val Clear: ImageVector = icon(
        "Clear",
        "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    )

    /** Bulleted list: the playlists entry on the search screen. */
    val ListBulleted: ImageVector = icon(
        "ListBulleted",
        "M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-6c-.83 0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-1.5.68-1.5 1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-6h14v-2H7v2zm0-8v2h14V5H7z",
    )

    /** Three horizontal dots: the settings menu on the search screen. */
    val MoreHoriz: ImageVector = icon(
        "MoreHoriz",
        "M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
    )

    val PlayArrow: ImageVector = icon("PlayArrow", "M8 5v14l11-7z")

    val Pause: ImageVector = icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")

    val SkipPrevious: ImageVector = icon("SkipPrevious", "M6 6h2v12H6zm3.5 6l8.5 6V6z")

    val SkipNext: ImageVector = icon("SkipNext", "M6 18l8.5-6L6 6v12zM16 6h2v12h-2z")

    /** Circular refresh arrow: every retry action. */
    val Refresh: ImageVector = icon(
        "Refresh",
        "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
    )

    /** Circled plus: add a track to a playlist, create a playlist. */
    val AddCircleOutline: ImageVector = icon(
        "AddCircleOutline",
        "M13 7h-2v4H7v2h4v4h2v-4h4v-2h-4V7zm-1-5C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
    )

    /** Circled minus: remove a track from a playlist. */
    val RemoveCircleOutline: ImageVector = icon(
        "RemoveCircleOutline",
        "M7 11v2h10v-2H7zm5-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
    )

    /** Right chevron: playlist rows, opening the detail screen. */
    val ChevronRight: ImageVector = icon(
        "ChevronRight",
        "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
    )

    /** Left chevron: back from the playlists and detail screens. */
    val ChevronLeft: ImageVector = icon(
        "ChevronLeft",
        "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z",
    )

    /** Down chevron: back from the player page, quality chip affordance. */
    val ChevronDown: ImageVector = icon(
        "ChevronDown",
        "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z",
    )

    /** Check mark: the selected quality, confirming dialogs. */
    val Check: ImageVector = icon(
        "Check",
        "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    )

    /** Copy: the log screen's copy action. */
    val ContentCopy: ImageVector = icon(
        "ContentCopy",
        "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z",
    )

    /** Speaker with waves: the loud end of the volume slider. */
    val VolumeUp: ImageVector = icon(
        "VolumeUp",
        "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    )

    /** Speaker with one wave: the quiet end of the volume slider. */
    val VolumeDown: ImageVector = icon(
        "VolumeDown",
        "M18.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 9v6h4l5 5V4L9 9H5z",
    )

    /** Crossing arrows: the shuffle toggle on the player page. */
    val Shuffle: ImageVector = icon(
        "Shuffle",
        "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z",
    )

    /** Loop arrows: the repeat button (off / repeat all) on the player page. */
    val Repeat: ImageVector = icon(
        "Repeat",
        "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z",
    )

    /** Loop arrows with a 1: the repeat button in repeat-one mode. */
    val RepeatOne: ImageVector = icon(
        "RepeatOne",
        "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z",
    )

    /** Trash: clear the log, delete a playlist. */
    val Delete: ImageVector = icon(
        "Delete",
        "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
    )

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                pathFillType = PathFillType.NonZero,
                fill = SolidColor(Color.Black),
            )
        }.build()
}
