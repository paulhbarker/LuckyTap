package com.luckytap.app

/**
 * One-shot events emitted by the ViewModel for the Activity to consume.
 * This replaces direct Toast calls from the ViewModel, keeping it free of Android framework references.
 */
sealed class UiEvent {
    data class ShowToast(val message: String, val longDuration: Boolean = false) : UiEvent()
}
