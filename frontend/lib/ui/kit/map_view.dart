import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:http/http.dart' as http;
import 'package:latlong2/latlong.dart';

import '../../theme/tokens.dart';

const LatLng defaultMapCenter = LatLng(10.7769, 106.7009);

const String _osrmBase = String.fromEnvironment('OSRM_URL', defaultValue: 'https://router.project-osrm.org');

class MapPin {
  const MapPin(this.point, this.label, {this.tone = Tone.info});

  final LatLng point;
  final String label;
  final Tone tone;
}

LatLng? latLngOf(Object? value) {
  if (value is Map && value['lat'] is num && value['lng'] is num) {
    return LatLng((value['lat'] as num).toDouble(), (value['lng'] as num).toDouble());
  }
  return null;
}

List<List<LatLng>> polygonsFromGeoJson(String? geoJson) {
  if (geoJson == null || geoJson.isEmpty) {
    return const [];
  }
  final data = jsonDecode(geoJson) as Map<String, dynamic>;
  List<LatLng> ring(List<dynamic> coordinates) => [
        for (final c in coordinates) LatLng(((c as List)[1] as num).toDouble(), (c[0] as num).toDouble()),
      ];
  final coordinates = data['coordinates'] as List<dynamic>;
  if (data['type'] == 'Polygon') {
    return [ring(coordinates.first as List<dynamic>)];
  }
  if (data['type'] == 'MultiPolygon') {
    return [for (final polygon in coordinates) ring((polygon as List).first as List<dynamic>)];
  }
  return const [];
}

Future<List<LatLng>?> fetchRoute(List<LatLng> stops) async {
  if (stops.length < 2) {
    return null;
  }
  final path = stops.map((p) => '${p.longitude},${p.latitude}').join(';');
  try {
    final response = await http
        .get(Uri.parse('$_osrmBase/route/v1/driving/$path?overview=full&geometries=geojson'))
        .timeout(const Duration(seconds: 8));
    if (response.statusCode != 200) {
      return null;
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    final routes = body['routes'] as List<dynamic>?;
    if (routes == null || routes.isEmpty) {
      return null;
    }
    final coordinates = ((routes.first as Map)['geometry'] as Map)['coordinates'] as List<dynamic>;
    return [for (final c in coordinates) LatLng(((c as List)[1] as num).toDouble(), (c[0] as num).toDouble())];
  } catch (_) {
    return null;
  }
}

class AppMap extends StatelessWidget {
  const AppMap({
    super.key,
    this.pins = const [],
    this.polygons = const [],
    this.route,
    this.straightLine = const [],
    this.onTap,
    this.height = 280,
    this.caption,
  });

  final List<MapPin> pins;
  final List<List<LatLng>> polygons;
  final List<LatLng>? route;
  final List<LatLng> straightLine;
  final void Function(LatLng point)? onTap;
  final double height;
  final String? caption;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    final scheme = Theme.of(context).colorScheme;
    final points = [
      for (final p in pins) p.point,
      for (final polygon in polygons) ...polygon,
    ];
    final options = MapOptions(
      initialCenter: points.isEmpty ? defaultMapCenter : points.first,
      initialZoom: points.isEmpty ? 12 : 14,
      initialCameraFit: points.length < 2
          ? null
          : CameraFit.coordinates(coordinates: points, padding: const EdgeInsets.all(36), maxZoom: 16),
      onTap: onTap == null ? null : (_, point) => onTap!(point),
      interactionOptions: const InteractionOptions(flags: InteractiveFlag.all & ~InteractiveFlag.rotate),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(AppTokens.radius),
          child: Container(
            height: height,
            decoration: BoxDecoration(
              border: Border.all(color: tokens.line),
              borderRadius: BorderRadius.circular(AppTokens.radius),
            ),
            child: Semantics(
              label: 'Bản đồ có ${pins.length} điểm đánh dấu',
              child: FlutterMap(
                key: ValueKey(Object.hashAll([...points, route?.length])),
                options: options,
                children: [
                  TileLayer(
                    urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                    userAgentPackageName: 'vn.edu.finalism.logistics_app',
                  ),
                  if (polygons.isNotEmpty)
                    PolygonLayer(
                      polygons: [
                        for (final polygon in polygons)
                          Polygon(
                            points: polygon,
                            color: scheme.primary.withValues(alpha: 0.12),
                            borderColor: scheme.primary,
                            borderStrokeWidth: 2,
                          ),
                      ],
                    ),
                  if (route != null && route!.length > 1)
                    PolylineLayer(polylines: [Polyline(points: route!, strokeWidth: 4, color: scheme.primary)])
                  else if (straightLine.length > 1)
                    PolylineLayer(
                      polylines: [
                        Polyline(
                          points: straightLine,
                          strokeWidth: 2,
                          color: tokens.inkSoft,
                          pattern: StrokePattern.dashed(segments: const [8, 6]),
                        ),
                      ],
                    ),
                  MarkerLayer(
                    markers: [
                      for (final pin in pins)
                        Marker(
                          point: pin.point,
                          width: 34,
                          height: 34,
                          child: _PinMark(label: pin.label, tone: pin.tone),
                        ),
                    ],
                  ),
                  RichAttributionWidget(
                    attributions: [TextSourceAttribution('OpenStreetMap contributors')],
                  ),
                ],
              ),
            ),
          ),
        ),
        if (caption != null)
          Padding(
            padding: const EdgeInsets.only(top: AppTokens.s1),
            child: Text(caption!, style: Theme.of(context).textTheme.bodySmall),
          ),
      ],
    );
  }
}

class _PinMark extends StatelessWidget {
  const _PinMark({required this.label, required this.tone});

  final String label;
  final Tone tone;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    return Container(
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: tokens.toneFg[tone],
        shape: BoxShape.circle,
        border: Border.all(color: Colors.white, width: 2),
        boxShadow: const [BoxShadow(blurRadius: 3, color: Color(0x55000000))],
      ),
      child: Text(
        label,
        style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 13),
      ),
    );
  }
}
