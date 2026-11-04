// TypeScript side of the TrackMe bridge protocol v1 (docs/07-ports.md). The wire uses the API's snake_case names
// (docs/trackme-api.md); these types are idiomatic camelCase. Unknown values (a newer server) never throw.

type Json = Record<string, any>;

// ---------------- trips ----------------

export interface Trip {
  id: number;
  name: string;
  myRole: string;
  myStatus: string;
  status: string;
  endedReason?: string;
  memberCount: number;
  startedOn: string;
  endedOn?: string;
  plannedSeconds: number;
  actualSeconds: number;
  expiresAt: string;
  remainingSeconds: number;
  joinCode?: string;
  isOwner: boolean;
  isLive: boolean;
}

export const trip = (j: Json): Trip => ({
  id: j.id,
  name: j.name,
  myRole: j.my_role,
  myStatus: j.my_status,
  status: j.status,
  endedReason: j.ended_reason ?? undefined,
  memberCount: j.member_count,
  startedOn: j.started_on,
  endedOn: j.ended_on ?? undefined,
  plannedSeconds: j.planned_s,
  actualSeconds: j.actual_s,
  expiresAt: j.expires_at,
  remainingSeconds: j.remaining_s ?? 0,
  joinCode: j.join_code ?? undefined,
  isOwner: j.my_role === 'owner',
  isLive: j.status === 'live',
});

export interface CreatedTrip {
  id: number;
  name: string;
  joinCode: string;
  viewLink: string;
  expiresAt: string;
  remainingSeconds: number;
}

export const createdTrip = (j: Json): CreatedTrip => ({
  id: j.id,
  name: j.name,
  joinCode: j.join_code,
  viewLink: j.view_link,
  expiresAt: j.expires_at,
  remainingSeconds: j.remaining_s,
});

export interface TripPreview {
  name: string;
  memberCount: number;
  ownerName?: string;
  expiresAt: string;
}

export const tripPreview = (j: Json): TripPreview => ({
  name: j.name,
  memberCount: j.member_count,
  ownerName: j.owner_name ?? undefined,
  expiresAt: j.expires_at,
});

/** Whether a member is sending their location, as they reported it. Undefined until the backend supports it. */
export type MemberSharingState = 'sharing' | 'paused' | 'stopped';

const sharingState = (v: unknown): MemberSharingState | undefined =>
  v === 'sharing' || v === 'paused' || v === 'stopped' ? v : undefined;

export interface TripMember {
  userId: number;
  fullName?: string;
  role: string;
  lat?: number;
  lon?: number;
  speed?: number;
  fixAgeSeconds?: number;
  sharingState?: MemberSharingState;
  isOwner: boolean;
  hasPosition: boolean;
}

export const tripMember = (j: Json): TripMember => ({
  userId: j.user_id,
  fullName: j.full_name ?? undefined,
  role: j.role,
  lat: j.lat ?? undefined,
  lon: j.lon ?? undefined,
  speed: j.speed ?? undefined,
  fixAgeSeconds: j.fix_age_s ?? undefined,
  sharingState: sharingState(j.sharing_state),
  isOwner: j.role === 'owner',
  hasPosition: j.lat != null && j.lon != null,
});

export interface TripDetail {
  trip: Trip;
  members: TripMember[];
}

export const tripDetail = (j: Json): TripDetail => ({ trip: trip(j.trip), members: (j.members as Json[]).map(tripMember) });

// ---------------- notifications ----------------

export interface TrackMeNotification {
  id: number;
  notificationId: number;
  title: string;
  body: string;
  createdAt: string;
  readAt?: string;
  isRead: boolean;
  tripId?: number;
}

export interface NotificationPage {
  items: TrackMeNotification[];
  nextCursor?: string;
  hasMore: boolean;
}

export const notificationPage = (j: Json): NotificationPage => ({
  items: (j.items as Json[]).map((n) => ({
    id: n.id,
    notificationId: n.notification_id,
    title: n.title,
    body: n.body,
    createdAt: n.created_at,
    readAt: n.read_at ?? undefined,
    isRead: n.is_read ?? false,
    tripId: n.trip_id ?? undefined,
  })),
  nextCursor: j.next_cursor ?? undefined,
  hasMore: j.has_more,
});

// ---------------- location ----------------

export type LocationPermissionLevel = 'NotDetermined' | 'Denied' | 'WhileInUse' | 'Always';

export interface LocationPermissionStatus {
  level: LocationPermissionLevel;
  precise: boolean;
  servicesEnabled: boolean;
  backgroundCapable: boolean;
  isGranted: boolean;
}

export const permissionStatus = (j: Json): LocationPermissionStatus => {
  const level: LocationPermissionLevel = ['Denied', 'WhileInUse', 'Always'].includes(j.level) ? j.level : 'NotDetermined';
  return {
    level,
    precise: j.precise,
    servicesEnabled: j.services_enabled,
    backgroundCapable: j.background_capable,
    isGranted: level === 'WhileInUse' || level === 'Always',
  };
};

/** One GPS fix: degrees, metres, metres/second, epoch milliseconds. */
export interface TrackMeLocation {
  latitude: number;
  longitude: number;
  timestampMs: number;
  accuracyMeters?: number;
  altitudeMeters?: number;
  speedMetersPerSecond?: number;
  bearingDegrees?: number;
}

const location = (j: Json): TrackMeLocation => ({
  latitude: j.latitude,
  longitude: j.longitude,
  timestampMs: j.timestamp_ms,
  accuracyMeters: j.accuracy_m ?? undefined,
  altitudeMeters: j.altitude_m ?? undefined,
  speedMetersPerSecond: j.speed_mps ?? undefined,
  bearingDegrees: j.bearing_deg ?? undefined,
});

// ---------------- sharing ----------------

export type StopReason =
  | 'User'
  | 'PermissionRevoked'
  | 'ServicesDisabled'
  | 'NoActiveTrips'
  | 'Unauthorized'
  | 'BackgroundNotAllowed'
  | 'Disposed';

export type SharingState =
  | { type: 'Idle' }
  | { type: 'Starting' }
  | { type: 'Paused' }
  | { type: 'Stopped'; reason: StopReason }
  | { type: 'Sharing'; tripIds: number[]; lastLocation?: TrackMeLocation; lastSentAtMs?: number; retrying: boolean };

export const sharingStateFrom = (j: Json): SharingState => {
  switch (j.type) {
    case 'Starting':
    case 'Paused':
      return { type: j.type };
    case 'Stopped':
      return { type: 'Stopped', reason: j.reason };
    case 'Sharing':
      return {
        type: 'Sharing',
        tripIds: j.trip_ids,
        lastLocation: j.last_location ? location(j.last_location) : undefined,
        lastSentAtMs: j.last_sent_at_ms ?? undefined,
        retrying: j.retrying ?? false,
      };
    default:
      return { type: 'Idle' };
  }
};

/** Starting, sharing or paused: the notification / Live Activity is up. */
export const isSharingActive = (s: SharingState) => s.type === 'Starting' || s.type === 'Sharing' || s.type === 'Paused';

/** Tuning for `TrackMe.sharing.start`. The defaults suit people and vehicles in a city. */
export interface SharingOptions {
  intervalMs?: number;
  minDistanceMeters?: number;
  heartbeatMs?: number;
  highAccuracy?: boolean;
}

export const sharingOptionsJson = (o: SharingOptions = {}) => ({
  ...(o.intervalMs != null && { interval_ms: o.intervalMs }),
  ...(o.minDistanceMeters != null && { min_distance_m: o.minDistanceMeters }),
  ...(o.heartbeatMs != null && { heartbeat_ms: o.heartbeatMs }),
  ...(o.highAccuracy != null && { high_accuracy: o.highAccuracy }),
});

// ---------------- realtime ----------------

export type RealtimeState =
  | { type: 'Idle' }
  | { type: 'Connecting' }
  | { type: 'Reconnecting'; attempt: number }
  | { type: 'Connected'; tripId?: number; watchingShare: boolean };

export const realtimeStateFrom = (j: Json): RealtimeState => {
  switch (j.type) {
    case 'Connecting':
      return { type: 'Connecting' };
    case 'Reconnecting':
      return { type: 'Reconnecting', attempt: j.attempt };
    case 'Connected':
      return { type: 'Connected', tripId: j.trip_id ?? undefined, watchingShare: j.watching_share ?? false };
    default:
      return { type: 'Idle' };
  }
};

/** One live position of a trip member (`trip_position` / `share_position`). */
export interface MemberPosition {
  tripId?: number;
  userId: number;
  displayName?: string;
  latitude: number;
  longitude: number;
  speedMetersPerSecond?: number;
  fixAgeSeconds?: number;
}

const position = (j: Json): MemberPosition => ({
  tripId: j.trip_id ?? undefined,
  userId: j.user_id,
  displayName: j.display_name ?? undefined,
  latitude: j.latitude,
  longitude: j.longitude,
  speedMetersPerSecond: j.speed_mps ?? undefined,
  fixAgeSeconds: j.fix_age_s ?? undefined,
});

// ---------------- lifecycle + events ----------------

export type TrackMeLifecycle = 'NotInitialized' | 'Ready' | 'Disposed';

export type TrackMeEvent =
  | { type: 'AuthenticationFailed' }
  | { type: 'TokenRefreshed' }
  | { type: 'ShareEnded' }
  | { type: 'SharingStopped'; reason: StopReason }
  | { type: 'TripPosition'; position: MemberPosition }
  | { type: 'SharePosition'; position: MemberPosition }
  | { type: 'MemberChanged'; tripId: number; userId: number; displayName?: string; change: string }
  | { type: 'MemberSharingChanged'; tripId: number; userId: number; displayName?: string; state: MemberSharingState }
  | { type: 'TripEnded'; tripId?: number; reason?: string }
  | { type: 'Evicted'; tripId?: number; reason?: string };

/** Undefined for an event type this package doesn't know yet (a newer SDK). */
export const eventFrom = (j: Json): TrackMeEvent | undefined => {
  switch (j.type) {
    case 'AuthenticationFailed':
    case 'TokenRefreshed':
    case 'ShareEnded':
      return { type: j.type };
    case 'SharingStopped':
      return { type: 'SharingStopped', reason: j.reason };
    case 'TripPosition':
    case 'SharePosition':
      return { type: j.type, position: position(j.position) };
    case 'MemberChanged':
      return { type: 'MemberChanged', tripId: j.trip_id, userId: j.user_id, displayName: j.display_name ?? undefined, change: j.change };
    case 'MemberSharingChanged': {
      const state = sharingState(j.state);
      return state && { type: 'MemberSharingChanged', tripId: j.trip_id, userId: j.user_id, displayName: j.display_name ?? undefined, state };
    }
    case 'TripEnded':
    case 'Evicted':
      return { type: j.type, tripId: j.trip_id ?? undefined, reason: j.reason ?? undefined };
    default:
      return undefined;
  }
};

// ---------------- errors ----------------

export type TrackMeErrorType =
  | 'NotInitialized'
  | 'Unauthorized'
  | 'Forbidden'
  | 'NotFound'
  | 'Conflict'
  | 'Validation'
  | 'Network'
  | 'Server'
  | 'LocationPermissionDenied'
  | 'LocationServicesDisabled'
  | 'BackgroundNotAllowed'
  | 'Unknown';

/**
 * Every way a TrackMe call can fail. `message` is always safe to show; for `Validation` from the server it's the
 * server's own text, e.g. "duration_min: Input should be greater than or equal to 5".
 */
export class TrackMeError extends Error {
  readonly type: TrackMeErrorType;
  /** HTTP status for `Server`. */
  readonly statusCode?: number;
  /** For `LocationPermissionDenied`: false = only the Settings app can fix it. */
  readonly canAskAgain?: boolean;

  constructor(j: Json) {
    super(j.message ?? 'Unknown error');
    this.name = 'TrackMeError';
    this.type = j.type ?? 'Unknown';
    this.statusCode = j.status_code ?? undefined;
    this.canAskAgain = j.can_ask_again ?? undefined;
  }
}
