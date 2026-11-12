import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

class SignatureController extends ChangeNotifier {
  final List<List<Offset>> _strokes = [];

  bool get isEmpty => _strokes.isEmpty;

  void start(Offset point) {
    _strokes.add([point]);
    notifyListeners();
  }

  void extend(Offset point) {
    if (_strokes.isEmpty) {
      return;
    }
    _strokes.last.add(point);
    notifyListeners();
  }

  void clear() {
    _strokes.clear();
    notifyListeners();
  }

  Future<Uint8List?> toPng(Size size) async {
    if (_strokes.isEmpty) {
      return null;
    }
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFFFFFFFF));
    _paint(canvas, const Color(0xFF111111));
    final image = await recorder.endRecording().toImage(size.width.ceil(), size.height.ceil());
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    return data?.buffer.asUint8List();
  }

  void _paint(Canvas canvas, Color color) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 2.5
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;
    for (final stroke in _strokes) {
      if (stroke.length == 1) {
        canvas.drawPoints(ui.PointMode.points, stroke, paint);
        continue;
      }
      final path = Path()..moveTo(stroke.first.dx, stroke.first.dy);
      for (final point in stroke.skip(1)) {
        path.lineTo(point.dx, point.dy);
      }
      canvas.drawPath(path, paint);
    }
  }
}

class SignaturePad extends StatelessWidget {
  const SignaturePad({super.key, required this.controller, this.height = 160, this.enabled = true, this.onSized});

  final SignatureController controller;
  final double height;
  final bool enabled;
  final ValueChanged<Size>? onSized;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    return Semantics(
      label: 'Khung chữ ký người nhận',
      child: LayoutBuilder(
        builder: (context, constraints) {
          final size = Size(constraints.maxWidth, height);
          onSized?.call(size);
          return Container(
            height: height,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surface,
              border: Border.all(color: tokens.lineStrong),
              borderRadius: BorderRadius.circular(AppTokens.radius),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(AppTokens.radius),
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onPanStart: enabled ? (d) => controller.start(d.localPosition) : null,
                onPanUpdate: enabled ? (d) => controller.extend(d.localPosition) : null,
                child: ListenableBuilder(
                  listenable: controller,
                  builder: (context, _) => CustomPaint(
                    size: size,
                    painter: _SignaturePainter(controller, tokens.ink),
                    child: controller.isEmpty
                        ? Center(child: Text('Ký tại đây', style: Theme.of(context).textTheme.bodySmall))
                        : null,
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

class _SignaturePainter extends CustomPainter {
  _SignaturePainter(this.controller, this.color) : super(repaint: controller);

  final SignatureController controller;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) => controller._paint(canvas, color);

  @override
  bool shouldRepaint(covariant _SignaturePainter oldDelegate) => true;
}
