import 'package:flutter_test/flutter_test.dart';
import 'package:trackme_flutter/trackme_flutter.dart';

// The JSON here is exactly what TrackMeBridge sends (see TrackMeBridgeTest in the SDK).
void main() {
  test('trip keeps the API field names on the wire', () {
    final t = Trip.fromJson({
      'id': 17, 'name': 'Trip 17', 'my_role': 'owner', 'my_status': 'joined', 'status': 'live', 'member_count': 2,
      'started_on': '2026-10-16T06:40:37Z', 'planned_s': 3600, 'actual_s': 129, 'expires_at': '2026-10-16T07:40:37Z',
      'remaining_s': 3470, 'join_code': 'C7FKK7',
    });
    expect(t.id, 17);
    expect(t.joinCode, 'C7FKK7');
    expect(t.isOwner && t.isLive, isTrue);
  });

  test('sharing states and events', () {
    final s = SharingState.fromJson({'type': 'Sharing', 'trip_ids': [17], 'last_sent_at_ms': 5, 'retrying': false});
    expect(s, isA<SharingActive>());
    expect((s as SharingActive).tripIds, [17]);
    expect(SharingState.fromJson({'type': 'Stopped', 'reason': 'NoActiveTrips'}), isA<SharingStopped>());

    final e = TrackMeEvent.fromJson({
      'type': 'TripPosition',
      'position': {'trip_id': 17, 'user_id': 9, 'display_name': 'Sita', 'latitude': 27.7, 'longitude': 85.3, 'fix_age_s': 2},
    });
    expect((e as TripPositionEvent).position.displayName, 'Sita');
    expect(TrackMeEvent.fromJson({'type': 'SomethingNew'}), isNull);   // a newer SDK can't break the app
  });

  test('errors carry the server message', () {
    final x = TrackMeException.fromJson({'type': 'Validation', 'message': 'duration_min: Input should be greater than or equal to 5'});
    expect(x.type, TrackMeErrorType.validation);
    expect(x.message, contains('duration_min'));
  });
}
