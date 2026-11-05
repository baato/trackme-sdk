// Small building blocks shared by the screens.
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_model.dart';

enum LocateState {
  /// Location allowed and a fix is in: tap flies to you.
  located,

  /// Allowed, but no fix yet (indoors, GPS warming up).
  searching,

  /// No permission, or location switched off: tap asks / explains.
  disabled,
}

/// Round "show me" button: `near_me` on a blue gradient when your position is known, on gray (gently pulsing)
/// while there's no fix yet, and `near_me_disabled` when location isn't available to the app.
class LocateButton extends StatefulWidget {
  const LocateButton({super.key, required this.state, required this.onTap});
  final LocateState state;
  final VoidCallback onTap;

  @override
  State<LocateButton> createState() => _LocateButtonState();
}

class _LocateButtonState extends State<LocateButton> with SingleTickerProviderStateMixin {
  late final _pulse = AnimationController(vsync: this, duration: const Duration(milliseconds: 900));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncPulse();
  }

  @override
  void didUpdateWidget(LocateButton old) {
    super.didUpdateWidget(old);
    _syncPulse();
  }

  void _syncPulse() {
    if (widget.state == LocateState.searching) {
      if (!_pulse.isAnimating) _pulse.repeat(reverse: true);
    } else {
      _pulse.stop();
      _pulse.value = 0;
    }
  }

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.state;
    final decoration = switch (s) {
      LocateState.located => const BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(colors: [Color(0xFF1E88E5), Color(0xFF00ACC1)]),
        ),
      LocateState.searching => const BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(colors: [Color(0xFF9E9E9E), Color(0xFF757575)]),
        ),
      LocateState.disabled => const BoxDecoration(shape: BoxShape.circle, color: Colors.white),
    };
    return Semantics(
      button: true,
      label: switch (s) {
        LocateState.located => 'Show my location',
        LocateState.searching => 'Looking for your location',
        LocateState.disabled => 'Location unavailable',
      },
      child: Material(
        elevation: 6,
        shape: const CircleBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: widget.onTap,
          child: Ink(
            width: 52,
            height: 52,
            decoration: decoration,
            child: FadeTransition(
              opacity: Tween(begin: 1.0, end: 0.45).animate(_pulse),
              child: Icon(
                s == LocateState.disabled ? Icons.near_me_disabled : Icons.near_me,
                color: s == LocateState.disabled ? const Color(0xFFD32F2F) : Colors.white,
                size: 26,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Trip length: preset chips plus "Custom", any whole number of minutes. The keypad only offers digits, so nothing
/// negative or fractional can be typed. The limits are the server's: out-of-range values come back as a Validation
/// error with the server's message, so this UI doesn't hard-code them. [minutes] is -1 while the field is empty.
class DurationPicker extends StatefulWidget {
  const DurationPicker({super.key, required this.presets, required this.minutes, required this.onChanged});
  final List<(String, int)> presets;
  final int minutes;
  final ValueChanged<int> onChanged;

  @override
  State<DurationPicker> createState() => _DurationPickerState();
}

class _DurationPickerState extends State<DurationPicker> {
  late bool _custom = widget.presets.every((p) => p.$2 != widget.minutes);
  late final _text = TextEditingController(text: _custom && widget.minutes >= 0 ? '${widget.minutes}' : '');
  final _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    _focus.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _text.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(children: [
            for (final (label, value) in widget.presets)
              Padding(
                padding: const EdgeInsets.only(right: 6),
                child: ChoiceChip(
                  label: Text(label),
                  selected: !_custom && widget.minutes == value,
                  onSelected: (_) {
                    setState(() => _custom = false);
                    widget.onChanged(value);
                  },
                ),
              ),
            ChoiceChip(
              label: const Text('Custom'),
              selected: _custom,
              onSelected: (_) {
                setState(() => _custom = true);
                if (_text.text.isEmpty) _text.text = '${widget.minutes < 0 ? 0 : widget.minutes}';
                widget.onChanged(int.tryParse(_text.text) ?? -1);
                _focus.requestFocus();
              },
            ),
          ]),
        ),
        if (_custom)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: TextField(
              controller: _text,
              focusNode: _focus,
              keyboardType: TextInputType.number,
              textInputAction: TextInputAction.done,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(5)],
              decoration: InputDecoration(
                labelText: 'Minutes',
                border: const OutlineInputBorder(),
                helperText: widget.minutes >= 0 ? '= ${formatMinutes(widget.minutes)}' : null,
                // The iOS number pad has no return key: a Done button while typing (as in the native sample).
                suffixIcon: _focus.hasFocus ? TextButton(onPressed: _focus.unfocus, child: const Text('Done')) : null,
              ),
              onChanged: (t) => widget.onChanged(int.tryParse(t) ?? -1),
            ),
          ),
      ]);
}

/// "Are you sure?" for anything destructive.
Future<void> confirm(BuildContext context, String title, String text, String action, VoidCallback onConfirm) =>
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(text),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              onConfirm();
            },
            child: Text(action),
          ),
        ],
      ),
    );
