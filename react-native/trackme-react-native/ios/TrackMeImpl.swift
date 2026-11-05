import Foundation
import TrackMeSDK

/// Pass-through to `TrackMeBridge` (docs/07-ports.md): strings in, strings out. All SDK logic, and all mapping to
/// JSON, is in the SDK. Called from the Objective-C++ shim in TrackMe.mm. Promises and the event emitter are
/// thread-safe, so there's no hop to the main thread.
@objc(TrackMeImpl)
public final class TrackMeImpl: NSObject {
    private let emit: (String, [String: Any]) -> Void
    private let lock = NSLock()
    private var subscription: BridgeSubscription?
    private var pendingTokens: [Int: (String?) -> Void] = [:]
    private var nextRequest = 0

    @objc public init(emit: @escaping (String, [String: Any]) -> Void) {
        self.emit = emit
    }

    @objc public func start(_ configJson: String, hasTokenProvider: Bool) -> String {
        let provider: TrackMeTokenProvider? = hasTokenProvider ? JsTokenProvider(impl: self) : nil
        // Sharing on the lock screen: the Live Activity (with a Widget Extension) or the notification.
        if LiveActivityPresenter.wanted(configJson: configJson) { Task { @MainActor in LiveActivityPresenter.install() } }
        return TrackMeBridge.shared.start(context: PlatformContext(), configJson: configJson, tokenProvider: provider)
    }

    @objc public func call(_ method: String, argsJson: String, completion: @escaping (String) -> Void) {
        TrackMeBridge.shared.call(method: method, argsJson: argsJson) { envelope in completion(envelope) }
    }

    @objc public func provideToken(_ requestId: Int, token: String?) {
        lock.lock()
        let callback = pendingTokens.removeValue(forKey: requestId)
        lock.unlock()
        callback?(token)
    }

    @objc public func startObserving() {
        lock.lock(); defer { lock.unlock() }
        subscription?.cancel()
        subscription = TrackMeBridge.shared.observe { [weak self] json in self?.emit("TrackMeEvent", ["json": json]) }
    }

    @objc public func stopObserving() {
        lock.lock(); defer { lock.unlock() }
        subscription?.cancel()
        subscription = nil
    }

    /// JS is going away (reload, app exit). The SDK session (and any sharing) keeps running natively.
    @objc public func invalidate() {
        stopObserving()
        lock.lock()
        let waiting = Array(pendingTokens.values)
        pendingTokens.removeAll()
        lock.unlock()
        waiting.forEach { $0(nil) }
    }

    fileprivate func askJsForToken(_ onResult: @escaping (String?) -> Void) {
        lock.lock()
        nextRequest += 1
        let id = nextRequest
        pendingTokens[id] = onResult
        lock.unlock()
        emit("TrackMeRefreshToken", ["requestId": id])
    }
}

/// Asks JS for a fresh token on a 401. No answer → the SDK's own timeout gives up.
private final class JsTokenProvider: NSObject, TrackMeTokenProvider {
    private weak var impl: TrackMeImpl?
    init(impl: TrackMeImpl) { self.impl = impl }

    func refreshToken(onResult: @escaping (String?) -> Void) {
        guard let impl else { onResult(nil); return }
        impl.askJsForToken(onResult)
    }
}

/// `sharing_ui.ios_live_activity`: false = the app shows its own lock-screen UI. Anything else = ours.
/// (LiveActivityPresenter is the native sample's, copied into this package by scripts/port-ios-sdk.sh.)
extension LiveActivityPresenter {
    static func wanted(configJson: String) -> Bool {
        guard let data = configJson.data(using: .utf8),
              let config = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let ui = config["sharing_ui"] as? [String: Any] else { return true }
        return ui["ios_live_activity"] as? Bool ?? true
    }
}
