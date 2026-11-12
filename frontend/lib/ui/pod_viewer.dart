import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../config.dart';
import '../state/auth_state.dart';
import 'common.dart';
import 'kit/kit.dart';

class PodViewer extends StatelessWidget {
  const PodViewer({super.key, required this.orderId});

  final String orderId;

  @override
  Widget build(BuildContext context) {
    final api = context.read<AuthState>().api;
    return LoadView<Map<String, dynamic>?>(
      loader: () async {
        try {
          return (await api.get('/orders/$orderId/pod')).map;
        } on ApiException catch (e) {
          if (e.status == 404) {
            return null;
          }
          rethrow;
        }
      },
      builder: (context, pod, reload) {
        if (pod == null) {
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Callout('Chưa có bằng chứng giao hàng.'),
          );
        }
        final evidence = (pod['evidence'] as List).cast<Map<String, dynamic>>();
        return Column(
          children: [
            FormGroup(
              title: 'Thông tin giao',
              children: [
                InfoRow('Thời gian giao', '${formatTime(pod['capturedAt'] as String?)} (máy chủ ghi nhận)'),
                InfoRow('Người nhận', pod['recipientName'] as String?),
                InfoRow('Vị trí GPS', pod['gpsConfirmed'] == true ? 'Đã xác nhận' : 'Chưa xác nhận'),
                InfoRow('Mã OTP', pod['otpStatus'] == 'VERIFIED' ? 'VERIFIED (đã xác minh)' : 'Không dùng'),
                Text('OTP chỉ hiển thị trạng thái đã xác minh, không hiển thị mã gốc.',
                    style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
            FormGroup(
              title: 'Hình ảnh và chữ ký',
              children: [
                if (evidence.isEmpty) const Text('Không có ảnh hoặc chữ ký.'),
                for (final item in evidence)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(item['kind'] == 'SIGNATURE' ? 'Chữ ký người nhận' : 'Ảnh giao hàng',
                            style: Theme.of(context).textTheme.titleSmall),
                        const SizedBox(height: 4),
                        _EvidenceImage(podId: pod['id'] as String, evidenceId: item['id'] as String, kind: item['kind'] as String),
                      ],
                    ),
                  ),
                Text('Liên kết ảnh có thời hạn ngắn; tải lại trang để lấy liên kết mới.',
                    style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ],
        );
      },
    );
  }
}

class _EvidenceImage extends StatefulWidget {
  const _EvidenceImage({required this.podId, required this.evidenceId, required this.kind});

  final String podId;
  final String evidenceId;
  final String kind;

  @override
  State<_EvidenceImage> createState() => _EvidenceImageState();
}

class _EvidenceImageState extends State<_EvidenceImage> {
  late final Future<String> _url = _grant();

  Future<String> _grant() async {
    final api = context.read<AuthState>().api;
    final result = await api.post('/pods/${widget.podId}/evidence/${widget.evidenceId}/access');
    return Uri.parse(apiBaseUrl).origin + (result.map['url'] as String);
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<String>(
      future: _url,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const Text('Không tải được ảnh.');
        }
        if (!snapshot.hasData) {
          return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
        }
        return ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: Image.network(
            snapshot.data!,
            height: 220,
            width: double.infinity,
            fit: widget.kind == 'SIGNATURE' ? BoxFit.contain : BoxFit.cover,
            errorBuilder: (_, _, _) => const SizedBox(height: 120, child: Center(child: Text('Không tải được ảnh.'))),
          ),
        );
      },
    );
  }
}
