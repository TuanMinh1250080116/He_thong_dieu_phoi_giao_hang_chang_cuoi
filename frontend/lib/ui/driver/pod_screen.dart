
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/kit.dart';
import '../kit/signature_pad.dart';

class PodScreen extends StatefulWidget {
  const PodScreen({
    super.key,
    required this.deliveryId,
    required this.stopId,
    required this.address,
    this.orderCode,
    this.stopLabel,
  });

  final String deliveryId;
  final String stopId;
  final String address;
  final String? orderCode;
  final String? stopLabel;

  @override
  State<PodScreen> createState() => _PodScreenState();
}

class _PodScreenState extends State<PodScreen> {
  final _recipient = TextEditingController();
  final _code = TextEditingController();
  final _picker = ImagePicker();
  final _signature = SignatureController();
  Size _signatureSize = const Size(320, 160);
  late final String _podKey = newIdempotencyKey();
  String? _challengeId;
  bool _otpVerified = false;

  Uint8List? _photo;
  String? _mime;
  double? _lat;
  double? _lng;
  double? _accuracy;
  bool _direct = true;
  bool _busy = false;
  String _progress = '';

  @override
  void dispose() {
    _recipient.dispose();
    _code.dispose();
    _signature.dispose();
    super.dispose();
  }

  Future<void> _sendOtp() async {
    final api = context.read<AuthState>().api;
    final result = await guarded(
      context,
      () => api.post('/deliveries/${widget.deliveryId}/stops/${widget.stopId}/otp-challenges', idempotent: true),
      success: 'Đã gửi mã tới email người nhận.',
    );
    if (result != null && mounted) {
      setState(() {
        _challengeId = result.map['challengeId'] as String;
        _otpVerified = false;
        _code.clear();
      });
    }
  }

  Future<void> _verifyOtp() async {
    final api = context.read<AuthState>().api;
    final id = _challengeId;
    if (id == null || _code.text.trim().isEmpty) {
      return;
    }
    final result = await guarded(
      context,
      () => api.post('/otp-challenges/$id/verify', body: {'code': _code.text.trim()}),
      success: 'Mã đã được xác minh.',
    );
    if (result != null && mounted) {
      setState(() => _otpVerified = true);
    }
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

  Future<void> _locate() async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      if (!await Geolocator.isLocationServiceEnabled()) {
        messenger.showSnackBar(const SnackBar(content: Text('Hãy bật định vị (GPS) trên thiết bị.')));
        return;
      }
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) {
        messenger.showSnackBar(const SnackBar(content: Text('Ứng dụng chưa được cấp quyền vị trí.')));
        return;
      }
      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.high, timeLimit: Duration(seconds: 15)),
      );
      setState(() {
        _lat = position.latitude;
        _lng = position.longitude;
        _accuracy = position.accuracy;
      });
    } catch (_) {
      messenger.showSnackBar(const SnackBar(content: Text('Không lấy được vị trí. Thử lại ở nơi thoáng hơn.')));
    }
  }

  void _useTestLocation() {
    setState(() {
      _lat = 10.7725;
      _lng = 106.6980;
      _accuracy = 10;
    });
  }

  Future<String> _upload(ApiClient api, Uint8List bytes, String mime, String kind) async {
    final ticket = await api.post('/deliveries/${widget.deliveryId}/evidence-uploads', body: {
      'purpose': 'POD',
      'kind': kind,
      'stopId': widget.stopId,
      'mimeType': mime,
      'sizeBytes': bytes.length,
    });
    final evidenceId = ticket.map['evidenceId'] as String;
    await api.putBytes(ticket.map['uploadUrl'] as String, bytes, mime);
    await api.post('/evidence/$evidenceId/complete', body: {'sha256': sha256.convert(bytes).toString()});
    return evidenceId;
  }

  Future<void> _submit() async {
    final signaturePng = await _signature.toPng(_signatureSize);
    final hasEvidence = _photo != null || _otpVerified || signaturePng != null;
    if (!hasEvidence || _lat == null || !mounted) {
      return;
    }
    final api = context.read<AuthState>().api;
    final photo = _photo;
    final mime = _mime;
    setState(() => _busy = true);
    final ok = await guarded(context, () async {
      final evidenceIds = <String>[];
      if (photo != null && mime != null) {
        setState(() => _progress = 'Đang tải ảnh lên...');
        evidenceIds.add(await _upload(api, photo, mime, 'PHOTO'));
      }
      if (signaturePng != null) {
        setState(() => _progress = 'Đang tải chữ ký lên...');
        evidenceIds.add(await _upload(api, signaturePng, 'image/png', 'SIGNATURE'));
      }
      setState(() => _progress = 'Đang nộp bằng chứng...');
      await api.post(
        '/deliveries/${widget.deliveryId}/stops/${widget.stopId}/pod',
        idempotencyKey: _podKey,
        body: {
          'gps': {
            'point': {'lat': _lat, 'lng': _lng},
            'accuracyM': _accuracy,
          },
          'isDirectHandoff': _direct,
          'recipientName': _recipient.text.trim().isEmpty ? null : _recipient.text.trim(),
          'evidenceIds': evidenceIds,
          'otpChallengeId': _otpVerified ? _challengeId : null,
        },
      );
      return true;
    }, success: 'Đã nộp bằng chứng giao hàng.');
    if (!mounted) {
      return;
    }
    setState(() {
      _busy = false;
      _progress = '';
    });
    if (ok == true) {
      Navigator.of(context).pop(true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return WindowFrame(
      title: 'Nộp bằng chứng giao hàng',
      body: PageBody(
        maxWidth: AppTokens.formMaxWidth,
        children: [
          FormGroup(
            title: 'Điểm giao',
            children: [
              InfoRow('Điểm giao / đơn', '${widget.stopLabel ?? 'Điểm giao'}${widget.orderCode == null ? '' : ' / ${widget.orderCode}'}'),
              InfoRow('Địa chỉ', widget.address),
              const InfoRow('Yêu cầu bằng chứng', 'GPS và ít nhất một trong: OTP, ảnh, chữ ký'),
            ],
          ),
          FormGroup(
            title: 'Người nhận',
            children: [
              TextField(
                controller: _recipient,
                textInputAction: TextInputAction.done,
                decoration: const InputDecoration(labelText: 'Tên người nhận'),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Giao trực tiếp cho người nhận'),
                value: _direct,
                onChanged: _busy ? null : (v) => setState(() => _direct = v),
              ),
            ],
          ),
          FormGroup(
            title: 'Bằng chứng',
            children: [
              Text('Mã OTP', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: AppTokens.s2),
              if (_otpVerified)
                const StatusBadge(label: 'Mã OTP đã được xác minh', tone: Tone.ok, icon: Icons.verified_outlined)
              else ...[
                Wrap(
                  spacing: AppTokens.s2,
                  runSpacing: AppTokens.s2,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    OutlinedButton.icon(
                      icon: const Icon(Icons.mail_outline),
                      onPressed: _busy ? null : _sendOtp,
                      label: Text(_challengeId == null ? 'Gửi mã tới người nhận' : 'Gửi lại mã'),
                    ),
                    if (_challengeId != null) ...[
                      SizedBox(
                        width: 160,
                        child: TextField(
                          controller: _code,
                          keyboardType: TextInputType.number,
                          maxLength: 6,
                          decoration: const InputDecoration(labelText: 'Mã OTP', counterText: ''),
                        ),
                      ),
                      FilledButton(onPressed: _busy ? null : _verifyOtp, child: const Text('Xác minh')),
                    ],
                  ],
                ),
              ],
              const Divider(height: AppTokens.s5),
              Text('Ảnh giao hàng', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: AppTokens.s2),
              if (_photo != null)
                ClipRRect(
                  borderRadius: BorderRadius.circular(AppTokens.radius),
                  child: Image.memory(_photo!, height: 200, width: double.infinity, fit: BoxFit.cover),
                ),
              if (_photo != null) const SizedBox(height: AppTokens.s2),
              Wrap(
                spacing: AppTokens.s2,
                runSpacing: AppTokens.s2,
                children: [
                  OutlinedButton.icon(
                    icon: const Icon(Icons.camera_alt_outlined),
                    onPressed: _busy ? null : () => _pick(ImageSource.camera),
                    label: const Text('Chụp ảnh'),
                  ),
                  OutlinedButton.icon(
                    icon: const Icon(Icons.photo_library_outlined),
                    onPressed: _busy ? null : () => _pick(ImageSource.gallery),
                    label: const Text('Chọn ảnh'),
                  ),
                  if (_photo != null)
                    TextButton(onPressed: _busy ? null : () => setState(() => _photo = null), child: const Text('Xóa ảnh')),
                ],
              ),
              const Divider(height: AppTokens.s5),
              Text('Chữ ký người nhận', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: AppTokens.s2),
              SignaturePad(
                controller: _signature,
                enabled: !_busy,
                onSized: (size) => _signatureSize = size,
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: ListenableBuilder(
                  listenable: _signature,
                  builder: (context, _) => TextButton(
                    onPressed: _busy || _signature.isEmpty ? null : () => setState(_signature.clear),
                    child: const Text('Xóa ký'),
                  ),
                ),
              ),
            ],
          ),
          FormGroup(
            title: 'Vị trí GPS hiện tại',
            children: [
              InfoRow(
                'Tọa độ',
                _lat == null ? 'Chưa có vị trí GPS' : '${_lat!.toStringAsFixed(5)}, ${_lng!.toStringAsFixed(5)}',
              ),
              InfoRow('Độ chính xác', _accuracy == null ? null : '± ${_accuracy!.toStringAsFixed(0)} m'),
              Wrap(
                spacing: AppTokens.s2,
                runSpacing: AppTokens.s2,
                children: [
                  OutlinedButton.icon(
                    icon: const Icon(Icons.my_location),
                    onPressed: _busy ? null : _locate,
                    label: Text(_lat == null ? 'Lấy vị trí' : 'Lấy lại vị trí'),
                  ),
                  if (kDebugMode)
                    TextButton(onPressed: _busy ? null : _useTestLocation, child: const Text('Vị trí thử nghiệm (debug)')),
                ],
              ),
            ],
          ),
        ],
      ),
      actions: ActionBar(
        footer: _busy ? _progress : 'Thời gian ghi nhận do máy chủ cấp khi bạn bấm Nộp.',
        children: [
          ListenableBuilder(
            listenable: _signature,
            builder: (context, _) => FilledButton.icon(
              icon: const Icon(Icons.cloud_upload_outlined),
              onPressed: (_photo != null || _otpVerified || !_signature.isEmpty) && _lat != null && !_busy ? _submit : null,
              label: Text(_busy ? 'Đang nộp...' : 'Nộp bằng chứng'),
            ),
          ),
          TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Hủy')),
        ],
      ),
    );
  }
}
