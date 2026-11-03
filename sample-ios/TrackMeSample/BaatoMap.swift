import CoreLocation
import MapLibre
import SwiftUI
import UIKit

/// Baato vector style. The access token comes from Secrets.xcconfig (BAATO_ACCESS_TOKEN → Info.plist), never from code.
enum Baato {
    static let style = "breeze"
    static var token: String { (Bundle.main.object(forInfoDictionaryKey: "BaatoAccessToken") as? String) ?? "" }
    static var isConfigured: Bool { !token.isEmpty && token != "your_baato_access_token_here" }
    static var styleURL: URL? { URL(string: "https://api.baato.io/api/v1/styles/\(style)?key=\(token)") }

    /// Map centre until we know where the user is.
    static let kathmandu = CLLocationCoordinate2D(latitude: 27.7172, longitude: 85.3240)
}

/// Build-time switch: Developer screens next to the Live map, or the Live map only.
enum AppConfig {
    static var devTools: Bool {
        let raw = ((Bundle.main.object(forInfoDictionaryKey: "TrackMeDevTools") as? String) ?? "").uppercased()
        if ["YES", "TRUE", "1"].contains(raw) { return true }
        if ["NO", "FALSE", "0"].contains(raw) { return false }
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
}

/// One dot on the map.
struct MapMarker: Equatable {
    let id: String
    let label: String
    let latitude: Double
    let longitude: Double
    let color: UIColor
}

/// "Fly to this marker". `seq` makes repeated taps on the same member count.
struct MapFocus: Equatable {
    let markerId: String
    let seq: Int
}

/// Stable colour per member, so a person keeps their colour across updates.
private let palette: [UIColor] = [0xE53935, 0x8E24AA, 0x3949AB, 0x00897B, 0xF4511E, 0x6D4C41, 0x039BE5, 0x7CB342].map {
    UIColor(red: CGFloat(($0 >> 16) & 0xFF) / 255, green: CGFloat(($0 >> 8) & 0xFF) / 255, blue: CGFloat($0 & 0xFF) / 255, alpha: 1)
}
func markerColor(_ key: String) -> UIColor {
    // Not hashValue: that changes between launches.
    palette[Int(key.unicodeScalars.reduce(UInt32(0)) { ($0 &* 31) &+ $1.value } % UInt32(palette.count))]
}

/// MapLibre map with the Baato style, member dots + names, and the blue "you" dot.
/// - `fitKey`: when it changes (e.g. a different trip), the camera fits all markers + you once.
/// - `recenterRequest`: bump it to fly back to you (or to the markers when your location is unknown).
/// - `focus`: fly to one marker (a tapped member chip). If that marker isn't drawn (it's you), fly to you.
/// - `bottomInset`: height of the panel over the map. It becomes the map's content inset, so every
///   "centre on X" lands in the visible part above the panel, not behind it.
/// - `onMarkerTap`: a member dot was tapped (its `MapMarker.id`).
/// - `showsUser`: the blue "you" dot. Only on once location is allowed: turning it on makes MapLibre ask for
///   permission by itself, and the app wants to decide when that prompt appears.
struct BaatoMapView: UIViewRepresentable {
    let markers: [MapMarker]
    let fitKey: String?
    let recenterRequest: Int
    var focus: MapFocus? = nil
    var bottomInset: CGFloat = 0
    var onMarkerTap: (String) -> Void = { _ in }
    var showsUser = false
    /// Whether MapLibre has a position for the blue dot (false while searching, or when it can't locate).
    var onUserLocated: (Bool) -> Void = { _ in }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: Baato.styleURL)
        map.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        map.setCenter(Baato.kathmandu, zoomLevel: 12, animated: false)
        map.showsUserLocation = showsUser
        map.automaticallyAdjustsContentInset = false   // we set it: top buttons + the panel
        map.delegate = context.coordinator
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c = context.coordinator
        c.onMarkerTap = onMarkerTap
        c.onUserLocated = onUserLocated
        if map.showsUserLocation != showsUser { map.showsUserLocation = showsUser }
        // Keep the logical map centre between the top buttons and the panel.
        let inset = UIEdgeInsets(top: map.safeAreaInsets.top + 56, left: 0, bottom: map.safeAreaInsets.bottom + bottomInset + 12, right: 0)
        if inset != c.lastInset {
            c.lastInset = inset
            map.setContentInset(inset, animated: true)
            // An animated inset change is a camera move of its own and cancels a fly that just started
            // (tapping a member also drops the panel to its peek). Fly there again, now with the new inset.
            if let target = c.recentTarget, Date().timeIntervalSince(target.at) < 1.5 {
                map.setCenter(target.coordinate, zoomLevel: target.zoom, animated: true)
            }
        }
        c.sync(map, markers)
        if let key = fitKey, key != c.fittedFor, !markers.isEmpty {
            c.fittedFor = key
            c.fit(map)
        }
        if recenterRequest != c.recentered {
            c.recentered = recenterRequest
            c.recenter(map)
        }
        if let focus, focus.seq != c.focusSeq {
            c.focusSeq = focus.seq
            c.focus(map, on: focus.markerId)
        }
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        private var annotations: [String: MemberAnnotation] = [:]
        var fittedFor: String?
        var recentered = 0
        var focusSeq = 0
        var lastInset = UIEdgeInsets.zero
        var onMarkerTap: (String) -> Void = { _ in }
        var onUserLocated: (Bool) -> Void = { _ in }
        private var located: Bool?
        /// The last "fly to" (member focus / recenter) and when it started; see the inset handling in updateUIView.
        var recentTarget: (coordinate: CLLocationCoordinate2D, zoom: Double, at: Date)?
        private var centeredOnUser = false

        /// Move existing dots, add new ones, remove the ones that left. Moving keeps the views (no flicker).
        func sync(_ map: MLNMapView, _ markers: [MapMarker]) {
            let wanted = Dictionary(markers.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            for (id, a) in annotations where wanted[id] == nil {
                map.removeAnnotation(a)
                annotations[id] = nil
            }
            for m in markers {
                let coordinate = CLLocationCoordinate2D(latitude: m.latitude, longitude: m.longitude)
                if let a = annotations[m.id] {
                    if a.coordinate.latitude != coordinate.latitude || a.coordinate.longitude != coordinate.longitude {
                        a.coordinate = coordinate
                    }
                } else {
                    let a = MemberAnnotation()
                    a.markerId = m.id
                    a.coordinate = coordinate
                    a.title = m.label
                    a.color = m.color
                    annotations[m.id] = a
                    map.addAnnotation(a)
                }
            }
        }

        func fit(_ map: MLNMapView) {
            var all: [MLNAnnotation] = Array(annotations.values)
            if let me = map.userLocation, me.location != nil { all.append(me) }
            if all.count == 1 {
                map.setCenter(all[0].coordinate, zoomLevel: 15, animated: true)
            } else if all.count > 1 {
                // contentInset already keeps clear of the buttons and the panel: just a margin here.
                map.showAnnotations(all, edgePadding: UIEdgeInsets(top: 40, left: 50, bottom: 40, right: 50), animated: true, completionHandler: nil)
            }
        }

        func focus(_ map: MLNMapView, on markerId: String) {
            guard let coordinate = annotations[markerId]?.coordinate ?? map.userLocation?.location?.coordinate else { return }
            flyTo(map, coordinate, zoom: max(map.zoomLevel, 16))
        }

        private func flyTo(_ map: MLNMapView, _ coordinate: CLLocationCoordinate2D, zoom: Double) {
            recentTarget = (coordinate, zoom, Date())
            map.setCenter(coordinate, zoomLevel: zoom, animated: true)
        }

        func recenter(_ map: MLNMapView) {
            if let me = map.userLocation?.location {
                flyTo(map, me.coordinate, zoom: 15)
            } else if !annotations.isEmpty {
                fit(map)
            } else {
                map.setCenter(Baato.kathmandu, zoomLevel: 12, animated: true)
            }
        }

        // MARK: MLNMapViewDelegate

        func mapView(_ mapView: MLNMapView, viewFor annotation: MLNAnnotation) -> MLNAnnotationView? {
            guard let member = annotation as? MemberAnnotation else { return nil }   // nil → default blue "you" dot
            let id = "trackme-member"
            let view = (mapView.dequeueReusableAnnotationView(withIdentifier: id) as? MemberDotView) ?? MemberDotView(reuseIdentifier: id)
            view.configure(color: member.color, label: member.title ?? "")
            return view
        }

        func mapView(_ mapView: MLNMapView, annotationCanShowCallout annotation: MLNAnnotation) -> Bool { true }

        /// Tap a member dot → their name callout, and the app flies to them.
        func mapView(_ mapView: MLNMapView, didSelect annotation: MLNAnnotation) {
            if let member = annotation as? MemberAnnotation { onMarkerTap(member.markerId) }
        }

        /// Go to the user the first time we know where they are (unless a trip / invite fit already happened).
        func mapView(_ mapView: MLNMapView, didUpdate userLocation: MLNUserLocation?) {
            report(userLocation?.location != nil)
            guard !centeredOnUser, fittedFor == nil, let location = userLocation?.location else { return }
            centeredOnUser = true
            mapView.setCenter(location.coordinate, zoomLevel: 15, animated: true)
        }

        func mapView(_ mapView: MLNMapView, didFailToLocateUserWithError error: Error) { report(false) }

        /// Only on change: SwiftUI state shouldn't update on every GPS fix.
        private func report(_ value: Bool) {
            guard value != located else { return }
            located = value
            DispatchQueue.main.async { self.onUserLocated(value) }
        }
    }
}

final class MemberAnnotation: MLNPointAnnotation {
    var markerId = ""
    var color: UIColor = .systemBlue
}

/// A coloured dot with a white ring and the member's name underneath.
final class MemberDotView: MLNAnnotationView {
    private let dot = UIView()
    private let label = UILabel()

    override init(reuseIdentifier: String?) {
        super.init(reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 22, height: 22)
        dot.frame = bounds
        dot.layer.cornerRadius = 11
        dot.layer.borderWidth = 3
        dot.layer.borderColor = UIColor.white.cgColor
        dot.layer.shadowOpacity = 0.25
        dot.layer.shadowRadius = 3
        dot.layer.shadowOffset = CGSize(width: 0, height: 1)
        addSubview(dot)

        label.font = .systemFont(ofSize: 12, weight: .semibold)
        label.textColor = .label
        label.textAlignment = .center
        label.layer.shadowColor = UIColor.systemBackground.cgColor
        label.layer.shadowOpacity = 1
        label.layer.shadowRadius = 2
        label.layer.shadowOffset = .zero
        addSubview(label)
        clipsToBounds = false
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func configure(color: UIColor, label text: String) {
        dot.backgroundColor = color
        label.text = text
        label.sizeToFit()
        label.center = CGPoint(x: bounds.midX, y: bounds.maxY + 4 + label.bounds.height / 2)
    }
}

/// What the map shows when the Baato key is missing.
struct MissingBaatoKeyView: View {
    var body: some View {
        ZStack {
            Color(.secondarySystemBackground).ignoresSafeArea()
            Text("Add BAATO_ACCESS_TOKEN to sample-ios/Secrets.xcconfig\nto show the Baato map.")
                .multilineTextAlignment(.center).padding(32)
        }
    }
}
