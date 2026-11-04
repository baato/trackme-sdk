import Flutter
import TrackMeSDK
import UIKit

/// Pass-through to `TrackMeBridge` (docs/07-ports.md): strings in, strings out. All SDK logic, and all mapping to
/// JSON, is in the SDK. Flutter wants every answer on the main thread, so results and events hop there.
///
/// Channels: `io.baato.trackme/bridge` (start, call; refreshToken goes the other way) and `io.baato.trackme/events`.
public final class TrackmeFlutterPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    private var methods: FlutterMethodChannel?
    private var subscription: BridgeSubscription?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let plugin = TrackmeFlutterPlugin()
        let methods = FlutterMethodChannel(name: "io.baato.trackme/bridge", binaryMessenger: registrar.messenger())
        plugin.methods = methods
        registrar.addMethodCallDelegate(plugin, channel: methods)
        FlutterEventChannel(name: "io.baato.trackme/events", binaryMessenger: registrar.messenger()).setStreamHandler(plugin)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? [String: Any] ?? [:]
        switch call.method {
        case "start":
            let provider: TrackMeTokenProvider? = (args["hasTokenProvider"] as? Bool ?? false) ? DartTokenProvider(plugin: self) : nil
            result(TrackMeBridge.shared.start(context: PlatformContext(),
                                              configJson: args["config"] as? String ?? "{}",
                                              tokenProvider: provider))
        case "call":
            TrackMeBridge.shared.call(method: args["method"] as? String ?? "", argsJson: args["args"] as? String ?? "{}") { envelope in
                DispatchQueue.main.async { result(envelope) }
            }
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        subscription?.cancel()
        var mine: BridgeSubscription?
        mine = TrackMeBridge.shared.observe { [weak self] json in
            DispatchQueue.main.async {
                guard let self, let mine, self.subscription === mine else { return }   // nothing after onCancel
                events(json)
            }
        }
        subscription = mine
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        subscription?.cancel()
        subscription = nil
        return nil
    }

    fileprivate func askDartForToken(_ onResult: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard let methods = self.methods else { onResult(nil); return }
            methods.invokeMethod("refreshToken", arguments: nil) { value in onResult(value as? String) }
        }
    }
}

/// Asks Dart for a fresh token on a 401. No Dart side any more → nil; the SDK's own timeout applies too.
private final class DartTokenProvider: NSObject, TrackMeTokenProvider {
    private weak var plugin: TrackmeFlutterPlugin?
    init(plugin: TrackmeFlutterPlugin) { self.plugin = plugin }

    func refreshToken(onResult: @escaping (String?) -> Void) {
        guard let plugin else { onResult(nil); return }
        plugin.askDartForToken(onResult)
    }
}
