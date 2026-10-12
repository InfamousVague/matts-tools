import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:glacier/glacier.dart';

import 'home.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  final sheet = await loadTokenSheet();
  runApp(FramesApp(sheet: sheet));
}

/// The app under the Glacier kit's tokens: the phone's own light or dark, the kit's type, haptics on every press.
class FramesApp extends StatelessWidget {
  const FramesApp({super.key, required this.sheet});
  final TokenSheet sheet;

  @override
  Widget build(BuildContext context) => WidgetsApp(
        title: 'Frames',
        color: const Color(0xFF0D0D0D),
        debugShowCheckedModeBanner: false,
        pageRouteBuilder: <T>(RouteSettings settings, WidgetBuilder builder) => PageRouteBuilder<T>(settings: settings, pageBuilder: (context, _, _) => builder(context)),
        builder: (context, child) => GlacierTheme(
          sheet: sheet,
          attrs: const {'platform': 'mobile'},
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
                child: DefaultTextStyle(style: context.tokens.text('md', color: context.tokens.color('glacier-text')), child: GlacierToastProvider(child: child!)),
              ),
            ),
          ),
        ),
        home: const Home(),
      );
}
