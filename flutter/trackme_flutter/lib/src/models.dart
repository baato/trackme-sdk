// Dart side of the TrackMe bridge protocol v1 (docs/07-ports.md). Server models keep the API's field names on
// the wire (docs/trackme-api.md); here they're idiomatic Dart. Unknown values (a newer server) never throw:
// strings stay strings, enums fall back to null.

typedef Json = Map<String, dynamic>;

int _int(Object? v) => (v as num).toInt();
int? _intOrNull(Object? v) => v == null ? null : (v as num).toInt();
double _double(Object? v) => (v as num).toDouble();
double? _doubleOrNull(Object? v) => v == null ? null : (v as num).toDouble();

// ---------------- trips ----------------

/// `TripSummary`: returned by list, join, rename and end.
class Trip {
  Trip.fromJson(Json j)
      : id = _int(j['id']),
        name = j['name'] as String,
        myRole = j['my_role'] as String,
        myStatus = j['my_status'] as String,
        status = j['status'] as String,
        endedReason = j['ended_reason'] as String?,
        memberCount = _int(j['member_count']),
        startedOn = j['started_on'] as String,
        endedOn = j['ended_on'] as String?,
        plannedSeconds = _int(j['planned_s']),
        actualSeconds = _int(j['actual_s']),
        expiresAt = j['expires_at'] as String,
        remainingSeconds = _intOrNull(j['remaining_s']) ?? 0,
        joinCode = j['join_code'] as String?;

  final int id;
  final String name;
  final String myRole;
  final String myStatus;
  final String status;
  final String? endedReason;
  final int memberCount;
  final String startedOn;
  final String? endedOn;
  final int plannedSeconds;
  final int actualSeconds;
  final String expiresAt;
  final int remainingSeconds;
  final String? joinCode;

  bool get isOwner => myRole == 'owner';
  bool get isLive => status == 'live';
}

/// `TripCreateResponse`.
class CreatedTrip {
  CreatedTrip.fromJson(Json j)
      : id = _int(j['id']),
        name = j['name'] as String,
        joinCode = j['join_code'] as String,
        viewLink = j['view_link'] as String,
        expiresAt = j['expires_at'] as String,
        remainingSeconds = _int(j['remaining_s']);

  final int id;
  final String name;
  final String joinCode;
  final String viewLink;
  final String expiresAt;
  final int remainingSeconds;
}

/// What a would-be member sees before joining.
class TripPreview {
  TripPreview.fromJson(Json j)
      : name = j['name'] as String,
        memberCount = _int(j['member_count']),
        ownerName = j['owner_name'] as String?,
        expiresAt = j['expires_at'] as String;

  final String name;
  final int memberCount;
  final String? ownerName;
  final String expiresAt;
}

/// Whether a member is sending their location, as they reported it. Null until the backend supports it.
enum MemberSharingState {
  sharing,
  paused,
  stopped;

  static MemberSharingState? fromWire(Object? v) {
    for (final s in MemberSharingState.values) {
      if (s.name == v) return s;
    }
    return null;
  }
}

/// `TripDetailMember`. Position fields are null until the member has sent a fix.
class TripMember {
  TripMember.fromJson(Json j)
      : userId = _int(j['user_id']),
        fullName = j['full_name'] as String?,
        role = j['role'] as String,
        lat = _doubleOrNull(j['lat']),
        lon = _doubleOrNull(j['lon']),
        speed = _doubleOrNull(j['speed']),
        fixAgeSeconds = _intOrNull(j['fix_age_s']),
        sharingState = MemberSharingState.fromWire(j['sharing_state']);

  final int userId;
  final String? fullName;
  final String role;
  final double? lat;
  final double? lon;
  final double? speed;
  final int? fixAgeSeconds;
  final MemberSharingState? sharingState;

  bool get isOwner => role == 'owner';
  bool get hasPosition => lat != null && lon != null;
}

class TripDetail {
  TripDetail.fromJson(Json j)
      : trip = Trip.fromJson(j['trip'] as Json),
        members = [for (final m in j['members'] as List) TripMember.fromJson(m as Json)];

  final Trip trip;
  final List<TripMember> members;
}

// ---------------- notifications ----------------

class TrackMeNotification {
  TrackMeNotification.fromJson(Json j)
      : id = _int(j['id']),
        notificationId = _int(j['notification_id']),
        title = j['title'] as String,
        body = j['body'] as String,
        createdAt = j['created_at'] as String,
        readAt = j['read_at'] as String?,
        isRead = j['is_read'] as bool? ?? false,
        tripId = _intOrNull(j['trip_id']);

  final int id;
  final int notificationId;
  final String title;
  final String body;
  final String createdAt;
  final String? readAt;
  final bool isRead;
  final int? tripId;
}

class NotificationPage {
  NotificationPage.fromJson(Json j)
      : items = [for (final n in j['items'] as List) TrackMeNotification.fromJson(n as Json)],
        nextCursor = j['next_cursor'] as String?,
        hasMore = j['has_more'] as bool;

  final List<TrackMeNotification> items;
  final String? nextCursor;
  final bool hasMore;
}

// ---------------- location ----------------

enum LocationPermissionLevel { notDetermined, denied, whileInUse, always }

LocationPermissionLevel _level(Object? v) => switch (v) {
      'Denied' => LocationPermissionLevel.denied,
      'WhileInUse' => LocationPermissionLevel.whileInUse,
      'Always' => LocationPermissionLevel.always,
      _ => LocationPermissionLevel.notDetermined,
    };

class LocationPermissionStatus {
  LocationPermissionStatus.fromJson(Json j)
      : level = _level(j['level']),
        precise = j['precise'] as bool,
        servicesEnabled = j['services_enabled'] as bool,
        backgroundCapable = j['background_capable'] as bool;

  final LocationPermissionLevel level;
  final bool precise;
  final bool servicesEnabled;
  final bool backgroundCapable;

  bool get isGranted => level == LocationPermissionLevel.whileInUse || level == LocationPermissionLevel.always;
}

/// One GPS fix: degrees, metres, metres/second.
class TrackMeLocation {
  TrackMeLocation.fromJson(Json j)
      : latitude = _double(j['latitude']),
        longitude = _double(j['longitude']),
        timestamp = DateTime.fromMillisecondsSinceEpoch(_int(j['timestamp_ms'])),
        accuracyMeters = _doubleOrNull(j['accuracy_m']),
        altitudeMeters = _doubleOrNull(j['altitude_m']),
        speedMetersPerSecond = _doubleOrNull(j['speed_mps']),
        bearingDegrees = _doubleOrNull(j['bearing_deg']);

  final double latitude;
  final double longitude;
  final DateTime timestamp;
  final double? accuracyMeters;
  final double? altitudeMeters;
  final double? speedMetersPerSecond;
  final double? bearingDegrees;
}

// ---------------- sharing ----------------

/// Why sharing ended.
enum StopReason { user, permissionRevoked, servicesDisabled, noActiveTrips, unauthorized, backgroundNotAllowed, disposed }

StopReason _reason(Object? v) => switch (v) {
      'PermissionRevoked' => StopReason.permissionRevoked,
      'ServicesDisabled' => StopReason.servicesDisabled,
      'NoActiveTrips' => StopReason.noActiveTrips,
      'Unauthorized' => StopReason.unauthorized,
      'BackgroundNotAllowed' => StopReason.backgroundNotAllowed,
      'Disposed' => StopReason.disposed,
      _ => StopReason.user,
    };

/// `TrackMe.sharing.state`.
sealed class SharingState {
  const SharingState();

  factory SharingState.fromJson(Json j) => switch (j['type']) {
        'Starting' => const SharingStarting(),
        'Paused' => const SharingPaused(),
        'Stopped' => SharingStopped(_reason(j['reason'])),
        'Sharing' => SharingActive(
            tripIds: [for (final id in j['trip_ids'] as List) _int(id)],
            lastLocation: j['last_location'] == null ? null : TrackMeLocation.fromJson(j['last_location'] as Json),
            lastSentAt: j['last_sent_at_ms'] == null ? null : DateTime.fromMillisecondsSinceEpoch(_int(j['last_sent_at_ms'])),
            retrying: j['retrying'] as bool? ?? false,
          ),
        _ => const SharingIdle(),
      };

  /// Starting, sharing or paused: the notification / Live Activity is up.
  bool get isActive => this is SharingStarting || this is SharingActive || this is SharingPaused;
}

class SharingIdle extends SharingState { const SharingIdle(); }
class SharingStarting extends SharingState { const SharingStarting(); }
class SharingPaused extends SharingState { const SharingPaused(); }
class SharingStopped extends SharingState { const SharingStopped(this.reason); final StopReason reason; }

/// Sharing: [tripIds] received the last upload; [retrying] while uploads fail (offline, 5xx).
class SharingActive extends SharingState {
  const SharingActive({required this.tripIds, this.lastLocation, this.lastSentAt, required this.retrying});
  final List<int> tripIds;
  final TrackMeLocation? lastLocation;
  final DateTime? lastSentAt;
  final bool retrying;
}

/// Tuning for `TrackMe.sharing.start`. The defaults suit people and vehicles in a city.
class SharingOptions {
  const SharingOptions({
    this.interval = const Duration(seconds: 5),
    this.minDistanceMeters = 5,
    this.heartbeat = const Duration(seconds: 30),
    this.highAccuracy = true,
  });
  final Duration interval;
  final double minDistanceMeters;
  final Duration heartbeat;
  final bool highAccuracy;

  Json toJson() => {
        'interval_ms': interval.inMilliseconds,
        'min_distance_m': minDistanceMeters,
        'heartbeat_ms': heartbeat.inMilliseconds,
        'high_accuracy': highAccuracy,
      };
}

// ---------------- realtime ----------------

sealed class RealtimeState {
  const RealtimeState();
  factory RealtimeState.fromJson(Json j) => switch (j['type']) {
        'Connecting' => const RealtimeConnecting(),
        'Reconnecting' => RealtimeReconnecting(_int(j['attempt'])),
        'Connected' => RealtimeConnected(tripId: _intOrNull(j['trip_id']), watchingShare: j['watching_share'] as bool? ?? false),
        _ => const RealtimeIdle(),
      };
}

class RealtimeIdle extends RealtimeState { const RealtimeIdle(); }
class RealtimeConnecting extends RealtimeState { const RealtimeConnecting(); }
class RealtimeReconnecting extends RealtimeState { const RealtimeReconnecting(this.attempt); final int attempt; }
class RealtimeConnected extends RealtimeState {
  const RealtimeConnected({this.tripId, required this.watchingShare});
  final int? tripId;
  final bool watchingShare;
}

/// One live position of a trip member (`trip_position` / `share_position`).
class MemberPosition {
  MemberPosition.fromJson(Json j)
      : tripId = _intOrNull(j['trip_id']),
        userId = _int(j['user_id']),
        displayName = j['display_name'] as String?,
        latitude = _double(j['latitude']),
        longitude = _double(j['longitude']),
        speedMetersPerSecond = _doubleOrNull(j['speed_mps']),
        fixAgeSeconds = _intOrNull(j['fix_age_s']);

  final int? tripId;
  final int userId;
  final String? displayName;
  final double latitude;
  final double longitude;
  final double? speedMetersPerSecond;
  final int? fixAgeSeconds;
}

// ---------------- lifecycle + events ----------------

enum TrackMeLifecycle { notInitialized, ready, disposed }

TrackMeLifecycle lifecycleFromJson(Json j) => switch (j['type']) {
      'Ready' => TrackMeLifecycle.ready,
      'Disposed' => TrackMeLifecycle.disposed,
      _ => TrackMeLifecycle.notInitialized,
    };

/// `TrackMe.events`: one-off things while the SDK runs. Live updates arrive once you watch a trip or a link.
sealed class TrackMeEvent {
  const TrackMeEvent();

  /// Null for an event type this package doesn't know yet (a newer SDK).
  static TrackMeEvent? fromJson(Json j) => switch (j['type']) {
        'AuthenticationFailed' => const AuthenticationFailed(),
        'TokenRefreshed' => const TokenRefreshed(),
        'ShareEnded' => const ShareEnded(),
        'SharingStopped' => SharingStoppedEvent(_reason(j['reason'])),
        'TripPosition' => TripPositionEvent(MemberPosition.fromJson(j['position'] as Json)),
        'SharePosition' => SharePositionEvent(MemberPosition.fromJson(j['position'] as Json)),
        'MemberChanged' => MemberChanged(
            tripId: _int(j['trip_id']), userId: _int(j['user_id']), displayName: j['display_name'] as String?, change: j['change'] as String),
        'MemberSharingChanged' => MemberSharingState.fromWire(j['state']) == null
            ? null
            : MemberSharingChanged(
                tripId: _int(j['trip_id']),
                userId: _int(j['user_id']),
                displayName: j['display_name'] as String?,
                state: MemberSharingState.fromWire(j['state'])!,
              ),
        'TripEnded' => TripEnded(tripId: _intOrNull(j['trip_id']), reason: j['reason'] as String?),
        'Evicted' => Evicted(tripId: _intOrNull(j['trip_id']), reason: j['reason'] as String?),
        _ => null,
      };
}

/// A 401 couldn't be recovered: get a new token and call `TrackMe.updateToken`.
class AuthenticationFailed extends TrackMeEvent { const AuthenticationFailed(); }
class TokenRefreshed extends TrackMeEvent { const TokenRefreshed(); }
class ShareEnded extends TrackMeEvent { const ShareEnded(); }
class SharingStoppedEvent extends TrackMeEvent { const SharingStoppedEvent(this.reason); final StopReason reason; }
class TripPositionEvent extends TrackMeEvent { const TripPositionEvent(this.position); final MemberPosition position; }
class SharePositionEvent extends TrackMeEvent { const SharePositionEvent(this.position); final MemberPosition position; }

/// Someone joined the watched trip.
class MemberChanged extends TrackMeEvent {
  const MemberChanged({required this.tripId, required this.userId, this.displayName, required this.change});
  final int tripId;
  final int userId;
  final String? displayName;
  final String change;
  bool get joined => change == 'joined';
}

/// A member started, paused, resumed or stopped sharing (needs the backend's sharing-state support).
class MemberSharingChanged extends TrackMeEvent {
  const MemberSharingChanged({required this.tripId, required this.userId, this.displayName, required this.state});
  final int tripId;
  final int userId;
  final String? displayName;
  final MemberSharingState state;
}

class TripEnded extends TrackMeEvent { const TripEnded({this.tripId, this.reason}); final int? tripId; final String? reason; }
class Evicted extends TrackMeEvent { const Evicted({this.tripId, this.reason}); final int? tripId; final String? reason; }
