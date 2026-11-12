const String apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://10.0.2.2:8081/api/v1',
);

class Place {
  const Place(this.name, this.lat, this.lng);

  final String name;
  final double lat;
  final double lng;
}

const List<Place> demoPlaces = [
  Place('Chợ Bến Thành', 10.7725, 106.6980),
  Place('Nhà thờ Đức Bà', 10.7798, 106.6990),
  Place('Landmark 81', 10.7951, 106.7218),
  Place('Sân bay Tân Sơn Nhất', 10.8188, 106.6520),
  Place('Bệnh viện Chợ Rẫy', 10.7570, 106.6592),
  Place('Công viên Đầm Sen', 10.7633, 106.6366),
];
