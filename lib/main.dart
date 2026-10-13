import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'settings/settings_css.dart';
import 'shell/app_shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  final sheet = await loadAppSheet();
  runApp(FramesApp(sheet: sheet));
}

/// The stylesheets the token engine reads after the kit's own: Ghost.md's ink and paper (ink.css, less its rule that
/// prints success, warning and danger in ink: Frames says how janky an app is in them) and its app.css, which has
/// the bar's and the page's measures.
const appStyleSheets = ['assets/css/ink.css', 'assets/css/app.css'];

/// The app's token sheet, with the settings' own rules laid over it (settings/settings_css.dart).
Future<TokenSheet> loadAppSheet() async {
  final sheet = await loadTokenSheet(more: appStyleSheets);
  installSettingsStyles(sheet);
  return sheet;
}

/// The app under the Glacier kit's tokens: the phone's own light or dark, the kit's type, haptics on every press.
class FramesApp extends StatelessWidget {
  const FramesApp({super.key, required this.sheet, this.home = const AppShell()});
  final TokenSheet sheet;

  /// The app's frame (shell/app_shell.dart); a test puts its own here.
  final Widget home;

  @override
  Widget build(BuildContext context) => WidgetsApp(
        title: 'Matt\'s Tools',
        color: const Color(0xFF0D0D0D),
        debugShowCheckedModeBanner: false,
        pageRouteBuilder: <T>(RouteSettings settings, WidgetBuilder builder) => PageRouteBuilder<T>(settings: settings, pageBuilder: (context, _, _) => builder(context)),
        builder: (context, child) => GlacierTheme(
          sheet: sheet,
          // `data-tabs='on'`: the app's bar is on every page, one row tall (app.css `--app-tabs`).
          attrs: const {'platform': 'mobile', 'tabs': 'on'},
          typography: const GlacierTypography(features: [FontFeature('cv11'), FontFeature('ss01')], fallbacks: {'Inter Variable': ['Inter Variable LatinExt']}),
          child: HapticsProvider(
            enabled: true,
            child: Builder(
              builder: (context) => AnnotatedRegion<SystemUiOverlayStyle>(
                value: SystemUiOverlayStyle(
                  statusBarColor: const Color(0x00000000),
                  systemNavigationBarColor: const Color(0x00000000),
                  statusBarIconBrightness: context.tokens.isDark ? Brightness.light : Brightness.dark,
                  systemNavigationBarIconBrightness: context.tokens.isDark ? Brightness.light : Brightness.dark,
                ),
                child: DefaultTextStyle(style: context.tokens.text('md', color: context.tokens.color('glacier-text')), child: GlacierToastProvider(child: InkOverrides(child: child!))),
              ),
            ),
          ),
        ),
        home: home,
      );
}

/// ink.css's rules that reach into the kit's parts, where no token does (Ghost.md's lib/app/ghost_app.dart
/// `_InkOverrides`): `[role='switch'] + span > span { background: var(--app-paper) }`,
/// `[role='radiogroup'] > label[data-selected] > span:last-child { color: var(--app-paper) }` and
/// `[role='radiogroup'] > label > span[aria-hidden='true'] { box-shadow: none }`.
class InkOverrides extends StatelessWidget {
  const InkOverrides({super.key, required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return GlacierOverrides(
      switchThumbColor: _paper,
      segmentedSelectedLabelColor: _paper,
      segmentedThumbShadow: _none,
      child: child,
    );
  }

  static Color _paper(Tokens t) => t.color('app-paper');
  static List<BoxShadow> _none(Tokens t) => const [];
}
