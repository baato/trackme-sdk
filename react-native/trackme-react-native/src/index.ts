// TrackMe for React Native: trips, live location sharing and realtime updates, on the native TrackMe SDK.
//
//   await TrackMe.initialize({ token: accessToken }, { tokenProvider: () => myBackend.freshToken() });
//   const trip = await TrackMe.trips.create({ name: 'Ride home', durationMinutes: 60 });
//   await TrackMe.sharing.start();
//   await TrackMe.realtime.watchTrip(trip.id);
//   const unsubscribe = TrackMe.onEvent((e) => { if (e.type === 'TripPosition') moveMarker(e.position); });
//
// Every call rejects with a TrackMeError on failure.
import { NativeEventEmitter } from 'react-native';
import Native from './NativeTrackMe';
import {
  TrackMeError,
  createdTrip,
  eventFrom,
  notificationPage,
  permissionStatus,
  realtimeStateFrom,
  sharingOptionsJson,
  sharingStateFrom,
  trip,
  tripDetail,
  tripPreview,
  type CreatedTrip,
  type LocationPermissionStatus,
  type NotificationPage,
  type RealtimeState,
  type SharingOptions,
  type SharingState,
  type TrackMeEvent,
  type TrackMeLifecycle,
  type Trip,
  type TripDetail,
  type TripPreview,
} from './models';

export * from './models';

/** Look and labels of the Android notification and the iOS Live Activity. All strings are yours to localize. */
export interface TrackMeSharingUi {
  title?: string;
  text?: string;
  pausedText?: string;
  /** ARGB, e.g. 0xFF1565C0. */
  accentColor?: number;
  showPauseAction?: boolean;
  showStopAction?: boolean;
  pauseLabel?: string;
  resumeLabel?: string;
  stopLabel?: string;
  /** A drawable *name* in your Android app (monochrome, for the status bar), e.g. "ic_stat_trackme". */
  androidSmallIcon?: string;
  androidChannelName?: string;
  /** SF Symbol shown in the Live Activity. */
  iosSymbolName?: string;
}

/** Everything the app supplies to `TrackMe.initialize`. Your backend gets the access token; the SDK never logs in. */
export interface TrackMeConfig {
  token: string;
  apiUrl?: string;
  socketUrl?: string;
  requestTimeoutMs?: number;
  tokenRefreshTimeoutMs?: number;
  sharingUi?: TrackMeSharingUi;
}

const pick = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined));

const configJson = (c: TrackMeConfig) =>
  JSON.stringify(
    pick({
      token: c.token,
      api_url: c.apiUrl,
      socket_url: c.socketUrl,
      request_timeout_ms: c.requestTimeoutMs,
      token_refresh_timeout_ms: c.tokenRefreshTimeoutMs,
      sharing_ui: c.sharingUi
        ? pick({
            title: c.sharingUi.title,
            text: c.sharingUi.text,
            paused_text: c.sharingUi.pausedText,
            accent_color: c.sharingUi.accentColor,
            show_pause_action: c.sharingUi.showPauseAction,
            show_stop_action: c.sharingUi.showStopAction,
            pause_label: c.sharingUi.pauseLabel,
            resume_label: c.sharingUi.resumeLabel,
            stop_label: c.sharingUi.stopLabel,
            android_small_icon_name: c.sharingUi.androidSmallIcon, // resolved to an id natively
            android_channel_name: c.sharingUi.androidChannelName,
            ios_symbol_name: c.sharingUi.iosSymbolName,
          })
        : undefined,
    }),
  );

// ---------------- the bridge ----------------

const emitter = new NativeEventEmitter(Native as any);

/** `{"ok":true,"value":…}` → value; `{"ok":false,"error":{…}}` → throws TrackMeError. */
const unwrap = (raw: string): any => {
  const envelope = JSON.parse(raw || '{}');
  if (envelope.ok === true) return envelope.value;
  throw new TrackMeError(envelope.error ?? { type: 'Unknown', message: 'No answer from the native SDK' });
};

const call = async (method: string, args: Record<string, unknown> = {}): Promise<any> =>
  unwrap(await Native.call(method, JSON.stringify(pick(args))));

type Envelope = { stream: string; value: any };
type Listener = (e: Envelope) => void;
const listeners = new Set<Listener>();
let nativeSubscription: { remove(): void } | undefined;

/** One native subscription, shared by every JS listener. */
const listen = (l: Listener) => {
  listeners.add(l);
  if (!nativeSubscription) {
    nativeSubscription = emitter.addListener('TrackMeEvent', (body: { json: string }) => {
      const e = JSON.parse(body.json) as Envelope;
      listeners.forEach((fn) => fn(e));
    });
  }
  return () => {
    listeners.delete(l);
    if (listeners.size === 0) {
      nativeSubscription?.remove();
      nativeSubscription = undefined;
    }
  };
};

const onStream = <T>(name: string, map: (j: any) => T | undefined, cb: (value: T) => void) =>
  listen((e) => {
    if (e.stream !== name) return;
    const v = map(e.value);
    if (v !== undefined) cb(v);
  });

let tokenProvider: (() => Promise<string | null>) | undefined;
let tokenSubscription: { remove(): void } | undefined;

// ---------------- the API ----------------

export const TrackMe = {
  /**
   * Starts a session; calling it again replaces the previous one (e.g. a different user). `tokenProvider` is
   * asked for a fresh token when the server answers 401; resolve null to give up.
   */
  async initialize(config: TrackMeConfig, options: { tokenProvider?: () => Promise<string | null> } = {}): Promise<void> {
    tokenProvider = options.tokenProvider;
    if (tokenProvider && !tokenSubscription) {
      tokenSubscription = emitter.addListener('TrackMeRefreshToken', async (body: { requestId: number }) => {
        let token: string | null = null;
        try {
          token = (await tokenProvider?.()) ?? null;
        } catch {
          token = null;
        }
        Native.provideToken(body.requestId, token);
      });
    }
    unwrap(await Native.start(configJson(config), tokenProvider != null));
  },

  /** For apps that refresh tokens on their own schedule. */
  updateToken: (token: string): Promise<void> => call('updateToken', { token }),

  /** Ends the session: stops sharing and realtime, removes the notification / Live Activity. */
  dispose: (): Promise<void> => call('dispose'),

  isInitialized: (): Promise<boolean> => call('isInitialized'),

  /** "ok" when the server is reachable. */
  checkHealth: async (): Promise<string> => (await call('checkHealth')).status,

  /** SDK lifecycle; the current value arrives first. Returns an unsubscribe function. */
  onState: (cb: (s: TrackMeLifecycle) => void) => onStream('state', (j) => j.type as TrackMeLifecycle, cb),

  /** One-off events: live positions, members, trip end, token trouble. Returns an unsubscribe function. */
  onEvent: (cb: (e: TrackMeEvent) => void) => onStream('event', eventFrom, cb),

  trips: {
    /** The duration range is the server's (5–1440 minutes today); outside it you get its validation message. */
    create: async (o: { name?: string; durationMinutes: number }): Promise<CreatedTrip> =>
      createdTrip(await call('trips.create', { name: o.name, duration_min: o.durationMinutes })),
    /** Trips you created or joined, newest first. */
    list: async (limit = 20): Promise<Trip[]> => ((await call('trips.list', { limit })) as any[]).map(trip),
    preview: async (code: string): Promise<TripPreview> => tripPreview(await call('trips.preview', { code })),
    join: async (code: string): Promise<Trip> => trip(await call('trips.join', { code })),
    detail: async (tripId: number): Promise<TripDetail> => tripDetail(await call('trips.detail', { trip_id: tripId })),
    rename: async (tripId: number, name: string): Promise<Trip> => trip(await call('trips.rename', { trip_id: tripId, name })),
    /** Owner only. */
    end: async (tripId: number): Promise<Trip> => trip(await call('trips.end', { trip_id: tripId })),
    leave: (tripId: number): Promise<void> => call('trips.leave', { trip_id: tripId }),
    /** Owner only; the member gets an `Evicted` event. */
    removeMember: (tripId: number, userId: number): Promise<void> => call('trips.removeMember', { trip_id: tripId, user_id: userId }),
    /** Ended trips only (a live trip rejects with `Conflict`). */
    delete: (tripId: number): Promise<void> => call('trips.delete', { trip_id: tripId }),
    /** The public, read-only link anyone can open. */
    viewLink: async (tripId: number): Promise<string> => (await call('trips.viewLink', { trip_id: tripId })).view_link,
    /** A new link; the old one stops working. */
    rotateViewLink: async (tripId: number): Promise<string> => (await call('trips.rotateViewLink', { trip_id: tripId })).view_link,
    /** The server's history JSON. `from` / `to`: ISO-8601. */
    history: (tripId: number, range: { from?: string; to?: string } = {}): Promise<unknown> =>
      call('trips.history', { trip_id: tripId, from: range.from, to: range.to }),
  },

  sharing: {
    /** Starts sharing to every live trip you're in. Ask for location permission first; the SDK only reports it. */
    start: (options?: SharingOptions): Promise<void> => call('sharing.start', { options: sharingOptionsJson(options) }),
    pause: (): Promise<void> => call('sharing.pause'),
    resume: (): Promise<void> => call('sharing.resume'),
    stop: (): Promise<void> => call('sharing.stop'),
    current: async (): Promise<SharingState> => sharingStateFrom(await call('sharing.state')),
    /** Every change; the current value arrives first. Returns an unsubscribe function. */
    onState: (cb: (s: SharingState) => void) => onStream('sharing', sharingStateFrom, cb),
  },

  location: {
    permissionStatus: async (): Promise<LocationPermissionStatus> => permissionStatus(await call('location.permissionStatus')),
  },

  realtime: {
    /** Live updates of one trip: `TripPosition`, `MemberChanged`, `TripEnded`, `Evicted`. */
    watchTrip: (tripId: number): Promise<void> => call('realtime.watchTrip', { trip_id: tripId }),
    /** Live updates of a view link, by its token (the last part of the link): `SharePosition`, `ShareEnded`. */
    watchShare: (viewToken: string): Promise<void> => call('realtime.watchShare', { view_token: viewToken }),
    stopWatchingTrip: (): Promise<void> => call('realtime.stopWatchingTrip'),
    stopWatchingShare: (): Promise<void> => call('realtime.stopWatchingShare'),
    stop: (): Promise<void> => call('realtime.stop'),
    current: async (): Promise<RealtimeState> => realtimeStateFrom(await call('realtime.state')),
    onState: (cb: (s: RealtimeState) => void) => onStream('realtime', realtimeStateFrom, cb),
  },

  notifications: {
    /** One page; pass the previous page's `nextCursor` as `cursor`. */
    list: async (o: { unreadOnly?: boolean; tripId?: number; cursor?: string; limit?: number } = {}): Promise<NotificationPage> =>
      notificationPage(
        await call('notifications.list', { unread_only: o.unreadOnly ?? false, trip_id: o.tripId, cursor: o.cursor, limit: o.limit ?? 20 }),
      ),
    markRead: (notificationId: number): Promise<void> => call('notifications.markRead', { notification_id: notificationId }),
    markAllRead: (): Promise<void> => call('notifications.markAllRead'),
  },
};

export default TrackMe;
