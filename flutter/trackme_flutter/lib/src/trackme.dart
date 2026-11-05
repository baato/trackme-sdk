import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'errors.dart';
import 'models.dart';

/// Everything the app supplies to [TrackMe.initialize]. The SDK never logs in: your backend gets the access token
/// (`POST /api/v1/auth/login`) and the app passes it here.
class TrackMeConfig {
  const TrackMeConfig({
    required this.token,
    this.apiUrl,
    this.socketUrl,
    this.requestTimeout,
    this.tokenRefreshTimeout,
    this.sharingUi = const TrackMeSharingUi(),
  });

  final String token;
  final String? apiUrl;
  final String? socketUrl;
  final Duration? requestTimeout;
  final Duration? tokenRefreshTimeout;
  final TrackMeSharingUi sharingUi;

  Map<String, Object?> toJson() => {
        'token': token,
        if (apiUrl != null) 'api_url': apiUrl,
        if (socketUrl != null) 'socket_url': socketUrl,
        if (requestTimeout != null) 'request_timeout_ms': requestTimeout!.inMilliseconds,
        if (tokenRefreshTimeout != null) 'token_refresh_timeout_ms': tokenRefreshTimeout!.inMilliseconds,
        'sharing_ui': sharingUi.toJson(),
      };
}

/// Look and labels of the Android notification and the iOS Live Activity. All strings are yours, so you can
/// localize them. [accentColor] is ARGB (e.g. `0xFF1565C0`); [androidSmallIcon] is a drawable *name* in your app
/// (e.g. `"ic_stat_trackme"`), monochrome, for the status bar.
class TrackMeSharingUi {
  const TrackMeSharingUi({
    this.title,
    this.text,
    this.pausedText,
    this.accentColor,
    this.showPauseAction,
    this.showStopAction,
    this.pauseLabel,
    this.resumeLabel,
    this.stopLabel,
    this.androidSmallIcon,
    this.androidChannelName,
    this.iosSymbolName,
    this.iosLiveActivity,
  });

  final String? title, text, pausedText, pauseLabel, resumeLabel, stopLabel;
  final int? accentColor;
  final bool? showPauseAction, showStopAction;
  final String? androidSmallIcon, androidChannelName, iosSymbolName;

  /// iOS lock screen while sharing (default true): a Live Activity when the app has the Widget Extension
  /// (docs/07-ports.md), otherwise a notification with Pause / Resume / Stop. False: the app shows its own.
  final bool? iosLiveActivity;

  Map<String, Object?> toJson() => {
        if (title != null) 'title': title,
        if (text != null) 'text': text,
        if (pausedText != null) 'paused_text': pausedText,
        if (accentColor != null) 'accent_color': accentColor,
        if (showPauseAction != null) 'show_pause_action': showPauseAction,
        if (showStopAction != null) 'show_stop_action': showStopAction,
        if (pauseLabel != null) 'pause_label': pauseLabel,
        if (resumeLabel != null) 'resume_label': resumeLabel,
        if (stopLabel != null) 'stop_label': stopLabel,
        if (androidSmallIcon != null) 'android_small_icon_name': androidSmallIcon, // resolved to an id natively
        if (androidChannelName != null) 'android_channel_name': androidChannelName,
        if (iosSymbolName != null) 'ios_symbol_name': iosSymbolName,
        if (iosLiveActivity != null) 'ios_live_activity': iosLiveActivity,
      };
}

/// The TrackMe SDK for Flutter: trips, live location sharing and realtime updates. Same API and behaviour as the
/// native SDK, which does the work underneath (sharing keeps running natively when the Flutter engine isn't).
///
/// Every call throws [TrackMeException] on failure.
class TrackMe {
  TrackMe._();

  static const trips = TripsApi._();
  static const sharing = SharingApi._();
  static const location = LocationApi._();
  static const realtime = RealtimeApi._();
  static const notifications = NotificationsApi._();

  /// Starts a session; calling it again replaces the previous one (e.g. a different user). [tokenProvider] is
  /// asked for a fresh token when the server answers 401; return null to give up.
  static Future<void> initialize(TrackMeConfig config, {Future<String?> Function()? tokenProvider}) async {
    _Bridge.tokenProvider = tokenProvider;
    _Bridge.listenForCallbacks();
    final raw = await _Bridge.methods.invokeMethod<String>('start', {
      'config': jsonEncode(config.toJson()),
      'hasTokenProvider': tokenProvider != null,
    });
    _Bridge.unwrap(raw);
  }

  /// For apps that refresh tokens on their own schedule.
  static Future<void> updateToken(String token) => _Bridge.call('updateToken', {'token': token});

  /// Ends the session: stops sharing and realtime, removes the notification / Live Activity.
  static Future<void> dispose() => _Bridge.call('dispose');

  static Future<bool> isInitialized() async => await _Bridge.call('isInitialized') as bool;

  /// `"ok"` when the server is reachable.
  static Future<String> checkHealth() async => ((await _Bridge.call('checkHealth')) as Json)['status'] as String;

  /// SDK lifecycle; the current value arrives first.
  static Stream<TrackMeLifecycle> get state => _Bridge.stream('state').map(lifecycleFromJson);

  /// One-off events: live positions, members, trip end, token trouble.
  static Stream<TrackMeEvent> get events =>
      _Bridge.stream('event').map(TrackMeEvent.fromJson).where((e) => e != null).cast<TrackMeEvent>();
}

class TripsApi {
  const TripsApi._();

  /// The duration range is the server's (5–1440 minutes today); outside it you get its validation message.
  Future<CreatedTrip> create({String? name, required int durationMinutes}) async => CreatedTrip.fromJson(
      await _Bridge.call('trips.create', {if (name != null) 'name': name, 'duration_min': durationMinutes}) as Json);

  /// Trips you created or joined, newest first.
  Future<List<Trip>> list({int limit = 20}) async =>
      [for (final t in await _Bridge.call('trips.list', {'limit': limit}) as List) Trip.fromJson(t as Json)];

  Future<TripPreview> preview(String code) async => TripPreview.fromJson(await _Bridge.call('trips.preview', {'code': code}) as Json);
  Future<Trip> join(String code) async => Trip.fromJson(await _Bridge.call('trips.join', {'code': code}) as Json);
  Future<TripDetail> detail(int tripId) async => TripDetail.fromJson(await _Bridge.call('trips.detail', {'trip_id': tripId}) as Json);
  Future<Trip> rename(int tripId, String name) async =>
      Trip.fromJson(await _Bridge.call('trips.rename', {'trip_id': tripId, 'name': name}) as Json);

  /// Owner only.
  Future<Trip> end(int tripId) async => Trip.fromJson(await _Bridge.call('trips.end', {'trip_id': tripId}) as Json);
  Future<void> leave(int tripId) => _Bridge.call('trips.leave', {'trip_id': tripId});

  /// Owner only; the member gets an [Evicted] event.
  Future<void> removeMember(int tripId, int userId) => _Bridge.call('trips.removeMember', {'trip_id': tripId, 'user_id': userId});

  /// Ended trips only (a live trip answers [TrackMeErrorType.conflict]).
  Future<void> delete(int tripId) => _Bridge.call('trips.delete', {'trip_id': tripId});

  /// The public, read-only link anyone can open.
  Future<String> viewLink(int tripId) async => ((await _Bridge.call('trips.viewLink', {'trip_id': tripId})) as Json)['view_link'] as String;

  /// A new link; the old one stops working.
  Future<String> rotateViewLink(int tripId) async =>
      ((await _Bridge.call('trips.rotateViewLink', {'trip_id': tripId})) as Json)['view_link'] as String;

  /// The server's history JSON for the trip. [from] / [to]: ISO-8601.
  Future<Object?> history(int tripId, {String? from, String? to}) =>
      _Bridge.call('trips.history', {'trip_id': tripId, if (from != null) 'from': from, if (to != null) 'to': to});
}

class SharingApi {
  const SharingApi._();

  /// Starts sharing to every live trip you're in. Ask for location permission first (e.g. `permission_handler`);
  /// the SDK only reports it.
  Future<void> start([SharingOptions options = const SharingOptions()]) => _Bridge.call('sharing.start', {'options': options.toJson()});
  Future<void> pause() => _Bridge.call('sharing.pause');
  Future<void> resume() => _Bridge.call('sharing.resume');
  Future<void> stop() => _Bridge.call('sharing.stop');

  Future<SharingState> current() async => SharingState.fromJson(await _Bridge.call('sharing.state') as Json);

  /// Every change; the current value arrives first.
  Stream<SharingState> get state => _Bridge.stream('sharing').map(SharingState.fromJson);
}

class LocationApi {
  const LocationApi._();
  Future<LocationPermissionStatus> permissionStatus() async =>
      LocationPermissionStatus.fromJson(await _Bridge.call('location.permissionStatus') as Json);
}

class RealtimeApi {
  const RealtimeApi._();

  /// Live updates of one trip (one at a time): [TripPositionEvent], [MemberChanged], [TripEnded], [Evicted].
  Future<void> watchTrip(int tripId) => _Bridge.call('realtime.watchTrip', {'trip_id': tripId});

  /// Live updates of a view link, by its token (the last part of the link): [SharePositionEvent], [ShareEnded].
  Future<void> watchShare(String viewToken) => _Bridge.call('realtime.watchShare', {'view_token': viewToken});
  Future<void> stopWatchingTrip() => _Bridge.call('realtime.stopWatchingTrip');
  Future<void> stopWatchingShare() => _Bridge.call('realtime.stopWatchingShare');
  Future<void> stop() => _Bridge.call('realtime.stop');

  Future<RealtimeState> current() async => RealtimeState.fromJson(await _Bridge.call('realtime.state') as Json);
  Stream<RealtimeState> get state => _Bridge.stream('realtime').map(RealtimeState.fromJson);
}

class NotificationsApi {
  const NotificationsApi._();

  /// One page; pass the previous page's [NotificationPage.nextCursor] as [cursor].
  Future<NotificationPage> list({bool unreadOnly = false, int? tripId, String? cursor, int limit = 20}) async =>
      NotificationPage.fromJson(await _Bridge.call('notifications.list', {
        'unread_only': unreadOnly,
        if (tripId != null) 'trip_id': tripId,
        if (cursor != null) 'cursor': cursor,
        'limit': limit,
      }) as Json);
  Future<void> markRead(int notificationId) => _Bridge.call('notifications.markRead', {'notification_id': notificationId});
  Future<void> markAllRead() => _Bridge.call('notifications.markAllRead');
}

/// The platform channels. Native side: android/…/TrackmeFlutterPlugin.kt, ios/Classes/TrackmeFlutterPlugin.swift.
class _Bridge {
  static const methods = MethodChannel('io.baato.trackme/bridge');
  static const _events = EventChannel('io.baato.trackme/events');

  static Future<String?> Function()? tokenProvider;
  static bool _listening = false;

  /// Native asks Dart for a fresh token on a 401.
  static void listenForCallbacks() {
    if (_listening) return;
    _listening = true;
    methods.setMethodCallHandler((call) async {
      if (call.method == 'refreshToken') {
        try {
          return await tokenProvider?.call();
        } catch (e) {
          debugPrint('TrackMe: token provider failed: $e');
          return null;
        }
      }
      throw MissingPluginException(call.method);
    });
  }

  static Future<Object?> call(String method, [Map<String, Object?> args = const {}]) async =>
      unwrap(await methods.invokeMethod<String>('call', {'method': method, 'args': jsonEncode(args)}));

  /// `{"ok":true,"value":…}` → value; `{"ok":false,"error":{…}}` → throws.
  static Object? unwrap(String? raw) {
    final envelope = jsonDecode(raw ?? '{}') as Json;
    if (envelope['ok'] == true) return envelope['value'];
    throw TrackMeException.fromJson((envelope['error'] as Json?) ?? const {'type': 'Unknown', 'message': 'No answer from the native SDK'});
  }

  /// One native subscription, shared by every Dart listener.
  static final Stream<Json> _all = _events.receiveBroadcastStream().map((e) => jsonDecode(e as String) as Json);

  /// Each state stream sends its current value when the native side starts observing. A late listener gets it
  /// from the matching `current()` call instead.
  static Stream<Json> stream(String name) => _all.where((e) => e['stream'] == name).map((e) => e['value'] as Json);
}
