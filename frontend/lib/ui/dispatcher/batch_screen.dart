import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../api/api_client.dart';
import '../../state/auth_state.dart';
import '../../theme/tokens.dart';
import '../common.dart';
import '../dialogs.dart';
import '../kit/kit.dart';
import 'batch_members_screen.dart';

class _BatchData {
  _BatchData(this.batch, this.batchEtag, this.orders, this.recommendation, this.recommendationEtag, this.decision,
      this.decisionEtag);

  final Map<String, dynamic> batch;
  final String? batchEtag;
  final List<Map<String, dynamic>> orders;
  final Map<String, dynamic>? recommendation;
  final String? recommendationEtag;
  final Map<String, dynamic>? decision;
  final String? decisionEtag;
}

class BatchScreen extends StatefulWidget {
  const BatchScreen({super.key, required this.batchId});

  final String batchId;

  @override
  State<BatchScreen> createState() => _BatchScreenState();
}

class _BatchScreenState extends State<BatchScreen> {
  int _reloadKey = 0;

  Future<_BatchData> _load(ApiClient api) async {
    final batchResult = await api.get('/dispatch/batches/${widget.batchId}');
    final batch = batchResult.map;
    final ids = (batch['orderIds'] as List).cast<String>();
    final orders = await Future.wait(ids.map((id) async => (await api.get('/orders/$id')).map));
    Map<String, dynamic>? recommendation;
    String? recommendationEtag;
    Map<String, dynamic>? decision;
    String? decisionEtag;
    if (batch['status'] == 'LOCKED') {
      try {
        final rec = await api.get('/dispatch/batches/${widget.batchId}/recommendations/current');
        recommendation = rec.map;
        recommendationEtag = rec.etag;
      } on ApiException catch (e) {
        if (e.status != 404) {
          rethrow;
        }
      }
      try {
        final result = await api.get('/dispatch/batches/${widget.batchId}/decision');
        decision = result.map;
        decisionEtag = result.etag;
      } on ApiException catch (e) {
        if (e.status != 404) {
          rethrow;
        }
      }
    }
    return _BatchData(batch, batchResult.etag, orders, recommendation, recommendationEtag, decision, decisionEtag);
  }

  Future<void> _run(Future<Object?> Function() action, {String? success}) async {
    final done = await guarded(context, action, success: success);
    if (done != null && mounted) {
      setState(() => _reloadKey++);
    }
  }

  SummaryRows _batchSummary(_BatchData data) {
    final batch = data.batch;
    return [
      ('Mã lô', '${batch['code']}'),
      ('Trạng thái', '${batch['status']}'),
      ('Số đơn hàng', '${batch['orderCount']} đơn — ${(batch['totalWeightG'] as num) / 1000} kg'),
    ];
  }

  Future<void> _assign(ApiClient api, _BatchData data, {Map<String, dynamic>? chosen}) async {
    final recData = data.recommendation!['data'] as Map<String, dynamic>;
    final snapshot = recData['snapshot'] as Map<String, dynamic>;
    final decisionId = recData['decisionId'] as String;
    String? reason;
    if (chosen != null) {
      final candidates = (snapshot['candidates'] as List).cast<Map<String, dynamic>>();
      reason = await showDialog<String>(
        context: context,
        builder: (_) => _OverrideDialog(snapshot: snapshot, recommended: candidates.first, chosen: chosen, batchSummary: _batchSummary(data)),
      );
      if (reason == null) {
        return;
      }
    }
    await _run(
      () => api.post('/dispatch/decisions/$decisionId/assign',
          body: {
            'snapshotId': snapshot['id'],
            'driverId': ?chosen?['driverId'],
            'overrideReason': ?reason,
          },
          ifMatch: data.recommendationEtag,
          idempotent: true),
      success: 'Đã gán tài xế. Đang chờ tài xế nhận chuyến.',
    );
  }

  Future<void> _reassign(ApiClient api, _BatchData data) async {
    final decisionData = data.decision!['data'] as Map<String, dynamic>;
    final assignment = decisionData['assignment'] as Map<String, dynamic>?;
    final currentDriver = assignment?['driverId'];
    final candidates = data.recommendation == null
        ? <Map<String, dynamic>>[]
        : (((data.recommendation!['data'] as Map<String, dynamic>)['snapshot'] as Map<String, dynamic>)['candidates']
                as List)
            .cast<Map<String, dynamic>>()
            .where((c) => c['driverId'] != currentDriver)
            .toList();
    final rejection = assignment?['rejectionReasonCode'] as String?;
    final choice = await askReassign(context, candidates, summary: [
      ('Lô', '${data.batch['code']} — ${decisionData['status']}'),
      ('Tài xế cũ', '${assignment?['driverName'] ?? '—'}'),
      if (rejection != null) ('Lý do từ chối', rejectionReasonText[rejection] ?? rejection),
    ]);
    if (choice == null) {
      return;
    }
    await _run(
      () => api.post('/dispatch/decisions/${decisionData['decisionId']}/reassign',
          body: {'driverId': choice.driverId, 'reason': choice.reason},
          ifMatch: data.decisionEtag,
          idempotent: true),
      success: 'Đã đổi tài xế.',
    );
  }

  Future<void> _cancelDispatch(ApiClient api, _BatchData data) async {
    final decisionData = data.decision!['data'] as Map<String, dynamic>;
    final assignment = decisionData['assignment'] as Map<String, dynamic>?;
    final reason = await askReason(
      context,
      title: 'Hủy điều phối',
      confirmLabel: 'Hủy điều phối',
      label: 'Lý do hủy',
      summaryTitle: 'Điều phối',
      summary: [
        ('Lô', '${data.batch['code']}'),
        ('Tài xế', '${assignment?['driverName'] ?? '—'}'),
        ('Trạng thái', '${decisionData['status']}'),
      ],
      callout: 'Tài xế và khách hàng bị ảnh hưởng sẽ nhận thông báo.',
      footer: 'Thao tác được ghi nhật ký kiểm toán.',
    );
    if (reason == null) {
      return;
    }
    await _run(
      () => api.post('/dispatch/decisions/${decisionData['decisionId']}/cancel',
          body: {'reason': reason}, ifMatch: data.decisionEtag),
      success: 'Đã hủy điều phối. Có thể tạo gợi ý mới.',
    );
  }

  Future<void> _editMembers(_BatchData data) async {
    final changed = await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
      builder: (_) => BatchMembersScreen(batch: data.batch, etag: data.batchEtag),
    ));
    if (changed == true && mounted) {
      setState(() => _reloadKey++);
    }
  }

  Future<void> _reopen(ApiClient api, _BatchData data) async {
    final reason = await askReason(
      context,
      title: 'Mở lại lô giao hàng',
      confirmLabel: 'Mở lại lô',
      label: 'Lý do mở lại',
      summaryTitle: 'Lô giao hàng',
      summary: _batchSummary(data),
      callout: 'Đề xuất tài xế hiện tại sẽ bị vô hiệu sau khi mở lại.',
      footer: 'Chỉ mở lại được khi chưa có tài xế chấp nhận chuyến.',
    );
    if (reason == null) {
      return;
    }
    await _run(
      () => api.post('/dispatch/batches/${widget.batchId}/reopen',
          body: {'reason': reason}, ifMatch: data.batchEtag, idempotent: true),
      success: 'Đã mở lại lô. Đề xuất cũ không còn hiệu lực.',
    );
  }

  Future<void> _cancelBatch(ApiClient api, _BatchData data) async {
    final reason = await askReason(
      context,
      title: 'Hủy lô giao hàng',
      confirmLabel: 'Hủy lô',
      label: 'Lý do hủy lô',
      summaryTitle: 'Lô giao hàng',
      summary: _batchSummary(data),
      callout: 'Các đơn trong lô sẽ quay lại hàng chờ điều phối.',
    );
    if (reason == null) {
      return;
    }
    await _run(
      () => api.post('/dispatch/batches/${widget.batchId}/cancel',
          body: {'reason': reason}, ifMatch: data.batchEtag, idempotent: true),
      success: 'Đã hủy lô. Các đơn quay lại hàng chờ điều phối.',
    );
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.read<AuthState>();
    final api = auth.api;
    final canOverride = auth.roles.contains('DISPATCH_OVERRIDER');
    return LoadView<_BatchData>(
      key: ValueKey(_reloadKey),
      loader: () => _load(api),
      builder: (context, data, reload) {
        final batch = data.batch;
        final status = batch['status'] as String;
        final recommendation = data.recommendation;
        final decisionData = data.decision?['data'] as Map<String, dynamic>?;
        final decisionStatus = decisionData?['status'] as String?;
        final canGenerate = status == 'LOCKED' && (decisionStatus == null || decisionStatus == 'PENDING_DECISION');
        final window = batch['plannedWindow'] as Map<String, dynamic>?;
        return WindowFrame(
          title: 'Lô giao hàng ${batch['code']}',
          body: PageBody(
            onRefresh: reload,
            children: [
              FormGroup(
                title: 'Thông tin lô',
                trailing: StatusChip(status),
                children: [
                  InfoRow('Khung giờ', window == null ? null : '${formatTime(window['start'] as String?)} – ${formatTime(window['end'] as String?)}'),
                  InfoRow('Số đơn', '${batch['orderCount']}'),
                  InfoRow('Tổng tải', '${(batch['totalWeightG'] as num) / 1000} kg / ${((batch['totalVolumeCm3'] as num) / 1000000).toStringAsFixed(2)} m³'),
                  InfoRow('Version', '${batch['version']}'),
                ],
              ),
              FormGroup(
                title: 'Đơn hàng trong lô',
                children: [
                  ResultGrid<Map<String, dynamic>>(
                    rows: data.orders,
                    columns: [
                      GridColumn(title: 'Mã đơn', primary: true, flex: 1.1, cell: (c, o) => Text('${o['code']}')),
                      GridColumn(title: 'Điểm giao', flex: 2, cell: (c, o) => Text('${(o['dropoff'] as Map)['address']}')),
                      GridColumn(
                        title: 'Khung giờ',
                        flex: 1.3,
                        cell: (c, o) {
                          final w = o['timeWindow'] as Map;
                          return Text('${formatTime(w['start'] as String?)} – ${formatTime(w['end'] as String?).split(' ').last}');
                        },
                      ),
                      GridColumn(title: 'Khối lượng', flex: 0.8, cell: (c, o) => Text('${((o['package'] as Map)['weightG'] as num) / 1000} kg')),
                      GridColumn(title: 'Trạng thái', flex: 1.2, cell: (c, o) => StatusChip(o['status'] as String)),
                    ],
                  ),
                ],
              ),
              if (decisionData != null && decisionStatus != 'PENDING_DECISION')
                _DecisionGroup(decision: data.decision!, batchCode: '${batch['code']}'),
              if (canGenerate && recommendation != null)
                _RecommendationGroup(
                  recommendation: recommendation,
                  canOverride: canOverride,
                  onAssign: ({Map<String, dynamic>? chosen}) => _assign(api, data, chosen: chosen),
                ),
            ],
          ),
          actions: ActionBar(
            footer: 'Thêm/bớt đơn chỉ khi DRAFT • Đề xuất chỉ khi LOCKED.',
            children: [
              if (status == 'DRAFT') ...[
                FilledButton.icon(
                  icon: const Icon(Icons.lock_outline),
                  onPressed: () => _run(
                    () => api.post('/dispatch/batches/${widget.batchId}/lock', ifMatch: data.batchEtag),
                    success: 'Đã khóa lô.',
                  ),
                  label: const Text('Khóa lô'),
                ),
                OutlinedButton.icon(
                  icon: const Icon(Icons.edit_note),
                  onPressed: () => _editMembers(data),
                  label: const Text('Thêm/bớt đơn'),
                ),
              ],
              if (canGenerate)
                FilledButton.icon(
                  icon: const Icon(Icons.auto_awesome),
                  onPressed: () => _run(
                    () => api.post('/dispatch/batches/${widget.batchId}/recommendations', ifMatch: data.batchEtag),
                    success: 'Đã tạo đề xuất tài xế.',
                  ),
                  label: Text(recommendation == null ? 'Tạo đề xuất' : 'Tạo lại đề xuất'),
                ),
              if (decisionData != null && (decisionStatus != 'PENDING_DECISION'))
                ...[
                  if ((data.decision!['capabilities'] as List).contains('canReassign'))
                    FilledButton.icon(
                      icon: const Icon(Icons.swap_horiz),
                      onPressed: () => _reassign(api, data),
                      label: const Text('Phân công lại...'),
                    ),
                  if ((data.decision!['capabilities'] as List).contains('canCancelDispatch'))
                    OutlinedButton(onPressed: () => _cancelDispatch(api, data), child: const Text('Hủy điều phối...')),
                ]
              else if (decisionStatus == 'PENDING_DECISION' && canGenerate)
                OutlinedButton(onPressed: () => _cancelDispatch(api, data), child: const Text('Hủy điều phối...')),
              if (status == 'LOCKED' && canGenerate)
                OutlinedButton.icon(
                  icon: const Icon(Icons.lock_open),
                  onPressed: () => _reopen(api, data),
                  label: const Text('Mở lại lô...'),
                ),
              if (status == 'DRAFT' || (status == 'LOCKED' && canGenerate))
                OutlinedButton(onPressed: () => _cancelBatch(api, data), child: const Text('Hủy lô...')),
              TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Đóng')),
            ],
          ),
        );
      },
    );
  }
}

class _DecisionGroup extends StatelessWidget {
  const _DecisionGroup({required this.decision, required this.batchCode});

  final Map<String, dynamic> decision;
  final String batchCode;

  @override
  Widget build(BuildContext context) {
    final data = decision['data'] as Map<String, dynamic>;
    final assignment = data['assignment'] as Map<String, dynamic>?;
    final status = data['status'] as String;
    final rejection = assignment?['rejectionReasonCode'] as String?;
    return FormGroup(
      title: 'Điều phối',
      trailing: StatusChip(status),
      children: [
        if (assignment != null) ...[
          InfoRow('Tài xế', '${assignment['driverName']}'),
          InfoRow('Trạng thái phân công', assignment['status'] == 'REJECTED' ? 'Đã từ chối' : '${assignment['status']}'),
          if (rejection != null) InfoRow('Lý do từ chối', rejectionReasonText[rejection] ?? rejection),
        ],
        if (status == 'AWAITING_REASSIGNMENT') const Callout('Tài xế đã từ chối. Hãy chọn tài xế khác cho chuyến này.', tone: Tone.warn),
      ],
    );
  }
}

class _RecommendationGroup extends StatelessWidget {
  const _RecommendationGroup({required this.recommendation, required this.canOverride, required this.onAssign});

  final Map<String, dynamic> recommendation;
  final bool canOverride;
  final void Function({Map<String, dynamic>? chosen}) onAssign;

  @override
  Widget build(BuildContext context) {
    final data = recommendation['data'] as Map<String, dynamic>;
    final snapshot = data['snapshot'] as Map<String, dynamic>;
    final candidates = (snapshot['candidates'] as List).cast<Map<String, dynamic>>();
    final capabilities = (recommendation['capabilities'] as List).cast<String>();
    final effective = snapshot['effectiveStatus'] as String;
    final exclusions = (snapshot['exclusionSummary'] as Map<String, dynamic>);
    final assignable = capabilities.contains('canAssign');
    final excluded = exclusions.values.fold<int>(0, (sum, v) => sum + (v as num).toInt());
    String score(Map<String, dynamic> c, String key) => '${(c['breakdown'] as Map<String, dynamic>)[key]}';
    return Column(
      children: [
        FormGroup(
          title: 'Snapshot đề xuất',
          trailing: StatusChip(effective),
          children: [
            InfoRow('Mã snapshot', '${snapshot['id']}'.substring(0, 8)),
            InfoRow('Tạo lúc', formatTime(snapshot['generatedAt'] as String?)),
            InfoRow('Hết hạn lúc', formatTime(snapshot['expiresAt'] as String?)),
            InfoRow('Rule version', 'v${(snapshot['ruleVersion'] as Map<String, dynamic>)['versionNo']}'),
            InfoRow('Ứng viên', '${snapshot['eligibleCount']} đủ điều kiện / ${snapshot['candidateCount']} được xét'),
            if (effective != 'ACTIVE') const Callout('Snapshot hết hạn không phân công được. Hãy tạo lại đề xuất.', tone: Tone.warn),
          ],
        ),
        FormGroup(
          title: 'Xếp hạng tài xế đủ điều kiện',
          children: [
            if (snapshot['outcome'] == 'NO_ELIGIBLE_DRIVER')
              const Callout('Không có tài xế nào đủ điều kiện. Kiểm tra lại điều kiện rồi tạo đề xuất mới.', tone: Tone.danger),
            ResultGrid<Map<String, dynamic>>(
              rows: candidates,
              columns: [
                GridColumn(title: 'Hạng', flex: 0.5, cell: (c, r) => Text('${r['rank']}')),
                GridColumn(title: 'Tài xế', primary: true, flex: 1.6, cell: (c, r) => Text('${r['driverName']}')),
                GridColumn(title: 'Xe', cell: (c, r) => Text('${(r['vehicle'] as Map)['type']}')),
                GridColumn(title: 'Tổng điểm', flex: 0.8, cell: (c, r) => Text('${r['totalScore']}', style: const TextStyle(fontWeight: FontWeight.w700))),
                GridColumn(title: 'Distance', flex: 0.8, cell: (c, r) => Text(score(r, 'distance'))),
                GridColumn(title: 'Capacity Fit', flex: 0.8, cell: (c, r) => Text(score(r, 'capacityFit'))),
                GridColumn(title: 'Availability', flex: 0.8, cell: (c, r) => Text(score(r, 'availability'))),
                GridColumn(title: 'Zone', flex: 0.8, cell: (c, r) => Text(score(r, 'zoneCompatibility'))),
                GridColumn(
                  title: 'Thao tác',
                  flex: 1.2,
                  cell: (c, r) => assignable && canOverride && r['rank'] != 1
                      ? TextButton(onPressed: () => onAssign(chosen: r), child: const Text('Ghi đè...'))
                      : const SizedBox.shrink(),
                ),
              ],
            ),
            const FieldGap(),
            Text(
              excluded == 0
                  ? 'Không có tài xế nào bị loại.'
                  : 'Tài xế bị loại do không đủ điều kiện cứng: $excluded (${exclusions.entries.map((e) => '${e.key}: ${e.value}').join(', ')})',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (assignable) ...[
              const FieldGap(),
              FilledButton.icon(
                icon: const Icon(Icons.person_add_alt_1),
                onPressed: () => onAssign(),
                label: Text('Phân công tài xế #1${candidates.isEmpty ? '' : ' — ${candidates.first['driverName']}'}'),
              ),
            ],
          ],
        ),
      ],
    );
  }
}

class _OverrideDialog extends StatefulWidget {
  const _OverrideDialog({required this.snapshot, required this.recommended, required this.chosen, required this.batchSummary});

  final Map<String, dynamic> snapshot;
  final Map<String, dynamic> recommended;
  final Map<String, dynamic> chosen;
  final SummaryRows batchSummary;

  @override
  State<_OverrideDialog> createState() => _OverrideDialogState();
}

class _OverrideDialogState extends State<_OverrideDialog> {
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final rec = widget.recommended;
    final cho = widget.chosen;
    String value(Map<String, dynamic> c, String key) => '${(c['breakdown'] as Map<String, dynamic>)[key]}';
    final rows = <(String, String, String)>[
      ('Distance', value(rec, 'distance'), value(cho, 'distance')),
      ('Capacity Fit', value(rec, 'capacityFit'), value(cho, 'capacityFit')),
      ('Availability', value(rec, 'availability'), value(cho, 'availability')),
      ('Zone Compatibility', value(rec, 'zoneCompatibility'), value(cho, 'zoneCompatibility')),
      ('Tổng điểm', '${rec['totalScore']}', '${cho['totalScore']}'),
    ];
    return FormDialog(
      title: 'Ghi đè đề xuất tài xế',
      footer: '${widget.batchSummary.first.$2} • Trường có dấu * là bắt buộc',
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Hủy')),
        FilledButton(
          onPressed: _reason.text.trim().isEmpty ? null : () => Navigator.of(context).pop(_reason.text.trim()),
          child: const Text('Xác nhận ghi đè'),
        ),
      ],
      children: [
        SummaryBlock('Đề xuất (Snapshot)', [
          ('Mã snapshot', '${widget.snapshot['id']}'.substring(0, 8)),
          ('Hết hạn lúc', formatTime(widget.snapshot['expiresAt'] as String?)),
          ('Tài xế được đề xuất', '${rec['driverName']} — ${(rec['vehicle'] as Map)['type']} — ${rec['totalScore']} điểm'),
        ]),
        SummaryBlock('Tài xế được chọn', [
          ('Tài xế', '${cho['driverName']} — ${(cho['vehicle'] as Map)['type']} — ${cho['totalScore']} điểm'),
        ]),
        Text('So sánh điểm (Score breakdown)', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: AppTokens.s1),
        Table(
          columnWidths: const {0: FlexColumnWidth(2), 1: FlexColumnWidth(1), 2: FlexColumnWidth(1)},
          children: [
            const TableRow(children: [Text('Tiêu chí'), Text('Đề xuất'), Text('Được chọn')]),
            for (final row in rows)
              TableRow(children: [
                Padding(padding: const EdgeInsets.symmetric(vertical: 3), child: Text(row.$1)),
                Text(row.$2),
                Text(row.$3),
              ]),
          ],
        ),
        const FieldGap(),
        TextField(
          controller: _reason,
          maxLines: 3,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Lý do ghi đè *'),
          onChanged: (_) => setState(() {}),
        ),
        const FieldGap(),
        const Callout('Tài xế được chọn sẽ được kiểm tra lại điều kiện trước khi phân công.'),
      ],
    );
  }
}
