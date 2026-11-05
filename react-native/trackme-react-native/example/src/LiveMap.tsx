// The showcase: a ready-to-use "share my trip live" feature on a Baato map, ported from the native samples.
// No trip → start or join. Invite pasted → see who's there, then join. In a trip → members live, share / pause,
// invite, leave (member) or end (owner). Built only from the same SDK calls as the developer screen.
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Animated, Keyboard, PanResponder, Platform, Pressable, ScrollView, Share, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useCurrentPosition, type LngLat } from '@maplibre/maplibre-react-native';
import type { RealtimeState, TripDetail } from '@baato/trackme-react-native';
import { BaatoMap, type MapFocus, type MapMarker } from './BaatoMap';
import { baatoConfigured } from './config';
import { LocationDialog, LocationPill, locationAccess, type LocationAccess } from './locationAccess';
import {
  formatSeconds,
  isSharingActive,
  markerColor,
  memberStatus,
  newestLive,
  store,
  useAppState,
  type AppState,
  type Invite,
} from './store';
import { Btn, Busy, Chip, DurationPicker, Field, LocateButton, Pill, T, useColors, type Colors } from './ui';

const firstName = (n?: string) => (n ?? 'Member').split(' ')[0];

/** "Are you sure?" for anything destructive. */
const confirm = (title: string, text: string, action: string, onConfirm: () => void) =>
  Alert.alert(title, text, [
    { text: 'Cancel', style: 'cancel' },
    { text: action, style: 'destructive', onPress: onConfirm },
  ]);

function buildMarkers(s: AppState, hideMe: boolean, now: number): MapMarker[] {
  // Watching an invite: whatever the view link shows.
  if (s.invite) {
    return Object.entries(s.viewerPositions).map(([key, p]) => ({
      id: `v${key}`,
      label: firstName(p.displayName),
      latitude: p.latitude,
      longitude: p.longitude,
      color: markerColor(key),
    }));
  }
  const d = s.mapDetail;
  if (!d) return [];
  const myName = s.login?.fullName;
  return d.members.flatMap((m) => {
    if (hideMe && m.fullName && m.fullName === myName) return []; // you are the "me" puck instead
    // The newer of the live socket position and the snapshot from trips.detail().
    const live = newestLive(s, m, d.trip.id, now);
    const lat = live?.latitude ?? m.lat;
    const lon = live?.longitude ?? m.lon;
    if (lat == null || lon == null) return [];
    return [{ id: `u${m.userId}`, label: firstName(m.fullName), latitude: lat, longitude: lon, color: markerColor(m.userId) }];
  });
}

export function LiveMapScreen() {
  const s = useAppState();
  const c = useColors();
  const insets = useSafeAreaInsets();
  const access = locationAccess(s);
  const [now, setNow] = useState(Date.now());
  const [recenter, setRecenter] = useState(0);
  const [focus, setFocus] = useState<MapFocus>();
  const [panelHeight, setPanelHeight] = useState(0);
  const [collapsed, setCollapsed] = useState(false);
  const [dialog, setDialog] = useState(false);
  const [screenHeight, setScreenHeight] = useState(0);
  const keyboard = useKeyboardOverlap(screenHeight);

  // Drag the panel anywhere: down to collapse it to its title and one line, up for everything. Inside the scrolling
  // content a drag moves the panel only once the content can't scroll that way (pulled down at the top, or up while
  // collapsed). Like the native samples, where the drag would end with its momentum decides; ±60 pt flips it.
  const drag = useRef(new Animated.Value(0)).current;
  const scrollY = useRef(0);
  const collapsedRef = useRef(collapsed);
  collapsedRef.current = collapsed;
  const pan = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponderCapture: (_, g) =>
          Math.abs(g.dy) > 12 &&
          Math.abs(g.dy) > Math.abs(g.dx) * 1.5 &&
          ((g.dy > 0 && scrollY.current <= 0) || (g.dy < 0 && collapsedRef.current)),
        onPanResponderMove: (_, g) => drag.setValue(Math.max(0, g.dy)),
        onPanResponderRelease: (_, g) => {
          const travel = g.dy + g.vy * 150;
          if (travel > 60) {
            setCollapsed(true);
            Keyboard.dismiss(); // collapsing hides the fields, so drop the keyboard too
          } else if (travel < -60) {
            setCollapsed(false);
          }
          Animated.spring(drag, { toValue: 0, useNativeDriver: true, bounciness: 0 }).start();
        },
        onPanResponderTerminate: () => Animated.spring(drag, { toValue: 0, useNativeDriver: true }).start(),
      }),
    [drag],
  );

  useEffect(() => {
    store.refreshPermission();
    store.mapRefresh(); // every time the map appears
    // Countdown and "live / 50 s ago" labels.
    const tick = setInterval(() => setNow(Date.now()), 1000);
    // Fallback next to the socket: while no live position arrives, refresh the trip snapshot every 5 s.
    const poll = setInterval(() => {
      if (store.get().mapDetail?.trip.isLive) store.mapPoll();
    }, 5000);
    return () => {
      clearInterval(tick);
      clearInterval(poll);
    };
  }, []);

  // Location: asked once by itself when the map first shows without it; after that only when the user taps the
  // locate button, "Allow location" or the location pill. Never nags.
  const p = s.permission;
  useEffect(() => {
    if (p && !p.isGranted && p.servicesEnabled && !store.get().locationAutoAsked && !store.get().locationBlocked) {
      store.markLocationAutoAsked();
      access.ask();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [p]);

  // A different panel (start → trip → ended, or an invite) opens fully, so its buttons are visible.
  const panelKind = s.invite ? 'invite' : s.mapDetail?.trip.isLive ? `trip:${s.mapDetail.trip.id}` : s.mapDetail ? 'ended' : 'start';
  useEffect(() => setCollapsed(false), [panelKind]);

  // You: the SDK's own last fix while sharing (what members see), otherwise the phone's location.
  const device = useCurrentPosition({ enabled: access.granted });
  const last = s.sharing.type === 'Sharing' ? s.sharing.lastLocation : undefined;
  const me: LngLat | undefined = last
    ? [last.longitude, last.latitude]
    : device
      ? [device.coords.longitude, device.coords.latitude]
      : undefined;

  const markers = useMemo(
    () => buildMarkers(s, me != null, Date.now()),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [s.invite, s.viewerPositions, s.mapDetail, s.livePositions, s.liveTripId, me != null],
  );

  /** Tap a member (chip or dot): collapse the panel, then fly to them. */
  const focusOn = (id: string) => {
    setCollapsed(true);
    setFocus((f) => ({ markerId: id, seq: (f?.seq ?? 0) + 1 }));
  };

  return (
    <View style={[styles.flex, { backgroundColor: c.bg }]} onLayout={(e) => setScreenHeight(e.nativeEvent.layout.height)}>
      {baatoConfigured ? (
        <BaatoMap
          markers={markers}
          me={me}
          fitKey={s.invite ? `invite:${s.invite.viewToken}:${s.invite.code}` : s.mapDetail?.trip.id}
          recenter={recenter}
          focus={focus}
          showMe={access.granted}
          topInset={insets.top + 64}
          bottomInset={panelHeight + 16}
          onMarkerTap={focusOn}
          onError={store.reportMapError}
        />
      ) : (
        <View style={[styles.flex, styles.center, { backgroundColor: c.surfaceVariant }]}>
          <T>{'Add BAATO_ACCESS_TOKEN to src/secrets.ts\nto show the Baato map.'}</T>
        </View>
      )}

      {/* Top: live status + location access, log out / locate. */}
      <View style={[styles.top, { top: insets.top + 12 }]} pointerEvents="box-none">
        <View style={styles.gap8}>
          {s.mapDetail?.trip.isLive || s.invite ? <LivePill realtime={s.realtime} watchingInvite={!!s.invite} c={c} /> : null}
          <LocationPill access={access} onPress={() => setDialog(true)} />
        </View>
        <View style={[styles.gap8, styles.end]}>
          <Pill onPress={store.logOut}>
            <T variant="label">Log out</T>
          </Pill>
          {/* Locate = "show me". Without location access it asks (or explains, when only Settings can help). */}
          <LocateButton
            state={!access.granted || access.servicesOff ? 'disabled' : me ? 'located' : 'searching'}
            onPress={() => {
              if (access.granted) setRecenter((r) => r + 1);
              else if (access.servicesOff || access.blocked) setDialog(true);
              else access.ask();
            }}
          />
        </View>
      </View>

      {/* Sits on the keyboard when it's up, and never grows under the top buttons. */}
      <Animated.View
        {...pan.panHandlers}
        onLayout={(e) => setPanelHeight(e.nativeEvent.layout.height)}
        style={[
          styles.panel,
          {
            bottom: keyboard,
            backgroundColor: c.surface,
            paddingBottom: keyboard > 0 ? 12 : insets.bottom + 16,
            transform: [{ translateY: drag }],
          },
        ]}>
        {/* Or tap the handle. */}
        <Pressable onPress={() => setCollapsed((v) => !v)} style={styles.handleArea} hitSlop={8}>
          <View style={[styles.handle, { backgroundColor: c.muted }]} />
        </Pressable>
        <ScrollView
          style={{ maxHeight: Math.max(160, screenHeight - keyboard - insets.top - 160) }}
          contentContainerStyle={styles.panelContent}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          bounces={false}
          scrollEventThrottle={16}
          onScroll={(e) => (scrollY.current = e.nativeEvent.contentOffset.y)}>
          <Panel s={s} c={c} now={now} collapsed={collapsed} access={access} onMemberTap={focusOn} />
          <Busy on={s.busy} />
        </ScrollView>
      </Animated.View>

      <LocationDialog access={access} visible={dialog} onClose={() => setDialog(false)} />
    </View>
  );
}

/**
 * How much of the screen the keyboard covers (0 when it's down). Measured against this screen's own height, so it's
 * right whether or not Android also resized the window for it.
 */
function useKeyboardOverlap(screenHeight: number): number {
  const [keyboardTop, setKeyboardTop] = useState<number>();
  useEffect(() => {
    const show = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillShow' : 'keyboardDidShow', (e) =>
      setKeyboardTop(e.endCoordinates.screenY),
    );
    const hide = Keyboard.addListener(Platform.OS === 'ios' ? 'keyboardWillHide' : 'keyboardDidHide', () => setKeyboardTop(undefined));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);
  return keyboardTop == null || screenHeight === 0 ? 0 : Math.max(0, screenHeight - keyboardTop);
}

function LivePill({ realtime, watchingInvite, c }: { realtime: RealtimeState; watchingInvite: boolean; c: Colors }) {
  const [text, color] =
    realtime.type === 'Connected'
      ? [watchingInvite ? '● Watching' : '● Live', c.green]
      : realtime.type === 'Reconnecting'
        ? ['Reconnecting…', c.amber]
        : realtime.type === 'Connecting'
          ? ['Connecting…', c.amber]
          : ['Offline', '#9e9e9e'];
  return (
    <Pill>
      <T variant="label" style={{ color }}>
        {text}
      </T>
    </Pill>
  );
}

/** Each panel starts with its title + one status line: that's what the collapsed panel shows. */
function Panel(props: { s: AppState; c: Colors; now: number; collapsed: boolean; access: LocationAccess; onMemberTap: (id: string) => void }) {
  const { s } = props;
  if (s.invite) return <InvitePanel {...props} invite={s.invite} />;
  if (s.mapDetail?.trip.isLive) return <TripPanel {...props} d={s.mapDetail} />;
  if (s.mapDetail) return <EndedPanel d={s.mapDetail} />;
  return <StartPanel collapsed={props.collapsed} />;
}

const DURATIONS: [string, number][] = [
  ['30 min', 30],
  ['1 h', 60],
  ['2 h', 120],
  ['4 h', 240],
];

function StartPanel({ collapsed }: { collapsed: boolean }) {
  const [mode, setMode] = useState<'' | 'start' | 'join'>('');
  const [name, setName] = useState('');
  const [minutes, setMinutes] = useState(60);
  const [invite, setInvite] = useState('');
  const busy = useAppState().busy;
  return (
    <>
      <T variant="title">Share your trip, live</T>
      <T>Start a trip and invite people, or join someone else's.</T>
      {collapsed ? null : (
        <>
          {/* The chosen mode is the filled button (Start, before anything is chosen). */}
          <View style={styles.rowGap}>
            <Btn title="Start a trip" kind={mode === 'join' ? 'outlined' : 'filled'} onPress={() => setMode('start')} style={styles.flex} />
            <Btn title="Join" kind={mode === 'join' ? 'filled' : 'outlined'} onPress={() => setMode('join')} style={styles.flex} />
          </View>
          {mode === 'start' ? (
            <>
              <Field label="Trip name (optional)" value={name} onChangeText={setName} returnKeyType="done" />
              <DurationPicker presets={DURATIONS} minutes={minutes} onChange={setMinutes} />
              <Btn title="Start & share my location" disabled={minutes < 0 || busy} onPress={() => store.mapCreate(name, minutes)} />
            </>
          ) : null}
          {mode === 'join' ? (
            <>
              {/* One line: Return means "Continue". Long-press to paste an invite message (it becomes one line; the
                  parser doesn't mind). */}
              <Field
                label="Join code or invite message"
                value={invite}
                onChangeText={setInvite}
                autoCapitalize="none"
                autoCorrect={false}
                returnKeyType="go"
                onSubmitEditing={() => invite.trim() && store.mapOpenInvite(invite)}
              />
              <Btn title="Continue" disabled={!invite.trim()} onPress={() => store.mapOpenInvite(invite)} />
            </>
          ) : null}
        </>
      )}
    </>
  );
}

function InvitePanel({ s, c, collapsed, invite }: { s: AppState; c: Colors; collapsed: boolean; invite: Invite }) {
  const [code, setCode] = useState(invite.code ?? '');
  const p = invite.preview;
  return (
    <>
      <T variant="title">{p?.name ?? 'Shared trip'}</T>
      <T>{s.viewerEnded ? 'This trip has ended.' : p ? `by ${p.ownerName ?? 'someone'} · ${p.memberCount} members` : 'Watching the shared link.'}</T>
      {collapsed ? null : (
        <>
          {invite.viewToken && !s.viewerEnded ? (
            <T variant="small">
              {Object.keys(s.viewerPositions).length === 0
                ? 'Waiting for positions…'
                : `${Object.keys(s.viewerPositions).length} on the map, live`}
            </T>
          ) : null}
          {invite.code ? null : (
            <Field
              label="Join code"
              value={code}
              onChangeText={(t) => setCode(t.toUpperCase().trim())}
              autoCapitalize="characters"
              autoCorrect={false}
              returnKeyType="go"
              onSubmitEditing={() => code.trim() && !s.viewerEnded && store.mapJoin(code)}
            />
          )}
          {/* The server sends each upload to EVERY live trip you're in, so joining while you share elsewhere starts
              sharing to this trip too. Say so instead of doing it silently. */}
          {isSharingActive(s.sharing) ? (
            <T variant="small" style={{ color: c.error }}>
              You're sharing your location right now, so joining adds this trip to it. Pause or stop sharing first to join
              without sharing.
            </T>
          ) : null}
          <View style={styles.rowGap}>
            <Btn title="Join" disabled={!code.trim() || s.viewerEnded} onPress={() => store.mapJoin(code)} style={styles.flex} />
            <Btn title="Close" kind="outlined" onPress={store.mapCancelInvite} style={styles.flex} />
          </View>
        </>
      )}
    </>
  );
}

function TripPanel({ s, c, now, collapsed, access, onMemberTap, d }: {
  s: AppState;
  c: Colors;
  now: number;
  collapsed: boolean;
  access: LocationAccess;
  onMemberTap: (id: string) => void;
  d: TripDetail;
}) {
  const trip = d.trip;
  const remaining = trip.remainingSeconds - Math.floor((now - s.mapLoadedAt) / 1000);
  const progress = Math.min(1, Math.max(0, remaining / Math.max(1, trip.plannedSeconds)));
  const error = s.mapSharingError;
  const sharing = s.sharing;
  const [label, active] =
    sharing.type === 'Sharing'
      ? ['● Sharing your location · tap to pause', true]
      : sharing.type === 'Starting'
        ? ['Starting…', true]
        : sharing.type === 'Paused'
          ? ['Paused · tap to resume', false]
          : ['Share my location too', false];

  return (
    <>
      <T variant="title">{trip.name}</T>
      <T>{`Ends in ${formatSeconds(remaining)} · ${d.members.length} ${d.members.length === 1 ? 'person' : 'people'}`}</T>
      {/* Time left, draining: same as the sharing notification. */}
      <View style={[styles.track, { backgroundColor: c.surfaceVariant }]}>
        <View style={[styles.bar, { backgroundColor: c.primary, width: `${progress * 100}%` }]} />
      </View>
      {collapsed ? null : (
        <>
          {/* More than one live trip: pick which one the map shows. */}
          {s.mapLiveTrips.length > 1 ? (
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.rowGap}>
              {s.mapLiveTrips.map((t) => (
                <Chip key={t.id} label={t.name} selected={t.id === trip.id} onPress={() => store.mapSelect(t.id)} />
              ))}
            </ScrollView>
          ) : null}

          {/* How others join: the code, big, with one-tap share. (The share text also carries the view link.) */}
          <View style={[styles.inviteCard, { backgroundColor: c.primaryContainer }]}>
            <T variant="label" style={{ color: c.onPrimaryContainer }}>
              {trip.joinCode ? 'Join code' : 'Invite people'}
            </T>
            {trip.joinCode ? (
              <T selectable style={[styles.code, { color: c.onPrimaryContainer }]}>
                {trip.joinCode}
              </T>
            ) : null}
            <Btn
              title="Share invite"
              onPress={async () => {
                const message = await store.mapInviteText();
                if (message) await Share.share({ message });
              }}
            />
          </View>

          {/* Who's here. Tap someone to find them on the map; the owner long-presses someone to remove them. */}
          {trip.isOwner && d.members.length > 1 ? <T variant="small">Tap someone to find them · long-press to remove</T> : null}
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.rowGap}>
            {d.members.map((m) => (
              <Chip
                key={m.userId}
                dot={markerColor(m.userId)}
                // "live" only while positions keep arriving: a paused member turns into "50 s ago" by itself.
                label={`${firstName(m.fullName)}${m.isOwner ? ' ★' : ''} · ${memberStatus(s, m, trip.id, now)}`}
                onPress={() => onMemberTap(`u${m.userId}`)}
                onLongPress={
                  trip.isOwner && !m.isOwner
                    ? () =>
                        confirm(`Remove ${m.fullName ?? 'member'}?`, "They're taken out of the trip and stop seeing it.", 'Remove', () =>
                          store.mapRemoveMember(m.userId),
                        )
                    : undefined
                }
              />
            ))}
          </ScrollView>

          {/* Your location. */}
          {!isSharingActive(sharing) && error?.type === 'LocationPermissionDenied' && !access.granted ? (
            // Granting starts sharing by itself (store.onLocationGranted).
            <Btn title={access.blocked ? 'Allow location in Settings' : 'Allow location to share'} onPress={access.ask} />
          ) : (
            <>
              <Btn title={label as string} color={active ? '#2e7d32' : undefined} onPress={store.mapToggleSharing} />
              {error && error.type !== 'LocationPermissionDenied' && !isSharingActive(sharing) ? (
                <T variant="small" style={{ color: c.error }}>
                  {error.message}
                </T>
              ) : null}
            </>
          )}

          <Btn
            title={trip.isOwner ? 'End trip' : 'Leave trip'}
            kind="outlined"
            color={c.error}
            onPress={() =>
              trip.isOwner
                ? confirm('End this trip?', 'Everyone stops sharing and the trip closes for all members.', 'End trip', store.mapEnd)
                : confirm('Leave this trip?', 'You stop sharing with this trip. You can join again with the code.', 'Leave', store.mapLeave)
            }
          />
        </>
      )}
    </>
  );
}

function EndedPanel({ d }: { d: TripDetail }) {
  return (
    <>
      <T variant="title">Trip ended</T>
      <T>{`${d.trip.name} · ${d.trip.endedReason ?? 'ended'} · lasted ${formatSeconds(d.trip.actualSeconds)}`}</T>
      <Btn title="Done" onPress={store.mapDone} />
    </>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  center: { alignItems: 'center', justifyContent: 'center', padding: 32 },
  top: { position: 'absolute', left: 12, right: 12, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' },
  gap8: { gap: 8 },
  end: { alignItems: 'flex-end' },
  panel: {
    position: 'absolute',
    left: 0,
    right: 0,
    borderTopLeftRadius: 28,
    borderTopRightRadius: 28,
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: -2 },
    elevation: 12,
  },
  handleArea: { paddingVertical: 10, alignItems: 'center' },
  handle: { width: 36, height: 4, borderRadius: 2, opacity: 0.5 },
  panelContent: { paddingHorizontal: 20, gap: 12 },
  rowGap: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  bar: { height: 6, borderRadius: 3 },
  inviteCard: { borderRadius: 18, padding: 16, gap: 8 },
  code: { fontSize: 30, fontWeight: '700', letterSpacing: 4, fontFamily: 'Menlo' },
});
