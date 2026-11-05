package io.baato.trackme.flutter

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.baato.trackme.PlatformContext
import io.baato.trackme.TrackMeTokenProvider
import io.baato.trackme.bridge.BridgeSubscription
import io.baato.trackme.bridge.TrackMeBridge
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/**
 * Pass-through to [TrackMeBridge] (docs/07-ports.md): strings in, strings out. All SDK logic, and all mapping
 * to JSON, is in the SDK. Flutter wants every answer on the main thread, so results and events hop there.
 *
 * Channels: `io.baato.trackme/bridge` (start, call; refreshToken goes the other way) and `io.baato.trackme/events`.
 */
class TrackmeFlutterPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var context: Context
    private var methods: MethodChannel? = null
    private var events: EventChannel? = null
    private var subscription: BridgeSubscription? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, "io.baato.trackme/bridge").also { it.setMethodCallHandler(this) }
        events = EventChannel(binding.binaryMessenger, "io.baato.trackme/events").also { it.setStreamHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // The SDK session (and any sharing) keeps running natively; only the link to Dart goes away.
        methods?.setMethodCallHandler(null)
        events?.setStreamHandler(null)
        methods = null
        events = null
        subscription?.cancel()
        subscription = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                val config = resolveSmallIcon(call.argument<String>("config") ?: "{}")
                val provider = if (call.argument<Boolean>("hasTokenProvider") == true) DartTokenProvider() else null
                result.success(TrackMeBridge.start(PlatformContext(context), config, provider))
            }
            "call" -> TrackMeBridge.call(call.argument<String>("method") ?: "", call.argument<String>("args") ?: "{}") { envelope ->
                main.post { result.success(envelope) }
            }
            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
        subscription?.cancel()
        lateinit var mine: BridgeSubscription
        mine = TrackMeBridge.observe { json ->
            main.post { if (subscription === mine) sink.success(json) }   // nothing after onCancel
        }
        subscription = mine
    }

    override fun onCancel(arguments: Any?) {
        subscription?.cancel()
        subscription = null
    }

    /** Asks Dart (`refreshToken` on the method channel). No Dart side any more → null; the SDK's timeout applies too. */
    private inner class DartTokenProvider : TrackMeTokenProvider {
        override fun refreshToken(onResult: (String?) -> Unit) {
            main.post {
                val channel = methods ?: return@post onResult(null)
                channel.invokeMethod("refreshToken", null, object : MethodChannel.Result {
                    override fun success(value: Any?) = onResult(value as? String)
                    override fun error(code: String, message: String?, details: Any?) = onResult(null)
                    override fun notImplemented() = onResult(null)
                })
            }
        }
    }

    /** `sharing_ui.android_small_icon_name` (a drawable in the app) → `android_small_icon` (its resource id). */
    private fun resolveSmallIcon(config: String): String = runCatching {
        val root = JSONObject(config)
        val ui = root.optJSONObject("sharing_ui") ?: return config
        val name = ui.optString("android_small_icon_name", "")
        if (name.isEmpty()) return config
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id != 0) ui.put("android_small_icon", id)
        ui.remove("android_small_icon_name")
        root.toString()
    }.getOrDefault(config)
}
