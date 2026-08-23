# GeoDuel — Game đoán địa điểm qua hình ảnh đối kháng online

Đồ án **Lập Trình Mạng**: hai người chơi so tài đoán vị trí chụp của ảnh đường phố
thật trên bản đồ thế giới, theo thời gian thực — mini GeoGuessr 1v1.

- **Kiến trúc**: 1 server TCP đa luồng + nhiều client desktop (Java Swing).
- **Giao thức**: JSON từng dòng qua TCP — đặc tả đầy đủ ở `docs/PROTOCOL.md`.
- **CSDL**: MySQL (`geoduel`) — tài khoản, địa điểm, ảnh, trận đấu, lịch sử, Elo.
- **Luật chơi**: trận 5 lượt × 3 phút; mỗi lượt cả hai cùng nhận 3–5 ảnh manh mối
  (ảnh phụ trong bán kính 200 m quanh toạ độ đích; ảnh 360° xoay được như GeoGuessr);
  **một bên nộp trước → bên kia chỉ còn 15 giây**; điểm = `round(5000·e^(−d/2000km))`
  theo khoảng cách Haversine; thắng nhờ tổng điểm; Elo K=32 cập nhật sau trận;
  thoát/mất kết nối giữa trận = xử thua.

## Yêu cầu

- JDK 17+ (đã kiểm thử JDK 24), Maven 3.9+, MySQL 8/9 ở `localhost:3306`, có Internet
  (bản đồ tải tile Carto lúc chạy, có cache đĩa).

## Thiết lập sau khi clone (dành cho thành viên nhóm)

Repo đã kèm sẵn **ảnh địa điểm đã xử lý** (`assets/locations/`) và **dữ liệu DB**
(`database/seed-data.sql`) — KHÔNG cần token hay thu thập lại gì để chạy được ngay:

```
copy config.example.properties config.properties            (rồi điền mật khẩu MySQL)
copy config-test.example.properties config-test.properties  (rồi điền mật khẩu MySQL)
mvn package -DskipTests        # dịch + đóng gói target\geoduel.jar
scripts\init-db.bat            # tạo database geoduel + geoduel_test (nhập mật khẩu MySQL)
scripts\import-data.bat        # nạp địa điểm + tài khoản demo1..demo4 (mật khẩu 123456)
scripts\run-integration.bat    # (khuyến nghị) kiểm thử E2E — tất cả phải PASS
```

Ghi chú: nhạc nền và cache bản đồ tự sinh khi chạy client lần đầu, không có trong repo.

## Mở rộng kho ảnh (tuỳ chọn)

```
scripts\collect-assets.bat     # tải thêm ảnh KartaView (Việt Nam) — chạy lại được, tự nối tiếp
java -cp target\geoduel.jar com.ltm.geoduel.tools.MapillaryCollector   # ảnh 360° toàn cầu (cần token, xem dưới)
scripts\seed-assets.bat        # xoá EXIF/GPS, resize, nạp DB
scripts\export-db.bat          # cập nhật database\seed-data.sql để commit cho cả nhóm
```

### Thêm ảnh 360° toàn cầu (Mapillary — khuyến nghị, cần token miễn phí)

1. Đăng nhập https://www.mapillary.com/dashboard/developers → **Register application**
   (điền tên bất kỳ) → copy **Client Token** (dạng `MLY|...`).
2. Dán vào `config.properties`: `mapillary.token=MLY|...`
3. Chạy: `java -cp target\geoduel.jar com.ltm.geoduel.tools.MapillaryCollector`
4. Nạp lại DB: `scripts\seed-assets.bat`

## Chơi trên một máy

```
scripts\run-server.bat         # cửa sổ 1: server, cổng 5555
scripts\run-client.bat         # client thứ nhất (demo1/123456)
scripts\run-client.bat         # client thứ hai (demo2/123456)
```

## Chơi qua mạng LAN (mỗi người một máy)

KHÔNG cần máy server riêng — server chạy chung máy với một người chơi.
Client ở máy khác **không cần MySQL, không cần ảnh** (mọi dữ liệu server gửi qua TCP).

**Máy chủ trận (người A):**
1. Chạy `scripts\allow-firewall.bat` bằng chuột phải → *Run as administrator*
   (mở cổng 5555; chỉ cần làm một lần).
2. Xem IP LAN của mình: `ipconfig` → dòng *IPv4 Address* của Wi-Fi/Ethernet
   (ví dụ `192.168.0.101`).
3. `scripts\run-server.bat`, rồi `scripts\run-client.bat` để tự chơi.

**Máy người B (cùng Wi-Fi/mạng với A):**
1. Clone repo, cài JDK 17+ và Maven, chạy `mvn package -DskipTests`
   (không cần MySQL, không cần sửa config).
2. `scripts\run-client-lan.bat` → nhập IP của máy A → đăng nhập/tạo tài khoản và chơi.

Khác mạng (xa nhau): cài [Tailscale](https://tailscale.com) hoặc Radmin VPN trên cả
hai máy (tạo mạng LAN ảo miễn phí), rồi làm y hệt các bước trên với IP ảo đó.

Sảnh → chọn người **đang rảnh** → **Mời thi đấu** → bên kia **Chấp nhận** → vào trận.

**Điều khiển trong trận**
- Ảnh phẳng: lăn chuột phóng to soi biển hiệu, kéo để xem, nhấp đúp thu về.
- Ảnh 360°: kéo chuột xoay nhìn quanh, lăn chuột đổi góc nhìn (có dải la bàn).
- Chuyển ảnh manh mối: nút `‹ ›` góc trái dưới hoặc các chấm.
- Bản đồ (góc phải dưới, rê chuột vào để phóng to): lăn = zoom tới mức phố,
  kéo = di chuyển, click = đặt ghim → **GỬI DỰ ĐOÁN** (gửi rồi không đổi được).
- Sau mỗi lượt: bản đồ toàn màn hình hiện đáp án + ghim hai bên trong 5 giây.

## Kiểm thử

- `mvn test` — 20 unit test (Haversine, điểm, Elo, băm mật khẩu, giao thức).
- `scripts\run-integration.bat` — 8 kịch bản server thật + bot TCP thật (DB `geoduel_test`):
  trận đầy đủ + Elo + DB, khoá dự đoán sau Send, ép 15 giây khi một bên đã nộp,
  hết giờ 0 điểm, thoát trận/mất kết nối xử thua, từ chối lời mời, mời người đang bận,
  tự mời bị chặn, người mời thoát trước khi bên kia trả lời, chơi lại, BXH/lịch sử,
  đồng bộ trạng thái FREE/BUSY.
- `scripts\run-gui-test.bat` — 2 cửa sổ client thật tự chơi 1 trận, chụp ảnh màn hình.

## Cấu trúc

```
src/main/java/com/ltm/geoduel/
├── common/    giao thức + Haversine + điểm + Elo (dùng chung 3 phía)
├── server/    ServerMain, GameServer, ClientHandler, MatchEngine, dao/
├── client/    ClientMain, net/, ui/ (Swing + FlatLaf; slippy map, PhotoCanvas, PanoViewer 360°)
├── tools/     KartaViewCollector, MapillaryCollector, ImageQuality, AssetSeeder, VietnamMapBuilder
└── sim/       BotClient, IntegrationSim, GuiSmokeTest (kiểm thử E2E)
```

## Ghi công dữ liệu

- Ảnh địa điểm: [KartaView](https://kartaview.org) + [Mapillary](https://mapillary.com)
  — CC BY-SA 4.0 (đã xoá toàn bộ EXIF/GPS khi seed; server không bao giờ gửi toạ độ
  đích cho client trước khi lượt kết thúc).
- Bản đồ: © OpenStreetMap contributors © CARTO.
