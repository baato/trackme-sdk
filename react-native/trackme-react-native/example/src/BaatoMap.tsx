// MapLibre (@maplibre/maplibre-react-native) with the Baato breeze style: member dots + name labels, and the
// platform's "you" puck.
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet } from 'react-native';
import { Camera, GeoJSONSource, Layer, Map, NativeUserLocation, type CameraRef, type LngLat } from '@maplibre/maplibre-react-native';
import { BAATO_STYLE_URL } from './config';

/** One dot on the map. color is "#RRGGBB". */
export interface MapMarker {
  id: string;
  label: string;
  latitude: number;
  longitude: number;
  color: string;
}

/** "Fly to this marker". seq makes repeated taps on the same member count. */
export interface MapFocus {
  markerId: string;
  seq: number;
}

export const KATHMANDU: LngLat = [85.324, 27.7172];

/**
 * - me: your position (the SDK's last fix while sharing, otherwise the phone's), for the camera.
 * - fitKey: when it changes (a different trip / invite), the camera fits all markers + you once.
 * - recenter: bump it to fly back to you (or fit the markers if your location is unknown).
 * - focus: fly to one marker (a tapped member chip); if it isn't drawn (it's you), fly to me.
 * - topInset / bottomInset: screen parts covered by the top buttons and the panel. Camera moves centre between
 *   them, so "centre on X" is never hidden behind the panel.
 */
export function BaatoMap(props: {
  markers: MapMarker[];
  me?: LngLat;
  fitKey?: string | number;
  recenter: number;
  focus?: MapFocus;
  showMe: boolean;
  topInset: number;
  bottomInset: number;
  onMarkerTap: (id: string) => void;
  onError: (message: string) => void;
}) {
  const { markers, me, fitKey, recenter, focus, showMe, topInset, bottomInset, onMarkerTap, onError } = props;
  const camera = useRef<CameraRef>(null);
  const fittedFor = useRef<string | number | undefined>(undefined);
  const centeredOnMe = useRef(false);
  const [font, setFont] = useState<string[] | undefined>(undefined);
  const padding = { top: topInset, bottom: bottomInset, left: 0, right: 0 };

  // A symbol layer needs a font the style's glyph server has; MapLibre's default may not exist there, and then
  // labels silently don't draw. So use the font the Baato style uses for its own labels.
  useEffect(() => {
    fetch(BAATO_STYLE_URL)
      .then((r) => r.json())
      .then((style: { layers?: { layout?: { 'text-font'?: unknown } }[] }) => {
        const f = style.layers?.map((l) => l.layout?.['text-font']).find((x) => Array.isArray(x) && x.every((s) => typeof s === 'string'));
        if (f) setFont(f as string[]);
      })
      .catch(() => undefined);
  }, []);

  const data = useMemo<GeoJSON.FeatureCollection>(
    () => ({
      type: 'FeatureCollection',
      features: markers.map((m) => ({
        type: 'Feature',
        id: m.id,
        properties: { id: m.id, label: m.label, color: m.color },
        geometry: { type: 'Point', coordinates: [m.longitude, m.latitude] },
      })),
    }),
    [markers],
  );

  const flyTo = (center: LngLat, zoom: number) => camera.current?.flyTo({ center, zoom, padding, duration: 600 });
  const fit = (points: LngLat[]) => {
    if (points.length === 0) return;
    if (points.length === 1) return flyTo(points[0], 15);
    const lons = points.map((p) => p[0]);
    const lats = points.map((p) => p[1]);
    camera.current?.fitBounds([Math.min(...lons), Math.min(...lats), Math.max(...lons), Math.max(...lats)], {
      padding: { top: topInset + 60, bottom: bottomInset + 60, left: 60, right: 60 },
      duration: 600,
    });
  };
  const markerPoints = (): LngLat[] => markers.map((m) => [m.longitude, m.latitude]);

  // Fit once per trip / invite; otherwise go to you the first time we know where you are.
  useEffect(() => {
    if (fitKey != null && fittedFor.current !== fitKey && (markers.length > 0 || me)) {
      fittedFor.current = fitKey;
      centeredOnMe.current = true;
      fit([...markerPoints(), ...(me ? [me] : [])]);
    } else if (!centeredOnMe.current && me && fittedFor.current == null) {
      centeredOnMe.current = true;
      flyTo(me, 15);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey, markers.length > 0, me != null]);

  // Locate button.
  useEffect(() => {
    if (recenter === 0) return;
    if (me) flyTo(me, 15);
    else fit(markers.length ? markerPoints() : [KATHMANDU]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recenter]);

  // A tapped member chip / dot.
  useEffect(() => {
    if (!focus) return;
    const m = markers.find((x) => x.id === focus.markerId);
    const target: LngLat | undefined = m ? [m.longitude, m.latitude] : me;
    if (target) flyTo(target, 16);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focus?.seq]);

  return (
    <Map
      style={StyleSheet.absoluteFill}
      mapStyle={BAATO_STYLE_URL}
      contentInset={padding}
      logo={false}
      compass
      attributionPosition={{ bottom: bottomInset + 8, left: 8 }}
      onDidFailLoadingMap={() => onError('Map failed to load (check BAATO_ACCESS_TOKEN in src/secrets.ts).')}>
      {/* Past zoom 23 the style has no detail left and the map turns black. */}
      <Camera ref={camera} initialViewState={{ center: KATHMANDU, zoom: 12 }} maxZoom={23} />
      {showMe ? <NativeUserLocation /> : null}
      <GeoJSONSource
        id="trackme-members"
        data={data}
        hitbox={{ top: 20, bottom: 20, left: 20, right: 20 }}
        onPress={(e) => {
          // Tap a member dot (or its name) → fly to them.
          const id = e.nativeEvent.features[0]?.properties?.id;
          if (typeof id === 'string') onMarkerTap(id);
        }}>
        <Layer
          type="circle"
          id="trackme-member-dots"
          source="trackme-members"
          paint={{ 'circle-radius': 9, 'circle-color': ['get', 'color'], 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 3 }}
        />
        <Layer
          type="symbol"
          id="trackme-member-labels"
          source="trackme-members"
          layout={{
            'text-field': ['get', 'label'],
            ...(font ? { 'text-font': font } : {}),
            'text-size': 12,
            'text-offset': [0, 1.4],
            'text-anchor': 'top',
            'text-allow-overlap': true,
            'text-ignore-placement': true,
          }}
          paint={{ 'text-color': '#1b1b1f', 'text-halo-color': '#ffffff', 'text-halo-width': 1.5 }}
        />
      </GeoJSONSource>
    </Map>
  );
}
