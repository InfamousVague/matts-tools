import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import '../../tools.dart';
import '../kit/settings_kit.dart';

/// The rows of "This build", in order: what the build itself knows for certain and what it runs on, each as a name
/// and its words. Ghost.md's lib/core/build_facts.dart `buildFacts`, with this app's facts: there is no updater and
/// no service, so no row for where updates or the account come from.
List<({String name, String words})> buildFacts(BuildState build) {
  final mode = kReleaseMode ? 'Release' : (kProfileMode ? 'Profile' : 'Debug');
  return [
    (name: 'Version', words: '${build.version} (${build.code})'),
    if (build.installed.isNotEmpty) (name: 'Installed', words: build.installed),
    (name: 'Kind', words: '$mode · Flutter, with Kotlin for the tools themselves'),
    (name: 'Dart', words: Platform.version.split(' ').first),
    (name: 'Package', words: build.package),
    (name: 'Phone', words: build.device),
    (name: 'Android', words: build.android),
  ];
}

/// About: the version, the permissions each tool needs with where each stands and the way to Android's own setting
/// for it, and what this build is. Modelled on Ghost.md's About (lib/settings/panes/about_pane.dart): the version
/// as the page's hero, and "This build" last. The permissions are the setting-up's (tools/Tools.kt `SetupActivity`),
/// which is still what a new phone is walked through and can be opened again from here.
class AboutPane extends StatelessWidget {
  const AboutPane({super.key, required this.tools});
  final ToolsState tools;

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final granted = tools.granted;
    final package = tools.build.package;
    SettingRow permission(String id, String label, String hint, bool given, {String? open, String word = 'Open the setting', String? command}) => SettingRow(
          key: ValueKey('granted.$id'),
          label: label,
          hint: command == null || given
              ? Text(hint)
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(hint),
                    SizedBox(height: t.space(2)),
                    Text(command, style: t.text('sm', mono: true, color: t.color('glacier-text'))),
                  ],
                ),
          value: given ? const Text('Given') : (open == null ? const Text('Not given') : null),
          control: given || open == null ? null : RowAction(onPress: () => Tools.openSetting(open), child: Text(word)),
          layout: given || open == null ? SettingRowLayout.trailing : SettingRowLayout.stacked,
        );
    return PaneBody(
      children: [
        PaneSection(
          children: [PaneHero(title: tools.build.version, meta: const Text('Matt\'s Tools · the dock, Amphetamine, Frames and Toasty in one app'))],
        ),
        PaneSection(
          title: 'Permissions',
          description: const Text('What each tool needs of the phone. Nothing here is needed to look round the app.'),
          children: [
            permission('notifications', 'Notifications', 'Android asks every tool that keeps running to show a notification while it does: the dock, a keep-awake session, a measuring run.', granted.notifications, open: 'notifications'),
            permission('overlay', 'Display over other apps', 'The dock floats over whatever is on screen, Frames can draw its readout over the app it measures, and Amphetamine holds the screen on with a window you cannot see.', granted.overlay, open: 'overlay'),
            permission('usage', 'Which app is in front', 'Frames files its measurements under the app they were of, and Amphetamine can stay on only while apps you choose are in front. Find Matt\'s Tools in the list and allow it.', granted.usage, open: 'usage'),
            permission('access', 'Taps on the dock beside the camera', 'Only if you dock it beside the camera. The one kind of window Android draws above the status bar is an accessibility service\'s. This one asks for no events and reads nothing on the screen. Under Installed apps, switch on Matt\'s Tools.', granted.access, open: 'accessibility', word: 'Open Accessibility'),
            permission('write', 'Modify system settings', 'Only for Amphetamine\'s other way of holding the screen: the phone\'s own timeout, set to its longest and put back after.', granted.write, open: 'write'),
            permission('dump', 'Frame timings, from a computer', 'Only for Frames. Android lets an app read another app\'s frame timings after a computer says so, once. With the phone plugged in or on wireless debugging:', granted.dump, command: 'adb shell pm grant $package android.permission.DUMP'),
            permission('secure', 'Developer switches, from a computer', 'Only for Frames\' animation speed and stay awake while charging:', granted.secure, command: 'adb shell pm grant $package android.permission.WRITE_SECURE_SETTINGS'),
            SettingRow(
              key: const ValueKey('granted.setup'),
              label: 'Walk through the setting-up',
              hint: const Text('The pages from the first launch, one permission at a time.'),
              onPress: () => Tools.openSetting('setup'),
            ),
          ],
        ),
        // What this build is made of, as the build itself knows it: for saying which one a thing was seen in.
        PaneSection(
          title: 'This build',
          children: [for (final fact in buildFacts(tools.build)) SettingRow(key: ValueKey('build.${fact.name}'), label: fact.name, hint: Text(fact.words))],
        ),
      ],
    );
  }
}
