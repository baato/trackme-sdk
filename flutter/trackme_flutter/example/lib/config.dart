// Build-time settings. Secrets come from secrets.json (git-ignored):
//   flutter run --dart-define-from-file=secrets.json        (copy secrets.example.json → secrets.json first)
import 'package:flutter/foundation.dart';

/// DEMO ONLY: the example logs in by itself with the API secret. A real app gets the token from its own backend.
const apiSecret = String.fromEnvironment('TRACKME_API_SECRET');
const apiUrl = 'https://tracker.baato.io/trackme/api';

/// Baato map style key for the live map.
const baatoToken = String.fromEnvironment('BAATO_ACCESS_TOKEN');
const baatoStyleUrl = 'https://api.baato.io/api/v1/styles/breeze?key=$baatoToken';
bool get baatoConfigured => baatoToken.isNotEmpty && !baatoToken.startsWith('your_');

/// The developer screens (a control for every SDK call). On in debug builds; hide them with
/// `--dart-define=TRACKME_DEV_TOOLS=false` to see the showcase alone, like a customer would.
const devTools = bool.fromEnvironment('TRACKME_DEV_TOOLS', defaultValue: kDebugMode);
