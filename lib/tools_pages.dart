import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'art/app_icon.dart' as art;
import 'art/lucide.g.dart';
import 'art/tool_icons.dart';
import 'shell/app_shell.dart';
import 'shell/visited.dart';
import 'tools.dart';
import 'ui.dart';

/// The first page: the tools, as a home screen shows its apps, an icon and its name under it, four across. A tap
/// opens the tool. (It was tools/Tools.kt `HomeActivity`, drawn in Kotlin.)
class ToolsPage extends StatelessWidget {
  const ToolsPage({super.key});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final shell = AppShell.of(context);
    Widget tool(String name, art.LucideIcon icon, Place place) => Expanded(
          child: GlacierPressable(
            onPressed: () => shell.go(place),
            semanticLabel: name,
            builder: (context, state) => Padding(
              padding: EdgeInsets.only(top: t.space(3), bottom: t.space(2)),
              child: Column(children: [
                // As a home screen draws an app: a rounded tile, its picture in the middle, its name under it.
                Container(
                  width: 62,
                  height: 62,
                  decoration: BoxDecoration(
                    color: state.pressed ? t.color('app-paper-3') : t.color('app-paper-2'),
                    borderRadius: BorderRadius.circular(19),
                    border: Border.all(color: ink(context, 'border-subtle')),
                  ),
                  alignment: Alignment.center,
                  child: art.AppIcon(icon, size: 28, color: ink(context, 'text')),
                ),
                SizedBox(height: t.space(2)),
                Txt(name, size: 'xs', maxLines: 1, align: TextAlign.center),
              ]),
            ),
          ),
        );
    return ValueListenableBuilder<ToolsState?>(
      valueListenable: Tools.state,
      builder: (context, tools, _) {
        final missing = tools?.granted.missing ?? 0;
        return Screen(title: 'Matt\'s Tools', subtitle: 'Small tools for the phone, kept in one place.', children: [
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            tool('Dock', ToolIcons.dock, settingsPlaceOf('dock')),
            tool('Amphetamine', ToolIcons.pill, amphetaminePlace),
            tool('Frames', ToolIcons.frames, framesPlace),
            tool('Toasty', Lucide.flame, toastyPlace),
          ]),
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [tool('X-ray', ToolIcons.layers, xrayPlace), const Spacer(), const Spacer(), const Spacer()]),
          const Heading('Setting up'),
          Line(
            title: 'Permissions',
            subtitle: 'What each tool needs of the phone, and where each stands',
            trailing: Txt(tools == null ? '' : missing == 0 ? 'All set' : '$missing to go', size: 'sm', color: 'text-muted'),
            onTap: () => shell.go(settingsPlaceOf('about')),
          ),
          Line(title: 'Settings', subtitle: 'A page for each tool', onTap: () => shell.go(settingsPlace)),
        ]);
      },
    );
  }
}

/// Amphetamine: the switch and how long it has been on. Its settings are its page of Settings. (It was
/// amphetamine/MainActivity.kt.)
class AmphetaminePage extends StatelessWidget {
  const AmphetaminePage({super.key});

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<ToolsState?>(
        valueListenable: Tools.state,
        builder: (context, tools, _) {
          final t = context.tokens;
          final awake = tools?.awake;
          if (awake == null) return const Screen(title: 'Amphetamine', children: []);
          final on = awake.on;
          return Screen(title: 'Amphetamine', children: [
            // The switch: paper on ink while a session is on, as the page it was.
            GlacierScope(
              classes: {if (on) 'app-inverse'},
              child: Builder(
                builder: (context) => Panel(
                  key: const ValueKey('awake.session'),
                  color: 'bg',
                  padding: EdgeInsets.symmetric(horizontal: t.space(5), vertical: t.space(8)),
                  onTap: () async {
                    if (await Tools.set('awake.session', !on) == 'asked' && context.mounted) say(context, 'Allow Amphetamine to appear on top, then come back');
                  },
                  child: Column(children: [
                    Txt(on ? 'Awake' : 'Asleep', size: '3xl', weight: 'bold', align: TextAlign.center),
                    if (on) Txt(spanOf(awake.forMs), size: 'xl', align: TextAlign.center),
                    SizedBox(height: t.space(2)),
                    Txt(
                      !on
                          ? 'Tap to keep the screen on'
                          : awake.why == 'charger'
                              ? 'On since it was plugged in. Tap to stop'
                              : awake.why == 'app'
                                  ? 'On for the app in front. Tap to stop'
                                  : 'The screen will not sleep. Tap to stop',
                      size: 'sm',
                      color: 'text-muted',
                      align: TextAlign.center,
                    ),
                  ]),
                ),
              ),
            ),
            if (!awake.canHold) const Note('Amphetamine needs one of two permissions before it can hold the screen: see "How the screen is held" in its settings.'),
            const Heading('Settings'),
            Line(
              title: 'Amphetamine\'s settings',
              subtitle: 'Full brightness, what ends a session, what starts one, how the screen is held',
              onTap: () => AppShell.of(context).go(settingsPlaceOf('amphetamine')),
            ),
            const Note('Add the Amphetamine tile from the pull-down\'s edit button, and the pill from the home screen\'s widgets.'),
          ]);
        },
      );
}

/// Toasty: the phone's temperature, large. Its settings are its page of Settings. (It was tools/Tools.kt
/// `ToastyActivity`.)
class ToastyPage extends StatelessWidget {
  const ToastyPage({super.key});

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<ToolsState?>(
        valueListenable: Tools.state,
        builder: (context, tools, _) {
          final t = context.tokens;
          return Screen(title: 'Toasty', children: [
            Panel(
              padding: EdgeInsets.symmetric(horizontal: t.space(5), vertical: t.space(6)),
              child: Row(children: [
                art.AppIcon(Lucide.flame, size: 44, color: ink(context, 'text')),
                SizedBox(width: t.space(3)),
                Expanded(child: Txt(tools?.heat ?? '', size: '3xl', weight: 'bold', maxLines: 1)),
              ]),
            ),
            const Heading('Settings'),
            Line(
              title: 'Toasty\'s settings',
              subtitle: 'The degrees it reads in, and whether the dock shows it',
              onTap: () => AppShell.of(context).go(settingsPlaceOf('toasty')),
            ),
            const Note('This is the battery\'s temperature, the one reading Android gives every app. It follows the whole phone, a little behind the processor.'),
          ]);
        },
      );
}
