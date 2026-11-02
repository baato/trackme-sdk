import Foundation
import TrackMeSDK

/// DEMO ONLY: gets an access token with the API secret.
/// In a real app this call lives on YOUR backend; the app receives the token and passes it to TrackMe.
enum DemoAuth {
    static let apiURL = "https://tracker.baato.io/trackme/api"

    static var secret: String {
        (Bundle.main.object(forInfoDictionaryKey: "TrackMeApiSecret") as? String) ?? ""
    }

    static var isConfigured: Bool { !secret.isEmpty && secret != "your_api_secret_here" }

    static func login(email: String, fullName: String) async -> String? {
        guard let url = URL(string: "\(apiURL)/v1/auth/login") else { return nil }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var body: [String: String] = ["email": email, "api_secret": secret]
        if !fullName.isEmpty { body["full_name"] = fullName }
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) == true,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return json["access_token"] as? String
    }
}

/// Kotlin interfaces become Objective-C protocols, so the conforming Swift class subclasses NSObject.
/// The SDK calls this when the server answers 401; call `onResult` exactly once.
final class DemoTokenProvider: NSObject, TrackMeTokenProvider {
    private let email: String
    private let fullName: String

    init(email: String, fullName: String) {
        self.email = email
        self.fullName = fullName
    }

    func refreshToken(onResult: @escaping (String?) -> Void) {
        Task { onResult(await DemoAuth.login(email: email, fullName: fullName)) }
    }
}
