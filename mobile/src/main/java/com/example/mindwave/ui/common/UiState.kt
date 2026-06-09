package com.example.mindwave.ui.common

/**
 * Generic UI-state envelope for screens that load asynchronous data.
 *
 * TODO: Use this across screens that load data
 */
sealed interface UiState<out T> {
    /** Initial / in-flight load. */
    data object Loading : UiState<Nothing>

    /** Load succeeded but there is nothing to show */
    data object Empty : UiState<Nothing>

    /** Load succeeded with content. */
    data class Success<T>(val data: T) : UiState<T>

    /** Load failed; [message] is safe to surface to the user. */
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>
}
