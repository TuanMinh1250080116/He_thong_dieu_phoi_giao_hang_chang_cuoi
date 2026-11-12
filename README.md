# Hệ thống điều phối giao hàng chặng cuối

Đồ án môn **Phương pháp phát triển phần mềm theo hướng đối tượng**, Khoa Công nghệ thông tin, Trường Đại học Tài nguyên và Môi trường.

- Sinh viên thực hiện: Trần Tuấn Minh, lớp 12 ĐH CNPM 2
- Giảng viên hướng dẫn: ThS. Phạm Trọng Huynh

## Giới thiệu

Ở chặng cuối của giao hàng (từ kho tới tay người nhận), việc chọn tài xế cho từng nhóm đơn thường dựa vào kinh nghiệm của điều phối viên. Cách làm này dễ giao nhầm người đang bận, xe không đủ tải, tài xế ở quá xa, và khi có khiếu nại thì không biết vì sao đã chọn người đó.

Hệ thống này hỗ trợ điều phối viên ra quyết định:

- Gom các đơn cùng vùng thành **lô giao hàng**.
- Lọc tài xế theo **điều kiện bắt buộc** (đang sẵn sàng, xe đủ tải, không trùng lịch, đúng vùng), sau đó **chấm điểm và xếp hạng** những người đạt. Mỗi đề xuất đều kèm lý do.
- **Điều phối viên giữ quyền quyết định cuối cùng**: có thể chọn người khác với đề xuất (ghi đè), nhưng phải nêu lý do và không được vượt qua các ràng buộc an toàn.
- Mọi quyết định quan trọng đều được **ghi nhật ký kiểm toán** để truy lại khi có sự cố hay khiếu nại.

## Tính năng theo vai trò

| Vai trò | Chức năng chính |
|---|---|
| **Khách hàng** | Tạo đơn (chọn điểm lấy và giao trên bản đồ), sửa hoặc hủy đơn khi còn được phép, theo dõi hành trình đơn, xem bằng chứng giao hàng |
| **Điều phối viên** | Bảng điều phối tổng quan; gom đơn thành lô, khóa và mở lại lô; tạo đề xuất tài xế; phân công, ghi đè có lý do, phân công lại, hủy điều phối; giám sát các chuyến đang chạy |
| **Tài xế** | Bật hoặc tắt trạng thái sẵn sàng; nhận hoặc từ chối chuyến (có lý do); bắt đầu giao, cập nhật từng điểm giao; nộp bằng chứng giao hàng (ảnh, chữ ký, mã OTP gửi qua email người nhận); báo cáo sự cố |
| **Quản lý vận hành** | Quản lý tài xế, phương tiện, vùng giao (vẽ ranh giới trên bản đồ); cấu hình quy tắc chấm điểm và chính sách bằng chứng theo phiên bản, có hẹn giờ hiệu lực; điều tra, giải quyết hoặc hủy sự cố; tra cứu nhật ký kiểm toán |
| **Quản trị hệ thống** | Quản lý tài khoản người dùng, khóa và mở khóa tài khoản, gán vai trò và quyền |

Mọi người dùng đều có trung tâm thông báo trong ứng dụng. Một số sự kiện (đơn được tạo, đã phân công tài xế, bắt đầu giao, giao xong...) còn được gửi kèm qua email.

## Luồng nghiệp vụ chính

```
Khách hàng tạo đơn
  → Điều phối viên gom đơn thành lô và khóa lô
  → Hệ thống lọc điều kiện, chấm điểm, đề xuất danh sách tài xế
  → Điều phối viên phân công (hoặc ghi đè có lý do)
  → Tài xế nhận chuyến (nếu từ chối: điều phối viên phân công lại)
  → Tài xế giao từng điểm, nộp bằng chứng giao hàng
  → Hệ thống kiểm tra đủ điểm, đủ bằng chứng, không còn sự cố chặn
  → Chuyến hoàn tất, đơn chuyển sang đã giao, khách hàng xem được bằng chứng
```

## Kiến trúc và công nghệ

| Thành phần | Công nghệ |
|---|---|
| Backend | Java 21, Spring Boot 4, Spring Security (JWT), Spring JDBC, Flyway |
| Cơ sở dữ liệu | PostgreSQL 16 + PostGIS 3.4 (tính khoảng cách, kiểm tra điểm thuộc vùng giao) |
| Truyền sự kiện | Transactional outbox + RabbitMQ 4 |
| Email | SMTP (MailHog khi phát triển) |
| Ứng dụng | Flutter (Android), provider, flutter_map + OpenStreetMap |
| Đặc tả API | OpenAPI 3 (`api/openapi.yaml`) |

Backend theo kiến trúc **modular monolith**: chỉ triển khai một ứng dụng, nhưng chia thành 10 phân hệ nghiệp vụ có ranh giới rõ ràng. Nhờ vậy một thao tác như "phân công tài xế" chỉ cần một giao dịch cơ sở dữ liệu, đồng thời sau này vẫn có thể tách từng phân hệ thành dịch vụ riêng. Mỗi phân hệ có 4 tầng:

| Tầng | Vai trò |
|---|---|
| `presentation` | REST controller, dữ liệu vào và ra |
| `application` | Xử lý từng use case, quản lý giao dịch, kiểm tra quyền |
| `domain` | Đối tượng nghiệp vụ, trạng thái và luật nghiệp vụ |
| `infrastructure` | Truy cập cơ sở dữ liệu, RabbitMQ, email, lưu tệp |

Các phân hệ: `identityaccess` (tài khoản, phân quyền), `order` (đơn hàng), `dispatch` (lô, đề xuất, phân công), `driverfleet` (tài xế, phương tiện), `delivery` (chuyến giao), `proof` (bằng chứng giao hàng, OTP), `exception` (sự cố), `administration` (vùng giao, quy tắc, chính sách), `audit` (nhật ký kiểm toán), `notification` (thông báo).

Ngoài các phân hệ còn có ba gói dùng chung:

| Gói | Nội dung |
|---|---|
| `shared` | Các kiểu dùng chung giữa các phân hệ |
| `platform` | Outbox sự kiện, chống gửi trùng lệnh, giới hạn tần suất gọi |
| `bootstrap` | Cấu hình Spring, bảo mật, xử lý lỗi chung |

### Một số điểm thiết kế đáng chú ý

- **Trạng thái chỉ đổi qua thao tác nghiệp vụ.** Không có API cho phép gửi trạng thái tùy ý; mỗi chuyển trạng thái đều được kiểm tra điều kiện trong đối tượng nghiệp vụ.
- **Chống phân công trùng.** Ràng buộc loại trừ theo khung thời gian ở cơ sở dữ liệu bảo đảm một tài xế không thể bị giao hai chuyến chồng giờ, kể cả khi hai điều phối viên bấm cùng lúc.
- **Khóa lạc quan.** Thao tác sửa phải gửi kèm phiên bản dữ liệu (If-Match), tránh ghi đè thay đổi của người khác.
- **Chống gửi trùng lệnh.** Mỗi lệnh quan trọng đi kèm khóa idempotency, nên gửi lại do mạng chập chờn cũng không tạo bản ghi thứ hai.
- **Lưu xong mới gửi thông báo.** Sự kiện được ghi vào bảng outbox trong cùng giao dịch nghiệp vụ, sau đó mới chuyển sang RabbitMQ. Gửi email lỗi không làm hỏng thao tác nghiệp vụ.
- **Nhật ký kiểm toán chỉ được ghi thêm.** Trigger ở cơ sở dữ liệu chặn sửa và xóa nhật ký. Phiên bản cấu hình đã công bố cũng không sửa được.
- **Bảo vệ bằng chứng.** Mã OTP chỉ lưu dạng băm. Ảnh và chữ ký chỉ xem được qua đường dẫn có chữ ký và thời hạn ngắn.

## Cách tính điểm đề xuất tài xế

Mỗi tiêu chí được quy về thang 0–100, rồi nhân với trọng số của phiên bản quy tắc đang hiệu lực (tổng trọng số bằng 1).

| Tiêu chí | Cách tính |
|---|---|
| Khoảng cách | 100 × (1 − khoảng cách tới điểm lấy hàng trung tâm / bán kính tìm kiếm); không có khoảng cách thì tính như xa nhất |
| Độ vừa tải | 100 × max(khối lượng lô / tải trọng xe, thể tích lô / thể tích xe) |
| Mức sẵn sàng | 100 − 25 × số chuyến đang chờ hoặc đã nhận trong 24 giờ qua (tối thiểu 0), để chia đều việc |
| Phù hợp khu vực | 100 × tỉ lệ điểm lấy và giao của các đơn trong lô nằm trong khu vực tài xế phụ trách |

## Cấu trúc thư mục

| Thư mục | Nội dung |
|---|---|
| `backend/` | Mã nguồn backend Spring Boot và các migration cơ sở dữ liệu |
| `frontend/` | Ứng dụng Flutter |
| `api/` | Đặc tả REST API |
| `docs/` | Kịch bản chạy thử bằng tay và câu SQL truy vết một đơn hàng |
| `docker-compose.yml` | PostgreSQL + PostGIS, RabbitMQ, MailHog |

## Cài đặt và chạy

**1. Khởi động cơ sở dữ liệu, RabbitMQ và MailHog** (tại thư mục gốc của dự án)

```
docker compose up -d
```

**2. Chạy backend kèm dữ liệu mẫu**

```
cd backend
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--app.security.refresh-cookie-secure=false"
```

Trong Command Prompt (cmd), gõ `mvnw` thay vì `./mvnw`. Kiểm tra backend đã chạy: http://localhost:8081/actuator/health

**3. Chạy ứng dụng Flutter trên Android Emulator**

```
cd frontend
flutter pub get
flutter emulators --launch <tên emulator>
flutter run
```

Emulator gọi backend qua địa chỉ `http://10.0.2.2:8081`.

### Tài khoản mẫu

Mật khẩu chung: `Passw0rd!dev`. Có thể đăng nhập bằng email hoặc phần tên đứng trước `@`.

| Tài khoản | Vai trò |
|---|---|
| customer@dev.local | Khách hàng |
| dispatcher@dev.local | Điều phối viên (có quyền ghi đè đề xuất) |
| driver@dev.local, driver2@dev.local | Tài xế |
| ops@dev.local | Quản lý vận hành |
| admin@dev.local | Quản trị hệ thống |

### Công cụ khi phát triển

- RabbitMQ: http://localhost:15672 (logistics / logistics)
- MailHog, để xem email OTP và thông báo: http://localhost:8025
- Vào cơ sở dữ liệu: `docker compose exec postgres psql -U logistics -d logistics`
- Xóa sạch dữ liệu để chạy lại từ đầu: `docker compose down -v`

## Kiểm thử

- Backend: chạy `./mvnw test` trong thư mục `backend` (cần Docker đang chạy). Bộ test gồm test đơn vị, test luồng nghiệp vụ qua HTTP, test tranh chấp đồng thời và test hiệu năng.
- Flutter: chạy `flutter test` trong thư mục `frontend`.
- Chạy thử bằng tay trên ứng dụng: xem `docs/manual-test-script.md`.

## Lưu ý bảo mật

Các khóa bí mật trong `application.yml` chỉ là giá trị mặc định cho môi trường phát triển. Khi triển khai thật phải đặt các biến môi trường `JWT_SECRET`, `OTP_PEPPER` và `EVIDENCE_URL_SECRET`.
