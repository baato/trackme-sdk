// The example's state and every SDK call it makes, in one place: a port of the native samples' SampleViewModel,
// so this file doubles as a "how to use @baato/trackme-react-native" reference. Calls reject with TrackMeError.
import { useSyncExternalStore } from 'react';
import TrackMe, {
  TrackMeError,
  isSharingActive,
  type LocationPermissionStatus,
  type MemberPosition,
  type MemberSharingState,
  type RealtimeState,
  type SharingState,
  type TrackMeEvent,
  type TrackMeLifecycle,
  type Trip,
  type TripDetail,
  type TripMember,
  type TripPreview,
} from '@baato/trackme-react-native';
import { API_SECRET, API_URL } from './config';

export type Section = 'map' | 'developer';

export interface LoginForm {
  email: string;
  fullName: string;
}

/** A pasted invite: "Follow me on TrackMe: https://…/trackme/s/<token>\nJoin code: ABC123", a link or a bare code. */
export interface Invite {
  viewToken?: string;
  code?: string;
  preview?: TripPreview;
}

export function parseInvite(text: string): Invite | undefined {
  const t = text.trim();
  const token = /\/s\/([A-Za-z0-9_-]+)/.exec(t)?.[1];
  const code = /code[:\s]+([A-Za-z0-9]{4,16})/i.exec(t)?.[1] ?? (/^[A-Za-z0-9]{4,16}$/.test(t) ? t : undefined);
  if (!token && !code) return undefined;
  return { viewToken: token, code: code?.toUpperCase() };
}

/** The text the live map shares; the same shape parseInvite reads back. */
export function inviteText(tripName: string, viewLink?: string, joinCode?: string): string {
  let s = 'Follow me on TrackMe';
  if (viewLink) s += `: ${viewLink}`;
  if (joinCode) s += `\nJoin code: ${joinCode}`;
  if (!viewLink && !joinCode) s += ` (${tripName})`;
  return s;
}

/**
 * How long a member still counts as "live" after their newest position. Sharing re-sends at least every heartbeat
 * (30 s by default) even when standing still, so a longer silence means paused, stopped, offline or no GPS.
 */
export const LIVE_WINDOW_SECONDS = 45;

export interface AppState {
  login?: LoginForm;
  busy: boolean;
  message?: string;
  section: Section;
  lifecycle: TrackMeLifecycle;
  sharing: SharingState;
  realtime: RealtimeState;
  log: string[];

  // ---- live map ----
  /** The trip the map shows (the most recent live trip, or the one picked / just created / joined). */
  mapDetail?: TripDetail;
  mapLoadedAt: number;
  mapLiveTrips: Trip[];
  mapViewLink?: string;
  invite?: Invite;
  /** Why sharing couldn't start from the map (e.g. no permission); the panel offers a fix. */
  mapSharingError?: TrackMeError;
  /** The trip TrackMe.realtime is watching; livePositions belong to it. */
  liveTripId?: number;
  livePositions: Record<number, MemberPosition>;
  /** When each live position arrived (epoch ms): a position only says "live" while new ones keep coming. */
  livePositionAt: Record<number, number>;
  /** Sharing state each member reported (MemberSharingChanged) and when it arrived. */
  memberSharing: Record<number, { state: MemberSharingState; at: number }>;

  // ---- location ----
  permission?: LocationPermissionStatus;
  /** The map asked for location once this session (it asks by itself only once, so it never nags). */
  locationAutoAsked: boolean;
  /** The system won't show the dialog again: only Settings can grant location now. */
  locationBlocked: boolean;

  // ---- invite preview (view link) ----
  viewerPositions: Record<string, MemberPosition>;
  viewerEnded: boolean;
}

const initial: AppState = {
  busy: false,
  section: 'map',
  lifecycle: 'NotInitialized',
  sharing: { type: 'Idle' },
  realtime: { type: 'Idle' },
  log: [],
  mapLoadedAt: Date.now(),
  mapLiveTrips: [],
  livePositions: {},
  livePositionAt: {},
  memberSharing: {},
  locationAutoAsked: false,
  locationBlocked: false,
  viewerPositions: {},
  viewerEnded: false,
};

const omit = <V>(r: Record<number, V>, key: number): Record<number, V> => {
  const { [key]: _, ...rest } = r;
  return rest;
};

const errorMessage = (e: unknown) => (e instanceof TrackMeError ? e.message : String(e));

class Store {
  private state: AppState = initial;
  private listeners = new Set<() => void>();
  private lastLivePositionAt = 0;

  constructor() {
    TrackMe.onState((lifecycle) => this.set({ lifecycle }));
    TrackMe.sharing.onState((sharing) => {
      this.set({ sharing });
      this.log(`Sharing: ${sharing.type}`);
    });
    TrackMe.realtime.onState((realtime) => {
      this.set({ realtime });
      this.log(`Live: ${realtime.type}`);
    });
    TrackMe.onEvent((e) => this.onEvent(e));
  }

  // ---- useSyncExternalStore plumbing ----
  get = () => this.state;
  subscribe = (l: () => void) => {
    this.listeners.add(l);
    return () => {
      this.listeners.delete(l);
    };
  };
  private set(patch: Partial<AppState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
  }

  // ======================= events =======================

  private onEvent(e: TrackMeEvent) {
    const s = this.state;
    switch (e.type) {
      case 'AuthenticationFailed':
        return this.fail('Session expired: log in again.');
      case 'TokenRefreshed':
        return this.log('Token refreshed by the token provider');
      case 'SharingStopped':
        return this.log(`Sharing stopped: ${e.reason}`);
      case 'TripPosition': {
        const p = e.position;
        if (p.tripId != null && p.tripId !== s.liveTripId) return; // stale, from the previous trip
        const now = Date.now();
        this.lastLivePositionAt = now;
        return this.set({
          livePositions: { ...s.livePositions, [p.userId]: p },
          livePositionAt: { ...s.livePositionAt, [p.userId]: now },
        });
      }
      case 'MemberSharingChanged':
        this.log(`${e.displayName ?? `Member ${e.userId}`}: ${e.state}`);
        if (e.tripId === s.liveTripId) this.set({ memberSharing: { ...s.memberSharing, [e.userId]: { state: e.state, at: Date.now() } } });
        return;
      case 'MemberChanged':
        this.log(`${e.displayName ?? 'Someone'} ${e.change}`);
        return this.reloadMapTrip();
      case 'TripEnded':
        this.log(`Trip ${e.tripId} ended (${e.reason ?? 'no reason'})`);
        this.set({ liveTripId: undefined });
        return this.reloadMapTrip(); // the panel shows "Trip ended"
      case 'Evicted':
        this.log(`Removed from trip ${e.tripId}: ${e.reason}`);
        if (!s.mapDetail || e.tripId == null || s.mapDetail.trip.id === e.tripId) this.set({ mapDetail: undefined });
        this.set({ liveTripId: undefined });
        this.fail(`You're no longer in this trip (${e.reason ?? 'removed'}).`);
        this.mapRefresh();
        return;
      case 'SharePosition':
        return this.set({ viewerPositions: { ...s.viewerPositions, [String(e.position.userId)]: e.position } });
      case 'ShareEnded':
        this.log('View link ended');
        return this.set({ viewerEnded: true });
    }
  }

  // ======================= login / lifecycle =======================

  /** DEMO ONLY: POST /v1/auth/login with the API secret. In a real app this call lives on YOUR backend. */
  static async demoLogin(form: LoginForm): Promise<string | null> {
    try {
      const res = await fetch(`${API_URL}/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: form.email, api_secret: API_SECRET, ...(form.fullName ? { full_name: form.fullName } : {}) }),
      });
      if (!res.ok) return null;
      return (await res.json()).access_token ?? null;
    } catch {
      return null;
    }
  }

  logIn = (form: LoginForm) =>
    this.run('Logging in', async () => {
      if (!API_SECRET || API_SECRET.startsWith('your_')) return this.fail('Fill in src/secrets.ts (copy src/secrets.example.ts).');
      const token = await Store.demoLogin(form);
      if (!token) return this.fail('Login failed (check email / secret / network).');
      await TrackMe.initialize(
        {
          token,
          sharingUi: { title: 'Sharing your location', text: 'Trip members can see where you are.', accentColor: 0xff1565c0 },
        },
        { tokenProvider: () => Store.demoLogin(form) }, // asked automatically when the server says the token expired
      );
      this.set({ login: form });
      this.log(`Initialized as ${form.email}`);
      await this.refreshPermission();
      await this.mapRefresh();
    });

  /** dispose(): stops sharing, removes the notification, closes the socket and HTTP. Safe to call twice. */
  logOut = async () => {
    await TrackMe.dispose().catch(() => undefined);
    // Replace the state, don't merge into it: optional fields such as login aren't in `initial`, so a merge
    // would keep them (and the app would stay on the map).
    const { log, lifecycle, sharing, realtime } = this.state;
    this.state = { ...initial, mapLoadedAt: Date.now(), log, lifecycle, sharing, realtime };
    this.listeners.forEach((l) => l());
    this.log('Disposed');
  };

  selectSection = (section: Section) => this.set({ section });
  clearMessage = () => this.set({ message: undefined });

  // ======================= location =======================

  refreshPermission = async () => {
    try {
      const permission = await TrackMe.location.permissionStatus();
      this.set({ permission, locationBlocked: this.state.locationBlocked && !permission.isGranted });
    } catch (e) {
      this.log(`permissionStatus: ${errorMessage(e)}`);
    }
  };

  markLocationAutoAsked = () => {
    this.state = { ...this.state, locationAutoAsked: true }; // no re-render needed
  };
  setLocationBlocked = (locationBlocked: boolean) => this.set({ locationBlocked });

  /** Location was just granted: if sharing from the map failed for lack of it, start it now. */
  onLocationGranted = async () => {
    await this.refreshPermission();
    if (this.state.mapSharingError?.type === 'LocationPermissionDenied') await this.startSharingFromMap();
  };

  // ======================= live map =======================

  /** Picks the trip the map shows and keeps it live. Called whenever the map appears. */
  mapRefresh = async () => {
    try {
      const trips = await TrackMe.trips.list(50);
      const live = trips.filter((t) => t.isLive);
      this.set({ mapLiveTrips: live });
      // Keep the current trip while it's in the list (even ended: the panel shows "Trip ended" until Done).
      const current = this.state.mapDetail?.trip.id;
      const keep = current != null && trips.some((t) => t.id === current) ? current : undefined;
      const id = keep ?? live[0]?.id;
      if (id == null) this.set({ mapDetail: undefined });
      else await this.loadMapTrip(id);
    } catch (e) {
      this.log(`trips.list: ${errorMessage(e)}`);
    }
  };

  mapSelect = (tripId: number) => this.loadMapTrip(tripId);

  /** Every 5 s from the live map: reloads the snapshot only when no live position came in the last 10 s. */
  mapPoll = () => {
    if (Date.now() - this.lastLivePositionAt > 10_000) this.reloadMapTrip();
  };

  private reloadMapTrip() {
    const id = this.state.mapDetail?.trip.id;
    if (id != null) this.loadMapTrip(id);
  }

  private async loadMapTrip(id: number) {
    try {
      const d = await TrackMe.trips.detail(id);
      const switched = this.state.mapDetail?.trip.id !== id;
      this.set({ mapDetail: d, mapLoadedAt: Date.now(), ...(switched ? { mapViewLink: undefined } : {}) });
      if (d.trip.isLive && (this.state.liveTripId !== id || this.state.realtime.type === 'Idle')) await this.watchLive(id);
    } catch (e) {
      this.log(`trips.detail: ${errorMessage(e)}`);
    }
  }

  /** One socket watches one trip; switching trips clears the positions of the previous one. */
  private async watchLive(tripId: number) {
    if (this.state.liveTripId !== tripId) {
      this.set({ liveTripId: tripId, livePositions: {}, livePositionAt: {}, memberSharing: {} });
    }
    try {
      await TrackMe.realtime.watchTrip(tripId); // resolves when the server confirms
    } catch (e) {
      if (this.state.liveTripId === tripId) this.set({ liveTripId: undefined });
      this.log(`Live updates unavailable: ${errorMessage(e)}`);
    }
  }

  /** Create → start sharing → show it. */
  mapCreate = (name: string, minutes: number) =>
    this.run('Starting trip', async () => {
      const c = await TrackMe.trips.create({ name: name.trim() || undefined, durationMinutes: minutes });
      this.log(`Created trip ${c.id}, code ${c.joinCode}`);
      await this.startSharingFromMap();
      await this.loadMapTrip(c.id);
      this.set({ mapViewLink: c.viewLink }); // after loadMapTrip, which resets it for a new trip
      await this.mapRefresh();
    });

  /** Join → show everyone. Sharing your own location is a separate, later choice ("Share my location too"). */
  mapJoin = (code: string) =>
    this.run('Joining', async () => {
      const t = await TrackMe.trips.join(code.trim());
      this.log(`Joined trip ${t.id}`);
      await this.mapCancelInvite();
      await this.loadMapTrip(t.id);
      await this.mapRefresh();
    });

  /** Owner removes a member (`trips.removeMember`). They get `Evicted`. */
  mapRemoveMember = (userId: number) => {
    const id = this.state.mapDetail?.trip.id;
    if (id == null) return;
    return this.run('Removing member', async () => {
      await TrackMe.trips.removeMember(id, userId);
      const s = this.state;
      this.set({
        livePositions: omit(s.livePositions, userId),
        livePositionAt: omit(s.livePositionAt, userId),
        memberSharing: omit(s.memberSharing, userId),
      });
      await this.loadMapTrip(id);
    });
  };

  mapLeave = () => {
    const id = this.state.mapDetail?.trip.id;
    if (id == null) return;
    return this.run('Leaving', async () => {
      await TrackMe.trips.leave(id);
      await TrackMe.realtime.stopWatchingTrip();
      this.set({ mapDetail: undefined, liveTripId: undefined });
      await this.mapRefresh();
    });
  };

  /** Owner only. Members get trip_ended over the socket; everyone's sharing to this trip stops. */
  mapEnd = () => {
    const id = this.state.mapDetail?.trip.id;
    if (id == null) return;
    return this.run('Ending trip', async () => {
      await TrackMe.trips.end(id);
      await this.loadMapTrip(id);
    });
  };

  /** Dismiss the "Trip ended" card. */
  mapDone = async () => {
    this.set({ mapDetail: undefined });
    await this.mapRefresh();
  };

  /** The invite text (view link + join code), for Share. */
  mapInviteText = async (): Promise<string | undefined> => {
    const d = this.state.mapDetail;
    if (!d) return undefined;
    let link = this.state.mapViewLink;
    if (!link) {
      try {
        link = await TrackMe.trips.viewLink(d.trip.id);
        this.set({ mapViewLink: link });
      } catch (e) {
        this.log(`viewLink: ${errorMessage(e)}`);
      }
    }
    return inviteText(d.trip.name, link, d.trip.joinCode);
  };

  /** A pasted invite: show who's on the map (watchShare) and the trip (preview) before joining. */
  mapOpenInvite = async (text: string) => {
    const invite = parseInvite(text);
    if (!invite) return this.fail('Paste a join code or a TrackMe invite.');
    this.set({ invite, viewerPositions: {}, viewerEnded: false });
    await this.run('Opening invite', async () => {
      if (invite.viewToken) {
        await TrackMe.realtime.watchShare(invite.viewToken).catch((e) => this.log(`Can't watch this link: ${errorMessage(e)}`));
      }
      if (invite.code) {
        const preview = await TrackMe.trips.preview(invite.code);
        if (this.state.invite) this.set({ invite: { ...this.state.invite, preview } });
      }
    });
  };

  mapCancelInvite = async () => {
    if (!this.state.invite) return;
    await TrackMe.realtime.stopWatchingShare().catch(() => undefined);
    this.set({ invite: undefined, viewerPositions: {} });
  };

  /** Start, pause or resume, depending on the current state. */
  mapToggleSharing = async () => {
    try {
      switch (this.state.sharing.type) {
        case 'Paused':
          return await TrackMe.sharing.resume();
        case 'Sharing':
        case 'Starting':
          return await TrackMe.sharing.pause();
        default:
          return await this.startSharingFromMap();
      }
    } catch (e) {
      this.fail(errorMessage(e));
    }
  };

  /** Also used after the user grants location from the map's "Allow location" button. */
  startSharingFromMap = async () => {
    if (isSharingActive(this.state.sharing)) return;
    try {
      await TrackMe.sharing.start();
      this.set({ mapSharingError: undefined });
    } catch (e) {
      this.log(`Start failed: ${errorMessage(e)}`);
      this.set({ mapSharingError: e instanceof TrackMeError ? e : undefined });
    }
    await this.refreshPermission();
  };

  /** Map style / load errors (e.g. a wrong Baato key). */
  reportMapError = (m: string) => this.fail(m);

  // ======================= helpers =======================

  /** Runs one step with the busy indicator; a rejection becomes the message dialog. */
  private async run(what: string, block: () => Promise<void>) {
    this.set({ busy: true });
    this.log(`${what}…`);
    try {
      await block();
    } catch (e) {
      this.fail(errorMessage(e));
    } finally {
      this.set({ busy: false });
    }
  }

  private fail(message: string) {
    this.set({ message });
    this.log(`Error: ${message}`);
  }

  /** Public, for the developer screen's own calls. */
  addLog = (line: string) => this.log(line);
  clearLog = () => this.set({ log: [] });

  private log(line: string) {
    const t = new Date().toTimeString().slice(0, 8);
    this.set({ log: [`${t}  ${line}`, ...this.state.log].slice(0, 200) });
  }
}

export const store = new Store();
export const demoLogin = Store.demoLogin;

/** The whole state, re-rendering on every change. */
export const useAppState = () => useSyncExternalStore(store.subscribe, store.get);

// ======================= member status (live vs snapshot) =======================

/** Seconds since userId's newest live (socket) position for tripId, or undefined if none arrived. */
export function liveAgeSeconds(s: AppState, userId: number, tripId: number, now: number): number | undefined {
  if (s.liveTripId !== tripId) return undefined;
  const p = s.livePositions[userId];
  const at = s.livePositionAt[userId];
  if (!p || at == null) return undefined;
  return Math.floor((now - at) / 1000) + (p.fixAgeSeconds ?? 0);
}

/** Seconds since m's fix in the trips.detail() snapshot, or undefined without a position. */
export const snapshotAgeSeconds = (s: AppState, m: TripMember, now: number) =>
  m.hasPosition ? (m.fixAgeSeconds ?? 0) + Math.floor((now - s.mapLoadedAt) / 1000) : undefined;

/**
 * "paused" / "not sharing" when the member said so (and no position arrived since), otherwise "live",
 * "2 min 5 s ago" or "no location", from whichever is newer: the live position or the snapshot.
 */
export function memberStatus(s: AppState, m: TripMember, tripId: number, now: number): string {
  const event = s.liveTripId === tripId ? s.memberSharing[m.userId] : undefined;
  const snapshot = m.sharingState ? { state: m.sharingState, at: s.mapLoadedAt } : undefined;
  const reported = [event, snapshot].filter((r) => r != null).sort((a, b) => b!.at - a!.at)[0];
  const liveAt = s.liveTripId === tripId ? s.livePositionAt[m.userId] : undefined;
  if (reported && reported.state !== 'sharing' && (liveAt == null || liveAt <= reported.at)) {
    return reported.state === 'paused' ? 'paused' : 'not sharing';
  }
  const ages = [liveAgeSeconds(s, m.userId, tripId, now), snapshotAgeSeconds(s, m, now)].filter((a): a is number => a != null);
  if (ages.length === 0) return 'no location';
  const age = Math.min(...ages);
  return age <= LIVE_WINDOW_SECONDS ? 'live' : `${formatSeconds(age)} ago`;
}

/** The live position, unless the snapshot is newer (e.g. the socket missed some while the poll caught up). */
export function newestLive(s: AppState, m: TripMember, tripId: number, now: number): MemberPosition | undefined {
  const live = liveAgeSeconds(s, m.userId, tripId, now);
  if (live == null) return undefined;
  const snap = snapshotAgeSeconds(s, m, now);
  return snap == null || live <= snap ? s.livePositions[m.userId] : undefined;
}

/** 3725 → "1 h 2 min", 125 → "2 min 5 s". */
export function formatSeconds(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  if (s >= 3600) return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
  if (s >= 60) return `${Math.floor(s / 60)} min ${s % 60} s`;
  return `${s} s`;
}

/** 90 → "1 h 30 min", 45 → "45 min", 1440 → "24 h". */
export function formatMinutes(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m} min`;
  if (m === 0) return `${h} h`;
  return `${h} h ${m} min`;
}

/** Stable colour per member, so a person keeps their colour across updates. */
const PALETTE = ['#E53935', '#8E24AA', '#3949AB', '#00897B', '#F4511E', '#6D4C41', '#039BE5', '#7CB342'];
export function markerColor(key: string | number): string {
  const str = String(key);
  let h = 0;
  for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(h) % PALETTE.length];
}

export { isSharingActive };
