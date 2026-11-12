import 'package:flutter_test/flutter_test.dart';
import 'package:latlong2/latlong.dart';
import 'package:logistics_app/ui/kit/map_view.dart';

void main() {
  test('a polygon GeoJSON becomes one ring with latitude first', () {
    final rings = polygonsFromGeoJson('{"type":"Polygon","coordinates":[[[106.6,10.7],[106.8,10.7],[106.8,10.85],[106.6,10.7]]]}');
    expect(rings, hasLength(1));
    expect(rings.first.first, const LatLng(10.7, 106.6));
    expect(rings.first, hasLength(4));
  });

  test('a multipolygon GeoJSON becomes one ring per polygon', () {
    final rings = polygonsFromGeoJson(
        '{"type":"MultiPolygon","coordinates":[[[[1,2],[3,4],[1,2]]],[[[5,6],[7,8],[5,6]]]]}');
    expect(rings, hasLength(2));
    expect(rings[1].first, const LatLng(6, 5));
  });

  test('missing or malformed points give no coordinates', () {
    expect(polygonsFromGeoJson(null), isEmpty);
    expect(latLngOf(null), isNull);
    expect(latLngOf({'lat': 'x', 'lng': 1}), isNull);
    expect(latLngOf({'lat': 10.5, 'lng': 106}), const LatLng(10.5, 106));
  });
}
