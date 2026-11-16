package com.siyu.musique.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.siyu.musique.R

/**
 * The localized message for a search or resolve failure. A
 * reported Bilibili business error code names the code in the
 * message; without one, the generic message applies. Read at
 * composition time so the text follows the app language.
 */
@Composable
fun failureMessage(kind: SearchErrorKind, apiCode: Int?): String = when (kind) {
    SearchErrorKind.NETWORK -> stringResource(R.string.error_network)
    SearchErrorKind.BLOCKED -> stringResource(R.string.error_blocked)
    SearchErrorKind.RATE_LIMITED -> stringResource(R.string.error_rate_limited)
    SearchErrorKind.PARSE -> stringResource(R.string.error_parse)
    SearchErrorKind.API -> if (apiCode != null) {
        stringResource(R.string.error_api_code, apiCode)
    } else {
        stringResource(R.string.error_unknown)
    }
}

/**
 * The localized message for a playback failure. A resolve
 * failure renders like a search failure; an engine failure
 * wraps the engine's detail in the localized template.
 */
@Composable
fun playerErrorMessage(failure: PlaybackFailure): String = when (failure) {
    is PlaybackFailure.Resolve -> failureMessage(failure.kind, failure.apiCode)
    is PlaybackFailure.Engine -> stringResource(R.string.error_playback_failed, failure.detail)
}
