// TrackMe React Native example: the Live map showcase and (dev-tools builds) a developer screen, ported from the
// native samples. Needs src/secrets.ts (copy src/secrets.example.ts).
import React, { useEffect, useState } from 'react';
import { Alert, Platform, Pressable, ScrollView, StatusBar, StyleSheet, useColorScheme, View } from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { DEV_TOOLS } from './src/config';
import { DeveloperScreen } from './src/Developer';
import { LiveMapScreen } from './src/LiveMap';
import { store, useAppState, type Section } from './src/store';
import { Btn, Busy, Field, T, useColors } from './src/ui';

export default function App() {
  const dark = useColorScheme() === 'dark';
  return (
    <SafeAreaProvider>
      <StatusBar barStyle={dark ? 'light-content' : 'dark-content'} />
      <Root />
    </SafeAreaProvider>
  );
}

function Root() {
  const s = useAppState();
  // SDK errors and notices, as an alert.
  useEffect(() => {
    if (!s.message) return;
    const m = s.message;
    store.clearMessage();
    Alert.alert('TrackMe', m);
  }, [s.message]);

  if (!s.login) return <LoginScreen />;
  if (!DEV_TOOLS) return <LiveMapScreen />;
  return <DevShell section={s.section} />;
}

/** Dev-tools build: a bottom bar switches between the showcase and the developer screen. */
function DevShell({ section }: { section: Section }) {
  const c = useColors();
  const insets = useSafeAreaInsets();
  const tabs: [Section, string][] = [
    ['map', '🗺  Live map'],
    ['developer', '🛠  Developer'],
  ];
  return (
    <View style={[styles.flex, { backgroundColor: c.bg }]}>
      {/* Both stay mounted, so the map keeps its camera while you're on the developer screen. */}
      <View style={[styles.flex, section !== 'map' && styles.hidden]}>
        <LiveMapScreen />
      </View>
      <View style={[styles.flex, section !== 'developer' && styles.hidden]}>
        <DeveloperScreen />
      </View>
      <View style={[styles.tabs, { backgroundColor: c.surface, borderTopColor: c.border, paddingBottom: insets.bottom }]}>
        {tabs.map(([key, label]) => (
          <Pressable key={key} style={styles.tab} onPress={() => store.selectSection(key)}>
            <T variant="label" style={{ color: section === key ? c.primary : c.muted }}>
              {label}
            </T>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

function LoginScreen() {
  const s = useAppState();
  const c = useColors();
  const insets = useSafeAreaInsets();
  // One user per device, so two phones can test a trip together.
  const [email, setEmail] = useState(`rn.${Platform.OS}@trackme.dev`);
  const [name, setName] = useState(`React Native ${Platform.OS === 'ios' ? 'iOS' : 'Android'}`);
  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.login, { paddingTop: insets.top + 24 }]}
      keyboardShouldPersistTaps="handled">
      <T variant="title">TrackMe React Native</T>
      <T variant="small">
        The example logs in with the API secret from src/secrets.ts. A real app gets the token from its own backend and
        only calls TrackMe.initialize. Log in with a different email on a second device to test trips together.
      </T>
      <Field label="Email" value={email} onChangeText={setEmail} autoCapitalize="none" autoCorrect={false} keyboardType="email-address" />
      <Field label="Full name" value={name} onChangeText={setName} />
      <Btn
        title="Log in & initialize SDK"
        disabled={!email.includes('@') || s.busy}
        onPress={() => store.logIn({ email: email.trim(), fullName: name.trim() })}
      />
      <Busy on={s.busy} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  hidden: { display: 'none' },
  tabs: { flexDirection: 'row', borderTopWidth: StyleSheet.hairlineWidth },
  tab: { flex: 1, alignItems: 'center', paddingVertical: 14 },
  login: { padding: 20, gap: 14 },
});
