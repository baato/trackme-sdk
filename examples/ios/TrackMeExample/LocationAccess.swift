import SwiftUI
import TrackMeSDK

/// Small status chip on the live map: what location access the app has. Tap → `LocationAccessSheet`.
struct LocationPill: View {
    @EnvironmentObject var model: SampleModel
    let onTap: () -> Void

    var body: some View {
        let (text, color) = state
        Button(action: onTap) {
            HStack(spacing: 6) {
                Circle().fill(color).frame(width: 8, height: 8)
                Text(text).font(.footnote.weight(.semibold)).foregroundColor(.primary)
            }
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(.regularMaterial, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private var state: (String, Color) {
        guard let p = model.permission else { return ("Location", .gray) }
        if !p.servicesEnabled { return ("Location is off", .red) }
        if !p.isGranted { return ("No location access", .red) }
        let approx = p.precise ? "" : " · approx."
        if p.level.isAlways { return ("Location: always" + approx, .green) }
        return ("Location: while using" + approx, .orange)
    }
}

/// What each level means for sharing, which one the app has, and the one action that improves it.
struct LocationAccessSheet: View {
    @EnvironmentObject var model: SampleModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let p = model.permission
        let level = p?.level
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Location access").font(.title3.bold())
                Spacer()
                Button("Close") { dismiss() }
            }
            LevelRow(title: "Not allowed", text: "Nobody in your trip sees you, and the map can't show you.",
                     selected: !model.locationGranted)
            LevelRow(title: "While using the app",
                     text: "Enough to share. Sharing you start keeps running in the background, shown on the lock screen.",
                     selected: level?.isWhileInUse == true)
            LevelRow(title: "Always",
                     text: "Also lets sharing start while the app is in the background, e.g. from the lock screen.",
                     selected: level?.isAlways == true)

            if p?.servicesEnabled == false { note("Location Services are off for the whole iPhone (Settings → Privacy & Security).", error: true) }
            if !model.locationGranted && !model.locationCanAsk { note("iOS won't ask again: allow location in Settings.") }
            if model.locationGranted && p?.precise == false { note("Precise Location is off, so others see an approximate position.") }

            Button { act() } label: { Text(actionLabel).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
        }
        .padding(20)
    }

    private var actionLabel: String {
        let p = model.permission
        if p?.servicesEnabled == false || (!model.locationGranted && !model.locationCanAsk) { return "Open Settings" }
        if !model.locationGranted { return "Allow" }
        if p?.level.isWhileInUse == true { return "Allow always" }
        return "Open Settings"
    }

    private func act() {
        dismiss()
        let p = model.permission
        if p?.servicesEnabled == false { model.openSettings() }
        else if !model.locationGranted { model.askLocation() }   // the prompt, or Settings once iOS won't ask
        else if p?.level.isWhileInUse == true { model.askLocationAlways() }
        else { model.openSettings() }
    }

    private func note(_ text: String, error: Bool = false) -> some View {
        Text(text).font(.footnote).foregroundColor(error ? .red : .secondary)
    }
}

private struct LevelRow: View {
    let title: String
    let text: String
    let selected: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                .foregroundColor(selected ? .accentColor : .secondary)
                .font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(selected ? .bold : .regular))
                Text(text).font(.footnote).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// Kotlin enum cases reach Swift renamed (`WhileInUse` → `.whileinuse`), so compare by the Kotlin `name`,
/// which stays exactly as declared in the SDK.
extension LocationPermissionLevel {
    var isNotDetermined: Bool { name == "NotDetermined" }
    var isWhileInUse: Bool { name == "WhileInUse" }
    var isAlways: Bool { name == "Always" }
}

extension View {
    /// Half-height sheet on iOS 16+, a normal sheet on iOS 15.
    @ViewBuilder func mediumDetent() -> some View {
        if #available(iOS 16.0, *) { presentationDetents([.medium]) } else { self }
    }
}
