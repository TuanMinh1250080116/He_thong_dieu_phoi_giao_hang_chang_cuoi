import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:logistics_app/theme/app_theme.dart';
import 'package:logistics_app/theme/tokens.dart';
import 'package:logistics_app/ui/kit/kit.dart';

Widget _harness(Brightness brightness, double textScale) {
  return MaterialApp(
    theme: buildTheme(brightness),
    home: Builder(
      builder: (context) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: WindowFrame(
          title: 'Chuyến giao DLV-0042',
          body: PageBody(
            children: [
              FormGroup(
                title: 'Bộ lọc',
                children: [
                  const InfoRow('Mã chuyến / lô', 'DLV-0042 / BAT-0042 với phần mô tả khá dài để kiểm tra việc xuống dòng của nhãn'),
                  const Callout('Chưa chấp nhận thì không thể bắt đầu giao.', tone: Tone.warn),
                  ResultGrid<Map<String, String>>(
                    rows: const [
                      {'code': 'ORD-1187', 'addr': '45 Lê Lợi, Quận 1, TP.HCM', 'status': 'COMPLETED'},
                      {'code': 'ORD-1188', 'addr': '8 Hai Bà Trưng, Quận 1, TP.HCM', 'status': 'BLOCKED'},
                    ],
                    columns: [
                      GridColumn(title: 'Đơn', primary: true, cell: (c, r) => Text(r['code']!)),
                      GridColumn(title: 'Địa chỉ', flex: 2, cell: (c, r) => Text(r['addr']!)),
                      GridColumn(title: 'Trạng thái', flex: 1.2, cell: (c, r) => StatusBadge(label: r['status']!, tone: Tone.ok)),
                    ],
                  ),
                ],
              ),
            ],
          ),
          actions: ActionBar(
            footer: 'Hoàn tất chỉ bật khi mọi điểm giao xong, đủ bằng chứng và không còn sự cố chặn.',
            children: [
              FilledButton(onPressed: () {}, child: const Text('Chấp nhận')),
              OutlinedButton(onPressed: () {}, child: const Text('Từ chối...')),
              OutlinedButton(onPressed: () {}, child: const Text('Sự cố của chuyến')),
              TextButton(onPressed: () {}, child: const Text('Đóng')),
            ],
          ),
        ),
      ),
    ),
  );
}

void main() {
  for (final size in const [Size(360, 740), Size(1200, 800)]) {
    for (final scale in const [1.0, 1.6]) {
      for (final brightness in Brightness.values) {
        testWidgets('kit lays out without overflow at ${size.width}px, scale $scale, $brightness', (tester) async {
          tester.view.physicalSize = size;
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.reset);
          await tester.pumpWidget(_harness(brightness, scale));
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
          expect(find.text('Chấp nhận'), findsOneWidget);
          expect(find.text('ORD-1187'), findsOneWidget);
        });
      }
    }
  }
}
