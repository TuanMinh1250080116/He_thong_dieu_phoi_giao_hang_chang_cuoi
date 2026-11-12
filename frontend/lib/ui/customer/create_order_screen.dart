import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../config.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../kit/fields.dart';
import '../kit/kit.dart';
import '../kit/map_view.dart';
import 'package:latlong2/latlong.dart';

class CreateOrderScreen extends StatefulWidget {
  const CreateOrderScreen({super.key, this.existing, this.etag});

  final Map<String, dynamic>? existing;
  final String? etag;

  @override
  State<CreateOrderScreen> createState() => _CreateOrderScreenState();
}

class _CreateOrderScreenState extends State<CreateOrderScreen> {
  final _formKey = GlobalKey<FormState>();
  final _pickupAddress = TextEditingController();
  final _pickupLat = TextEditingController();
  final _pickupLng = TextEditingController();
  final _dropoffAddress = TextEditingController();
  final _dropoffLat = TextEditingController();
  final _dropoffLng = TextEditingController();
  final _recipient = TextEditingController();
  final _contact = TextEditingController();
  final _weightKg = TextEditingController(text: '2.5');
  final _length = TextEditingController(text: '30');
  final _width = TextEditingController(text: '20');
  final _height = TextEditingController(text: '15');
  final _note = TextEditingController();

  bool _pinPickup = true;
  DateTime _start = DateTime.now().add(const Duration(hours: 1));
  DateTime _end = DateTime.now().add(const Duration(hours: 5));
  bool _busy = false;
  late final String _idempotencyKey = newIdempotencyKey();

  bool get _editing => widget.existing != null;

  List<TextEditingController> get _all => [
        _pickupAddress,
        _pickupLat,
        _pickupLng,
        _dropoffAddress,
        _dropoffLat,
        _dropoffLng,
        _recipient,
        _contact,
        _weightKg,
        _length,
        _width,
        _height,
        _note,
      ];

  @override
  void initState() {
    super.initState();
    final order = widget.existing;
    if (order == null) {
      return;
    }
    final pickup = order['pickup'] as Map<String, dynamic>;
    final dropoff = order['dropoff'] as Map<String, dynamic>;
    final recipient = order['recipient'] as Map<String, dynamic>;
    final pkg = order['package'] as Map<String, dynamic>;
    final window = order['timeWindow'] as Map<String, dynamic>;
    _pickupAddress.text = '${pickup['address'] ?? ''}';
    _dropoffAddress.text = '${dropoff['address'] ?? ''}';
    final pickupPoint = pickup['point'] as Map<String, dynamic>?;
    final dropoffPoint = dropoff['point'] as Map<String, dynamic>?;
    _pickupLat.text = pickupPoint == null ? '' : '${pickupPoint['lat']}';
    _pickupLng.text = pickupPoint == null ? '' : '${pickupPoint['lng']}';
    _dropoffLat.text = dropoffPoint == null ? '' : '${dropoffPoint['lat']}';
    _dropoffLng.text = dropoffPoint == null ? '' : '${dropoffPoint['lng']}';
    _recipient.text = '${recipient['name'] ?? ''}';
    _contact.text = '${recipient['contact'] ?? ''}';
    _weightKg.text = ((pkg['weightG'] as num) / 1000).toString();
    _length.text = '${pkg['lengthCm'] ?? 30}';
    _width.text = '${pkg['widthCm'] ?? 20}';
    _height.text = '${pkg['heightCm'] ?? 15}';
    _note.text = '${pkg['note'] ?? ''}';
    _start = DateTime.parse(window['start'] as String).toLocal();
    _end = DateTime.parse(window['end'] as String).toLocal();
  }

  @override
  void dispose() {
    for (final c in _all) {
      c.dispose();
    }
    super.dispose();
  }

  void _usePlace(bool pickup, Place place) {
    setState(() {
      (pickup ? _pickupAddress : _dropoffAddress).text = place.name;
      (pickup ? _pickupLat : _dropoffLat).text = place.lat.toString();
      (pickup ? _pickupLng : _dropoffLng).text = place.lng.toString();
    });
  }

  LatLng? _point(TextEditingController lat, TextEditingController lng) {
    final a = double.tryParse(lat.text.trim());
    final b = double.tryParse(lng.text.trim());
    return a == null || b == null ? null : LatLng(a, b);
  }

  void _pinAt(LatLng point) {
    setState(() {
      (_pinPickup ? _pickupLat : _dropoffLat).text = point.latitude.toStringAsFixed(6);
      (_pinPickup ? _pickupLng : _dropoffLng).text = point.longitude.toStringAsFixed(6);
    });
  }

  Future<void> _geocode(bool pickup) async {
    final address = (pickup ? _pickupAddress : _dropoffAddress).text.trim();
    final messenger = ScaffoldMessenger.of(context);
    if (address.length < 3) {
      messenger.showSnackBar(const SnackBar(content: Text('Nhập địa chỉ trước khi tìm.')));
      return;
    }
    final api = context.read<AuthState>().api;
    try {
      final result = await api.get('/geo/geocode', query: {'q': address});
      if (!mounted) {
        return;
      }
      setState(() {
        (pickup ? _pickupLat : _dropoffLat).text = '${result.map['lat']}';
        (pickup ? _pickupLng : _dropoffLng).text = '${result.map['lng']}';
        _pinPickup = pickup;
      });
    } on ApiException catch (e) {
      messenger.showSnackBar(SnackBar(
        content: Text(e.status == 503
            ? 'Máy chủ chưa bật tìm địa chỉ. Hãy chạm vào bản đồ để ghim vị trí.'
            : e.status == 404
                ? 'Không tìm thấy địa chỉ này. Hãy chạm vào bản đồ để ghim vị trí.'
                : e.message),
      ));
    } catch (_) {
      messenger.showSnackBar(const SnackBar(content: Text('Không tìm được địa chỉ lúc này.')));
    }
  }

  Future<void> _pickPlace(bool pickup) async {
    final place = await showModalBottomSheet<Place>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(AppTokens.s4, 0, AppTokens.s4, AppTokens.s2),
              child: Text('Chọn địa điểm mẫu'),
            ),
            for (final p in demoPlaces)
              ListTile(
                title: Text(p.name),
                subtitle: Text('${p.lat}, ${p.lng}'),
                onTap: () => Navigator.pop(context, p),
              ),
          ],
        ),
      ),
    );
    if (place != null) {
      _usePlace(pickup, place);
    }
  }

  Future<void> _save(String saveMode) async {
    if (!_formKey.currentState!.validate()) {
      return;
    }
    if (!_end.isAfter(_start)) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Thời điểm kết thúc phải sau thời điểm bắt đầu.')));
      return;
    }
    final api = context.read<AuthState>().api;
    Map<String, dynamic>? point(TextEditingController lat, TextEditingController lng) =>
        lat.text.trim().isEmpty || lng.text.trim().isEmpty
            ? null
            : {'lat': double.parse(lat.text.trim()), 'lng': double.parse(lng.text.trim())};
    final body = {
      if (!_editing) 'saveMode': saveMode,
      'pickup': {'address': _pickupAddress.text.trim(), 'point': point(_pickupLat, _pickupLng)},
      'dropoff': {'address': _dropoffAddress.text.trim(), 'point': point(_dropoffLat, _dropoffLng)},
      'recipient': {'name': _recipient.text.trim(), 'contact': _contact.text.trim()},
      'package': {
        'weightG': (double.parse(_weightKg.text) * 1000).round(),
        'lengthCm': int.parse(_length.text),
        'widthCm': int.parse(_width.text),
        'heightCm': int.parse(_height.text),
        'note': _note.text.trim().isEmpty ? null : _note.text.trim(),
      },
      'timeWindow': {'start': _start.toUtc().toIso8601String(), 'end': _end.toUtc().toIso8601String()},
    };
    setState(() => _busy = true);
    final result = await guarded(
      context,
      () => _editing
          ? api.put('/orders/${widget.existing!['id']}', body: body, ifMatch: widget.etag)
          : api.post('/orders', body: body, idempotencyKey: _idempotencyKey),
      success: _editing
          ? 'Đã lưu thay đổi.'
          : saveMode == 'SUBMIT' ? 'Đã gửi đơn để điều phối.' : 'Đã lưu nháp.',
    );
    if (!mounted) {
      return;
    }
    setState(() => _busy = false);
    if (result != null) {
      Navigator.of(context).pop(true);
    }
  }

  String? _required(String? value) => value == null || value.trim().isEmpty ? 'Trường bắt buộc.' : null;

  String? _number(String? value) => double.tryParse((value ?? '').replaceAll(',', '.')) == null ? 'Nhập số.' : null;

  String? _coordinate(String? value) {
    if (value == null || value.trim().isEmpty) {
      return null;
    }
    return double.tryParse(value.trim()) == null ? 'Nhập số.' : null;
  }

  Widget _addressBlock({
    required String label,
    required TextEditingController address,
    required TextEditingController lat,
    required TextEditingController lng,
    required bool pickup,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TextFormField(
          controller: address,
          validator: _required,
          textInputAction: TextInputAction.next,
          decoration: InputDecoration(
            labelText: '$label *',
            suffixIcon: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  tooltip: 'Tìm trên bản đồ',
                  icon: const Icon(Icons.travel_explore),
                  onPressed: _busy ? null : () => _geocode(pickup),
                ),
                IconButton(
                  tooltip: 'Chọn địa điểm mẫu',
                  icon: const Icon(Icons.place_outlined),
                  onPressed: _busy ? null : () => _pickPlace(pickup),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: AppTokens.s2),
        Row(
          children: [
            Expanded(
              child: TextFormField(
                controller: lat,
                validator: _coordinate,
                keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                decoration: const InputDecoration(labelText: 'Vĩ độ (lat)'),
              ),
            ),
            const SizedBox(width: AppTokens.s2),
            Expanded(
              child: TextFormField(
                controller: lng,
                validator: _coordinate,
                keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
                decoration: const InputDecoration(labelText: 'Kinh độ (lng)'),
              ),
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final digits = [FilteringTextInputFormatter.allow(RegExp(r'[0-9]'))];
    final zone = widget.existing?['zoneId'];
    return WindowFrame(
      title: _editing ? 'Sửa đơn hàng' : 'Tạo đơn hàng',
      body: Form(
        key: _formKey,
        child: PageBody(
          maxWidth: AppTokens.formMaxWidth,
          children: [
            FormGroup(
              title: 'Điểm lấy và điểm giao hàng',
              children: [
                _addressBlock(label: 'Địa chỉ lấy hàng', address: _pickupAddress, lat: _pickupLat, lng: _pickupLng, pickup: true),
                const FieldGap(size: AppTokens.s4),
                _addressBlock(label: 'Địa chỉ giao hàng', address: _dropoffAddress, lat: _dropoffLat, lng: _dropoffLng, pickup: false),
                const FieldGap(),
                Text('Ghim cho', style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: AppTokens.s1),
                SegmentedButton<bool>(
                  segments: const [
                    ButtonSegment(value: true, label: Text('Điểm lấy (A)'), icon: Icon(Icons.store_outlined)),
                    ButtonSegment(value: false, label: Text('Điểm giao (B)'), icon: Icon(Icons.flag_outlined)),
                  ],
                  selected: {_pinPickup},
                  onSelectionChanged: (v) => setState(() => _pinPickup = v.first),
                ),
                const FieldGap(),
                AppMap(
                  pins: [
                    if (_point(_pickupLat, _pickupLng) != null) MapPin(_point(_pickupLat, _pickupLng)!, 'A', tone: Tone.info),
                    if (_point(_dropoffLat, _dropoffLng) != null) MapPin(_point(_dropoffLat, _dropoffLng)!, 'B', tone: Tone.ok),
                  ],
                  straightLine: [
                    if (_point(_pickupLat, _pickupLng) != null) _point(_pickupLat, _pickupLng)!,
                    if (_point(_dropoffLat, _dropoffLng) != null) _point(_dropoffLat, _dropoffLng)!,
                  ],
                  onTap: _busy ? null : _pinAt,
                  caption: 'Chạm vào bản đồ để ghim cho điểm đang chọn, hoặc bấm biểu tượng tìm cạnh ô địa chỉ.',
                ),
              ],
            ),
            FormGroup(
              title: 'Người nhận',
              children: [
                TextFormField(
                  controller: _recipient,
                  validator: _required,
                  textInputAction: TextInputAction.next,
                  decoration: const InputDecoration(labelText: 'Tên người nhận *'),
                ),
                const FieldGap(),
                TextFormField(
                  controller: _contact,
                  keyboardType: TextInputType.emailAddress,
                  decoration: const InputDecoration(
                    labelText: 'SĐT / Email',
                    helperText: 'Nhập email nếu muốn người nhận xác nhận bằng mã OTP.',
                  ),
                ),
              ],
            ),
            FormGroup(
              title: 'Hàng hóa (Package)',
              children: [
                TextFormField(
                  controller: _weightKg,
                  validator: _number,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(labelText: 'Khối lượng (kg) *'),
                ),
                const FieldGap(),
                Row(
                  children: [
                    for (final entry in {'Dài (cm)': _length, 'Rộng (cm)': _width, 'Cao (cm)': _height}.entries)
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.only(right: AppTokens.s2),
                          child: TextFormField(
                            controller: entry.value,
                            validator: _number,
                            inputFormatters: digits,
                            keyboardType: TextInputType.number,
                            decoration: InputDecoration(labelText: entry.key),
                          ),
                        ),
                      ),
                  ],
                ),
                const FieldGap(),
                TextFormField(controller: _note, decoration: const InputDecoration(labelText: 'Ghi chú')),
              ],
            ),
            FormGroup(
              title: 'Khung giờ và vùng giao',
              children: [
                DateField(label: 'Bắt đầu *', value: _start, withTime: true, onChanged: (v) => setState(() => _start = v ?? _start)),
                const FieldGap(),
                DateField(label: 'Kết thúc *', value: _end, withTime: true, onChanged: (v) => setState(() => _end = v ?? _end)),
                const FieldGap(),
                InfoRow('Vùng giao (Zone)', zone == null ? 'Tự xác định từ tọa độ điểm lấy khi lưu' : 'Đã xác định'),
              ],
            ),
          ],
        ),
      ),
      actions: ActionBar(
        footer: _editing ? 'Trường có dấu * là bắt buộc.' : 'Trạng thái: DRAFT • Trường có dấu * là bắt buộc.',
        children: [
          if (_editing)
            FilledButton(onPressed: _busy ? null : () => _save('DRAFT'), child: const Text('Lưu thay đổi'))
          else ...[
            OutlinedButton(onPressed: _busy ? null : () => _save('DRAFT'), child: const Text('Lưu nháp')),
            FilledButton(onPressed: _busy ? null : () => _save('SUBMIT'), child: const Text('Gửi điều phối')),
          ],
          TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Hủy')),
        ],
      ),
    );
  }
}
