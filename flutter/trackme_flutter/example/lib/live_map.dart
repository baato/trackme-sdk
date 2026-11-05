// The showcase: a ready-to-use "share my trip live" feature on a Baato map, ported from the native samples.
// No trip → start or join. Invite pasted → see who's there, then join. In a trip → members live, share / pause,
// invite, leave (member) or end (owner). Built only from the same SDK calls as the developer screen.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:maplibre_gl/maplibre_gl.dart' show LatLng;
import 'package:share_plus/share_plus.dart';
import 'package:trackme_flutter/trackme_flutter.dart';

import 'app_model.dart';
import 'baato_map.dart';
import 'config.dart';
import 'location_access.dart';
import 'widgets.dart';

class LiveMapScreen extends StatefulWidget {
  const LiveMapScreen({super.key, required this.model});
  final AppModel model;

  @override
  State<LiveMapScreen> createState() => _LiveMapScreenState();
}

class _LiveMapScreenState extends State<LiveMapScreen> {
  AppModel get m => widget.model;
  late final access = LocationAccess(m);
  final _panelKey = GlobalKey();
  Timer? _tick, _poll;
  int _recenter = 0;
  MapFocus? _focus;
  LatLng? _device;
  double _panelHeight = 0;
  bool _collapsed = false;

  /// Finger travel of the current panel drag (+ = down): the panel follows it, then settles collapsed / expanded.
  double _drag = 0;
  String _panelKind = '';

  @override
  void initState() {
    super.initState();
    m.refreshPermission();
    m.mapRefresh(); // every time the map appears
    m.addListener(_onModel);
    // Countdown and "live / 50 s ago" labels.
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
    // Fallback next to the socket: while no live position arrives, refresh the trip snapshot every 5 s.
    _poll = Timer.periodic(const Duration(seconds: 5), (_) {
      if (m.mapDetail?.trip.isLive == true) m.mapPoll();
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    _poll?.cancel();
    m.removeListener(_onModel);
    super.dispose();
  }

  void _onModel() {
    // Location: asked once by itself when the map first shows without it; after that only when the user taps the
    // locate button, "Allow location" or the location pill. Never nags.
    final p = m.permission;
    if (p != null && !p.isGranted && p.servicesEnabled && !m.locationAutoAsked && !m.locationBlocked) {
      m.markLocationAutoAsked();
      access.ask();
    }
    // A different panel (start → trip → ended, or an invite) opens fully, so its buttons are visible.
    final kind = m.invite != null
        ? 'invite'
        : m.mapDetail?.trip.isLive == true
            ? 'trip:${m.mapDetail!.trip.id}'
            : m.mapDetail != null
                ? 'ended'
                : 'start';
    if (kind != _panelKind) {
      _panelKind = kind;
      _collapsed = false;
    }
  }

  /// Tap a member (chip or dot): collapse the panel, then fly to them.
  void _focusOn(String id) => setState(() {
        _collapsed = true;
        _focus = MapFocus(id, (_focus?.seq ?? 0) + 1);
      });

  void _measurePanel() {
    final h = _panelKey.currentContext?.size?.height ?? 0;
    if ((h - _panelHeight).abs() > 1) setState(() => _panelHeight = h);
  }

  LatLng? get _me {
    final s = m.sharing;
    final last = s is SharingActive ? s.lastLocation : null;
    return last != null ? LatLng(last.latitude, last.longitude) : _device;
  }

  List<MapMarker> _markers(bool hideMe) {
    // Watching an invite: whatever the view link shows.
    if (m.invite != null) {
      return [
        for (final MapEntry(:key, :value) in m.viewerPositions.entries)
          MapMarker('v$key', (value.displayName ?? 'Member').split(' ').first, value.latitude, value.longitude, markerHex(key)),
      ];
    }
    final d = m.mapDetail;
    if (d == null) return const [];
    final now = DateTime.now();
    final myName = m.login?.fullName;
    return [
      for (final member in d.members)
        if (!(hideMe && member.fullName != null && member.fullName == myName)) // you are the "me" puck instead
          if (_position(member, d.trip.id, now) case (final lat, final lon))
            MapMarker('u${member.userId}', (member.fullName ?? 'Member').split(' ').first, lat, lon, markerHex(member.userId)),
    ];
  }

  /// The newer of the live socket position and the snapshot from trips.detail().
  (double, double)? _position(TripMember member, int tripId, DateTime now) {
    final live = m.newestLive(member, tripId, now);
    if (live != null) return (live.latitude, live.longitude);
    if (member.lat != null && member.lon != null) return (member.lat!, member.lon!);
    return null;
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: m,
        builder: (context, _) {
          WidgetsBinding.instance.addPostFrameCallback((_) => _measurePanel());
          final me = _me;
          final top = MediaQuery.paddingOf(context).top;
          return LayoutBuilder(builder: (context, box) => Stack(children: [
            Positioned.fill(
              child: baatoConfigured
                  ? BaatoMap(
                      markers: _markers(me != null),
                      me: me,
                      fitKey: m.invite != null ? 'invite:${m.invite!.viewToken}:${m.invite!.code}' : m.mapDetail?.trip.id,
                      recenter: _recenter,
                      focus: _focus,
                      showMe: access.granted,
                      topInset: top + 64,
                      bottomInset: _panelHeight + 16,
                      onMarkerTap: _focusOn,
                      onUserLocation: (p) => _device = p,
                      onError: m.reportMapError,
                    )
                  : ColoredBox(
                      color: Theme.of(context).colorScheme.surfaceContainerHighest,
                      child: const Center(
                        child: Padding(
                          padding: EdgeInsets.all(32),
                          child: Text('Add BAATO_ACCESS_TOKEN to secrets.json\nto show the Baato map.', textAlign: TextAlign.center),
                        ),
                      ),
                    ),
            ),
            // Top: live status + location access, log out / locate.
            Positioned(
              top: top + 12,
              left: 12,
              right: 12,
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  if (m.mapDetail?.trip.isLive == true || m.invite != null) ...[
                    _LivePill(realtime: m.realtime, watchingInvite: m.invite != null),
                    const SizedBox(height: 8),
                  ],
                  LocationPill(access: access, onTap: () => showLocationDialog(context, access)),
                ]),
                const Spacer(),
                Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                  Pill(onTap: m.logOut, child: const Text('Log out', style: TextStyle(fontWeight: FontWeight.w600))),
                  const SizedBox(height: 8),
                  // Locate = "show me". Without location access it asks (or explains, when only Settings can help).
                  LocateButton(
                    state: !access.granted || access.servicesOff
                        ? LocateState.disabled
                        : me == null
                            ? LocateState.searching
                            : LocateState.located,
                    onTap: () {
                      if (access.granted) {
                        setState(() => _recenter++);
                      } else if (access.servicesOff || access.blocked) {
                        showLocationDialog(context, access);
                      } else {
                        access.ask();
                      }
                    },
                  ),
                ]),
              ]),
            ),
            // The space left under the top buttons; the Scaffold already shrank it above the keyboard, so the
            // focused field stays in view (the scroll view brings it up).
            Positioned(left: 0, right: 0, bottom: 0, child: _panel(context, (box.maxHeight - top - 140).clamp(160.0, double.infinity))),
          ]));
        },
      );

  void _dragBy(double dy) => setState(() => _drag += dy);

  /// Like the native samples: where the drag would end with its momentum decides; ±60 pt flips the panel.
  void _dragEnd(double velocity) {
    final travel = _drag + velocity * 0.15;
    setState(() {
      if (travel > 60) {
        _collapsed = true;
        FocusScope.of(context).unfocus(); // collapsing hides the fields, so drop the keyboard too
      } else if (travel < -60) {
        _collapsed = false;
      }
      _drag = 0;
    });
  }

  /// The bottom panel. Drag it anywhere (or tap the handle) to collapse it to its title and one status line, for
  /// more map, and back up for everything. Inside the scrolling content a drag moves the panel once the content
  /// can't scroll further that way (pulled down at the top, or up when it all fits).
  Widget _panel(BuildContext context, double maxHeight) {
    final children = switch ((m.invite, m.mapDetail)) {
      (final Invite invite, _) => _invitePanel(invite),
      (null, final TripDetail d) when d.trip.isLive => _tripPanel(context, d),
      (null, final TripDetail d) => _endedPanel(d),
      _ => [
          _title('Share your trip, live'),
          const Text("Start a trip and invite people, or join someone else's."),
          _StartPanel(model: m),
        ],
    };
    final scheme = Theme.of(context).colorScheme;
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onVerticalDragUpdate: (d) => _dragBy(d.delta.dy),
      onVerticalDragEnd: (d) => _dragEnd(d.primaryVelocity ?? 0),
      child: Transform.translate(
        offset: Offset(0, _drag.clamp(0.0, 400.0)),
        child: Material(
          key: _panelKey,
          elevation: 12,
          color: scheme.surface,
          shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: maxHeight),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => setState(() => _collapsed = !_collapsed),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  child: Center(
                    child: Container(
                      width: 36,
                      height: 4,
                      decoration: BoxDecoration(color: scheme.onSurfaceVariant.withValues(alpha: 0.4), borderRadius: BorderRadius.circular(2)),
                    ),
                  ),
                ),
              ),
              Flexible(
                child: NotificationListener<ScrollNotification>(
                  onNotification: (n) {
                    if (n is OverscrollNotification && n.dragDetails != null) {
                      _dragBy(-n.overscroll); // pulled past the top → down; past the end → up
                    } else if (n is ScrollEndNotification && _drag != 0) {
                      _dragEnd(n.dragDetails?.primaryVelocity ?? 0);
                    }
                    return false;
                  },
                  child: SingleChildScrollView(
                    // Clamping on iOS too: a bounce would swallow the pull that moves the panel.
                    physics: const ClampingScrollPhysics(),
                    keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                    padding: EdgeInsets.fromLTRB(20, 0, 20, 20 + MediaQuery.paddingOf(context).bottom),
                    child: AnimatedSize(
                      duration: const Duration(milliseconds: 200),
                      alignment: Alignment.topCenter,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          // Collapsed: the first two lines (title + status). The rest stays mounted, hidden, so
                          // what's typed in it survives collapsing.
                          for (final (i, w) in children.indexed)
                            if (i < 2 || !_collapsed)
                              Padding(padding: const EdgeInsets.only(bottom: 12), child: w)
                            else
                              Visibility(visible: false, maintainState: true, child: w),
                          if (m.busy) const LinearProgressIndicator(),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ]),
          ),
        ),
      ),
    );
  }

  Text _title(String t) => Text(t, style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold), maxLines: 1);

  List<Widget> _invitePanel(Invite invite) {
    final p = invite.preview;
    return [
      _title(p?.name ?? 'Shared trip'),
      Text(m.viewerEnded
          ? 'This trip has ended.'
          : p != null
              ? 'by ${p.ownerName ?? 'someone'} · ${p.memberCount} members'
              : 'Watching the shared link.'),
      if (invite.viewToken != null && !m.viewerEnded)
        Text(
          m.viewerPositions.isEmpty ? 'Waiting for positions…' : '${m.viewerPositions.length} on the map, live',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      _InviteJoin(model: m, invite: invite),
    ];
  }

  List<Widget> _tripPanel(BuildContext context, TripDetail d) {
    final trip = d.trip;
    final now = DateTime.now();
    final remaining = trip.remainingSeconds - now.difference(m.mapLoadedAt).inSeconds;
    final scheme = Theme.of(context).colorScheme;
    final sharing = m.sharing;
    final error = m.mapSharingError;
    final (label, active) = switch (sharing) {
      SharingActive() => ('● Sharing your location · tap to pause', true),
      SharingStarting() => ('Starting…', true),
      SharingPaused() => ('Paused · tap to resume', false),
      _ => ('Share my location too', false),
    };
    return [
      _title(trip.name),
      Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('Ends in ${formatSeconds(remaining)} · ${d.members.length} ${d.members.length == 1 ? 'person' : 'people'}'),
        const SizedBox(height: 8),
        // Time left, draining: same as the sharing notification.
        ClipRRect(
          borderRadius: BorderRadius.circular(50),
          child: LinearProgressIndicator(value: (remaining / (trip.plannedSeconds < 1 ? 1 : trip.plannedSeconds)).clamp(0.0, 1.0)),
        ),
      ]),
      // More than one live trip: pick which one the map shows.
      if (m.mapLiveTrips.length > 1)
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(children: [
            for (final t in m.mapLiveTrips)
              Padding(
                padding: const EdgeInsets.only(right: 6),
                child: ChoiceChip(label: Text(t.name), selected: t.id == trip.id, onSelected: (_) => m.mapSelect(t.id)),
              ),
          ]),
        ),
      // How others join: the code, big, with one-tap copy and share. (The share text also carries the view link.)
      _InviteCard(model: m, joinCode: trip.joinCode),
      // Who's here. Tap someone to find them on the map; the owner long-presses someone to remove them.
      if (trip.isOwner && d.members.length > 1)
        Text('Tap someone to find them · long-press to remove', style: Theme.of(context).textTheme.labelSmall),
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(children: [
          for (final member in d.members)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Material(
                color: scheme.surfaceContainerHighest,
                shape: const StadiumBorder(),
                child: InkWell(
                  customBorder: const StadiumBorder(),
                  onTap: () => _focusOn('u${member.userId}'),
                  onLongPress: trip.isOwner && !member.isOwner
                      ? () {
                          HapticFeedback.mediumImpact();
                          confirm(context, 'Remove ${member.fullName ?? 'member'}?', "They're taken out of the trip and stop seeing it.",
                              'Remove', () => m.mapRemoveMember(member.userId));
                        }
                      : null,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    child: Row(mainAxisSize: MainAxisSize.min, children: [
                      Container(width: 10, height: 10, decoration: BoxDecoration(color: Color(markerColor(member.userId)), shape: BoxShape.circle)),
                      // "live" only while positions keep arriving: a paused member turns into "50 s ago" by itself.
                      Text(
                        '  ${(member.fullName ?? 'Member').split(' ').first}${member.isOwner ? ' ★' : ''} · ${m.memberStatus(member, trip.id, now)}',
                        style: Theme.of(context).textTheme.labelMedium,
                      ),
                    ]),
                  ),
                ),
              ),
            ),
        ]),
      ),
      // Your location.
      if (!sharing.isActive && error?.type == TrackMeErrorType.locationPermissionDenied && !access.granted)
        FilledButton(
          onPressed: access.ask, // granting starts sharing by itself (AppModel.onLocationGranted)
          child: Text(access.blocked ? 'Allow location in Settings' : 'Allow location to share'),
        )
      else ...[
        FilledButton(
          style: active ? FilledButton.styleFrom(backgroundColor: const Color(0xFF2E7D32)) : null,
          onPressed: m.mapToggleSharing,
          child: Text(label),
        ),
        if (error != null && error.type != TrackMeErrorType.locationPermissionDenied && !sharing.isActive)
          Text(error.message, style: TextStyle(color: scheme.error, fontSize: 12)),
      ],
      OutlinedButton(
        style: OutlinedButton.styleFrom(foregroundColor: scheme.error),
        onPressed: () => trip.isOwner
            ? confirm(context, 'End this trip?', 'Everyone stops sharing and the trip closes for all members.', 'End trip', m.mapEnd)
            : confirm(context, 'Leave this trip?', 'You stop sharing with this trip. You can join again with the code.', 'Leave', m.mapLeave),
        child: Text(trip.isOwner ? 'End trip' : 'Leave trip'),
      ),
    ];
  }

  List<Widget> _endedPanel(TripDetail d) => [
        _title('Trip ended'),
        Text('${d.trip.name} · ${d.trip.endedReason ?? 'ended'} · lasted ${formatSeconds(d.trip.actualSeconds)}'),
        FilledButton(onPressed: m.mapDone, child: const Text('Done')),
      ];
}

class _LivePill extends StatelessWidget {
  const _LivePill({required this.realtime, required this.watchingInvite});
  final RealtimeState realtime;
  final bool watchingInvite;

  @override
  Widget build(BuildContext context) {
    final (text, color) = switch (realtime) {
      RealtimeConnected() => (watchingInvite ? '● Watching' : '● Live', const Color(0xFF2E7D32)),
      RealtimeReconnecting() => ('Reconnecting…', const Color(0xFFEF6C00)),
      RealtimeConnecting() => ('Connecting…', const Color(0xFFEF6C00)),
      RealtimeIdle() => ('Offline', Colors.grey),
    };
    return Pill(child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w600)));
  }
}

const _durations = [('30 min', 30), ('1 h', 60), ('2 h', 120), ('4 h', 240)];

class _StartPanel extends StatefulWidget {
  const _StartPanel({required this.model});
  final AppModel model;

  @override
  State<_StartPanel> createState() => _StartPanelState();
}

class _StartPanelState extends State<_StartPanel> {
  String _mode = ''; // '', 'start', 'join'
  final _name = TextEditingController();
  final _invite = TextEditingController();
  int _minutes = 60;

  @override
  void dispose() {
    _name.dispose();
    _invite.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final m = widget.model;
    // The chosen mode is the filled button (Start, before anything is chosen).
    Widget modeButton(String mode, String label) {
      final chosen = _mode == mode || (_mode.isEmpty && mode == 'start');
      void choose() => setState(() => _mode = mode);
      return Expanded(
        child: chosen ? FilledButton(onPressed: choose, child: Text(label)) : OutlinedButton(onPressed: choose, child: Text(label)),
      );
    }

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [modeButton('start', 'Start a trip'), const SizedBox(width: 12), modeButton('join', 'Join')]),
      if (_mode == 'start') ...[
        const SizedBox(height: 12),
        TextField(
          controller: _name,
          textInputAction: TextInputAction.done,
          decoration: const InputDecoration(labelText: 'Trip name (optional)', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 12),
        DurationPicker(presets: _durations, minutes: _minutes, onChanged: (v) => setState(() => _minutes = v)),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _minutes >= 0 && !m.busy ? () => m.mapCreate(_name.text, _minutes) : null,
          child: const Text('Start & share my location'),
        ),
      ],
      if (_mode == 'join') ...[
        const SizedBox(height: 12),
        ListenableBuilder(
          listenable: _invite,
          builder: (context, _) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            // One line: Return means "Continue" (a pasted multi-line invite becomes one line; the parser doesn't mind).
            TextField(
              controller: _invite,
              autocorrect: false,
              textInputAction: TextInputAction.go,
              onSubmitted: (t) {
                if (t.trim().isNotEmpty) m.mapOpenInvite(t);
              },
              decoration: InputDecoration(
                labelText: 'Join code or invite message',
                border: const OutlineInputBorder(),
                suffixIcon: TextButton(
                  onPressed: () async {
                    final clip = await Clipboard.getData(Clipboard.kTextPlain);
                    if (clip?.text != null) _invite.text = clip!.text!;
                  },
                  child: const Text('Paste'),
                ),
              ),
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: _invite.text.trim().isEmpty ? null : () => m.mapOpenInvite(_invite.text),
              child: const Text('Continue'),
            ),
          ]),
        ),
      ],
    ]);
  }
}

/// The invite's join field (when the invite had no code) and Join / Close.
class _InviteJoin extends StatefulWidget {
  const _InviteJoin({required this.model, required this.invite});
  final AppModel model;
  final Invite invite;

  @override
  State<_InviteJoin> createState() => _InviteJoinState();
}

class _InviteJoinState extends State<_InviteJoin> {
  late final _code = TextEditingController(text: widget.invite.code ?? '');

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final m = widget.model;
    return ListenableBuilder(
      listenable: _code,
      builder: (context, _) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (widget.invite.code == null) ...[
          TextField(
            controller: _code,
            textCapitalization: TextCapitalization.characters,
            autocorrect: false,
            textInputAction: TextInputAction.go,
            onSubmitted: (t) {
              if (t.trim().isNotEmpty && !m.viewerEnded) m.mapJoin(t);
            },
            decoration: const InputDecoration(labelText: 'Join code', border: OutlineInputBorder()),
          ),
          const SizedBox(height: 12),
        ],
        // The server sends each upload to EVERY live trip you're in, so joining while you share elsewhere starts
        // sharing to this trip too. Say so instead of doing it silently.
        if (m.sharing.isActive) ...[
          Text(
            "You're sharing your location right now, so joining adds this trip to it. Pause or stop sharing first to join without sharing.",
            style: TextStyle(color: Theme.of(context).colorScheme.error, fontSize: 12),
          ),
          const SizedBox(height: 12),
        ],
        Row(children: [
          Expanded(
            child: FilledButton(
              onPressed: _code.text.trim().isEmpty || m.viewerEnded ? null : () => m.mapJoin(_code.text),
              child: const Text('Join'),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(child: OutlinedButton(onPressed: m.mapCancelInvite, child: const Text('Close'))),
        ]),
      ]),
    );
  }
}

/// The join code in large type, with Copy (code only), Copy invite (link + code) and Share.
class _InviteCard extends StatefulWidget {
  const _InviteCard({required this.model, required this.joinCode});
  final AppModel model;
  final String? joinCode;

  @override
  State<_InviteCard> createState() => _InviteCardState();
}

class _InviteCardState extends State<_InviteCard> {
  String? _copied;
  Timer? _clear;

  @override
  void dispose() {
    _clear?.cancel();
    super.dispose();
  }

  Future<void> _copy(String text, String what) async {
    await Clipboard.setData(ClipboardData(text: text));
    _clear?.cancel();
    setState(() => _copied = what);
    _clear = Timer(const Duration(milliseconds: 1500), () {
      if (mounted) setState(() => _copied = null);
    });
  }

  @override
  Widget build(BuildContext context) {
    final code = widget.joinCode;
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.primaryContainer,
      borderRadius: BorderRadius.circular(18),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(code != null ? 'Join code' : 'Invite people', style: Theme.of(context).textTheme.labelMedium),
                if (code != null)
                  SelectableText(
                    code,
                    style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                          fontFamily: 'monospace',
                          letterSpacing: 4,
                          color: scheme.onPrimaryContainer,
                        ),
                  ),
              ]),
            ),
            if (code != null) TextButton(onPressed: () => _copy(code, 'Code copied'), child: const Text('Copy')),
          ]),
          Row(children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () async {
                  final t = await widget.model.mapInviteText();
                  if (t != null) await _copy(t, 'Invite copied');
                },
                child: const Text('Copy invite'),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: FilledButton(
                onPressed: () async {
                  final box = context.findRenderObject() as RenderBox?;
                  final t = await widget.model.mapInviteText();
                  if (t == null) return;
                  // sharePositionOrigin: iPads need an anchor for the share popover.
                  await SharePlus.instance.share(ShareParams(
                    text: t,
                    sharePositionOrigin: box == null ? null : box.localToGlobal(Offset.zero) & box.size,
                  ));
                },
                child: const Text('Share'),
              ),
            ),
          ]),
          if (_copied != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(_copied!, style: Theme.of(context).textTheme.labelMedium)),
        ]),
      ),
    );
  }
}
