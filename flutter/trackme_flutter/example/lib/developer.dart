// Developer screen (dev-tools builds only): a control for every area of the SDK, with the results in a log.
// It shares the live map's session; anything changed here shows on the map too.
import 'package:flutter/material.dart';
import 'package:trackme_flutter/trackme_flutter.dart';

import 'app_model.dart';

class DeveloperScreen extends StatefulWidget {
  const DeveloperScreen({super.key, required this.model});
  final AppModel model;

  @override
  State<DeveloperScreen> createState() => _DeveloperScreenState();
}

class _DeveloperScreenState extends State<DeveloperScreen> {
  AppModel get m => widget.model;
  final _code = TextEditingController();
  final _viewLink = TextEditingController();
  int? _tripId;
  bool _running = false;

  @override
  void dispose() {
    _code.dispose();
    _viewLink.dispose();
    super.dispose();
  }

  /// Runs one SDK call and logs its result or its TrackMeException.
  Future<void> _run(String label, Future<Object?> Function() call) async {
    setState(() => _running = true);
    try {
      final r = await call();
      m.addLog('$label ✓${r == null ? '' : '  $r'}');
    } on TrackMeException catch (e) {
      m.addLog('$label ✗ ${e.type.name}: ${e.message}');
    } finally {
      if (mounted) setState(() => _running = false);
    }
  }

  int? get _trip => _tripId ?? m.mapDetail?.trip.id;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: m,
        builder: (context, _) {
          final trip = _trip;
          Widget b(String label, Future<Object?> Function()? call) => OutlinedButton(
                onPressed: call == null || _running ? null : () => _run(label, call),
                child: Text(label),
              );
          Widget section(String title, List<Widget> buttons) => Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(title, style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 6),
                  Wrap(spacing: 8, runSpacing: 8, children: buttons),
                ]),
              );
          return Scaffold(
            appBar: AppBar(
              title: Text('Developer · ${m.login?.fullName ?? ''}', maxLines: 1),
              actions: [TextButton(onPressed: m.logOut, child: const Text('Log out'))],
            ),
            body: ListView(padding: const EdgeInsets.all(16), children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: SelectableText(
                    'SDK: ${m.lifecycle.name}\n'
                    'Sharing: ${m.sharing.runtimeType}\n'
                    'Realtime: ${m.realtime.runtimeType}\n'
                    'Location: ${m.permission == null ? '–' : '${m.permission!.level.name}, services ${m.permission!.servicesEnabled ? 'on' : 'off'}'}\n'
                    'Trip: ${trip ?? '–'}',
                    style: const TextStyle(fontFamily: 'monospace'),
                  ),
                ),
              ),
              if (_running || m.busy) const LinearProgressIndicator(),
              section('Trips', [
                b('Create 15 min', () async {
                  final t = await TrackMe.trips.create(name: 'Flutter dev', durationMinutes: 15);
                  setState(() => _tripId = t.id);
                  return 'trip ${t.id}, code ${t.joinCode}';
                }),
                b('List', () async => (await TrackMe.trips.list(limit: 10)).map((t) => '${t.id} ${t.status}').join(', ')),
                b('Members', trip == null
                    ? null
                    : () async => (await TrackMe.trips.detail(trip)).members
                        .map((x) => '${x.fullName ?? x.userId}${x.sharingState == null ? '' : ' [${x.sharingState!.name}]'}')
                        .join(', ')),
                b('View link', trip == null ? null : () => TrackMe.trips.viewLink(trip)),
                b('Rotate link', trip == null ? null : () => TrackMe.trips.rotateViewLink(trip)),
                b('History 10 min', trip == null
                    ? null
                    : () async {
                        final from = DateTime.now().toUtc().subtract(const Duration(minutes: 10)).toIso8601String();
                        final h = await TrackMe.trips.history(trip, from: from);
                        final s = '$h';
                        return s.length > 300 ? '${s.substring(0, 300)}…' : s;
                      }),
                b('End', trip == null ? null : () async => (await TrackMe.trips.end(trip)).status),
                b('Leave', trip == null ? null : () => TrackMe.trips.leave(trip).then((_) => 'left')),
                b('Delete (ended only)', trip == null ? null : () => TrackMe.trips.delete(trip).then((_) => 'deleted')),
              ]),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Row(children: [
                  Expanded(
                    child: TextField(
                      controller: _code,
                      textCapitalization: TextCapitalization.characters,
                      decoration: const InputDecoration(labelText: 'Join code', border: OutlineInputBorder(), isDense: true),
                    ),
                  ),
                  const SizedBox(width: 8),
                  b('Preview', () async {
                    final p = await TrackMe.trips.preview(_code.text.trim());
                    return '"${p.name}" by ${p.ownerName}, ${p.memberCount} members';
                  }),
                  const SizedBox(width: 8),
                  b('Join', () async {
                    final t = await TrackMe.trips.join(_code.text.trim());
                    setState(() => _tripId = t.id);
                    return 'joined ${t.id}';
                  }),
                ]),
              ),
              section('Sharing', [
                b('Start', () => TrackMe.sharing.start()),
                b('Pause', TrackMe.sharing.pause),
                b('Resume', TrackMe.sharing.resume),
                b('Stop', TrackMe.sharing.stop),
                b('State', () async => '${await TrackMe.sharing.current()}'),
                b('Permission', () async {
                  final p = await TrackMe.location.permissionStatus();
                  return '${p.level.name}, precise ${p.precise}, background ${p.backgroundCapable}';
                }),
              ]),
              section('Realtime', [
                b('Watch trip', trip == null ? null : () => TrackMe.realtime.watchTrip(trip)),
                b('Stop watching trip', TrackMe.realtime.stopWatchingTrip),
                b('Stop all', TrackMe.realtime.stop),
              ]),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Row(children: [
                  Expanded(
                    child: TextField(
                      controller: _viewLink,
                      decoration: const InputDecoration(labelText: 'View link or token', border: OutlineInputBorder(), isDense: true),
                    ),
                  ),
                  const SizedBox(width: 8),
                  // The SDK takes the view TOKEN; taking it out of a pasted link is the app's choice.
                  b('Watch link', () => TrackMe.realtime.watchShare(Invite.parse(_viewLink.text)?.viewToken ?? _viewLink.text.trim())),
                  const SizedBox(width: 8),
                  b('Stop', TrackMe.realtime.stopWatchingShare),
                ]),
              ),
              section('Inbox', [
                b('Notifications', () async {
                  final page = await TrackMe.notifications.list(limit: 10);
                  return '${page.items.length} (more: ${page.hasMore}): ${page.items.map((n) => n.title).join(' | ')}';
                }),
                b('Mark all read', TrackMe.notifications.markAllRead),
              ]),
              section('SDK', [
                b('Health', TrackMe.checkHealth),
                b('Fresh token', () async {
                  final form = m.login;
                  final t = form == null ? null : await AppModel.demoLogin(form);
                  if (t == null) return 'no token';
                  await TrackMe.updateToken(t);
                  return 'updateToken() done';
                }),
                // A broken token: the next request gets 401 → the SDK asks the token provider → TokenRefreshed.
                b('Simulate expiry', () async {
                  await TrackMe.updateToken('expired-demo-token');
                  return '${(await TrackMe.trips.list(limit: 5)).length} trips after the automatic refresh';
                }),
              ]),
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Row(children: [
                  Text('Log', style: Theme.of(context).textTheme.titleMedium),
                  const Spacer(),
                  TextButton(onPressed: m.clearLog, child: const Text('Clear')),
                ]),
              ),
              for (final line in m.log) SelectableText(line, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            ]),
          );
        },
      );
}
