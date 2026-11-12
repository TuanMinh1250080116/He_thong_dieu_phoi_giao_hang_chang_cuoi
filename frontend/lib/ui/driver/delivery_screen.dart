import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../dialogs.dart';
import '../exceptions/exceptions_screen.dart';
import '../exceptions/report_exception_screen.dart';
import '../kit/kit.dart';
import '../kit/map_view.dart';
import 'package:latlong2/latlong.dart';
import 'pod_screen.dart';

class _DeliveryData {
  _DeliveryData(this.overview, this.delivery, this.etag);

  final Map<String, dynamic> overview;
  final Map<String, dynamic> delivery;
  final String? etag;
}

class DeliveryScreen extends StatefulWidget {
  const DeliveryScreen({super.key, required this.deliveryId});

  final String deliveryId;

  @override
  State<DeliveryScreen> createState() => _DeliveryScreenState();
}

class _DeliveryScreenState extends State<DeliveryScreen> {
  int _reloadKey = 0;
  String? _selectedStop;
  Future<List<LatLng>?>? _route;
  String? _routeKey;

  Future<_DeliveryData> _load() async {
    final api = context.read<AuthState>().api;
    final overview = (await api.get('/me/deliveries/${widget.deliveryId}')).map;
    final detail = await api.get('/deliveries/${widget.deliveryId}');
    return _DeliveryData(overview, detail.map, detail.etag);
  }

  Future<void> _run(Future<Object?> Function() action, {String? success}) async {
    final done = await guarded(context, action, success: success);
    if (done != null && mounted) {
      setState(() => _reloadKey++);
    }
  }

  Future<void> _reject(ApiClient api, Map<String, dynamic> overview) async {
    final reasons = await guarded(context, () async {
      final result = await api.get('/catalogs/rejection-reasons');
      return (result.map['items'] as List).cast<Map<String, dynamic>>();
    });
    if (reasons == null || !mounted) {
      return;
    }
    final choice = await askRejection(context, reasons, summary: [
      ('Mã chuyến', '${overview['code']}'),
      ('Số điểm giao', '${(overview['stops'] as List).length} điểm — ${overview['zoneName'] ?? overview['zoneCode']}'),
      ('Khung giờ', _window(overview)),
    ]);
    if (choice == null || !mounted) {
      return;
    }
    await _run(
      () => api.post('/assignments/${overview['assignmentId']}/reject',
          body: {'reasonCode': choice.code, 'description': choice.description},
          ifMatch: '"${overview['decisionVersion']}"',
          idempotent: true),
      success: 'Đã từ chối chuyến.',
    );
  }

  String _window(Map<String, dynamic> overview) {
    final start = overview['windowStart'] as String?;
    final end = overview['windowEnd'] as String?;
    if (start == null || end == null) {
      return '—';
    }
    return '${formatTime(start)} – ${formatTime(end).split(' ').last}';
  }

  @override
  Widget build(BuildContext context) {
    final api = context.read<AuthState>().api;
    final base = '/deliveries/${widget.deliveryId}';
    return LoadView<_DeliveryData>(
      key: ValueKey(_reloadKey),
      loader: _load,
      builder: (context, data, reload) {
        final overview = data.overview;
        final delivery = data.delivery;
        final status = delivery['status'] as String;
        final capabilities = (delivery['capabilities'] as List).cast<String>();
        final blocked = (delivery['blockedBy'] as List?)?.cast<Map<String, dynamic>>() ?? const [];
        final live = {for (final s in (delivery['stops'] as List).cast<Map<String, dynamic>>()) s['id'] as String: s};
        final stops = (overview['stops'] as List).cast<Map<String, dynamic>>();
        final pending = overview['assignmentStatus'] == 'PENDING';
        final selected = stops.where((s) => s['id'] == _selectedStop).firstOrNull;
        final selectedLive = selected == null ? null : live[selected['id']];
        final inProgress = status == 'IN_PROGRESS';
        return WindowFrame(
          title: 'Chuyến giao ${overview['code']}',
          body: PageBody(
            onRefresh: reload,
            children: [
              FormGroup(
                title: 'Chuyến giao',
                trailing: StatusChip(pending ? 'PENDING' : status),
                children: [
                  InfoRow('Mã chuyến / lô', '${overview['code']} / ${overview['batchCode']}'),
                  InfoRow('Khung giờ', _window(overview)),
                  InfoRow('Khu vực', '${overview['zoneName'] ?? overview['zoneCode']}'),
                  InfoRow('Phương tiện', overview['vehicleCode'] == null ? null : '${overview['vehicleCode']} — ${overview['vehicleType']}'),
                  InfoRow('Tiến độ', '${delivery['completedStops']} / ${stops.length} điểm giao'),
                  if (pending) const Callout('Chuyến đang chờ bạn phản hồi. Chưa chấp nhận thì không thể bắt đầu giao.', tone: Tone.warn),
                  if (status == 'AWAITING_REASSIGNMENT')
                    const Callout('Chuyến này đang chờ điều phối viên gán lại và không còn thuộc về bạn.', tone: Tone.info),
                ],
              ),
              Builder(builder: (context) {
                final points = [for (final s in stops) if (latLngOf(s) != null) latLngOf(s)!];
                if (points.isEmpty) {
                  return const SizedBox.shrink();
                }
                final key = points.map((p) => '${p.latitude},${p.longitude}').join('|');
                if (_routeKey != key) {
                  _routeKey = key;
                  _route = fetchRoute(points);
                }
                return FormGroup(
                  title: 'Lộ trình các điểm giao',
                  children: [
                    FutureBuilder<List<LatLng>?>(
                      future: _route,
                      builder: (context, snapshot) => AppMap(
                        height: 280,
                        pins: [
                          for (final s in stops)
                            if (latLngOf(s) != null)
                              MapPin(
                                latLngOf(s)!,
                                '${s['sequenceNo']}',
                                tone: switch ((live[s['id']]?['status'] ?? s['status']) as String) {
                                  'COMPLETED' => Tone.ok,
                                  'BLOCKED' => Tone.danger,
                                  'IN_PROGRESS' => Tone.warn,
                                  _ => Tone.info,
                                },
                              ),
                        ],
                        route: snapshot.data,
                        straightLine: points,
                        caption: snapshot.connectionState != ConnectionState.done
                            ? 'Đang tính lộ trình...'
                            : snapshot.data == null
                                ? 'Không lấy được lộ trình đường bộ, đang nối thẳng các điểm theo thứ tự.'
                                : 'Lộ trình đường bộ theo thứ tự điểm giao (OSRM, chỉ để tham khảo).',
                      ),
                    ),
                  ],
                );
              }),
              FormGroup(
                title: 'Điểm giao (${stops.length})',
                children: [
                  ResultGrid<Map<String, dynamic>>(
                    rows: stops,
                    isSelected: (row) => row['id'] == _selectedStop,
                    onTap: (row) => setState(() => _selectedStop = row['id'] as String),
                    columns: [
                      GridColumn(title: 'STT', flex: 0.5, cell: (c, r) => Text('${r['sequenceNo']}')),
                      GridColumn(title: 'Đơn', primary: true, flex: 1.1, cell: (c, r) => Text('${r['orderCode']}')),
                      GridColumn(title: 'Địa chỉ', flex: 2, cell: (c, r) => Text('${r['address']}')),
                      GridColumn(
                        title: 'Khung giờ',
                        flex: 1.3,
                        cell: (c, r) => Text('${formatTime(r['windowStart'] as String?)} – ${formatTime(r['windowEnd'] as String?).split(' ').last}'),
                      ),
                      GridColumn(title: 'Khối lượng', flex: 0.8, cell: (c, r) => Text('${(r['weightG'] as num) / 1000} kg')),
                      GridColumn(
                        title: 'Trạng thái',
                        flex: 1.2,
                        cell: (c, r) => StatusChip((live[r['id']]?['status'] ?? r['status']) as String),
                      ),
                      GridColumn(title: 'Sự cố', flex: 0.9, cell: (c, r) => Text('${r['exceptionCodes'] ?? '—'}')),
                    ],
                  ),
                ],
              ),
              if (inProgress)
                FormGroup(
                  title: selected == null ? 'Thao tác với điểm giao' : 'Thao tác với điểm giao #${selected['sequenceNo']}',
                  children: [
                    if (selected == null)
                      const Text('Chọn một điểm giao trong bảng để thao tác.')
                    else
                      Wrap(
                        spacing: AppTokens.s2,
                        runSpacing: AppTokens.s2,
                        children: [
                          if (selectedLive?['status'] == 'PENDING')
                            OutlinedButton.icon(
                              icon: const Icon(Icons.navigation_outlined),
                              onPressed: () => _run(() => api.post('$base/stops/${selected['id']}/actions',
                                  body: {'action': 'START_STOP'}, ifMatch: data.etag)),
                              label: const Text('Bắt đầu điểm giao'),
                            ),
                          if (selectedLive?['status'] == 'BLOCKED')
                            OutlinedButton.icon(
                              icon: const Icon(Icons.play_arrow),
                              onPressed: () => _run(() => api.post('$base/stops/${selected['id']}/actions',
                                  body: {'action': 'RESUME_AFTER_EXCEPTION'}, ifMatch: data.etag)),
                              label: const Text('Tiếp tục điểm giao'),
                            ),
                          if (selectedLive?['status'] == 'IN_PROGRESS') ...[
                            if (selectedLive?['arrivedAt'] == null)
                              OutlinedButton.icon(
                                icon: const Icon(Icons.place_outlined),
                                onPressed: () => _run(() => api.post('$base/stops/${selected['id']}/actions',
                                    body: {'action': 'MARK_ARRIVED'}, ifMatch: data.etag)),
                                label: const Text('Đã đến nơi'),
                              ),
                            FilledButton.icon(
                              icon: const Icon(Icons.camera_alt_outlined),
                              onPressed: () async {
                                final submitted = await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
                                  builder: (_) => PodScreen(
                                    deliveryId: widget.deliveryId,
                                    stopId: selected['id'] as String,
                                    address: selected['address'] as String,
                                    orderCode: selected['orderCode'] as String?,
                                    stopLabel: 'Điểm giao ${selected['sequenceNo']}/${stops.length}',
                                  ),
                                ));
                                if (submitted == true && mounted) {
                                  setState(() => _reloadKey++);
                                }
                              },
                              label: const Text('Nộp bằng chứng...'),
                            ),
                            OutlinedButton.icon(
                              icon: const Icon(Icons.report_problem_outlined),
                              onPressed: () async {
                                final sent = await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
                                  builder: (_) => ReportExceptionScreen(
                                    deliveryId: widget.deliveryId,
                                    stopId: selected['id'] as String,
                                    address: selected['address'] as String,
                                    deliveryCode: '${overview['code']}',
                                    orderCode: selected['orderCode'] as String?,
                                  ),
                                ));
                                if (sent == true && mounted) {
                                  setState(() => _reloadKey++);
                                }
                              },
                              label: const Text('Báo cáo sự cố...'),
                            ),
                          ],
                        ],
                      ),
                  ],
                ),
              if (blocked.isNotEmpty)
                for (final item in blocked) Callout('${item['reason']}', tone: Tone.warn),
            ],
          ),
          actions: ActionBar(
            footer: pending
                ? 'Chưa chấp nhận thì không thể bắt đầu giao.'
                : 'Hoàn tất chỉ bật khi mọi điểm giao xong, đủ bằng chứng và không còn sự cố chặn.',
            children: [
              if (pending) ...[
                FilledButton.icon(
                  icon: const Icon(Icons.check),
                  onPressed: () => _run(
                    () => api.post('/assignments/${overview['assignmentId']}/accept',
                        ifMatch: '"${overview['decisionVersion']}"', idempotent: true),
                    success: 'Đã nhận chuyến.',
                  ),
                  label: const Text('Chấp nhận'),
                ),
                OutlinedButton(onPressed: () => _reject(api, overview), child: const Text('Từ chối...')),
              ],
              if (capabilities.contains('canStartDelivery'))
                FilledButton.icon(
                  icon: const Icon(Icons.play_arrow),
                  onPressed: () => _run(
                    () => api.post('$base/start', ifMatch: data.etag, idempotent: true),
                    success: 'Đã bắt đầu chuyến.',
                  ),
                  label: const Text('Bắt đầu giao'),
                ),
              if (inProgress)
                FilledButton.icon(
                  icon: const Icon(Icons.flag),
                  onPressed: capabilities.contains('canCompleteDelivery')
                      ? () => _run(
                            () => api.post('$base/complete', ifMatch: data.etag, idempotent: true),
                            success: 'Đã hoàn tất chuyến giao.',
                          )
                      : null,
                  label: const Text('Hoàn tất chuyến'),
                ),
              OutlinedButton.icon(
                icon: const Icon(Icons.report_problem_outlined),
                onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                  builder: (_) => DeliveryExceptionsPage(deliveryId: widget.deliveryId),
                )),
                label: const Text('Sự cố của chuyến'),
              ),
              TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Đóng')),
            ],
          ),
        );
      },
    );
  }
}
