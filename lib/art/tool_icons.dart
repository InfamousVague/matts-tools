import 'app_icon.dart';

/// The tools' own marks, the drawings their tiles and widgets wear on the phone (android/app/src/main/res/drawable
/// `ic_dock`, `ic_pill`, `ic_frames`), as paths on the 24 grid for [AppIcon] to draw the way it draws the kit's.
class ToolIcons {
  /// The dock: one pill.
  static const dock = LucideIcon('dock', ['M7 8h10a4 4 0 0 1 0 8h-10a4 4 0 0 1 0 -8z'], [0]);

  /// Amphetamine: a capsule on the slant, with the line across its middle.
  static const pill = LucideIcon('pill', ['M10.5 20.5 L20.5 10.5 a4.95 4.95 0 1 0 -7 -7 L3.5 13.5 a4.95 4.95 0 1 0 7 7 Z', 'M8.5 8.5 L15.5 15.5'], [0]);

  /// Frames: a trace of frame times, steady, one spike.
  static const frames = LucideIcon('frames', ['M3 16 L7 16 L9 12 L11 16 L13 5 L15 16 L17 14 L21 14', 'M3 20 L21 20'], []);
}
