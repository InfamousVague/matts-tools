import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../../tools.dart';
import '../kit/settings_kit.dart';

/// Toasty's settings: the degrees it reads in, and whether the dock shows it. Both are the dock's own preferences
/// (dock/Dock.kt `Prefs.fahrenheit`, `Prefs.heat`), the same two the dock's page has.
class ToastyPane extends StatelessWidget {
  const ToastyPane({super.key, required this.tools});
  final ToolsState tools;

  @override
  Widget build(BuildContext context) => PaneBody(
        children: [
          PaneSection(
            children: [
              SettingRow(
                key: const ValueKey('toasty.fahrenheit'),
                label: 'Degrees',
                hint: Text('Now ${tools.heat}.'),
                control: GlacierSegmentedControl(
                  size: GlacierSegmentedSize.sm,
                  value: tools.dock.fahrenheit ? 'f' : 'c',
                  onValueChange: (v) => Tools.set('dock.fahrenheit', v == 'f'),
                  options: const [GlacierSegmentedOption(value: 'c', label: Text('Celsius')), GlacierSegmentedOption(value: 'f', label: Text('Fahrenheit'))],
                ),
              ),
              SettingRow(
                key: const ValueKey('toasty.heat'),
                label: 'In the dock',
                hint: const Text('The temperature beside a flame, in the dock.'),
                control: GlacierSwitch(ariaLabel: 'In the dock', checked: tools.dock.heat, onCheckedChange: (now) => Tools.set('dock.heat', now)),
              ),
            ],
          ),
          const SettingsFootnote(
            child: Text('This is the battery\'s temperature, the one reading Android gives every app. It follows the whole phone, a little behind the processor.'),
          ),
        ],
      );
}
