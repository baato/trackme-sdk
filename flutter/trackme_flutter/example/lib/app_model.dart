// The example's state and every SDK call it makes, in one place: a port of the native samples' SampleViewModel,
// so this file doubles as a "how to use trackme_flutter" reference. Every call throws TrackMeException on failure.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:trackme_flutter/trackme_flutter.dart';

import 'config.dart';

enum Section { map, developer }

/// Who to log in as. A different email per device, to test trips together.
class LoginForm {
  const LoginForm(this.email, this.fullName);
  final String email;
  final String fullName;
}

/// A pasted invite: `Follow me on TrackMe: https://…/trackme/s/<token>\nJoin code: ABC123`, a link or a bare code.
class Invite {
  const Invite(this.viewToken, this.code, [this.preview]);
  final String? viewToken;
  final String? code;
  final TripPreview? preview;

  Invite withPreview(TripPreview p) => Invite(viewToken, code, p);

  static final _link = RegExp(r'/s/([A-Za-z0-9_\-]+)');
  static final _labelledCode = RegExp(r'code[:\s]+([A-Za-z0-9]{4,16})', caseSensitive: false);
  static final _bareCode = RegExp(r'^[A-Za-z0-9]{4,16}$');

  static Invite? parse(String text) {
    final t = text.trim();
    final token = _link.firstMatch(t)?.group(1);
    final code = _labelledCode.firstMatch(t)?.group(1) ?? (_bareCode.hasMatch(t) ? t : null);
    if (token == null && code == null) return null;
    return Invite(token, code?.toUpperCase());
  }
}

/// The text the live map shares; the same shape [Invite.parse] reads back.
String inviteText(String tripName, String? viewLink, String? joinCode) {
  final b = StringBuffer('Follow me on TrackMe');
  if (viewLink != null) b.write(': $viewLink');
  if (joinCode != null) b.write('\nJoin code: $joinCode');
  if (viewLink == null && joinCode == null) b.write(' ($tripName)');
  return b.toString();
}

/// How long a member still counts as "live" after their newest position. Sharing re-sends at least every heartbeat
/// (30 s by default) even when standing still, so a longer silence means paused, stopped, offline or no GPS.
const liveWindowSeconds = 45;

class AppModel extends ChangeNotifier {
  AppModel() {
    _subs
      ..add(TrackMe.state.listen((s) => _set(() => lifecycle = s)))
      ..add(TrackMe.sharing.state.listen((s) {
        sharing = s;
        _log('Sharing: ${s.runtimeType}');
      }))
      ..add(TrackMe.realtime.state.listen((s) {
        realtime = s;
        _log('Live: ${s.runtimeType}');
      }))
      ..add(TrackMe.events.listen(_onEvent));
  }

  final _subs = <StreamSubscription<Object?>>[];

  // ---- session ----
  LoginForm? login;
  bool get loggedIn => login != null;
  bool busy = false;
  String? message;
  Section section = Section.map;
  TrackMeLifecycle lifecycle = TrackMeLifecycle.notInitialized;
  SharingState sharing = const SharingIdle();
  RealtimeState realtime = const RealtimeIdle();
  List<String> log = [];

  // ---- live map ----
  /// The trip the map shows (the most recent live trip, or the one picked / just created / joined).
  TripDetail? mapDetail;
  DateTime mapLoadedAt = DateTime.now();
  List<Trip> mapLiveTrips = [];
  List<Trip> trips = [];
  String? mapViewLink;
  Invite? invite;

  /// Why sharing couldn't start from the map (e.g. no permission); the panel offers a fix.
  TrackMeException? mapSharingError;

  /// The trip TrackMe.realtime is watching; [livePositions] belong to it.
  int? liveTripId;
  Map<int, MemberPosition> livePositions = {};

  /// When each live position arrived: a position only says "live" while new ones keep coming.
  Map<int, DateTime> livePositionAt = {};

  /// Sharing state each member reported (MemberSharingChanged) and when it arrived.
  Map<int, (MemberSharingState, DateTime)> memberSharing = {};
  DateTime _lastLivePositionAt = DateTime.fromMillisecondsSinceEpoch(0);

  // ---- location ----
  LocationPermissionStatus? permission;

  /// The map asked for location once this session (it asks by itself only once, so it never nags).
  bool locationAutoAsked = false;

  /// The system won't show the dialog again: only Settings can grant location now.
  bool locationBlocked = false;

  // ---- invite preview (view link) ----
  Map<String, MemberPosition> viewerPositions = {};
  bool viewerEnded = false;

  // ======================= events =======================

  void _onEvent(TrackMeEvent e) {
    switch (e) {
      case AuthenticationFailed():
        _fail('Session expired: log in again.');
      case TokenRefreshed():
        _log('Token refreshed by the token provider');
      case SharingStoppedEvent(:final reason):
        _log('Sharing stopped: ${reason.name}');
      case TripPositionEvent(:final position):
        if (position.tripId != null && position.tripId != liveTripId) return; // stale, from the previous trip
        final now = DateTime.now();
        _lastLivePositionAt = now;
        livePositions = {...livePositions, position.userId: position};
        livePositionAt = {...livePositionAt, position.userId: now};
        notifyListeners();
      case MemberSharingChanged(:final tripId, :final userId, :final displayName, :final state):
        _log('${displayName ?? 'Member $userId'}: ${state.name}');
        if (tripId == liveTripId) {
          memberSharing = {...memberSharing, userId: (state, DateTime.now())};
          notifyListeners();
        }
      case MemberChanged(:final displayName, :final change):
        _log('${displayName ?? 'Someone'} $change');
        _reloadMapTrip();
      case TripEnded(:final tripId, :final reason):
        _log('Trip $tripId ended (${reason ?? 'no reason'})');
        liveTripId = null;
        _reloadMapTrip(); // the panel shows "Trip ended"
      case Evicted(:final tripId, :final reason):
        _log('Removed from trip $tripId: $reason');
        liveTripId = null;
        if (mapDetail == null || tripId == null || mapDetail!.trip.id == tripId) mapDetail = null;
        _fail("You're no longer in this trip (${reason ?? 'removed'}).");
        mapRefresh();
      case SharePositionEvent(:final position):
        viewerPositions = {...viewerPositions, '${position.userId}': position};
        notifyListeners();
      case ShareEnded():
        _log('View link ended');
        viewerEnded = true;
        notifyListeners();
    }
  }

  // ======================= login / lifecycle =======================

  /// DEMO ONLY: POST /v1/auth/login with the API secret. In a real app this call lives on YOUR backend.
  static Future<String?> demoLogin(LoginForm form) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 10);
    try {
      final req = await client.postUrl(Uri.parse('$apiUrl/v1/auth/login'));
      req.headers.contentType = ContentType.json;
      req.write(jsonEncode({'email': form.email, 'api_secret': apiSecret, if (form.fullName.isNotEmpty) 'full_name': form.fullName}));
      final res = await req.close();
      final body = await res.transform(utf8.decoder).join();
      if (res.statusCode >= 300) return null;
      return (jsonDecode(body) as Map<String, dynamic>)['access_token'] as String?;
    } catch (_) {
      return null;
    } finally {
      client.close();
    }
  }

  Future<void> logIn(LoginForm form) => _run('Logging in', () async {
        if (apiSecret.isEmpty) return _fail('TRACKME_API_SECRET missing: run with --dart-define-from-file=secrets.json');
        final token = await demoLogin(form);
        if (token == null) return _fail('Login failed (check email / secret / network).');
        await TrackMe.initialize(
          TrackMeConfig(
            token: token,
            sharingUi: const TrackMeSharingUi(
              title: 'Sharing your location',
              text: 'Trip members can see where you are.',
              accentColor: 0xFF1565C0,
            ),
          ),
          tokenProvider: () => demoLogin(form), // asked automatically when the server says the token expired
        );
        login = form;
        _log('Initialized as ${form.email}');
        await refreshPermission();
        await mapRefresh();
      });

  /// dispose(): stops sharing, removes the notification, closes the socket and HTTP. Safe to call twice.
  Future<void> logOut() async {
    await TrackMe.dispose();
    login = null;
    mapDetail = null;
    invite = null;
    liveTripId = null;
    livePositions = {};
    livePositionAt = {};
    memberSharing = {};
    trips = [];
    mapLiveTrips = [];
    _log('Disposed');
  }

  void selectSection(Section s) => _set(() => section = s);
  void clearMessage() => _set(() => message = null);

  // ======================= location =======================

  Future<void> refreshPermission() async {
    try {
      permission = await TrackMe.location.permissionStatus();
      if (permission!.isGranted) locationBlocked = false;
      notifyListeners();
    } on TrackMeException catch (e) {
      _log('permissionStatus: ${e.message}');
    }
  }

  void markLocationAutoAsked() => locationAutoAsked = true;
  void setLocationBlocked(bool blocked) => _set(() => locationBlocked = blocked);

  /// Location was just granted: if sharing from the map failed for lack of it, start it now.
  Future<void> onLocationGranted() async {
    await refreshPermission();
    if (mapSharingError?.type == TrackMeErrorType.locationPermissionDenied) await startSharingFromMap();
  }

  // ======================= live map =======================

  /// Picks the trip the map shows and keeps it live. Called whenever the map appears.
  Future<void> mapRefresh() async {
    try {
      final all = await TrackMe.trips.list(limit: 50);
      trips = all;
      mapLiveTrips = all.where((t) => t.isLive).toList();
      // Keep the current trip while it's in the list (even ended: the panel shows "Trip ended" until Done).
      final current = mapDetail?.trip.id;
      final keep = current != null && all.any((t) => t.id == current) ? current : null;
      final id = keep ?? (mapLiveTrips.isEmpty ? null : mapLiveTrips.first.id);
      if (id == null) {
        _set(() => mapDetail = null);
      } else {
        await _loadMapTrip(id);
      }
    } on TrackMeException catch (e) {
      _log('trips.list: ${e.message}');
    }
  }

  Future<void> mapSelect(int tripId) => _loadMapTrip(tripId);

  /// Called every 5 s by the live map. Reloads the snapshot only when no live position came in the last 10 s, so
  /// positions keep moving even if live messages don't arrive (the SDK itself doesn't poll).
  void mapPoll() {
    if (DateTime.now().difference(_lastLivePositionAt).inSeconds > 10) _reloadMapTrip();
  }

  void _reloadMapTrip() {
    final id = mapDetail?.trip.id;
    if (id != null) _loadMapTrip(id);
  }

  Future<void> _loadMapTrip(int id) async {
    try {
      final d = await TrackMe.trips.detail(id);
      final switched = mapDetail?.trip.id != id;
      mapDetail = d;
      mapLoadedAt = DateTime.now();
      if (switched) mapViewLink = null;
      notifyListeners();
      if (d.trip.isLive && (liveTripId != id || realtime is RealtimeIdle)) await _watchLive(id);
    } on TrackMeException catch (e) {
      _log('trips.detail: ${e.message}');
    }
  }

  /// One socket watches one trip; switching trips clears the positions of the previous one.
  Future<void> _watchLive(int tripId) async {
    if (liveTripId != tripId) {
      liveTripId = tripId;
      livePositions = {};
      livePositionAt = {};
      memberSharing = {};
    }
    try {
      await TrackMe.realtime.watchTrip(tripId); // completes when the server confirms
    } on TrackMeException catch (e) {
      if (liveTripId == tripId) liveTripId = null;
      _log('Live updates unavailable: ${e.message}');
    }
    notifyListeners();
  }

  /// Create → start sharing → show it.
  Future<void> mapCreate(String name, int minutes) => _run('Starting trip', () async {
        final c = await TrackMe.trips.create(name: name.trim().isEmpty ? null : name.trim(), durationMinutes: minutes);
        _log('Created trip ${c.id}, code ${c.joinCode}');
        await startSharingFromMap();
        await _loadMapTrip(c.id);
        mapViewLink = c.viewLink; // after _loadMapTrip, which resets it for a new trip
        await mapRefresh();
      });

  /// Join → show everyone. Sharing your own location is a separate, later choice ("Share my location too").
  Future<void> mapJoin(String code) => _run('Joining', () async {
        final t = await TrackMe.trips.join(code.trim());
        _log('Joined trip ${t.id}');
        await mapCancelInvite();
        await _loadMapTrip(t.id);
        await mapRefresh();
      });

  /// Owner removes a member (`trips.removeMember`). They get `evicted`.
  Future<void> mapRemoveMember(int userId) async {
    final id = mapDetail?.trip.id;
    if (id == null) return;
    await _run('Removing member', () async {
      await TrackMe.trips.removeMember(id, userId);
      livePositions = {...livePositions}..remove(userId);
      livePositionAt = {...livePositionAt}..remove(userId);
      memberSharing = {...memberSharing}..remove(userId);
      await _loadMapTrip(id);
    });
  }

  Future<void> mapLeave() async {
    final id = mapDetail?.trip.id;
    if (id == null) return;
    await _run('Leaving', () async {
      await TrackMe.trips.leave(id);
      await TrackMe.realtime.stopWatchingTrip();
      mapDetail = null;
      liveTripId = null;
      await mapRefresh();
    });
  }

  /// Owner only. Members get trip_ended over the socket; everyone's sharing to this trip stops.
  Future<void> mapEnd() async {
    final id = mapDetail?.trip.id;
    if (id == null) return;
    await _run('Ending trip', () async {
      await TrackMe.trips.end(id);
      await _loadMapTrip(id);
    });
  }

  /// Dismiss the "Trip ended" card.
  Future<void> mapDone() async {
    _set(() => mapDetail = null);
    await mapRefresh();
  }

  /// The invite text (view link + join code), for Copy and Share.
  Future<String?> mapInviteText() async {
    final d = mapDetail;
    if (d == null) return null;
    try {
      mapViewLink ??= await TrackMe.trips.viewLink(d.trip.id);
    } on TrackMeException catch (e) {
      _log('viewLink: ${e.message}');
    }
    notifyListeners();
    return inviteText(d.trip.name, mapViewLink, d.trip.joinCode);
  }

  /// A pasted invite: show who's on the map (watchShare) and the trip (preview) before joining.
  Future<void> mapOpenInvite(String text) async {
    final parsed = Invite.parse(text);
    if (parsed == null) return _fail('Paste a join code or a TrackMe invite.');
    invite = parsed;
    viewerPositions = {};
    viewerEnded = false;
    notifyListeners();
    await _run('Opening invite', () async {
      final token = parsed.viewToken;
      if (token != null) {
        try {
          await TrackMe.realtime.watchShare(token);
        } on TrackMeException catch (e) {
          _log("Can't watch this link: ${e.message}");
        }
      }
      final code = parsed.code;
      if (code != null) {
        final p = await TrackMe.trips.preview(code);
        invite = invite?.withPreview(p);
      }
    });
  }

  Future<void> mapCancelInvite() async {
    if (invite == null) return;
    await TrackMe.realtime.stopWatchingShare();
    invite = null;
    viewerPositions = {};
    notifyListeners();
  }

  /// Start, pause or resume, depending on the current state.
  Future<void> mapToggleSharing() async {
    try {
      switch (sharing) {
        case SharingPaused():
          await TrackMe.sharing.resume();
        case SharingActive() || SharingStarting():
          await TrackMe.sharing.pause();
        default:
          await startSharingFromMap();
      }
    } on TrackMeException catch (e) {
      _fail(e.message);
    }
  }

  /// Also used after the user grants location from the map's "Allow location" button.
  Future<void> startSharingFromMap() async {
    if (sharing.isActive) return;
    try {
      await TrackMe.sharing.start();
      mapSharingError = null;
    } on TrackMeException catch (e) {
      _log('Start failed: ${e.message}');
      mapSharingError = e;
    }
    await refreshPermission();
  }

  /// Map style / load errors (e.g. a wrong Baato key).
  void reportMapError(String m) => _fail(m);

  // ======================= member status (live vs snapshot) =======================

  /// Seconds since [userId]'s newest live (socket) position for [tripId], or null if none arrived.
  int? liveAgeSeconds(int userId, int tripId, DateTime now) {
    if (liveTripId != tripId) return null;
    final p = livePositions[userId];
    final at = livePositionAt[userId];
    if (p == null || at == null) return null;
    return now.difference(at).inSeconds + (p.fixAgeSeconds ?? 0);
  }

  /// Seconds since [m]'s fix in the trips.detail() snapshot, or null without a position.
  int? snapshotAgeSeconds(TripMember m, DateTime now) =>
      m.hasPosition ? (m.fixAgeSeconds ?? 0) + now.difference(mapLoadedAt).inSeconds : null;

  /// "paused" / "not sharing" when the member said so (and no position arrived since), otherwise "live",
  /// "2 min 5 s ago" or "no location", from whichever is newer: the live position or the snapshot.
  String memberStatus(TripMember m, int tripId, DateTime now) {
    final event = liveTripId == tripId ? memberSharing[m.userId] : null;
    final snapshot = m.sharingState == null ? null : (m.sharingState!, mapLoadedAt);
    final reported = [event, snapshot].whereType<(MemberSharingState, DateTime)>().fold<(MemberSharingState, DateTime)?>(
          null,
          (best, r) => best == null || r.$2.isAfter(best.$2) ? r : best,
        );
    final liveAt = liveTripId == tripId ? livePositionAt[m.userId] : null;
    if (reported != null && reported.$1 != MemberSharingState.sharing && (liveAt == null || !liveAt.isAfter(reported.$2))) {
      return reported.$1 == MemberSharingState.paused ? 'paused' : 'not sharing';
    }
    final ages = [liveAgeSeconds(m.userId, tripId, now), snapshotAgeSeconds(m, now)].whereType<int>();
    if (ages.isEmpty) return 'no location';
    final age = ages.reduce((a, b) => a < b ? a : b);
    return age <= liveWindowSeconds ? 'live' : '${formatSeconds(age)} ago';
  }

  /// The live position, unless the snapshot is newer (e.g. the socket missed some while the poll caught up).
  MemberPosition? newestLive(TripMember m, int tripId, DateTime now) {
    final live = liveAgeSeconds(m.userId, tripId, now);
    if (live == null) return null;
    final snap = snapshotAgeSeconds(m, now);
    return snap == null || live <= snap ? livePositions[m.userId] : null;
  }

  // ======================= helpers =======================

  /// Runs one step with the busy indicator; a TrackMeException becomes the message dialog.
  Future<void> _run(String what, Future<void> Function() block) async {
    _set(() => busy = true);
    _log('$what…');
    try {
      await block();
    } on TrackMeException catch (e) {
      _fail(e.message);
    } finally {
      _set(() => busy = false);
    }
  }

  void _fail(String m) {
    message = m;
    _log('Error: $m');
  }

  /// Public, for the developer screen's own calls.
  void addLog(String line) => _log(line);
  void clearLog() => _set(() => log = []);

  void _log(String line) {
    final t = DateTime.now();
    String two(int v) => v.toString().padLeft(2, '0');
    log = ['${two(t.hour)}:${two(t.minute)}:${two(t.second)}  $line', ...log.take(199)];
    if (kDebugMode) debugPrint('TrackMe example: $line');
    notifyListeners();
  }

  void _set(VoidCallback f) {
    f();
    notifyListeners();
  }

  @override
  void dispose() {
    for (final s in _subs) {
      s.cancel();
    }
    super.dispose();
  }
}

/// 3725 → "1 h 2 min", 125 → "2 min 5 s".
String formatSeconds(int seconds) {
  final s = seconds < 0 ? 0 : seconds;
  if (s >= 3600) return '${s ~/ 3600} h ${(s % 3600) ~/ 60} min';
  if (s >= 60) return '${s ~/ 60} min ${s % 60} s';
  return '$s s';
}

/// 90 → "1 h 30 min", 45 → "45 min", 1440 → "24 h".
String formatMinutes(int minutes) {
  final h = minutes ~/ 60, m = minutes % 60;
  if (h == 0) return '$m min';
  if (m == 0) return '$h h';
  return '$h h $m min';
}

/// Stable colour per member, so a person keeps their colour across updates.
const _palette = [0xFFE53935, 0xFF8E24AA, 0xFF3949AB, 0xFF00897B, 0xFFF4511E, 0xFF6D4C41, 0xFF039BE5, 0xFF7CB342];
int markerColor(Object key) => _palette[key.hashCode.abs() % _palette.length];
String markerHex(Object key) => '#${(markerColor(key) & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';
