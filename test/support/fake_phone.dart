import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// The phone's side of the channel, for a test: it answers `tools` and `state` from maps a test may change, keeps
/// every call made of it, and applies a `set` to its own map the way the Kotlin side applies one to the
/// preferences, so a page drawn after a press shows what the press did.
class FakePhone {
  FakePhone() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('frames'), _answer);
  }

  final List<MethodCall> calls = [];

  /// The page the app is opened at (`launch`), and what a `set` answers ("asked" where a permission is wanted).
  String? launch;
  String? setAnswers;

  final Map<String, Object?> dock = {
    'on': true, 'overlay': true, 'camera': true, 'cameraAt': '589, 34 to 659, 104', 'guess': false, 'beside': false, 'taps': false, 'side': 'left',
    'vertical': false, 'blur': true, 'heat': true, 'fahrenheit': false, 'awake': true, 'frames': true, //
  };
  final Map<String, Object?> awake = {
    'on': false, 'forMs': 0, 'why': 'tap', 'bright': false, 'endOnBattery': true, 'batteryFloor': 20, 'endUnplugged': true, 'endFolded': true,
    'startCharging': false, 'apps': <String>[], 'appNames': <String>[], 'seesApps': true, 'method': 'overlay', 'overlay': true, 'system': false, //
  };
  final Map<String, Object?> granted = {'notifications': true, 'overlay': true, 'usage': true, 'access': false, 'write': false, 'dump': true, 'secure': true, 'xray': false};
  final Map<String, Object?> build = {'version': '1.0.1', 'code': 2, 'package': 'com.mattssoftware.tools', 'device': 'samsung SM-F971U', 'android': '17 (37)', 'installed': '2026-10-12 18:30'};
  double? celsius = 31.4;

  final Map<String, Object?> frames = {
    'running': false, 'dump': true, 'usage': true, 'overlay': true, 'secure': true, 'hud': false, 'corner': 0, 'scenario': '', 'sessions': '/nowhere',
    'package': 'com.mattssoftware.tools', 'animation': 1.0, 'awake': false, 'live': null, 'hardware': true, 'batteryStats': false, //
  };

  final List<Map<String, String>> apps = [
    {'package': 'com.example.maps', 'label': 'Maps'},
    {'package': 'com.example.reader', 'label': 'Reader'},
  ];

  /// What X-ray lists to choose among (Matt's Tools too, as the phone's side does), and what it answers of each by package: a missing one
  /// is an app the phone cannot read.
  final List<Map<String, String>> xrayApps = [
    {'package': 'com.example.maps', 'label': 'Maps'},
    {'package': 'com.mattssoftware.tools', 'label': 'Matt\'s Tools'},
  ];
  final Map<String, Map<String, Object?>> anatomies = {};

  /// X-ray's switch and its last reading (xray/Door.kt `state`): on, not started, nothing captured. A test turns the
  /// switch off or puts a capture here; `forget` drops it, as the phone's side does.
  final Map<String, Object?> xrayState = {'on': true, 'running': false, 'dump': true, 'capture': null};
  Map<String, Object?>? xrayCapture;

  /// Whether the last `private` call asked for no Recents picture.
  bool privateOn = false;

  /// The calls of [method], oldest first.
  List<MethodCall> of(String method) => [for (final call in calls) if (call.method == method) call];

  /// What the last `set` of [name] gave, or null where none was made.
  Object? lastSet(String name) {
    for (final call in of('set').reversed) {
      final args = call.arguments as Map;
      if (args['name'] == name) return args['value'];
    }
    return null;
  }

  bool wasSet(String name) => of('set').any((call) => (call.arguments as Map)['name'] == name);

  Future<Object?> _answer(MethodCall call) async {
    calls.add(call);
    switch (call.method) {
      case 'tools':
        return {'dock': dock, 'awake': awake, 'granted': granted, 'build': build, 'celsius': celsius};
      case 'state':
        return frames;
      case 'launch':
        return launch;
      case 'apps':
        return apps;
      case 'xray':
        final args = call.arguments as Map;
        if (args['do'] == 'apps') return xrayApps;
        if (args['do'] == 'anatomy') return anatomies[args['package']] ?? {'error': 'Android would not let Matt\'s Tools read this app\'s package.'};
        if (args['do'] == 'state') return xrayState;
        if (args['do'] == 'capture') return xrayCapture;
        if (args['do'] == 'private') privateOn = args['on'] == true;
        if (args['do'] == 'forget') {
          xrayState['capture'] = null;
          xrayCapture = null;
        }
        return null;
      case 'set':
        final args = call.arguments as Map;
        final name = args['name'] as String, value = args['value'];
        final at = name.indexOf('.');
        final tool = name.substring(0, at), key = name.substring(at + 1);
        if (setAnswers != null) return setAnswers;
        if (name == 'dock.mode') {
          dock['beside'] = value == 'island';
        } else if (name == 'awake.session') {
          awake['on'] = value;
        } else if (name == 'awake.apps') {
          awake['apps'] = value;
          awake['appNames'] = [for (final app in apps) if ((value as List).contains(app['package'])) app['label']];
        } else if (name != 'dock.nudge') {
          (tool == 'dock' ? dock : awake)[key] = value;
        }
        return null;
      case 'scenario':
        frames['scenario'] = call.arguments;
      case 'corner':
        frames['corner'] = call.arguments;
      case 'animation':
        frames['animation'] = call.arguments;
      case 'awake':
        frames['awake'] = call.arguments;
      case 'hardware':
        frames['hardware'] = call.arguments;
      case 'hud':
        frames['hud'] = !(frames['hud'] as bool);
        return frames['hud'] == true ? 'on' : 'off';
    }
    return null;
  }
}
