import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import 'exception_text.dart';

class ReportExceptionScreen extends StatefulWidget {
  const ReportExceptionScreen({
    super.key,
    required this.deliveryId,
    required this.stopId,
    required this.address,
    this.deliveryCode,
    this.orderCode,
  });

  final String deliveryId;
  final String stopId;
  final String address;
  final String? deliveryCode;
  final String? orderCode;

  @override
  State<ReportExceptionScreen> createState() => _ReportExceptionScreenState();
}

class _ReportExceptionScreenState extends State<ReportExceptionScreen> {
  final _description = TextEditingController();
  late final String _key = newIdempotencyKey();
  late final Future<List<Map<String, dynamic>>> _types = _loadTypes();
  final _picker = ImagePicker();
  String? _type;
  String _severity = 'MEDIUM';
  Uint8List? _photo;
  String? _mime;
  bool _busy = false;

  Future<List<Map<String, dynamic>>> _loadTypes() async {
    final api = context.read<AuthState>().api;
    return ((await api.get('/catalogs/exception-types')).map['items'] as List).cast<Map<String, dynamic>>();
  }

  @override
  void dispose() {
    _description.dispose();
    super.dispose();
  }

  static String? _detectMime(Uint8List bytes) {
    if (bytes.length > 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) {
      return 'image/jpeg';
    }
    if (bytes.length > 4 && bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
      return 'image/png';
    }
    return null;
  }

  Future<void> _pick(ImageSource source) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final file = await _picker.pickImage(source: source, maxWidth: 1600, imageQuality: 80);
      if (file == null) {
        return;
      }
      final bytes = await file.readAsBytes();
      final mime = _detectMime(bytes);
      if (mime == null) {
        messenger.showSnackBar(const SnackBar(content: Text('Chỉ nhận ảnh JPEG hoặc PNG.')));
        return;
      }
      setState(() {
        _photo = bytes;
        _mime = mime;
      });
    } catch (_) {
      messenger.showSnackBar(const SnackBar(content: Text('Không mở được camera hoặc thư viện ảnh.')));
    }
  }

  Future<void> _submit() async {
    final api = context.read<AuthState>().api;
    final photo = _photo;
    final mime = _mime;
    setState(() => _busy = true);
    final result = await guarded(context, () async {
      final evidenceIds = <String>[];
      if (photo != null && mime != null) {
        final ticket = await api.post('/deliveries/${widget.deliveryId}/evidence-uploads', body: {
          'purpose': 'EXCEPTION',
          'kind': 'PHOTO',
          'mimeType': mime,
          'sizeBytes': photo.length,
        });
        final evidenceId = ticket.map['evidenceId'] as String;
        await api.putBytes(ticket.map['uploadUrl'] as String, photo, mime);
        await api.post('/evidence/$evidenceId/complete', body: {'sha256': sha256.convert(photo).toString()});
        evidenceIds.add(evidenceId);
      }
      return api.post('/deliveries/${widget.deliveryId}/exceptions', idempotencyKey: _key, body: {
        'type': _type,
        'description': _description.text.trim(),
        'stopId': widget.stopId,
        'severity': _severity,
        'evidenceIds': evidenceIds,
      });
    });
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (result != null) {
      final code = (result.map['data'] as Map<String, dynamic>)['code'];
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Đã gửi báo cáo $code. Quản lý vận hành sẽ xử lý.')),
      );
      Navigator.of(context).pop(true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ready = _type != null && _description.text.trim().isNotEmpty && !_busy;
    return WindowFrame(
      title: 'Báo cáo sự cố',
      body: PageBody(
        maxWidth: AppTokens.formMaxWidth,
        children: [
          FormGroup(
            title: 'Liên quan',
            children: [
              InfoRow('Chuyến giao', widget.deliveryCode),
              InfoRow('Điểm giao / Đơn', '${widget.orderCode ?? ''} ${widget.address}'.trim()),
            ],
          ),
          FormGroup(
            title: 'Nội dung sự cố',
            children: [
              FutureBuilder<List<Map<String, dynamic>>>(
                future: _types,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return const Callout('Không tải được danh sách loại sự cố.', tone: Tone.danger);
                  }
                  if (!snapshot.hasData) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  final types = snapshot.data!;
                  final chosen = types.where((t) => t['code'] == _type).toList();
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      DropdownButtonFormField<String>(
                        initialValue: _type,
                        isExpanded: true,
                        decoration: const InputDecoration(labelText: 'Loại sự cố *'),
                        items: [
                          for (final t in types)
                            DropdownMenuItem(
                              value: t['code'] as String,
                              child: Text(typeLabel(t['code'] as String, t['name'] as String)),
                            ),
                        ],
                        onChanged: _busy ? null : (value) => setState(() => _type = value),
                      ),
                      if (chosen.isNotEmpty) ...[
                        const FieldGap(),
                        StatusBadge(
                          label: 'Ảnh hưởng: ${impactText[chosen.first['impact']] ?? chosen.first['impact']}',
                          tone: chosen.first['impact'] == 'NON_BLOCKING' ? Tone.neutral : Tone.warn,
                          icon: Icons.info_outline,
                        ),
                      ],
                    ],
                  );
                },
              ),
              const FieldGap(),
              ChoiceField<String>(
                label: 'Mức độ',
                value: _severity,
                options: severityText,
                onChanged: (v) => setState(() => _severity = v),
              ),
              const FieldGap(),
              TextField(
                controller: _description,
                maxLines: 4,
                decoration: const InputDecoration(labelText: 'Mô tả *'),
                onChanged: (_) => setState(() {}),
              ),
            ],
          ),
          FormGroup(
            title: 'Ảnh minh chứng (tùy chọn)',
            children: [
              if (_photo != null) ...[
                ClipRRect(
                  borderRadius: BorderRadius.circular(AppTokens.radius),
                  child: Image.memory(_photo!, height: 180, width: double.infinity, fit: BoxFit.cover),
                ),
                const FieldGap(),
              ],
              Wrap(
                spacing: AppTokens.s2,
                runSpacing: AppTokens.s2,
                children: [
                  OutlinedButton.icon(
                    icon: const Icon(Icons.photo_camera_outlined),
                    onPressed: _busy ? null : () => _pick(ImageSource.camera),
                    label: Text(_photo == null ? 'Chụp ảnh' : 'Chụp lại'),
                  ),
                  OutlinedButton.icon(
                    icon: const Icon(Icons.photo_library_outlined),
                    onPressed: _busy ? null : () => _pick(ImageSource.gallery),
                    label: const Text('Chọn ảnh...'),
                  ),
                  if (_photo != null)
                    TextButton(onPressed: _busy ? null : () => setState(() => _photo = null), child: const Text('Bỏ ảnh')),
                ],
              ),
            ],
          ),
        ],
      ),
      actions: ActionBar(
        footer: 'Sự cố mới sẽ ở trạng thái OPEN • Trường có dấu * là bắt buộc',
        children: [
          FilledButton.icon(
            icon: const Icon(Icons.send),
            onPressed: ready ? _submit : null,
            label: Text(_busy ? 'Đang gửi...' : 'Gửi báo cáo'),
          ),
          TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Hủy')),
        ],
      ),
    );
  }
}
