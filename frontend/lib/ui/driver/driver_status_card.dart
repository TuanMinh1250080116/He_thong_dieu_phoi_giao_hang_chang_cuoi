import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:provider/provider.dart';

import '../../api/error_text.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/kit.dart';

const _availabilityLabel = <String, String>{
  'OFFLINE': 'Ngoại tuyến',
  'AVAILABLE': 'Trực tuyến, sẵn sàng nhận chuyến',
  'RESERVED': 'Đã được giữ chỗ cho một chuyến',
  'BUSY': 'Đang thực hiện chuyến giao',
};

class DriverStatusCard extends StatefulWidget {
  const DriverStatusCard({super.key, this.waitingCount, this.runningCount});

  final int? waitingCount;
  final int? runningCount;

  @override
  State<DriverStatusCard> createState() => _DriverStatusCardState();
}

class _DriverStatusCardState extends State<DriverStatusCard> {
  Map<String, dynamic>? _driver;
  String? _etag;
  String? _error;
  String? _choice;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final api = context.read<AuthState>().api;
    try {
      final result = await api.get('/me/driver');
      if (mounted) {
        setState(() {
          _driver = result.map;
          _etag = result.etag;
          _choice = result.map['availability'] as String;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() => _error = describeError(e));
      }
    }
  }

  Future<void> _apply() async {
    final api = context.read<AuthState>().api;
    setState(() => _busy = true);
    final result = await guarded(
      context,
      () => api.put('/me/driver/availability', body: {'availability': _choice}, ifMatch: _etag),
      success: 'Đã cập nhật trạng thái làm việc.',
    );
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (result != null) {
      setState(() {
        _driver = result.map;
        _etag = result.etag;
        _choice = result.map['availability'] as String;
      });
    } else {
      await _load();
    }
  }

  Future<void> _send(double lat, double lng, double accuracy) async {
    final api = context.read<AuthState>().api;
    setState(() => _busy = true);
    final done = await guarded(
      context,
      () => api.put('/me/driver/location', body: {
        'point': {'lat': lat, 'lng': lng},
        'accuracyM': accuracy,
      }),
      success: 'Đã cập nhật vị trí.',
    );
    if (mounted) {
      setState(() => _busy = false);
      if (done != null) {
        await _load();
      }
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
      await _send(position.latitude, position.longitude, position.accuracy);
    } catch (_) {
      messenger.showSnackBar(const SnackBar(content: Text('Không lấy được vị trí. Thử lại ở nơi thoáng hơn.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final driver = _driver;
    if (driver == null) {
      return _error == null
          ? const Padding(padding: EdgeInsets.all(AppTokens.s5), child: Center(child: CircularProgressIndicator()))
          : Callout(_error!, tone: Tone.danger);
    }
    final availability = driver['availability'] as String;
    final active = driver['active'] == true;
    final system = availability == 'RESERVED' || availability == 'BUSY';
    final location = driver['location'] as Map<String, dynamic>?;
    final vehicle = driver['vehicle'] as Map<String, dynamic>?;
    final pending = widget.waitingCount ?? (driver['pendingAssignments'] as num?)?.toInt() ?? 0;
    final running = widget.runningCount;
    final changed = _choice != availability;
    return FormGroup(
      title: 'Trạng thái làm việc',
      children: [
        InfoRow('Hiện tại', _availabilityLabel[availability] ?? availability),
        InfoRow('Đồng bộ lần cuối', location == null ? 'Chưa có vị trí nào được gửi' : formatTime(location['recordedAt'] as String?)),
        InfoRow('Phương tiện', vehicle == null ? 'Chưa được gán phương tiện' : '${vehicle['code']} (${vehicle['vehicleType']})'),
        const FieldGap(),
        if (!active) const Callout('Tài khoản tài xế đang bị vô hiệu hóa.', tone: Tone.danger),
        RadioGroup<String>(
          groupValue: _choice,
          onChanged: (value) {
            if (!_busy && active && !system) {
              setState(() => _choice = value);
            }
          },
          child: Column(
            children: [
              RadioListTile<String>(
                contentPadding: EdgeInsets.zero,
                value: 'AVAILABLE',
                enabled: active && !system && !_busy,
                title: const Text('Trực tuyến, sẵn sàng nhận chuyến'),
              ),
              RadioListTile<String>(
                contentPadding: EdgeInsets.zero,
                value: 'OFFLINE',
                enabled: active && !system && !_busy,
                title: const Text('Ngoại tuyến'),
              ),
            ],
          ),
        ),
        Text('RESERVED và BUSY do hệ thống tự đặt khi có chuyến.', style: Theme.of(context).textTheme.bodySmall),
        const FieldGap(),
        InfoRow('Chuyến chờ phản hồi', pending == 0 ? 'Không có' : '$pending chuyến'),
        if (running != null) InfoRow('Chuyến đang giao', running == 0 ? 'Không có' : '$running chuyến'),
        const FieldGap(),
        Wrap(
          spacing: AppTokens.s2,
          runSpacing: AppTokens.s2,
          children: [
            FilledButton(onPressed: changed && !_busy && !system ? _apply : null, child: const Text('Cập nhật')),
            OutlinedButton.icon(
              icon: const Icon(Icons.my_location),
              onPressed: _busy ? null : _locate,
              label: const Text('Cập nhật vị trí'),
            ),
            if (kDebugMode)
              TextButton(
                onPressed: _busy ? null : () => _send(10.77, 106.70, 10),
                child: const Text('Vị trí thử nghiệm (debug)'),
              ),
          ],
        ),
      ],
    );
  }
}
