import SwiftUI

/// What the locate button shows.
enum LocateState {
    /// Location allowed and the map has your position: tap flies to you.
    case located
    /// Allowed, but no position yet (indoors, GPS warming up).
    case searching
    /// No permission, or Location Services off: tap asks / explains.
    case disabled
}

/// Round "show me" button: the location arrow on a blue gradient when your position is known, on gray (gently
/// pulsing) while there's no fix yet, and the struck-through arrow when location isn't available to the app.
/// Same look as the Android sample (Material `near_me` / `near_me_disabled`); here the SF Symbols equivalents.
struct LocateButton: View {
    let state: LocateState
    let action: () -> Void
    @State private var pulse = false

    var body: some View {
        Button(action: action) {
            Image(systemName: state == .disabled ? "location.slash.fill" : "location.fill")
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(state == .disabled ? Color(red: 0.83, green: 0.18, blue: 0.18) : .white)
                .opacity(state == .searching && pulse ? 0.45 : 1)
                .frame(width: 52, height: 52)
                .background(background)
                .clipShape(Circle())
                .shadow(color: .black.opacity(0.25), radius: 6, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .onAppear { startPulse() }
        .onChange(of: state) { _ in startPulse() }
    }

    @ViewBuilder private var background: some View {
        switch state {
        case .located:
            LinearGradient(colors: [Color(red: 0.12, green: 0.53, blue: 0.90), Color(red: 0.0, green: 0.67, blue: 0.76)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
        case .searching:
            LinearGradient(colors: [Color(white: 0.62), Color(white: 0.46)], startPoint: .topLeading, endPoint: .bottomTrailing)
        case .disabled:
            Color(.systemBackground)
        }
    }

    private var label: String {
        switch state {
        case .located: return "Show my location"
        case .searching: return "Looking for your location"
        case .disabled: return "Location unavailable"
        }
    }

    private func startPulse() {
        pulse = false
        guard state == .searching else { return }
        withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { pulse = true }
    }
}
