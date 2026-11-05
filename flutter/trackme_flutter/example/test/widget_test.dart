// Pure-Dart tests of the example's helpers (the screens need the native SDK, so they're tried on a device).
import 'package:flutter_test/flutter_test.dart';
import 'package:trackme_flutter_example/app_model.dart';

void main() {
  test('Invite.parse reads a shared invite, a link and a bare code', () {
    final full = Invite.parse('Follow me on TrackMe: https://tracker.baato.io/trackme/s/abc_12-x\nJoin code: k7p2qa');
    expect(full?.viewToken, 'abc_12-x');
    expect(full?.code, 'K7P2QA');
    expect(Invite.parse('https://tracker.baato.io/trackme/s/tok')?.code, isNull);
    expect(Invite.parse('ab12cd')?.code, 'AB12CD');
    expect(Invite.parse('hello there'), isNull);
  });

  test('inviteText round-trips through Invite.parse', () {
    final text = inviteText('Ride', 'https://x/trackme/s/tok9', 'ABC123');
    final parsed = Invite.parse(text)!;
    expect(parsed.viewToken, 'tok9');
    expect(parsed.code, 'ABC123');
  });

  test('formatters', () {
    expect(formatSeconds(3725), '1 h 2 min');
    expect(formatSeconds(125), '2 min 5 s');
    expect(formatSeconds(-3), '0 s');
    expect(formatMinutes(90), '1 h 30 min');
    expect(formatMinutes(1440), '24 h');
  });
}
