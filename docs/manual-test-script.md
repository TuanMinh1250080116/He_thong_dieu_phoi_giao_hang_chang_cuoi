# Kịch bản chạy thử bằng tay trên app

Mục đích: bấm thử các màn hình chưa được kiểm tra bằng test tự động. Ghi kết quả (đạt / lỗi + mô tả) vào cột cuối.

Chuẩn bị: chạy backend (profile `dev`, có dữ liệu mẫu), mở app Flutter trên emulator (cổng 8081). Mật khẩu mọi tài khoản mẫu: `Passw0rd!dev`. Tài khoản mẫu cũng đăng nhập được bằng tên trước `@` (ví dụ `dispatcher`). Bản đồ cần máy có Internet. Màn hình đăng nhập (bản debug) có nút điền sẵn từng tài khoản.

| Tài khoản | Vai trò |
|---|---|
| customer@dev.local | Khách hàng |
| dispatcher@dev.local | Điều phối viên (có quyền ghi đè đề xuất) |
| driver@dev.local, driver2@dev.local | Tài xế |
| ops@dev.local | Quản lý vận hành |
| admin@dev.local | Quản trị hệ thống |

Giao diện thích ứng: thử cả cửa sổ hẹp (điện thoại, thanh điều hướng dưới hoặc ngăn kéo) và rộng (thanh bên trái).

## 0. Chung

| # | Thao tác | Kết quả mong đợi | Kết quả |
|---|---|---|---|
| 0.1 | Đăng nhập sai mật khẩu 1 lần | Báo lỗi, không vào app | |
| 0.2 | Đăng nhập đúng, bỏ chọn "Ghi nhớ đăng nhập", đóng hẳn app và mở lại | Phải đăng nhập lại | |
| 0.3 | Bấm biểu tượng tài khoản (góc) | Màn "Hồ sơ và phiên đăng nhập": họ tên, vai trò, giờ đăng nhập | |
| 0.4 | Ở màn hồ sơ, bấm "Làm mới phiên" rồi "Đăng xuất" | Báo đã làm mới; đăng xuất có hộp xác nhận rồi về màn đăng nhập | |
| 0.5 | Bật chế độ tối của máy | Màu và chữ vẫn đọc rõ, huy hiệu trạng thái có biểu tượng và chữ | |

## A. Khách hàng (customer@dev.local)

| # | Thao tác | Kết quả mong đợi | Kết quả |
|---|---|---|---|
| A1 | "Tạo đơn mới": chọn "Điểm lấy (A)" rồi chạm bản đồ, chọn "Điểm giao (B)" rồi chạm bản đồ (hoặc dùng địa điểm mẫu), điền người nhận, "Lưu nháp" | Hai ghim A, B hiện trên bản đồ, ô tọa độ tự điền; đơn ở trạng thái Nháp | |
| A1b | Bấm biểu tượng tìm cạnh ô địa chỉ | Nếu backend chưa bật tìm địa chỉ thì báo hãy ghim trên bản đồ; nếu đã bật (`app.geocoding.enabled=true`) thì ghim tự nhảy tới địa chỉ | |
| A2 | Mở đơn nháp, "Sửa đơn", đổi khối lượng, lưu | Thông tin mới được lưu | |
| A3 | "Gửi điều phối" | Trạng thái Chờ điều phối; "Hành trình đơn hàng" có mốc "Đã tiếp nhận" | |
| A4 | Ở danh sách, lọc theo trạng thái, theo ngày giao, theo mã đơn | Bảng lọc đúng; "Xóa lọc" trả về đủ | |
| A5 | Chọn một đơn chờ điều phối, "Hủy đơn", nhập lý do | Đơn chuyển Đã hủy | |

## B. Điều phối viên (dispatcher@dev.local)

Cần ít nhất 3 đơn chờ điều phối từ phần A.

| # | Thao tác | Kết quả mong đợi | Kết quả |
|---|---|---|---|
| B1 | Tab "Tạo lô": chọn 2 đơn; xem phần "Tổng hợp lô"; "Tạo lô" | Mở màn lô ở trạng thái Nháp | |
| B2 | "Thêm/bớt đơn": thêm đơn thứ 3, gỡ đơn thứ nhất, lưu | Số đơn và tổng khối lượng cập nhật | |
| B3 | Gỡ hết đơn rồi lưu | Báo lỗi: lô phải giữ ít nhất 1 đơn | |
| B4 | "Khóa lô", rồi "Tạo đề xuất" | Có bảng xếp hạng tài xế với 4 cột điểm | |
| B5 | "Mở lại lô..." nhập lý do | Lô về Nháp, đề xuất cũ không còn dùng được | |
| B6 | Khóa lại, tạo đề xuất; ở dòng hạng 2 bấm "Ghi đè...", xem bảng so sánh điểm, nhập lý do | Phân công được tài xế đã chọn | |
| B7 | Khi đã gán tài xế: thử "Mở lại lô..." hoặc "Hủy lô..." | Bị từ chối, yêu cầu hủy điều phối trước | |
| B8 | Tab "Bảng điều phối": xem 8 ô số liệu, bấm ô có số đỏ | Bảng "Việc cần xử lý" lọc theo ô; bấm "Bỏ lọc" trả lại | |
| B9 | Trong bảng điều phối, tab "Chuyến đang chạy": lọc trạng thái, "Có sự cố" | Bảng đúng; chọn chuyến có sự cố rồi "Xem sự cố của chuyến" | |

## C. Tài xế (driver@dev.local)

| # | Thao tác | Kết quả mong đợi | Kết quả |
|---|---|---|---|
| C1 | Tab "Chuyến giao": xem nhóm "Trạng thái làm việc" | Hiện trạng thái, phương tiện, số chuyến chờ phản hồi | |
| C2 | Chọn "Trực tuyến", bấm "Cập nhật" | Báo đã cập nhật | |
| C3 | "Cập nhật vị trí" (máy thật) hoặc "Vị trí thử nghiệm (debug)" | Báo đã cập nhật, hiện giờ đồng bộ | |
| C4 | Bấm cập nhật vị trí hơn 6 lần trong 1 phút | Báo quá nhiều yêu cầu | |
| C5 | Chọn chuyến được giao ở B6, "Xem chi tiết", "Chấp nhận" | Trạng thái chuyển Đã nhận | |
| C6 | Với chuyến khác: "Từ chối...", chọn lý do (thử "Khác" không nhập mô tả) | Nút Từ chối bị khóa đến khi có mô tả | |
| C6b | Mở chi tiết chuyến | Bản đồ hiện các điểm giao đánh số và lộ trình đường bộ (hoặc đường nối thẳng nếu không gọi được OSRM) | |
| C7 | "Bắt đầu giao"; chọn một điểm giao, "Bắt đầu điểm giao", "Đã đến nơi" | Trạng thái điểm giao đổi theo | |
| C8 | "Nộp bằng chứng...": lấy vị trí, chụp ảnh, ký vào ô chữ ký, nộp | Báo đã nộp; điểm giao hoàn tất | |
| C9 | Ở điểm giao khác: "Báo cáo sự cố...", chọn loại, mức độ, mô tả, đính ảnh, gửi | Báo đã gửi mã sự cố | |
| C10 | Tab "Lịch sử": lọc theo ngày và trạng thái | Chỉ chuyến của tài xế này | |

## D. Quản lý vận hành (ops@dev.local)

| # | Thao tác | Kết quả mong đợi | Kết quả |
|---|---|---|---|
| D1 | "Đội xe" > "Phương tiện": chọn một xe, sửa, "Ngừng hoạt động", "Kích hoạt lại"; "Thêm mới" | Danh sách và form cập nhật đúng | |
| D2 | Tab "Tài xế": "Thêm mới", chọn tài khoản chưa có hồ sơ, xe, vùng | Tài xế xuất hiện trong danh sách | |
| D3 | Ngừng hoạt động tài xế đang có chuyến chưa xong | Bị từ chối, nêu rõ chuyến đang vướng | |
| D4 | Tab "Sự cố": mở sự cố từ C9, xem ảnh, "Điều tra", rồi "Giải quyết" | Trạng thái đổi theo, lịch sử có ghi chú | |
| D5 | Tab "Cấu hình" > "Quy tắc điều phối và POD": tạo quy tắc với tổng trọng số khác 1 | Có cảnh báo, nút Lưu bị khóa | |
| D6 | Sửa tổng về 1, bấm "Lưu nháp" rồi "Kích hoạt" ở bảng | Phiên bản mới ở trạng thái Còn hiệu lực | |
| D6b | Ở bảng phiên bản, bấm "Kích hoạt..." một bản nháp, chọn "Hẹn giờ hiệu lực" sau 2 phút | Trạng thái "Đã hẹn kích hoạt"; sau khoảng 2 phút (tối đa thêm 15 giây) tự thành "Còn hiệu lực", bản cũ "Đã ngưng" | |
| D6c | Hẹn một bản khác rồi bấm "Hủy hẹn" | Bản đó quay về nháp | |
| D7 | Tương tự với "Chính sách bằng chứng" (chọn ALL_OF, thêm chữ ký) | Lưu và kích hoạt được | |
| D8 | Tab "Vùng giao hàng": thêm vùng, chọn vùng, "Vẽ vùng", chạm hai góc trên bản đồ, "Lưu ranh giới đã vẽ", kích hoạt | Vùng chuyển sang "Đang hoạt động"; chi tiết hiện cấu hình điều phối và chính sách POD đang áp dụng | |
| D9 | Chọn vùng còn đơn chờ điều phối, bấm "Ngừng dùng...", nhập lý do | Báo chưa ngừng được và nêu số đơn, số lô còn mở | |
| D10 | Chọn vùng không còn đơn mở, "Ngừng dùng..."; sau đó "Kích hoạt lại vùng..." | Vùng hiện "Đã ngừng dùng" rồi trở lại hoạt động | |

## E. Quản trị (admin@dev.local)

| # | Thao tác | Kết quả mong đợi | Kết quả |
|---|---|---|---|
| E1 | "Người dùng": lọc, chọn một dòng, "Xem / sửa" | Màn thông tin người dùng, vai trò hiển thị dạng tick | |
| E2 | "Sửa vai trò và phạm vi", đổi vai trò, nhập lý do | Lưu được; không tự đổi vai trò của chính mình | |
| E2c | Đăng xuất, đăng nhập lại bằng tên đăng nhập vừa đặt (viết hoa cũng được) | Đăng nhập thành công | |
| E2b | "Thêm mới" người dùng có tên đăng nhập và số điện thoại (thử "abc" trước) rồi "Sửa thông tin" đổi số | Số sai bị từ chối; số đúng hiển thị ở màn thông tin | |
| E3 | "Vai trò và quyền": chọn vai trò OPS_MANAGER, bỏ một quyền, nhập lý do, lưu; sau đó hoàn tác bằng cách tick lại và lưu | Lưu được, có dòng trong "Nhật ký" | |
| E4 | Thử bỏ quyền quản lý người dùng của vai trò quản trị | Bị từ chối | |
| E5 | "Nhật ký": lọc theo hành động, chọn một dòng | Hiện khối "Chi tiết bản ghi" trước và sau | |

## F. Nhân viên hủy đơn đã vào batch

| # | Thao tác | Kết quả mong đợi | Kết quả |
|---|---|---|---|
| F1 | Tạo lô từ 2 đơn, khóa, tạo đề xuất. Dùng API (hoặc công cụ khác) để nhân viên hủy một đơn trong lô | Đơn bị hủy, lô về Nháp còn 1 đơn | |
| F2 | Hủy nốt đơn còn lại | Lô tự chuyển Đã hủy | |

Ghi chú: app chưa có màn cho nhân viên mở và hủy một đơn bất kỳ, nên F1 và F2 kiểm tra qua API (`POST /api/v1/orders/{id}/cancel`).
