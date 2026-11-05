// Developer screen (dev-tools builds only): a control for every area of the SDK, with the results in a log.
// It shares the live map's session; anything changed here shows on the map too.
import React, { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import TrackMe, { TrackMeError } from '@baato/trackme-react-native';
import { demoLogin, parseInvite, store, useAppState } from './store';
import { Btn, Busy, Field, T, useColors } from './ui';

export function DeveloperScreen() {
  const s = useAppState();
  const c = useColors();
  const insets = useSafeAreaInsets();
  const [code, setCode] = useState('');
  const [viewLink, setViewLink] = useState('');
  const [tripId, setTripId] = useState<number>();
  const [running, setRunning] = useState(false);
  const trip = tripId ?? s.mapDetail?.trip.id;

  /** Runs one SDK call and logs its result or its TrackMeError. */
  const run = async (label: string, call: () => Promise<unknown>) => {
    setRunning(true);
    try {
      const r = await call();
      store.addLog(`${label} ✓${r == null ? '' : `  ${typeof r === 'string' ? r : JSON.stringify(r)}`}`);
    } catch (e) {
      store.addLog(`${label} ✗ ${e instanceof TrackMeError ? `${e.type}: ${e.message}` : String(e)}`);
    } finally {
      setRunning(false);
    }
  };
  const B = ({ title, call }: { title: string; call?: () => Promise<unknown> }) => (
    <Btn kind="outlined" title={title} disabled={!call || running} onPress={call ? () => run(title, call) : undefined} />
  );
  const Section = ({ title, children }: { title: string; children: React.ReactNode }) => (
    <View style={styles.section}>
      <T variant="label">{title}</T>
      <View style={styles.wrap}>{children}</View>
    </View>
  );

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.screen, { paddingTop: insets.top + 12 }]}
      keyboardShouldPersistTaps="handled">
      <View style={styles.header}>
        <T variant="title">{`Developer · ${s.login?.fullName ?? ''}`}</T>
        <Btn kind="text" title="Log out" onPress={store.logOut} />
      </View>
      <View style={[styles.status, { backgroundColor: c.surfaceVariant }]}>
        <T variant="mono" selectable>
          {`SDK: ${s.lifecycle}\nSharing: ${s.sharing.type}\nRealtime: ${s.realtime.type}\n` +
            `Location: ${s.permission ? `${s.permission.level}, services ${s.permission.servicesEnabled ? 'on' : 'off'}` : '–'}\n` +
            `Trip: ${trip ?? '–'}`}
        </T>
      </View>
      <Busy on={running || s.busy} />

      <Section title="Trips">
        <B
          title="Create 15 min"
          call={async () => {
            const t = await TrackMe.trips.create({ name: 'React Native dev', durationMinutes: 15 });
            setTripId(t.id);
            return `trip ${t.id}, code ${t.joinCode}`;
          }}
        />
        <B title="List" call={async () => (await TrackMe.trips.list(10)).map((t) => `${t.id} ${t.status}`).join(', ')} />
        <B
          title="Members"
          call={
            trip == null
              ? undefined
              : async () =>
                  (await TrackMe.trips.detail(trip)).members
                    .map((m) => `${m.fullName ?? m.userId}${m.sharingState ? ` [${m.sharingState}]` : ''}`)
                    .join(', ')
          }
        />
        <B title="View link" call={trip == null ? undefined : () => TrackMe.trips.viewLink(trip)} />
        <B title="Rotate link" call={trip == null ? undefined : () => TrackMe.trips.rotateViewLink(trip)} />
        <B
          title="History 10 min"
          call={
            trip == null
              ? undefined
              : async () => {
                  const from = new Date(Date.now() - 10 * 60_000).toISOString();
                  const h = JSON.stringify(await TrackMe.trips.history(trip, { from }));
                  return h.length > 300 ? `${h.slice(0, 300)}…` : h;
                }
          }
        />
        <B title="End" call={trip == null ? undefined : async () => (await TrackMe.trips.end(trip)).status} />
        <B title="Leave" call={trip == null ? undefined : async () => (await TrackMe.trips.leave(trip), 'left')} />
        <B title="Delete (ended only)" call={trip == null ? undefined : async () => (await TrackMe.trips.delete(trip), 'deleted')} />
      </Section>
      <View style={styles.inline}>
        <Field label="Join code" value={code} onChangeText={setCode} autoCapitalize="characters" style={styles.flex} />
        <B
          title="Preview"
          call={async () => {
            const p = await TrackMe.trips.preview(code.trim());
            return `"${p.name}" by ${p.ownerName}, ${p.memberCount} members`;
          }}
        />
        <B
          title="Join"
          call={async () => {
            const t = await TrackMe.trips.join(code.trim());
            setTripId(t.id);
            return `joined ${t.id}`;
          }}
        />
      </View>

      <Section title="Sharing">
        <B title="Start" call={() => TrackMe.sharing.start()} />
        <B title="Pause" call={TrackMe.sharing.pause} />
        <B title="Resume" call={TrackMe.sharing.resume} />
        <B title="Stop" call={TrackMe.sharing.stop} />
        <B title="State" call={TrackMe.sharing.current} />
        <B title="Permission" call={TrackMe.location.permissionStatus} />
      </Section>

      <Section title="Realtime">
        <B title="Watch trip" call={trip == null ? undefined : () => TrackMe.realtime.watchTrip(trip)} />
        <B title="Stop watching trip" call={TrackMe.realtime.stopWatchingTrip} />
        <B title="Stop all" call={TrackMe.realtime.stop} />
      </Section>
      <View style={styles.inline}>
        <Field label="View link or token" value={viewLink} onChangeText={setViewLink} autoCapitalize="none" style={styles.flex} />
        {/* The SDK takes the view TOKEN; taking it out of a pasted link is the app's choice. */}
        <B title="Watch link" call={() => TrackMe.realtime.watchShare(parseInvite(viewLink)?.viewToken ?? viewLink.trim())} />
        <B title="Stop" call={TrackMe.realtime.stopWatchingShare} />
      </View>

      <Section title="Inbox">
        <B
          title="Notifications"
          call={async () => {
            const page = await TrackMe.notifications.list({ limit: 10 });
            return `${page.items.length} (more: ${page.hasMore}): ${page.items.map((n) => n.title).join(' | ')}`;
          }}
        />
        <B title="Mark all read" call={TrackMe.notifications.markAllRead} />
      </Section>

      <Section title="SDK">
        <B title="Health" call={TrackMe.checkHealth} />
        <B
          title="Fresh token"
          call={async () => {
            const t = s.login ? await demoLogin(s.login) : null;
            if (!t) return 'no token';
            await TrackMe.updateToken(t);
            return 'updateToken() done';
          }}
        />
        {/* A broken token: the next request gets 401 → the SDK asks the token provider → TokenRefreshed. */}
        <B
          title="Simulate expiry"
          call={async () => {
            await TrackMe.updateToken('expired-demo-token');
            return `${(await TrackMe.trips.list(5)).length} trips after the automatic refresh`;
          }}
        />
      </Section>

      <View style={styles.header}>
        <T variant="label">Log</T>
        <Btn kind="text" title="Clear" onPress={store.clearLog} />
      </View>
      {s.log.map((line, i) => (
        <T key={i} variant="mono" selectable>
          {line}
        </T>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { padding: 16, gap: 10 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  status: { borderRadius: 10, padding: 12 },
  section: { gap: 6, marginTop: 6 },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  inline: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  flex: { flex: 1 },
});
