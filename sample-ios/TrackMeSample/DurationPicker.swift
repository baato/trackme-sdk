import SwiftUI

/// Trip length: presets plus "Custom", any whole number of minutes. The number pad only has digits, so nothing
/// negative or fractional can be typed. The limits are the server's (the API spec says 5–1440 today): out-of-range
/// values come back as a Validation error with the server's message, so this UI doesn't hard-code them.
/// `minutes` is -1 while the field is empty. The text field takes the surrounding `textFieldStyle`.
struct DurationPicker: View {
    let presets: [(label: String, minutes: Int)]
    @Binding var minutes: Int
    @State private var custom = false
    @State private var text = ""
    @FocusState private var typing: Bool

    private static let customTag = -2

    var body: some View {
        Picker("Duration", selection: selection) {
            ForEach(presets.indices, id: \.self) { i in Text(presets[i].label).tag(presets[i].minutes) }
            Text("Custom").tag(Self.customTag)
        }
        .pickerStyle(.segmented)
        .onAppear {
            if !presets.contains(where: { $0.minutes == minutes }) {
                custom = true
                text = minutes >= 0 ? String(minutes) : ""
            }
        }

        if custom {
            HStack {
                TextField("Minutes", text: $text)
                    .keyboardType(.numberPad)
                    .focused($typing)
                    .onChange(of: text) { typed in
                        let digits = String(typed.filter(\.isASCIIDigit).prefix(5))   // pasted text too
                        if digits != typed { text = digits }
                        minutes = Int(digits) ?? -1
                    }
                if typing { Button("Done") { typing = false } }   // the number pad has no return key
            }
            if minutes >= 0 {
                Text("= \(formatMinutes(minutes))").font(.caption).foregroundColor(.secondary)
            }
        }
    }

    private var selection: Binding<Int> {
        Binding(
            get: { custom ? Self.customTag : minutes },
            set: { value in
                if value == Self.customTag {
                    custom = true
                    if text.isEmpty { text = String(max(0, minutes)) }
                    minutes = Int(text) ?? -1
                    typing = true
                } else {
                    custom = false
                    minutes = value
                }
            }
        )
    }
}

private extension Character {
    var isASCIIDigit: Bool { ("0"..."9").contains(self) }
}

/// 90 → "1 h 30 min", 45 → "45 min", 1440 → "24 h".
func formatMinutes(_ minutes: Int) -> String {
    let h = minutes / 60, m = minutes % 60
    if h == 0 { return "\(m) min" }
    return m == 0 ? "\(h) h" : "\(h) h \(m) min"
}
