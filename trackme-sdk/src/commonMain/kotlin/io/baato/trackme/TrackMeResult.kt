package io.baato.trackme

/**
 * Every public SDK operation returns this instead of throwing, so the host app can't crash on a
 * network error, an expired token or a call made before [TrackMe.initialize].
 *
 * Kotlin: `when (r) { is TrackMeResult.Success -> r.data; is TrackMeResult.Failure -> r.error }`
 * Swift:  `if let s = r as? TrackMeResultSuccess<Trip> { s.data } else if let f = r as? TrackMeResultFailure { f.error }`
 */
sealed class TrackMeResult<out T> {
    data class Success<out T>(val data: T) : TrackMeResult<T>()
    data class Failure(val error: TrackMeError) : TrackMeResult<Nothing>()

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): TrackMeError? = (this as? Failure)?.error

    inline fun onSuccess(action: (T) -> Unit): TrackMeResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onFailure(action: (TrackMeError) -> Unit): TrackMeResult<T> {
        if (this is Failure) action(error)
        return this
    }
}
