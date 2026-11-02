package io.baato.trackme

/** Every way an SDK call can fail. [message] is always safe to log or show. */
sealed class TrackMeError {
    abstract val message: String

    /** Called before [TrackMe.initialize] or after [TrackMe.dispose]. */
    data object NotInitialized : TrackMeError() {
        override val message = "TrackMe is not initialized. Call TrackMe.initialize() first."
    }

    /** 401, and the token provider (if any) couldn't supply a working token. */
    data class Unauthorized(override val message: String = "Access token missing, invalid or expired.") : TrackMeError()

    /** 403, e.g. a member trying an owner-only action. */
    data class Forbidden(override val message: String) : TrackMeError()

    /** 404, e.g. an unknown trip id or join code. */
    data class NotFound(override val message: String) : TrackMeError()

    /** 409, e.g. deleting a trip that is still live. */
    data class Conflict(override val message: String) : TrackMeError()

    /** Invalid input: caught by the SDK before sending, or a 400/422 from the server. */
    data class Validation(override val message: String) : TrackMeError()

    /** Offline, DNS failure, timeout. Usually worth retrying. */
    data class Network(override val message: String) : TrackMeError()

    /** 5xx from the server. */
    data class Server(val statusCode: Int, override val message: String) : TrackMeError()

    /** Location permission not granted. [canAskAgain] = false means only the Settings app can fix it. */
    data class LocationPermissionDenied(val canAskAgain: Boolean) : TrackMeError() {
        override val message = "Location permission not granted."
    }

    /** Location services switched off for the whole device. */
    data object LocationServicesDisabled : TrackMeError() {
        override val message = "Location services are turned off on this device."
    }

    /** Background sharing requested, but the app lacks the permission or capability for it. */
    data class BackgroundNotAllowed(override val message: String) : TrackMeError()

    /** Anything else (unexpected response shape, internal error). */
    data class Unknown(override val message: String) : TrackMeError()
}
