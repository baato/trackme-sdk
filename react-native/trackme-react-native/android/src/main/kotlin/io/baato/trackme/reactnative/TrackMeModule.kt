package io.baato.trackme.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import io.baato.trackme.PlatformContext
import io.baato.trackme.TrackMeTokenProvider
import io.baato.trackme.bridge.BridgeSubscription
import io.baato.trackme.bridge.TrackMeBridge
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pass-through to [TrackMeBridge] (docs/07-ports.md): strings in, strings out. All SDK logic, and all mapping to
 * JSON, is in the SDK. Promises and the event emitter are thread-safe, so no hop to the main thread is needed.
 *
 * Events: `TrackMeEvent` `{json}` while JS listens, and `TrackMeRefreshToken` `{requestId}`, answered by
 * [provideToken].
 */
@ReactModule(name = TrackMeModule.NAME)
class TrackMeModule(private val ctx: ReactApplicationContext) : NativeTrackMeSpec(ctx) {
    private var listeners = 0
    private var subscription: BridgeSubscription? = null
    private val pendingTokens = ConcurrentHashMap<Int, (String?) -> Unit>()
    private val nextRequest = AtomicInteger()

    override fun getName() = NAME

    override fun start(configJson: String, hasTokenProvider: Boolean, promise: Promise) {
        val provider = if (hasTokenProvider) JsTokenProvider() else null
        promise.resolve(TrackMeBridge.start(PlatformContext(ctx), resolveSmallIcon(configJson), provider))
    }

    override fun call(method: String, argsJson: String, promise: Promise) {
        TrackMeBridge.call(method, argsJson) { envelope -> promise.resolve(envelope) }
    }

    override fun provideToken(requestId: Double, token: String?) {
        pendingTokens.remove(requestId.toInt())?.invoke(token)
    }

    @Synchronized
    override fun addListener(eventName: String) {
        if (listeners++ == 0) {
            subscription = TrackMeBridge.observe { json -> emit("TrackMeEvent", json) }
        }
    }

    @Synchronized
    override fun removeListeners(count: Double) {
        listeners = (listeners - count.toInt()).coerceAtLeast(0)
        if (listeners == 0) {
            subscription?.cancel()
            subscription = null
        }
    }

    /** JS is going away (reload, app exit). The SDK session (and any sharing) keeps running natively. */
    override fun invalidate() {
        subscription?.cancel()
        subscription = null
        pendingTokens.values.forEach { it(null) }
        pendingTokens.clear()
        super.invalidate()
    }

    private fun emit(name: String, json: String) {
        if (!ctx.hasActiveReactInstance()) return
        ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(name, Arguments.createMap().apply { putString("json", json) })
    }

    /** Asks JS for a fresh token on a 401. No answer → the SDK's own timeout gives up. */
    private inner class JsTokenProvider : TrackMeTokenProvider {
        override fun refreshToken(onResult: (String?) -> Unit) {
            if (!ctx.hasActiveReactInstance()) return onResult(null)
            val id = nextRequest.incrementAndGet()
            pendingTokens[id] = onResult
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("TrackMeRefreshToken", Arguments.createMap().apply { putInt("requestId", id) })
        }
    }

    /** `sharing_ui.android_small_icon_name` (a drawable in the app) → `android_small_icon` (its resource id). */
    private fun resolveSmallIcon(config: String): String = runCatching {
        val root = JSONObject(config)
        val ui = root.optJSONObject("sharing_ui") ?: return config
        val name = ui.optString("android_small_icon_name", "")
        if (name.isEmpty()) return config
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        if (id != 0) ui.put("android_small_icon", id)
        ui.remove("android_small_icon_name")
        root.toString()
    }.getOrDefault(config)

    companion object {
        const val NAME = "TrackMe"
    }
}
