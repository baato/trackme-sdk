// Location access for the live map, in one place. The SDK never shows a prompt: it reports
// (TrackMe.location.permissionStatus()), and the app asks, here with permission_handler.
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:trackme_flutter/trackme_flutter.dart';

import 'app_model.dart';

class LocationAccess {
  LocationAccess(this.model);
  final AppModel model;

  LocationPermissionStatus? get status => model.permission;
  bool get granted => status?.isGranted == true;
  bool get servicesOff => status?.servicesEnabled == false;

  /// The system won't show the dialog again: only Settings can grant location now.
  bool get blocked => model.locationBlocked && !granted;

  /// The system dialog (precise / approximate, plus notifications for the sharing notification). When it won't
  /// show any more ("don't ask again" / denied once on iOS), opens the app's settings instead.
  Future<void> ask() async {
    if (blocked) {
      await openAppSettings();
      return;
    }
    final result = await [Permission.locationWhenInUse, Permission.notification].request();
    final location = result[Permission.locationWhenInUse];
    if (location != null && (location.isGranted || location.isLimited)) {
      model.setLocationBlocked(false);
      await model.onLocationGranted();
    } else {
      await model.refreshPermission();
      if (location?.isPermanentlyDenied == true) model.setLocationBlocked(true);
    }
  }

  /// "Allow all the time". Android 11+ and iOS show it as a second step after "while using".
  Future<void> askAlways() async {
    final s = await Permission.locationAlways.request();
    await model.refreshPermission();
    if (s.isPermanentlyDenied) await openAppSettings();
  }

  Future<void> openSettings() => openAppSettings();
}

const _green = Color(0xFF2E7D32), _amber = Color(0xFFEF6C00), _red = Color(0xFFC62828);

/// Small status chip on the map: what access the app has. Tap → [showLocationDialog].
class LocationPill extends StatelessWidget {
  const LocationPill({super.key, required this.access, required this.onTap});
  final LocationAccess access;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final s = access.status;
    final (text, color) = switch (s) {
      null => ('Location', Colors.grey),
      LocationPermissionStatus(servicesEnabled: false) => ('Location is off', _red),
      LocationPermissionStatus(isGranted: false) => ('No location access', _red),
      LocationPermissionStatus(level: LocationPermissionLevel.always) => ('Location: always', _green),
      _ => ('Location: while using', _amber),
    };
    final approximate = s != null && s.isGranted && !s.precise;
    return Pill(
      onTap: onTap,
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 8, height: 8, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        Text('  $text${approximate ? ' · approx.' : ''}', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
      ]),
    );
  }
}

/// A rounded, shadowed chip over the map.
class Pill extends StatelessWidget {
  const Pill({super.key, required this.child, this.onTap});
  final Widget child;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Material(
        elevation: 4,
        shape: const StadiumBorder(),
        color: Theme.of(context).colorScheme.surface,
        child: InkWell(
          customBorder: const StadiumBorder(),
          onTap: onTap,
          child: Padding(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8), child: child),
        ),
      );
}

/// What each level means for sharing, which one the app has, and the one action that improves it.
Future<void> showLocationDialog(BuildContext context, LocationAccess access) {
  final s = access.status;
  final level = s?.level;
  final (String label, Future<void> Function() action) = switch (()) {
    _ when access.servicesOff => ('Open Settings', access.openSettings),
    _ when !access.granted && !access.blocked => ('Allow', access.ask),
    _ when !access.granted => ('Open Settings', access.openSettings),
    _ when level == LocationPermissionLevel.whileInUse => ('Allow all the time', access.askAlways),
    _ => ('Open Settings', access.openSettings),
  };
  final scheme = Theme.of(context).colorScheme;
  Widget row(String title, String text, bool selected) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(selected ? Icons.radio_button_checked : Icons.radio_button_unchecked, size: 20, color: scheme.primary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: TextStyle(fontWeight: selected ? FontWeight.bold : FontWeight.normal)),
              Text(text, style: Theme.of(context).textTheme.bodySmall),
            ]),
          ),
        ]),
      );
  Widget note(String text, {bool error = false}) => Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Text(text, style: TextStyle(fontSize: 12, color: error ? scheme.error : scheme.onSurfaceVariant)),
      );
  return showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Location access'),
      content: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          row('Not allowed', "Nobody in your trip sees you, and the map can't show you.", !access.granted),
          row('While using the app', 'Enough to share. Sharing you start keeps running in the background.',
              level == LocationPermissionLevel.whileInUse),
          row('All the time', 'Also lets sharing start while the app is in the background.', level == LocationPermissionLevel.always),
          if (access.servicesOff) note("Location is off for the whole phone: turn it on in the phone's settings.", error: true),
          if (access.blocked) note("The system won't ask again: allow location in Settings."),
          if (access.granted && s?.precise == false) note('Precise location is off, so others see an approximate position.'),
        ]),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        TextButton(
          onPressed: () {
            Navigator.pop(ctx);
            action();
          },
          child: Text(label),
        ),
      ],
    ),
  );
}
