package com.saif.mosque.util

/** Generic UI state for lists / screens. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val retryAfterSec: Long? = null) : UiState<Nothing>
}

/** One-shot message for snackbars. [Msg.Res.detail] carries the raw technical
 *  error (e.g. the Firebase error code) so login problems are diagnosable. */
sealed interface Msg {
    data class Text(val message: String) : Msg
    data class Res(val resId: Int, val detail: String? = null) : Msg
}
