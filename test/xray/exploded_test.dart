import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:frames/xray/exploded.dart';
import 'package:frames/xray/xray.dart';

CaptureBox box(int i, int depth, double l, double t, double r, double b, {int? parent}) =>
    CaptureBox({'i': i, 'parent': parent, 'depth': depth, 'l': l, 't': t, 'r': r, 'b': b});

void main() {
  const view = Size(400, 400);

  test('an empty capture draws nothing', () {
    expect(explode(const [], width: 1000, height: 2000, yaw: 0.5, pitch: 0.3, size: view).planes, isEmpty);
    expect(explode([box(0, 0, 0, 0, 10, 10)], width: 0, height: 0, yaw: 0, pitch: 0, size: view).planes, isEmpty);
  });

  test('with no turn and one depth, a box is the screen\'s own rectangle scaled and centred', () {
    final e = explode([box(0, 0, 0, 0, 1000, 2000), box(1, 0, 250, 500, 750, 1000, parent: 0)], width: 1000, height: 2000, yaw: 0, pitch: 0, size: view);
    expect(e.planes, hasLength(1));
    final whole = e.planes.single.quads.first.corners;
    final inner = e.planes.single.quads.last.corners;
    // The whole screen keeps its shape, and the inner box is a quarter of its width and a quarter of its height.
    expect((whole[1].dx - whole[0].dx) / (whole[2].dy - whole[1].dy), closeTo(0.5, 1e-9));
    expect((inner[1].dx - inner[0].dx) / (whole[1].dx - whole[0].dx), closeTo(0.5, 1e-9));
    expect(whole[0].dx + whole[1].dx, closeTo(view.width, 1e-9));
    expect(whole[0].dy + whole[2].dy, closeTo(view.height, 1e-9));
    expect(whole[0].dx, lessThan(whole[1].dx));
    expect(whole[0].dy, lessThan(whole[2].dy));
  });

  Offset centre(Plane p) {
    final c = p.quads.first.corners;
    return Offset(c.fold(0.0, (n, o) => n + o.dx) / 4, c.fold(0.0, (n, o) => n + o.dy) / 4);
  }

  final stack = [for (var d = 0; d < 3; d++) box(d, d, 0, 0, 1000, 2000, parent: d == 0 ? null : d - 1)];

  test('with no turn the planes sit one behind another, and a yaw moves the deeper ones sideways, more with more turn', () {
    final flat = explode(stack, width: 1000, height: 2000, yaw: 0, pitch: 0, size: view).planes;
    expect(centre(flat[0]).dx, closeTo(centre(flat[2]).dx, 1e-9));
    final some = explode(stack, width: 1000, height: 2000, yaw: 0.3, pitch: 0, size: view).planes;
    final more = explode(stack, width: 1000, height: 2000, yaw: 0.9, pitch: 0, size: view).planes;
    final someApart = (centre(some[2]).dx - centre(some[0]).dx).abs();
    final moreApart = (centre(more[2]).dx - centre(more[0]).dx).abs();
    expect(someApart, greaterThan(1));
    expect(moreApart, greaterThan(someApart));
  });

  test('a pitch moves the deeper planes up or down the same way', () {
    final tilted = explode(stack, width: 1000, height: 2000, yaw: 0, pitch: 0.6, size: view).planes;
    expect((centre(tilted[2]).dy - centre(tilted[0]).dy).abs(), greaterThan(1));
    expect(centre(tilted[2]).dx, closeTo(centre(tilted[0]).dx, 1e-9));
  });

  test('planes come back to front: the shallowest first and the deepest, nearest the viewer, last', () {
    for (final yaw in [0.0, 0.7, -0.7]) {
      final planes = explode([stack[2], stack[0], stack[1]], width: 1000, height: 2000, yaw: yaw, pitch: 0.3, size: view).planes;
      expect([for (final p in planes) p.depth], [0, 1, 2]);
    }
  });

  test('a turn past 70 degrees is held at 70', () {
    final far = explode(stack, width: 1000, height: 2000, yaw: 3, pitch: -3, size: view);
    final held = explode(stack, width: 1000, height: 2000, yaw: maxTurn, pitch: -maxTurn, size: view);
    expect(maxTurn, closeTo(70 * math.pi / 180, 1e-12));
    expect(far.planes.last.quads.first.corners, held.planes.last.quads.first.corners);
  });

  test('a tap picks the nearest box under it, and nothing where there is none', () {
    final boxes = [box(0, 0, 0, 0, 1000, 2000), box(1, 1, 250, 500, 750, 1000, parent: 0)];
    final e = explode(boxes, width: 1000, height: 2000, yaw: 0, pitch: 0, size: view);
    final inner = e.planes.last.quads.single.corners;
    final middle = Offset((inner[0].dx + inner[2].dx) / 2, (inner[0].dy + inner[2].dy) / 2);
    expect(e.at(middle), 1);
    final whole = e.planes.first.quads.single.corners;
    expect(e.at(Offset(whole[0].dx + 1, whole[0].dy + 1)), 0);
    expect(e.at(const Offset(-50, -50)), isNull);
  });

  test('past the box limit nothing is lost and nothing throws', () {
    final many = [for (var i = 0; i < 3000; i++) box(i, i % 64, 0, 0, 100.0 + i, 100.0, parent: i == 0 ? null : i - 1)];
    final e = explode(many, width: 1000, height: 2000, yaw: 0.4, pitch: 0.4, size: view);
    expect(e.planes.fold<int>(0, (n, p) => n + p.quads.length), 3000);
  });
}
