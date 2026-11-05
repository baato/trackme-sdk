// TrackMe Flutter example: the Live map showcase and (dev-tools builds) a developer screen, ported from the native
// samples. Run with the secrets file:
//   flutter run --dart-define-from-file=secrets.json        (copy secrets.example.json → secrets.json first)
import 'dart:io' show Platform;

import 'package:flutter/material.dart';

import 'app_model.dart';
import 'config.dart';
import 'developer.dart';
import 'live_map.dart';

void main() => runApp(const ExampleApp());

class ExampleApp extends StatefulWidget {
  const ExampleApp({super.key});
  @override
  State<ExampleApp> createState() => _ExampleAppState();
}

class _ExampleAppState extends State<ExampleApp> {
  final model = AppModel();
  final _navigator = GlobalKey<NavigatorState>();

  @override
  void initState() {
    super.initState();
    model.addListener(_showMessage);
  }

  @override
  void dispose() {
    model.dispose();
    super.dispose();
  }

  /// SDK errors and notices, as a dialog.
  void _showMessage() {
    final msg = model.message;
    final ctx = _navigator.currentContext;
    if (msg == null || ctx == null) return;
    model.message = null;
    showDialog<void>(
      context: ctx,
      builder: (c) => AlertDialog(
        title: const Text('TrackMe'),
        content: Text(msg),
        actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('OK'))],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF1565C0);
    return MaterialApp(
      title: 'TrackMe Flutter',
      navigatorKey: _navigator,
      // Light and dark; follows the system setting.
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: seed)),
      darkTheme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: seed, brightness: Brightness.dark)),
      home: ListenableBuilder(
        listenable: model,
        builder: (context, _) => !model.loggedIn
            ? LoginScreen(model: model)
            : devTools
                ? _DevShell(model: model)
                : Scaffold(body: LiveMapScreen(model: model)),
      ),
    );
  }
}

/// Dev-tools build: a bottom bar switches between the showcase and the developer screen.
class _DevShell extends StatelessWidget {
  const _DevShell({required this.model});
  final AppModel model;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: IndexedStack(
          index: model.section.index,
          children: [LiveMapScreen(model: model), DeveloperScreen(model: model)],
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: model.section.index,
          onDestinationSelected: (i) => model.selectSection(Section.values[i]),
          destinations: const [
            NavigationDestination(icon: Icon(Icons.map_outlined), selectedIcon: Icon(Icons.map), label: 'Live map'),
            NavigationDestination(icon: Icon(Icons.build_outlined), selectedIcon: Icon(Icons.build), label: 'Developer'),
          ],
        ),
      );
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.model});
  final AppModel model;
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  // One user per device, so two phones can test a trip together.
  final _email = TextEditingController(text: 'flutter.${Platform.isIOS ? 'ios' : 'android'}@trackme.dev');
  final _name = TextEditingController(text: 'Flutter ${Platform.isIOS ? 'iOS' : 'Android'}');

  @override
  void dispose() {
    _email.dispose();
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final m = widget.model;
    return Scaffold(
      appBar: AppBar(title: const Text('TrackMe Flutter')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        if (m.busy) const LinearProgressIndicator(),
        Text('Demo login', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        Text(
          'The example logs in with the API secret from secrets.json. A real app gets the token from its own backend '
          'and only calls TrackMe.initialize. Log in with a different email on a second device to test trips together.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _email,
          keyboardType: TextInputType.emailAddress,
          autocorrect: false,
          decoration: const InputDecoration(labelText: 'Email', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 12),
        TextField(controller: _name, decoration: const InputDecoration(labelText: 'Full name', border: OutlineInputBorder())),
        const SizedBox(height: 16),
        ListenableBuilder(
          listenable: _email,
          builder: (context, _) => FilledButton(
            onPressed: _email.text.contains('@') && !m.busy
                ? () => m.logIn(LoginForm(_email.text.trim(), _name.text.trim()))
                : null,
            child: const Text('Log in & initialize SDK'),
          ),
        ),
      ]),
    );
  }
}
