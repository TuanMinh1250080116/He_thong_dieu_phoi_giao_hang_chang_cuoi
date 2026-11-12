import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:logistics_app/api/api_client.dart';

ApiClient newClient() => ApiClient(baseUrl: 'http://localhost:8081/api/v1', tokenStore: MemoryTokenStore());

void main() {
  final enabled = Platform.environment['LIVE_API'] == '1';

  test('whole delivery flow through the app API client', () async {
    final customer = newClient();
    final dispatcher = newClient();
    final driver = newClient();
    await customer.login('customer@dev.local', 'Passw0rd!dev');
    await dispatcher.login('dispatcher@dev.local', 'Passw0rd!dev');
    await driver.login('driver@dev.local', 'Passw0rd!dev');

    final start = DateTime.now().toUtc().add(const Duration(minutes: 30));
    final created = await customer.post('/orders', idempotent: true, body: {
      'saveMode': 'SUBMIT',
      'pickup': {'address': 'Chợ Bến Thành', 'point': {'lat': 10.7725, 'lng': 106.6980}},
      'dropoff': {'address': 'Landmark 81', 'point': {'lat': 10.7951, 'lng': 106.7218}},
      'recipient': {'name': 'Nguyen Van A', 'contact': '+84900000000'},
      'package': {'weightG': 2500, 'lengthCm': 30, 'widthCm': 20, 'heightCm': 15, 'note': null},
      'timeWindow': {
        'start': start.toIso8601String(),
        'end': start.add(const Duration(hours: 4)).toIso8601String(),
      },
    });
    final order = created.map['data'] as Map<String, dynamic>;
    expect(order['status'], 'READY_FOR_DISPATCH');

    final batch = (await dispatcher.post('/dispatch/batches',
            idempotent: true, body: {'zoneId': order['zoneId'], 'orderIds': [order['id']]}))
        .map['data'] as Map<String, dynamic>;
    final locked = await dispatcher.post('/dispatch/batches/${batch['id']}/lock', ifMatch: '"${batch['version']}"');
    final recommendation = await dispatcher.post('/dispatch/batches/${batch['id']}/recommendations',
        ifMatch: locked.etag);
    final data = recommendation.map['data'] as Map<String, dynamic>;
    final snapshot = data['snapshot'] as Map<String, dynamic>;
    expect(snapshot['outcome'], 'RANKED', reason: 'The dev driver location may be older than 10 minutes. Restart the backend.');

    final assigned = await dispatcher.post('/dispatch/decisions/${data['decisionId']}/assign',
        body: {'snapshotId': snapshot['id']}, ifMatch: recommendation.etag, idempotent: true);
    final assignment = assigned.map['data'] as Map<String, dynamic>;

    final mine = (await driver.get('/me/assignments')).map['items'] as List<dynamic>;
    final listed = mine.cast<Map<String, dynamic>>().firstWhere((a) => a['id'] == assignment['id']);
    await driver.post('/assignments/${assignment['id']}/accept', ifMatch: '"${listed['version']}"', idempotent: true);

    final deliveryPath = '/deliveries/${assignment['deliveryId']}';
    var delivery = await driver.get(deliveryPath);
    await driver.post('$deliveryPath/start', ifMatch: delivery.etag, idempotent: true);

    delivery = await driver.get(deliveryPath);
    final stop = (delivery.map['stops'] as List).cast<Map<String, dynamic>>().first;
    await driver.post('$deliveryPath/stops/${stop['id']}/actions', body: {'action': 'START_STOP'}, ifMatch: delivery.etag);

    final jpeg = Uint8List(2048);
    jpeg[0] = 0xFF;
    jpeg[1] = 0xD8;
    jpeg[2] = 0xFF;
    final ticket = await driver.post('$deliveryPath/evidence-uploads', body: {
      'purpose': 'POD',
      'kind': 'PHOTO',
      'stopId': stop['id'],
      'mimeType': 'image/jpeg',
      'sizeBytes': jpeg.length,
    });
    await driver.putBytes(ticket.map['uploadUrl'] as String, jpeg, 'image/jpeg');
    await driver.post('/evidence/${ticket.map['evidenceId']}/complete', body: {'sha256': sha256.convert(jpeg).toString()});
    await driver.post('$deliveryPath/stops/${stop['id']}/pod', idempotent: true, body: {
      'gps': {'point': {'lat': 10.7951, 'lng': 106.7218}, 'accuracyM': 10},
      'isDirectHandoff': true,
      'recipientName': 'Nguyen Van A',
      'evidenceIds': [ticket.map['evidenceId']],
    });

    delivery = await driver.get(deliveryPath);
    final completed = await driver.post('$deliveryPath/complete', ifMatch: delivery.etag, idempotent: true);
    expect((completed.map['data'] as Map<String, dynamic>)['status'], 'COMPLETED');

    final finalOrder = await customer.get('/orders/${order['id']}');
    expect(finalOrder.map['status'], 'DELIVERED');
  }, skip: enabled ? false : 'Set LIVE_API=1 with the backend running to execute', timeout: const Timeout(Duration(minutes: 2)));
}
