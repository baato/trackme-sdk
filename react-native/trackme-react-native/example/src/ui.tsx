// Theme (light + dark, following the system) and small building blocks shared by the screens.
import React, { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  Keyboard,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  useColorScheme,
  View,
  type StyleProp,
  type TextInputProps,
  type ViewStyle,
} from 'react-native';
import { formatMinutes } from './store';

const LIGHT = {
  bg: '#ffffff',
  surface: '#ffffff',
  surfaceVariant: '#eef1f5',
  primary: '#1565c0',
  onPrimary: '#ffffff',
  primaryContainer: '#d6e3ff',
  onPrimaryContainer: '#001b3e',
  text: '#1b1b1f',
  muted: '#5f6368',
  border: '#c4c7c5',
  error: '#c62828',
  green: '#2e7d32',
  amber: '#ef6c00',
};
const DARK: typeof LIGHT = {
  bg: '#121212',
  surface: '#1e1f22',
  surfaceVariant: '#2c2e33',
  primary: '#a8c7fa',
  onPrimary: '#002f66',
  primaryContainer: '#0b3a78',
  onPrimaryContainer: '#d6e3ff',
  text: '#e8eaed',
  muted: '#9aa0a6',
  border: '#5f6368',
  error: '#f28b82',
  green: '#81c995',
  amber: '#fdd663',
};
export type Colors = typeof LIGHT;
export const useColors = (): Colors => (useColorScheme() === 'dark' ? DARK : LIGHT);

/** A label + text the theme colours. */
export function T({ children, style, variant = 'body', selectable }: {
  children: React.ReactNode;
  style?: StyleProp<any>;
  variant?: 'title' | 'body' | 'small' | 'label' | 'mono';
  selectable?: boolean;
}) {
  const c = useColors();
  return (
    <Text selectable={selectable} style={[styles[variant], { color: variant === 'small' ? c.muted : c.text }, style]}>
      {children}
    </Text>
  );
}

/** Filled, outlined or text button. */
export function Btn({ title, onPress, kind = 'filled', disabled, color, style }: {
  title: string;
  onPress?: () => void;
  kind?: 'filled' | 'outlined' | 'text';
  disabled?: boolean;
  /** Overrides the filled background / outlined text colour. */
  color?: string;
  style?: StyleProp<ViewStyle>;
}) {
  const c = useColors();
  const accent = color ?? c.primary;
  const filled = kind === 'filled';
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || !onPress}
      style={({ pressed }) => [
        styles.btn,
        filled && { backgroundColor: accent },
        kind === 'outlined' && { borderWidth: 1, borderColor: c.border },
        kind === 'text' && styles.btnText,
        { opacity: disabled ? 0.4 : pressed ? 0.7 : 1 },
        style,
      ]}>
      <Text style={[styles.btnLabel, { color: filled ? (color ? '#ffffff' : c.onPrimary) : accent }]}>{title}</Text>
    </Pressable>
  );
}

export function Field(props: TextInputProps & { label?: string }) {
  const c = useColors();
  return (
    <TextInput
      placeholder={props.label}
      placeholderTextColor={c.muted}
      {...props}
      style={[styles.field, { color: c.text, borderColor: c.border }, props.style]}
    />
  );
}

/** A rounded, shadowed chip over the map. */
export function Pill({ children, onPress }: { children: React.ReactNode; onPress?: () => void }) {
  const c = useColors();
  return (
    <Pressable onPress={onPress} disabled={!onPress} style={[styles.pill, { backgroundColor: c.surface }]}>
      <View style={styles.row}>{children}</View>
    </Pressable>
  );
}

export function Chip({ label, selected, onPress, onLongPress, dot }: {
  label: string;
  selected?: boolean;
  onPress?: () => void;
  onLongPress?: () => void;
  dot?: string;
}) {
  const c = useColors();
  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      style={[
        styles.chip,
        { backgroundColor: selected ? c.primaryContainer : c.surfaceVariant, borderColor: selected ? c.primary : 'transparent' },
      ]}>
      {dot ? <View style={[styles.dot, { backgroundColor: dot }]} /> : null}
      <Text style={[styles.label, { color: selected ? c.onPrimaryContainer : c.text }]}>{label}</Text>
    </Pressable>
  );
}

export function Busy({ on }: { on: boolean }) {
  return on ? <ActivityIndicator style={styles.busy} /> : null;
}

export type LocateState = 'located' | 'searching' | 'disabled';

/**
 * Round "show me" button: the near-me arrow on a blue background when your position is known, on gray (gently
 * pulsing) while there's no fix yet, and struck through on white when location isn't available to the app.
 */
export function LocateButton({ state, onPress }: { state: LocateState; onPress: () => void }) {
  const pulse = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    if (state !== 'searching') {
      pulse.setValue(1);
      return;
    }
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 0.45, duration: 900, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 1, duration: 900, useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [state, pulse]);
  const bg = state === 'located' ? '#1e88e5' : state === 'searching' ? '#8e8e8e' : '#ffffff';
  const fg = state === 'disabled' ? '#d32f2f' : '#ffffff';
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={state === 'located' ? 'Show my location' : state === 'searching' ? 'Looking for your location' : 'Location unavailable'}
      style={[styles.locate, { backgroundColor: bg }]}>
      <Animated.View style={{ opacity: pulse }}>
        <Text style={[styles.arrow, { color: fg }]}>➤</Text>
      </Animated.View>
      {state === 'disabled' ? <View style={[styles.strike, { backgroundColor: fg }]} /> : null}
    </Pressable>
  );
}

/**
 * Trip length: preset chips plus "Custom", any whole number of minutes. The keypad only offers digits (and typed
 * non-digits are dropped). The limits are the server's: out-of-range values come back as a Validation error with
 * the server's message, so this UI doesn't hard-code them. `minutes` is -1 while the field is empty.
 */
export function DurationPicker({ presets, minutes, onChange }: {
  presets: [string, number][];
  minutes: number;
  onChange: (m: number) => void;
}) {
  const [custom, setCustom] = useState(!presets.some(([, v]) => v === minutes));
  const [text, setText] = useState(custom && minutes >= 0 ? String(minutes) : '');
  const [typing, setTyping] = useState(false);
  return (
    <View style={styles.gap}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.rowGap}>
        {presets.map(([label, value]) => (
          <Chip
            key={label}
            label={label}
            selected={!custom && minutes === value}
            onPress={() => {
              setCustom(false);
              onChange(value);
            }}
          />
        ))}
        <Chip
          label="Custom"
          selected={custom}
          onPress={() => {
            setCustom(true);
            const t = text || String(Math.max(0, minutes));
            setText(t);
            onChange(parseInt(t, 10));
          }}
        />
      </ScrollView>
      {custom ? (
        <>
          <View style={styles.rowGap}>
            <Field
              label="Minutes"
              value={text}
              keyboardType="number-pad"
              maxLength={5}
              autoFocus
              onFocus={() => setTyping(true)}
              onBlur={() => setTyping(false)}
              onChangeText={(t) => {
                const digits = t.replace(/[^0-9]/g, '');
                setText(digits);
                onChange(digits ? parseInt(digits, 10) : -1);
              }}
              style={styles.grow}
            />
            {/* The iOS number pad has no return key: a Done button while typing (as in the native sample). */}
            {typing ? <Btn kind="text" title="Done" onPress={Keyboard.dismiss} /> : null}
          </View>
          {minutes >= 0 ? <T variant="small">= {formatMinutes(minutes)}</T> : null}
        </>
      ) : null}
    </View>
  );
}

export const styles = StyleSheet.create({
  title: { fontSize: 20, fontWeight: '700' },
  body: { fontSize: 15 },
  small: { fontSize: 12 },
  label: { fontSize: 13, fontWeight: '600' },
  mono: { fontFamily: 'Menlo', fontSize: 12 },
  btn: { borderRadius: 24, paddingVertical: 11, paddingHorizontal: 16, alignItems: 'center', justifyContent: 'center' },
  btnText: { paddingHorizontal: 8 },
  btnLabel: { fontSize: 15, fontWeight: '600' },
  field: { borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 15 },
  pill: {
    borderRadius: 50,
    paddingHorizontal: 12,
    paddingVertical: 8,
    alignSelf: 'flex-start',
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 2 },
    elevation: 4,
  },
  chip: { flexDirection: 'row', alignItems: 'center', borderRadius: 50, borderWidth: 1, paddingHorizontal: 12, paddingVertical: 6 },
  dot: { width: 10, height: 10, borderRadius: 5, marginRight: 6 },
  row: { flexDirection: 'row', alignItems: 'center' },
  rowGap: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  gap: { gap: 10 },
  grow: { flex: 1 },
  busy: { marginTop: 4 },
  locate: {
    width: 52,
    height: 52,
    borderRadius: 26,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOpacity: 0.25,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
    elevation: 6,
  },
  arrow: { fontSize: 22, transform: [{ rotate: '-45deg' }] },
  strike: { position: 'absolute', width: 30, height: 2.5, borderRadius: 2, transform: [{ rotate: '45deg' }] },
});
