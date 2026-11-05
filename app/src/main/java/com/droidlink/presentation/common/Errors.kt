package com.droidlink.presentation.common

import com.droidlink.domain.usecase.InvalidPairingCodeException
import com.droidlink.network.ApiException

/** Maps any failure to a short message that is safe and useful to show to the user. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message ?: "The request failed."
    is InvalidPairingCodeException -> message ?: "Invalid pairing code."
    is IllegalArgumentException -> message ?: "Invalid input."
    else -> "Something went wrong. Please try again."
}

/** Friendly text for the error codes a remote device reports back during a session. */
fun remoteErrorText(code: String): String = when (code) {
    "accessibility_service_disabled" -> "The other device has not enabled DroidLink's Accessibility service, so it cannot be controlled."
    "control_not_authorised", "scope_control_required" -> "Remote control is not allowed in this session."
    "scope_view_required" -> "Screen viewing is not allowed in this session."
    "scope_files_required" -> "File transfer is not allowed in this session."
    "password_field_blocked" -> "Typing into password fields is blocked for safety."
    "no_focused_field" -> "No text field is focused on the other device."
    "no_other_field" -> "There is no other field to move to."
    "droidlink_screen_protected" -> "DroidLink's own screens cannot be controlled remotely."
    "app_not_launchable" -> "That app cannot be opened remotely."
    "blocked_by_system" -> "Android blocked that action."
    "rate_limited" -> "Slow down - too many actions at once."
    "enter_not_supported_here" -> "Enter is not supported in this field."
    else -> "The other device could not do that ($code)."
}
