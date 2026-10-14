import 'dart:math' as math;

import 'package:flutter/widgets.dart';

import '../ui.dart';
import 'xray.dart';

/// The exploded view: the boxes of a capture as planes stacked by depth, in outline, turned by a finger. No picture of
/// the screen is taken (the brief: "in outline (no picture of the screen is taken)"), so a plane is its boxes' outlines
/// and nothing of what is in them.

/// The furthest a turn goes either way, in radians: past it the planes lie edge on and cannot be told apart.
final double maxTurn = 70 * math.pi / 180;

/// One box on a plane: its place in the capture ([box], as [CaptureBox.i]) and its four corners on the view, clockwise
/// from the top left.
class Quad {
  const Quad(this.box, this.corners);
  final int box;
  final List<Offset> corners;
}

/// Every box of one depth, and the outline of the screen at that depth.
class Plane {
  const Plane(this.depth, this.outline, this.quads);
  final int depth;
  final List<Offset> outline;
  final List<Quad> quads;
}

/// The planes laid out for one turn, back to front.
class Exploded {
  const Exploded(this.planes);
  final List<Plane> planes;

  /// The box nearest the viewer under [point] (its [CaptureBox.i]), or null where there is none.
  int? at(Offset point) {
    for (final plane in planes.reversed) {
      for (final quad in plane.quads.reversed) {
        if (_inside(quad.corners, point)) return quad.box;
      }
    }
    return null;
  }
}

bool _inside(List<Offset> corners, Offset p) {
  var side = 0.0;
  for (var i = 0; i < corners.length; i++) {
    final a = corners[i], b = corners[(i + 1) % corners.length];
    final cross = (b.dx - a.dx) * (p.dy - a.dy) - (b.dy - a.dy) * (p.dx - a.dx);
    if (cross == 0) continue;
    if (side != 0 && (cross < 0) != (side < 0)) return false;
    side = cross;
  }
  return true;
}

/// Lays [boxes] (of a screen [width] by [height] pixels) out as planes at a height of their depth, turned by [yaw]
/// about the upright and [pitch] about the sideways (each held to [maxTurn]), seen through a perspective and fitted to
/// [size]. The stack turns about its middle, nearer planes (the deeper ones) are larger. With no turn and one depth a
/// box is the screen's own rectangle scaled to fit and centred.
Exploded explode(List<CaptureBox> boxes, {required int width, required int height, required double yaw, required double pitch, required Size size}) {
  if (boxes.isEmpty || width <= 0 || height <= 0) return const Exploded([]);
  final turnY = yaw.clamp(-maxTurn, maxTurn), turnX = pitch.clamp(-maxTurn, maxTurn);
  final deepest = boxes.fold<int>(0, (most, b) => math.max(most, b.depth));
  final shown = math.min(size.width / width, size.height / height) * 0.62;
  final gap = height * 0.9 / math.max(deepest, 3);
  final eye = 2.5 * height;
  final sinY = math.sin(turnY), cosY = math.cos(turnY), sinX = math.sin(turnX), cosX = math.cos(turnX);

  // One point of the capture at [depth]: turned, then seen through the perspective.
  (Offset, double) project(double x, double y, int depth) {
    final u = x - width / 2, v = y - height / 2, w = (depth - deepest / 2) * gap;
    final x1 = u * cosY + w * sinY;
    final z1 = -u * sinY + w * cosY;
    final y2 = v * cosX - z1 * sinX;
    final z2 = v * sinX + z1 * cosX;
    final near = eye / (eye - z2);
    return (Offset(size.width / 2 + x1 * near * shown, size.height / 2 + y2 * near * shown), z2);
  }

  List<Offset> corners(Rect r, int depth) => [
        project(r.left, r.top, depth).$1,
        project(r.right, r.top, depth).$1,
        project(r.right, r.bottom, depth).$1,
        project(r.left, r.bottom, depth).$1,
      ];

  final byDepth = <int, List<CaptureBox>>{};
  for (final b in boxes) {
    (byDepth[b.depth] ??= []).add(b);
  }
  final depths = byDepth.keys.toList()..sort((a, b) => project(0, 0, a).$2.compareTo(project(0, 0, b).$2));
  return Exploded([
    for (final d in depths)
      Plane(d, corners(Rect.fromLTWH(0, 0, width.toDouble(), height.toDouble()), d), [for (final b in byDepth[d]!) Quad(b.i, corners(b.place, d))]),
  ]);
}

/// The depth colours of the overlay (xray/Overlay.kt `COLOURS`): the same box is the same colour on the app and here.
const List<Color> depthColours = [Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFF06292), Color(0xFFBA68C8), Color(0xFFFFF176)];

class ExplodedPainter extends CustomPainter {
  ExplodedPainter({required this.exploded, required this.yaw, required this.pitch, required this.picked, required this.faint, required this.wash});
  final Exploded exploded;
  final double yaw, pitch;
  final int? picked;

  /// The ink of a plane's own outline, and of the picked box's wash.
  final Color faint, wash;

  @override
  void paint(Canvas canvas, Size size) {
    Path path(List<Offset> c) => Path()..addPolygon(c, true);
    final plane = Paint()..style = PaintingStyle.stroke..strokeWidth = 1..color = faint;
    final line = Paint()..style = PaintingStyle.stroke..strokeWidth = 1.5..strokeJoin = StrokeJoin.round;
    for (final p in exploded.planes) {
      canvas.drawPath(path(p.outline), plane);
      line.color = depthColours[p.depth % depthColours.length];
      for (final q in p.quads) {
        if (q.box == picked) canvas.drawPath(path(q.corners), Paint()..color = wash);
        canvas.drawPath(path(q.corners), line);
      }
    }
  }

  @override
  bool shouldRepaint(ExplodedPainter old) => old.exploded != exploded || old.picked != picked;
}

/// The planes, turned by a drag and put back by a double tap; a tap on a box picks it.
class ExplodedView extends StatefulWidget {
  const ExplodedView({super.key, required this.capture, required this.picked, required this.onPick});
  final Capture capture;
  final int? picked;
  final ValueChanged<int?> onPick;

  /// Where it rests, and where a double tap puts it back: turned enough that the planes can be seen as planes.
  static const double restYaw = 0.6, restPitch = 0.45;

  @override
  State<ExplodedView> createState() => _ExplodedViewState();
}

class _ExplodedViewState extends State<ExplodedView> {
  double _yaw = ExplodedView.restYaw, _pitch = ExplodedView.restPitch;
  Exploded _lay(Size size) {
    final c = widget.capture;
    return explode(c.boxes, width: c.width, height: c.height, yaw: _yaw, pitch: _pitch, size: size);
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, box) {
        // As tall as it is wide, so the stack has room to turn.
        final size = Size(box.maxWidth, box.maxWidth);
        final laid = _lay(size);
        return GestureDetector(
          key: const ValueKey('xray.exploded'),
          behavior: HitTestBehavior.opaque,
          onPanUpdate: (d) => setState(() {
            _yaw = (_yaw + d.delta.dx * 0.01).clamp(-maxTurn, maxTurn);
            _pitch = (_pitch - d.delta.dy * 0.01).clamp(-maxTurn, maxTurn);
          }),
          onDoubleTap: () => setState(() {
            _yaw = ExplodedView.restYaw;
            _pitch = ExplodedView.restPitch;
          }),
          onTapUp: (d) => widget.onPick(laid.at(d.localPosition)),
          child: CustomPaint(
            size: size,
            painter: ExplodedPainter(exploded: laid, yaw: _yaw, pitch: _pitch, picked: widget.picked, faint: ink(context, 'border-subtle'), wash: depthWash(ink(context, 'text'))),
          ),
        );
      },
    );
  }
}

/// A box's wash: its own ink, faint.
Color depthWash(Color ink) => ink.withValues(alpha: 0.18);
