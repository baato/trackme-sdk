// Location access for the live map, in one place. The SDK never shows a prompt: it reports
// (TrackMe.location.permissionStatus()), and the app asks, here with react-native-permissions.
import React from 'react';
import { Modal, Platform, Pressable, StyleSheet, View } from 'react-native';
import { PERMISSIONS, RESULTS, openSettings, request, requestMultiple, requestNotifications } from 'react-native-permissions';
import type { LocationPermissionStatus } from '@baato/trackme-react-native';
import { store, type AppState } from './store';
import { Btn, Pill, T, useColors } from './ui';

export interface LocationAccess {
  status?: LocationPermissionStatus;
  granted: boolean;
  servicesOff: boolean;
  /** The system won't show the dialog again: only Settings can grant location now. */
  blocked: boolean;
  ask: () => Promise<void>;
  askAlways: () => Promise<void>;
  openSettings: () => Promise<void>;
}

const FOREGROUND =
  Platform.OS === 'ios'
    ? [PERMISSIONS.IOS.LOCATION_WHEN_IN_USE]
    : [PERMISSIONS.ANDROID.ACCESS_FINE_LOCATION, PERMISSIONS.ANDROID.ACCESS_COARSE_LOCATION];
const BACKGROUND = Platform.OS === 'ios' ? PERMISSIONS.IOS.LOCATION_ALWAYS : PERMISSIONS.ANDROID.ACCESS_BACKGROUND_LOCATION;

export function locationAccess(s: AppState): LocationAccess {
  const granted = s.permission?.isGranted === true;
  const blocked = s.locationBlocked && !granted;
  return {
    status: s.permission,
    granted,
    servicesOff: s.permission?.servicesEnabled === false,
    blocked,
    /** The system dialog (plus notifications, for the sharing notification). When it won't show any more, Settings. */
    ask: async () => {
      if (blocked) return openSettings();
      const result = await requestMultiple(FOREGROUND);
      await requestNotifications(['alert', 'sound']).catch(() => undefined);
      const values = Object.values(result);
      if (values.some((r) => r === RESULTS.GRANTED || r === RESULTS.LIMITED)) {
        store.setLocationBlocked(false);
        await store.onLocationGranted();
      } else {
        await store.refreshPermission();
        if (values.some((r) => r === RESULTS.BLOCKED)) store.setLocationBlocked(true);
      }
    },
    /** "Allow all the time": a second step after "while using" on Android 11+ and iOS. */
    askAlways: async () => {
      const r = await request(BACKGROUND);
      await store.refreshPermission();
      if (r === RESULTS.BLOCKED) await openSettings();
    },
    openSettings: () => openSettings(),
  };
}

/** Small status chip on the map: what access the app has. */
export function LocationPill({ access, onPress }: { access: LocationAccess; onPress: () => void }) {
  const c = useColors();
  const s = access.status;
  const [text, color] = !s
    ? ['Location', '#9e9e9e']
    : !s.servicesEnabled
      ? ['Location is off', c.error]
      : !s.isGranted
        ? ['No location access', c.error]
        : s.level === 'Always'
          ? ['Location: always', c.green]
          : ['Location: while using', c.amber];
  const approximate = s?.isGranted && !s.precise;
  return (
    <Pill onPress={onPress}>
      <View style={[styles.dot, { backgroundColor: color }]} />
      <T variant="label">{`${text}${approximate ? ' · approx.' : ''}`}</T>
    </Pill>
  );
}

/** What each level means for sharing, which one the app has, and the one action that improves it. */
export function LocationDialog({ access, visible, onClose }: { access: LocationAccess; visible: boolean; onClose: () => void }) {
  const c = useColors();
  const level = access.status?.level;
  const [label, action]: [string, () => Promise<void>] = access.servicesOff
    ? ['Open Settings', access.openSettings]
    : !access.granted && !access.blocked
      ? ['Allow', access.ask]
      : !access.granted
        ? ['Open Settings', access.openSettings]
        : level === 'WhileInUse'
          ? ['Allow all the time', access.askAlways]
          : ['Open Settings', access.openSettings];
  const Row = ({ title, text, selected }: { title: string; text: string; selected: boolean }) => (
    <View style={styles.levelRow}>
      <T style={{ color: c.primary }}>{selected ? '◉' : '○'}</T>
      <View style={styles.flex}>
        <T style={selected ? styles.bold : undefined}>{title}</T>
        <T variant="small">{text}</T>
      </View>
    </View>
  );
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose}>
        <Pressable style={[styles.dialog, { backgroundColor: c.surface }]}>
          <T variant="title">Location access</T>
          <Row title="Not allowed" text="Nobody in your trip sees you, and the map can't show you." selected={!access.granted} />
          <Row
            title="While using the app"
            text="Enough to share. Sharing you start keeps running in the background."
            selected={level === 'WhileInUse'}
          />
          <Row title="All the time" text="Also lets sharing start while the app is in the background." selected={level === 'Always'} />
          {access.servicesOff ? <T style={{ color: c.error }}>Location is off for the whole phone: turn it on in Settings.</T> : null}
          {access.blocked ? <T variant="small">The system won't ask again: allow location in Settings.</T> : null}
          {access.granted && access.status?.precise === false ? (
            <T variant="small">Precise location is off, so others see an approximate position.</T>
          ) : null}
          <View style={styles.actions}>
            <Btn kind="text" title="Close" onPress={onClose} />
            <Btn
              kind="text"
              title={label}
              onPress={() => {
                onClose();
                action();
              }}
            />
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

const styles = StyleSheet.create({
  dot: { width: 8, height: 8, borderRadius: 4, marginRight: 6 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'center', padding: 24 },
  dialog: { borderRadius: 24, padding: 20, gap: 12 },
  levelRow: { flexDirection: 'row', gap: 10 },
  flex: { flex: 1 },
  bold: { fontWeight: '700' },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8 },
});
