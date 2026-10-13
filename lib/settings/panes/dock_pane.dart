import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../../tools.dart';
import '../../ui.dart' show say;
import '../kit/settings_kit.dart';
import 'pane_parts.dart';

/// The dock's settings: whether it shows, how it looks on this screen, and what is in it. Every row reads and writes
/// the dock's own preferences (dock/Dock.kt `Prefs`), and each change makes the dock again as its own page did
/// (`DockService.apply`).
class DockPane extends StatelessWidget {
  const DockPane({super.key, required this.dock});
  final DockState dock;

  static const _sides = {'left': 'Left', 'right': 'Right', 'both': 'Both sides'};

  @override
  Widget build(BuildContext context) {
    Widget toggle(String label, String name, bool on, {String? hint}) => SettingRow(
          key: ValueKey(name),
          label: label,
          hint: hint == null ? null : Text(hint),
          control: GlacierSwitch(ariaLabel: label, checked: on, onCheckedChange: (now) => Tools.set(name, now)),
        );
    RowAction nudge(String word, List<int> step) => RowAction(key: ValueKey('nudge $word'), onPress: () => Tools.set('dock.nudge', step), child: Text(word));
    return PaneBody(
      children: [
        PaneSection(
          children: [
            SettingRow(
              key: const ValueKey('dock.on'),
              label: 'Show the dock',
              hint: Text(dock.overlay
                  ? 'One small pill over whatever is on screen.'
                  : 'It has to be allowed to display over other apps. Switching it on opens that setting, and the dock is on when you come back.'),
              control: GlacierSwitch(
                ariaLabel: 'Show the dock',
                checked: dock.on,
                onCheckedChange: (now) async {
                  if (await Tools.set('dock.on', now) == 'asked' && context.mounted) say(context, 'Allow the dock to display over other apps');
                },
              ),
            ),
          ],
        ),
        PaneSection(
          title: 'How it looks',
          description: const Text('Each screen of a folding phone keeps its own place and its own look, so set the other one with the phone the other way.'),
          children: [
            if (!dock.camera)
              const SettingRow(key: ValueKey('dock.mode'), label: 'On this screen', hint: Text('Where this screen\'s camera is, is not known, so the dock floats.'), value: Text('Floating'))
            else
              SettingRow(
                key: const ValueKey('dock.mode'),
                label: 'On this screen',
                hint: const Text('Floating, it stays wherever you drag it. Beside the camera, it sits against the camera with the hole inside its frame.'),
                layout: SettingRowLayout.stacked,
                control: GlacierSegmentedControl(
                  fullWidth: true,
                  size: GlacierSegmentedSize.sm,
                  value: dock.beside ? 'island' : 'float',
                  onValueChange: (v) => Tools.set('dock.mode', v),
                  options: const [GlacierSegmentedOption(value: 'float', label: Text('Floating')), GlacierSegmentedOption(value: 'island', label: Text('Beside the camera'))],
                ),
              ),
            if (dock.beside) ...[
              SettingRow(
                key: const ValueKey('dock.taps'),
                label: 'Taps beside the camera',
                hint: Text(dock.taps
                    ? 'A tap reaches the dock there: the accessibility service is on.'
                    : 'The status bar takes every touch on its strip. Under Accessibility, Installed apps, switch on Matt\'s Tools.'),
                value: dock.taps ? const Text('On') : null,
                layout: dock.taps ? SettingRowLayout.trailing : SettingRowLayout.stacked,
                control: dock.taps ? null : RowAction(onPress: () => Tools.openSetting('accessibility'), child: const Text('Open Accessibility')),
              ),
              SettingRow(
                key: const ValueKey('dock.side'),
                label: 'Its parts',
                hint: Text(dock.side == 'both' ? 'Shared out to either side, the camera in the middle.' : 'Which side of the camera they stand on.'),
                layout: SettingRowLayout.stacked,
                control: GlacierSegmentedControl(
                  fullWidth: true,
                  size: GlacierSegmentedSize.sm,
                  value: dock.side,
                  onValueChange: (v) => Tools.set('dock.side', v),
                  options: [for (final side in _sides.entries) GlacierSegmentedOption(value: side.key, label: Text(side.value))],
                ),
              ),
              SettingRow(
                key: const ValueKey('dock.nudge'),
                label: 'Line it up with the camera',
                hint: Text('Move the frame until the camera sits in the middle of its end. Camera taken to be at ${dock.cameraAt}${dock.guess ? ': a first guess for this screen, which does not say where its camera is.' : '.'}'),
                layout: SettingRowLayout.stacked,
                control: PaneActions(
                  children: [
                    nudge('Left', const [-3, 0, 0]),
                    nudge('Right', const [3, 0, 0]),
                    nudge('Up', const [0, -3, 0]),
                    nudge('Down', const [0, 3, 0]),
                    nudge('Smaller', const [0, 0, -2]),
                    nudge('Larger', const [0, 0, 2]),
                  ],
                ),
              ),
            ] else ...[
              SettingRow(
                key: const ValueKey('dock.vertical'),
                label: 'Direction',
                hint: const Text('Its parts side by side, or one under another.'),
                control: GlacierSegmentedControl(
                  size: GlacierSegmentedSize.sm,
                  value: dock.vertical ? 'down' : 'across',
                  onValueChange: (v) => Tools.set('dock.vertical', v == 'down'),
                  options: const [GlacierSegmentedOption(value: 'across', label: Text('Across')), GlacierSegmentedOption(value: 'down', label: Text('Down'))],
                ),
              ),
              toggle('Blur what is behind it', 'dock.blur', dock.blur, hint: 'Where the phone can. Off, it is a darker glass.'),
            ],
          ],
        ),
        PaneSection(
          title: 'In it',
          children: [
            toggle('Temperature', 'dock.heat', dock.heat, hint: 'The battery\'s, the one reading Android gives every app.'),
            SettingRow(
              key: const ValueKey('dock.fahrenheit'),
              label: 'Degrees',
              control: GlacierSegmentedControl(
                size: GlacierSegmentedSize.sm,
                value: dock.fahrenheit ? 'f' : 'c',
                onValueChange: (v) => Tools.set('dock.fahrenheit', v == 'f'),
                options: const [GlacierSegmentedOption(value: 'c', label: Text('Celsius')), GlacierSegmentedOption(value: 'f', label: Text('Fahrenheit'))],
              ),
            ),
            toggle('Time awake', 'dock.awake', dock.awake, hint: 'From Amphetamine. A tap on it switches a session on or off.'),
            toggle('Rate', 'dock.frames', dock.frames, hint: 'From Frames, while it measures.'),
          ],
        ),
        const SettingsFootnote(
          child: Text('Floating, drag the dock to wherever you want it; it stays there. A tap on Matt\'s Software\'s mark folds it down to the mark, and another opens it. A tap on the flame opens Toasty.'),
        ),
      ],
    );
  }
}
