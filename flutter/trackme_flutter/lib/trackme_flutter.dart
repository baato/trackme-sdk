/// TrackMe for Flutter: trips, live location sharing and realtime updates, on the native TrackMe SDK.
///
/// ```dart
/// await TrackMe.initialize(TrackMeConfig(token: accessToken), tokenProvider: () => myBackend.freshToken());
/// final trip = await TrackMe.trips.create(name: 'Ride home', durationMinutes: 60);
/// await TrackMe.sharing.start();
/// await TrackMe.realtime.watchTrip(trip.id);
/// TrackMe.events.listen((e) { if (e is TripPositionEvent) moveMarker(e.position); });
/// ```
library trackme_flutter;

export 'src/errors.dart';
export 'src/models.dart' hide Json, lifecycleFromJson;
export 'src/trackme.dart';
