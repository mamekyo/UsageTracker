package com.mamekyo.usagetracker.net

import java.io.IOException

/** Sign-in failure with a machine-readable [reason]; the UI turns it into localized text. */
class LoginException(val reason: Reason, val detail: String? = null) :
    IOException(listOfNotNull(reason.name, detail).joinToString(": ")) {
    enum class Reason {
        CODE_MISSING,
        STATE_MISMATCH,
        CODE_INVALID,
        BAD_RESPONSE,
        PORT_UNAVAILABLE,
        DENIED,
        CALLBACK_URL_INVALID,
        CALLBACK_NO_CODE,
        DEVICE_DISABLED,
        DEVICE_EXPIRED,
        NOT_STARTED,
    }
}
