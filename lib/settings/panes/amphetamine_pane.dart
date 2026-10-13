import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../../tools.dart';
import '../../ui.dart' as ui show AppIcon;
import '../kit/settings_kit.dart';
import '../level_slider.dart';

/// Amphetamine's settings: full brightness, what ends a session, what starts one by itself, and how the screen is
/// held. Every row reads and writes Amphetamine's own preferences (amphetamine/State.kt `Prefs`), through what its
/// own page called when it changed one: the session told of the brightness and of the way of holding, the charger's
/// job armed, the watch for apps in front started.
class AmphetaminePane extends StatefulWidget {
  const AmphetaminePane({super.key, required this.awake, this.onApps});
  final AwakeState awake;

  /// Opens the page of apps to choose among.
  final VoidCallback? onApps;

  @override
  State<AmphetaminePane> createState() => _AmphetaminePaneState();
}

class _AmphetaminePaneState extends State<AmphetaminePane> {
  /// The level under the finger while the line is dragged.
  int? _floor;

  @override
  Widget build(BuildContext context) {
    final awake = widget.awake;
    final floor = _floor ?? awake.batteryFloor;
    Widget toggle(String label, String name, bool on, String hint) => SettingRow(
          key: ValueKey(name),
          label: label,
          hint: Text(hint),
          control: GlacierSwitch(ariaLabel: label, checked: on, onCheckedChange: (now) => Tools.set(name, now)),
        );
    Widget way(String label, String method, String hint, bool allowed, String needs) => SettingRow(
          key: ValueKey('awake.method.$method'),
          label: label,
          hint: Text('$hint ${allowed ? 'Allowed.' : 'Needs "$needs": choosing it opens that setting.'}'),
          control: Pick(checked: awake.method == method, label: label, onPress: () => Tools.set('awake.method', method)),
        );
    return PaneBody(
      children: [
        if (!awake.canHold)
          const SettingsCallout(child: Text('Amphetamine needs one of two permissions before it can hold the screen: see "How the screen is held" below.')),
        PaneSection(
          title: 'While awake',
          children: [toggle('Full brightness', 'awake.bright', awake.bright, 'Also on its own tile in the pull-down.')],
        ),
        PaneSection(
          title: 'End a session',
          children: [
            toggle('When the battery is low', 'awake.endOnBattery', awake.endOnBattery, 'At $floor% and not charging.'),
            if (awake.endOnBattery)
              SettingRow(
                key: const ValueKey('awake.batteryFloor'),
                label: 'Low is',
                value: Text('$floor%'),
                layout: SettingRowLayout.stacked,
                control: LevelSlider(
                  value: awake.batteryFloor,
                  min: 5,
                  max: 50,
                  label: 'Battery level that ends a session',
                  onChanged: (v) => setState(() => _floor = v),
                  onChangeEnd: (v) async {
                    await Tools.set('awake.batteryFloor', v);
                    if (mounted) setState(() => _floor = null);
                  },
                ),
              ),
            toggle('When unplugged', 'awake.endUnplugged', awake.endUnplugged, 'Only a session that began on the charger.'),
            toggle('When the phone is folded shut', 'awake.endFolded', awake.endFolded, 'A session begun on the cover screen is left alone.'),
          ],
        ),
        PaneSection(
          title: 'Start one by itself',
          children: [
            toggle('When plugged in', 'awake.startCharging', awake.startCharging, 'Begins as the charger goes in.'),
            SettingRow(
              key: const ValueKey('awake.apps'),
              label: 'While these apps are in front',
              hint: Text(awake.appNames.isEmpty ? 'None chosen.' : awake.appNames.join(', ')),
              onPress: widget.onApps ?? () {},
              disabled: widget.onApps == null,
            ),
            if (awake.apps.isNotEmpty && !awake.seesApps)
              SettingRow(
                key: const ValueKey('awake.usage'),
                label: 'Allow usage access',
                hint: const Text('Needed to know which app is in front.'),
                control: RowAction(onPress: () => Tools.openSetting('usage'), child: const Text('Allow')),
              ),
          ],
        ),
        PaneSection(
          title: 'How the screen is held',
          children: [
            way('An unseen window', 'overlay', 'Over every app; changes nothing of the phone\'s.', awake.overlay, 'appear on top'),
            way('The phone\'s own settings', 'system', 'Sets the screen timeout to its longest and puts it back after.', awake.system, 'modify system settings'),
          ],
        ),
        const SettingsFootnote(child: Text('Add the Amphetamine tile from the pull-down\'s edit button, and the pill from the home screen\'s widgets.')),
      ],
    );
  }
}

/// The apps a session is kept on for while one of them is in front: every app that can be opened, each with a
/// switch. A switch writes the choice at once (`Prefs.apps`) and the watch for apps in front is started, as the old
/// list did when it was closed with Done.
class AmphetamineAppsPane extends StatefulWidget {
  const AmphetamineAppsPane({super.key, required this.awake});
  final AwakeState awake;

  @override
  State<AmphetamineAppsPane> createState() => _AmphetamineAppsPaneState();
}

class _AmphetamineAppsPaneState extends State<AmphetamineAppsPane> {
  List<AppChoice>? _apps;

  @override
  void initState() {
    super.initState();
    Tools.apps().then((apps) {
      if (mounted) setState(() => _apps = apps);
    }, onError: (Object _) {
      if (mounted) setState(() => _apps = const []);
    });
  }

  @override
  Widget build(BuildContext context) {
    final apps = _apps;
    final chosen = widget.awake.apps.toSet();
    return PaneBody(
      children: [
        if (chosen.isNotEmpty && !widget.awake.seesApps)
          SettingsCallout(
            action: RowAction(onPress: () => Tools.openSetting('usage'), child: const Text('Allow')),
            child: const Text('Android has to allow Matt\'s Tools to know which app is in front.'),
          ),
        if (apps == null)
          const PaneSection(children: [SettingRow(label: 'Reading the apps on this phone…')])
        else if (apps.isEmpty)
          const PaneSection(children: [SettingRow(label: 'No apps to choose among.')])
        else
          PaneSection(
            description: const Text('A session starts when one of these comes to the front, and ends when it leaves.'),
            children: [
              for (final app in apps)
                SettingRow(
                  key: ValueKey('app ${app.package}'),
                  icon: ui.AppIcon(app.package, size: 28, name: app.label),
                  label: app.label,
                  control: GlacierSwitch(
                    ariaLabel: app.label,
                    checked: chosen.contains(app.package),
                    onCheckedChange: (now) => Tools.set('awake.apps', [...chosen.where((p) => p != app.package), if (now) app.package]),
                  ),
                ),
            ],
          ),
      ],
    );
  }
}
