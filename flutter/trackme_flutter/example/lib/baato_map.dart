// MapLibre (maplibre_gl) with the Baato breeze style: member dots + name labels, and the platform's "you" puck.
import 'dart:convert';
import 'dart:io';
import 'dart:math' show Point;

import 'package:flutter/material.dart';
import 'package:maplibre_gl/maplibre_gl.dart';

import 'config.dart';

/// One dot on the map. [color] is "#RRGGBB".
class MapMarker {
  const MapMarker(this.id, this.label, this.latitude, this.longitude, this.color);
  final String id, label, color;
  final double latitude, longitude;
}

/// "Fly to this marker". [seq] makes repeated taps on the same member count.
class MapFocus {
  const MapFocus(this.markerId, this.seq);
  final String markerId;
  final int seq;
}

const kathmandu = LatLng(27.7172, 85.3240);
const _members = 'trackme-members';
const _dots = 'trackme-member-dots';
const _labels = 'trackme-member-labels';

/// - [me]: your position (the SDK's last fix while sharing, otherwise the phone's), for the camera.
/// - [fitKey]: when it changes (a different trip / invite), the camera fits all markers + you once.
/// - [recenter]: bump it to fly back to you (or fit the markers if your location is unknown).
/// - [focus]: fly to one marker (a tapped member chip); if it isn't drawn (it's you), fly to [me].
/// - [topInset] / [bottomInset]: screen parts covered by the top buttons and the panel. Camera moves centre
///   between them, so "centre on X" is never hidden behind the panel.
class BaatoMap extends StatefulWidget {
  const BaatoMap({
    super.key,
    required this.markers,
    required this.me,
    required this.fitKey,
    required this.recenter,
    required this.focus,
    required this.showMe,
    required this.topInset,
    required this.bottomInset,
    required this.onMarkerTap,
    required this.onUserLocation,
    required this.onError,
  });

  final List<MapMarker> markers;
  final LatLng? me;
  final Object? fitKey;
  final int recenter;
  final MapFocus? focus;
  final bool showMe;
  final double topInset, bottomInset;
  final ValueChanged<String> onMarkerTap;
  final ValueChanged<LatLng> onUserLocation;
  final ValueChanged<String> onError;

  @override
  State<BaatoMap> createState() => _BaatoMapState();
}

class _BaatoMapState extends State<BaatoMap> {
  MapLibreMapController? _c;
  bool _styleReady = false;
  Object? _fittedFor;
  bool _centeredOnMe = false;
  String _markerSig = '';

  static String _signature(List<MapMarker> ms) => ms.map((m) => '${m.id}@${m.latitude},${m.longitude}').join('|');

  @override
  void didUpdateWidget(BaatoMap old) {
    super.didUpdateWidget(old);
    if (!_styleReady) return;
    // The panel rebuilds every second (countdown): only push markers to the map when they actually changed.
    final sig = _signature(widget.markers);
    if (sig != _markerSig) {
      _markerSig = sig;
      _setMarkers();
    }
    if (old.bottomInset != widget.bottomInset || old.topInset != widget.topInset) _insets();
    _autoCamera();
    if (old.recenter != widget.recenter) _recenter();
    if (widget.focus != null && (old.focus?.seq != widget.focus!.seq)) _flyToFocus();
  }

  Future<void> _onStyleLoaded() async {
    final c = _c;
    if (c == null) return;
    try {
      _markerSig = _signature(widget.markers);
      await c.addGeoJsonSource(_members, _collection());
      await c.addCircleLayer(
        _members,
        _dots,
        const CircleLayerProperties(
          circleRadius: 9,
          circleColor: ['get', 'color'],
          circleStrokeColor: '#FFFFFF',
          circleStrokeWidth: 3,
        ),
      );
      // A symbol layer needs a font the style's glyph server has; MapLibre's default may not exist there, and then
      // labels silently don't draw. So use the font the Baato style uses for its own labels.
      final font = await _styleFont();
      await c.addSymbolLayer(
        _members,
        _labels,
        SymbolLayerProperties(
          textField: ['get', 'label'],
          textFont: font,
          textSize: 12,
          textOffset: const [0, 1.4],
          textAnchor: 'top',
          textColor: '#1B1B1F',
          textHaloColor: '#FFFFFF',
          textHaloWidth: 1.5,
          textAllowOverlap: true,
          textIgnorePlacement: true,
        ),
      );
    } catch (e) {
      widget.onError('Marker layers: $e');
    }
    _styleReady = true;
    await _insets();
    _autoCamera();
  }

  /// The first plain `text-font` among the style's own symbol layers (null → MapLibre's default).
  Future<List<String>?> _styleFont() async {
    final client = HttpClient();
    try {
      final res = await (await client.getUrl(Uri.parse(baatoStyleUrl))).close();
      final style = jsonDecode(await res.transform(utf8.decoder).join()) as Map<String, dynamic>;
      for (final l in (style['layers'] as List? ?? const [])) {
        final font = ((l as Map)['layout'] as Map?)?['text-font'];
        if (font is List && font.isNotEmpty && font.every((f) => f is String)) return font.cast<String>();
      }
    } catch (_) {
      // fall back to the default font
    } finally {
      client.close();
    }
    return null;
  }

  Map<String, dynamic> _collection() => {
        'type': 'FeatureCollection',
        'features': [
          for (final m in widget.markers)
            {
              'type': 'Feature',
              'id': m.id,
              'properties': {'id': m.id, 'label': m.label, 'color': m.color},
              'geometry': {
                'type': 'Point',
                'coordinates': [m.longitude, m.latitude],
              },
            },
        ],
      };

  Future<void> _setMarkers() async => _c?.setGeoJsonSource(_members, _collection());

  Future<void> _insets() async =>
      _c?.updateContentInsets(EdgeInsets.only(top: widget.topInset, bottom: widget.bottomInset));

  /// Fit once per trip / invite; otherwise go to you the first time we know where you are.
  void _autoCamera() {
    final key = widget.fitKey;
    final me = widget.me;
    if (key != null && _fittedFor != key && (widget.markers.isNotEmpty || me != null)) {
      _fittedFor = key;
      _centeredOnMe = true;
      _fit([for (final m in widget.markers) LatLng(m.latitude, m.longitude), ?me]);
    } else if (!_centeredOnMe && me != null && _fittedFor == null) {
      _centeredOnMe = true;
      _flyTo(me, 15);
    }
  }

  void _recenter() {
    final me = widget.me;
    if (me != null) {
      _flyTo(me, 15);
    } else {
      final points = [for (final m in widget.markers) LatLng(m.latitude, m.longitude)];
      _fit(points.isEmpty ? const [kathmandu] : points);
    }
  }

  void _flyToFocus() {
    final f = widget.focus!;
    final hit = widget.markers.where((m) => m.id == f.markerId);
    final target = hit.isEmpty ? widget.me : LatLng(hit.first.latitude, hit.first.longitude);
    if (target != null) _flyTo(target, 16);
  }

  void _flyTo(LatLng target, double zoom) =>
      _c?.animateCamera(CameraUpdate.newLatLngZoom(target, zoom), duration: const Duration(milliseconds: 600));

  void _fit(List<LatLng> points) {
    if (points.isEmpty) return;
    if (points.length == 1) return _flyTo(points.first, 15);
    var s = points.first.latitude, n = s, w = points.first.longitude, e = w;
    for (final p in points) {
      if (p.latitude < s) s = p.latitude;
      if (p.latitude > n) n = p.latitude;
      if (p.longitude < w) w = p.longitude;
      if (p.longitude > e) e = p.longitude;
    }
    _c?.animateCamera(
      CameraUpdate.newLatLngBounds(
        LatLngBounds(southwest: LatLng(s, w), northeast: LatLng(n, e)),
        left: 60,
        top: 60,
        right: 60,
        bottom: 60,
      ),
      duration: const Duration(milliseconds: 600),
    );
  }

  @override
  Widget build(BuildContext context) => MapLibreMap(
        styleString: baatoStyleUrl,
        initialCameraPosition: const CameraPosition(target: kathmandu, zoom: 12),
        onMapCreated: (c) {
          _c = c;
          // Tap a member dot (or its name) → fly to them.
          c.onFeatureTapped.add((Point<double> point, LatLng coordinates, String id, String layerId, Annotation? a) {
            if (layerId == _dots || layerId == _labels) widget.onMarkerTap(id);
          });
        },
        onStyleLoadedCallback: _onStyleLoaded,
        myLocationEnabled: widget.showMe,
        onUserLocationUpdated: (loc) => widget.onUserLocation(loc.position),
        compassEnabled: true,
        attributionButtonPosition: AttributionButtonPosition.bottomLeft,
      );
}
