# GeoDuel — Game đoán địa điểm qua hình ảnh đối kháng online (LTM)

Đồ án Lập Trình Mạng: game 1v1 kiểu GeoGuessr. Server TCP + nhiều client Swing, MySQL.
Đặc tả gốc: `LTM.md` (file DOCX đổi tên) — bản text đã trích xuất: `docs/REQUIREMENTS.txt`.

## Lệnh thường dùng

```
mvn -q compile                 # biên dịch
mvn -q test                    # unit test (logic thuần, không cần DB)
mvn -q package -DskipTests     # tạo fat-jar target/geoduel.jar
scripts\init-db.bat            # tạo schema MySQL (database: geoduel, geoduel_test)
scripts\seed-assets.bat        # nạp địa điểm + ảnh vào DB (chạy sau init-db)
scripts\run-server.bat         # chạy server (cổng 5555)
scripts\run-client.bat         # chạy 1 client (chạy nhiều lần để mở nhiều client)
scripts\run-integration.bat    # kiểm thử tích hợp E2E bằng bot (cần DB + assets)
scripts\run-gui-test.bat       # kiểm thử khói GUI: 2 cửa sổ client tự chơi 1 trận + chụp ảnh
scripts\collect-assets.bat     # thu thập ảnh KartaView + ghép bản đồ (chạy lại an toàn)
```

MySQL local: `localhost:3306`, user `root`, mật khẩu trong `config.properties` (không hard-code trong mã nguồn). Database chính: `geoduel`, database kiểm thử: `geoduel_test` — KHÔNG đụng vào các database khác đang có trên máy.

## Kiến trúc

- `common/` — giao thức (JSON từng dòng qua TCP), Haversine, tính điểm, Elo. Dùng chung cho server, client, bot.
- `server/` — `ServerMain` accept vòng lặp; mỗi client 1 thread (`ClientHandler`); `SessionRegistry` giữ người online + trạng thái; `MatchEngine` là máy trạng thái 1 trận (5 lượt × 30s, timer phía server bằng `ScheduledExecutorService`); `dao/` truy cập MySQL.
- `client/` — Swing + FlatLaf; `NetworkClient` có thread đọc riêng, mọi cập nhật UI qua `SwingUtilities.invokeLater`; `MainFrame` chuyển màn hình bằng CardLayout.
- `sim/` — `BotClient` headless nói đúng giao thức; `IntegrationSim` chạy các kịch bản E2E (trận đầy đủ, timeout, thoát giữa trận, mất kết nối) và in PASS/FAIL.
- `tools/AssetSeeder` — đọc ảnh thô đã tải về, re-encode JPEG (xoá sạch EXIF/GPS), resize ≤1280px, ghi vào `assets/locations/`, insert DB.

## Luật bất biến của trò chơi (theo đặc tả + quyết định của chủ đồ án — không được đổi)

1. Server là nguồn chân lý duy nhất: toạ độ đích chỉ tồn tại ở server; client CHỈ gửi toạ độ dự đoán. Ảnh gửi đi không kèm bất kỳ metadata/toạ độ nào.
2. Trận = 5 lượt; mỗi lượt tối đa `round.duration.sec` (180 s — chủ đồ án yêu cầu 3-4 phút), thời gian do SERVER quản lý (client chỉ hiển thị). Hết giờ chưa gửi → lượt đó 0 điểm. **Một bên đã nộp → bên kia chỉ còn tối đa `round.snipe.sec` (15 s); server phát `TIMER_SYNC` cho cả hai.**
3. Đã bấm Send là khoá dự đoán của lượt, không đổi được.
4. Điểm lượt: `round(5000 * exp(-d_km / decay))`, tối đa 5000; `decay` đặt trong config (`score.decay.km`, phạm vi TOÀN THẾ GIỚI dùng 2000 km). Điểm trận = tổng 5 lượt. Điểm trận ≠ Elo.
5. Elo: khởi tạo 1000, K=32, `E = 1/(1+10^((Rb-Ra)/400))`, `R' = R + K(S-E)` làm tròn về số nguyên gần nhất. Chỉ cập nhật khi trận kết thúc.
6. Kết quả lượt hiển thị 5 giây rồi server tự bắt đầu lượt kế.
7. Thoát giữa trận hoặc mất kết nối = xử thua, đối thủ thắng, Elo cập nhật như trận bình thường; đối thủ được trả về trạng thái rảnh.
8. Mời đấu: chỉ mời được người đang rảnh; server kiểm tra trạng thái CẢ HAI trước khi chuyển lời mời.
9. Bảng xếp hạng: Elo giảm dần → số trận thắng giảm dần → tổng điểm trong các trận giảm dần.
10. Chơi lại: cần CẢ HAI đồng ý; một người Thoát → phiên kết thúc, cả hai về rảnh.

## Quy tắc phát triển

- **Server không tin client.** Mọi thông điệp từ client phải được kiểm tra: đúng phiên, đúng trận, đúng lượt, đúng trạng thái. Sai thì bỏ qua + log, không crash.
- **Mọi truy cập trạng thái chia sẻ phải đồng bộ.** `SessionRegistry` và `MatchEngine` synchronized trên lock riêng; không gọi I/O mạng khi đang giữ lock nếu tránh được; gửi tin cho client qua hàng đợi ghi riêng của handler.
- **Không nuốt exception.** Log đầy đủ (logger đơn giản ra console, có timestamp). Exception trong 1 handler không được làm chết server.
- **SQL luôn dùng PreparedStatement.** Không nối chuỗi SQL. Mật khẩu băm PBKDF2WithHmacSHA256 (65536 vòng, salt 16 byte); không bao giờ lưu/ghi log mật khẩu thô.
- **Giao thức chỉ sửa ở `common/Msg.java` + `docs/PROTOCOL.md`** — hai file này phải luôn khớp nhau. Thêm message mới = thêm hằng số, cập nhật tài liệu, xử lý cả hai đầu.
- **Mọi thay đổi logic điểm/Elo/Haversine phải kèm unit test.** Chạy `mvn -q test` trước khi coi là xong.
- **UI: mọi cập nhật component chỉ trên EDT.** Thread mạng không được đụng Swing trực tiếp.
- **Sau thay đổi lớn ở server/client: chạy `scripts\run-integration.bat`** — tất cả kịch bản phải PASS.

## Luật giao diện (chống "AI hoá")

- Chủ đề: **bản đồ thám hiểm ban ngày** (chủ đồ án yêu cầu giao diện TƯƠI SÁNG) — nền giấy ấm `#F2EDE3`, thẻ `#FBF8F1`, ô nhập trắng; nhấn hổ phách đồng `#D9913B` (mình) + xanh ngọc `#2F8B8E` (đối thủ) + đỏ đất `#C0524A` (nguy hiểm). Vùng ảnh manh mối giữ letterbox tối `#14181E` cho nổi ảnh (chữ trên ảnh dùng `Theme.ON_PHOTO`). KHÔNG gradient tím/hồng, không emoji làm icon.
- Chữ: Segoe UI (tiêu đề Semibold), toạ độ/đồng hồ dùng Consolas. Chữ chính mực `#2A3138` trên nền sáng (tương phản ≥ 4.5:1), phụ `#66707D`. Mọi màu lấy từ `Theme.java`, không hard-code rải rác.
- Component tự vẽ: panel bo góc 12px, nút dạng pill có trạng thái hover/pressed rõ, con trỏ bàn tay trên mọi thứ bấm được, focus nhìn thấy được. Icon vẽ bằng Java2D (la bàn, ghim, đồng hồ) — không dùng emoji.
- Mọi chữ tiếng Việt có dấu phải hiển thị đúng (file nguồn UTF-8, chạy JVM với `-Dfile.encoding=UTF-8`).
- Bản đồ: TOÀN THẾ GIỚI, slippy-map thật trong `MapPanel` — tải tile Carto Voyager theo nhu cầu (zoom 2–17, Web Mercator), cache RAM + đĩa (`assets/tilecache/`); zoom bằng con lăn quanh vị trí chuột, kéo để pan, click đặt ghim. Bắt buộc ghi công "© OpenStreetMap contributors © CARTO" trên bản đồ và "Ảnh: KartaView/Mapillary · CC BY-SA 4.0" cạnh ảnh.
- Màn trận đấu kiểu GeoGuessr: ảnh tràn màn hình (`PhotoCanvas` phẳng có zoom soi biển hiệu / `PanoViewer` xoay 360° khi ảnh `pano=true`), HUD nổi (pill mờ), mini-map góc phải dưới tự phóng to khi rê chuột, kết quả lượt = bản đồ toàn màn hình + dải kết quả.

## Quy ước mã nguồn

- Java 17+ (đang dùng JDK 24), mã hoá UTF-8, package gốc `com.ltm.geoduel`.
- Tên định danh tiếng Anh; chuỗi hiển thị cho người dùng tiếng Việt.
- Không thêm dependency mới nếu chưa thật cần (hiện chỉ: mysql-connector-j, gson, flatlaf, junit5).
- Ảnh địa điểm nằm ở `assets/locations/<slug>/<n>.jpg`; DB chỉ lưu đường dẫn tương đối. Bản đồ: `assets/map/vietnam.png` + `vietnam.json`.
- Nguồn dữ liệu ảnh (đều CC BY-SA 4.0, ghi chung vào `assets/raw/manifest.json`, chạy lại an toàn):
  - **KartaView** (`tools/KartaViewCollector`) — ảnh phẳng Việt Nam; API: radius tối đa 2000 m, itemsPerPage tối đa 100, hay 429 + tarpit (giữ kết nối treo) → bắt buộc watchdog sendAsync+get(timeout).
  - **Mapillary** (`tools/MapillaryCollector`) — toàn cầu, ưu tiên ảnh 360° (`is_pano`); CẦN token trong `config.properties` (`mapillary.token`, lấy ở mapillary.com/dashboard/developers). Manifest phần tử files dạng `{"f": tên, "pano": bool}` (chuỗi trần = ảnh phẳng cũ).
  - Mọi ảnh qua bộ lọc `tools/ImageQuality` (độ nét Laplacian ≥100 với ảnh phẳng, ≥50 với pano; độ sáng 35–220) + chống trùng nội dung bằng SHA-1 ảnh đích.
- `tile.openstreetmap.org` bị chặn DNS trên nhiều mạng VN — chỉ dùng tile Carto.
