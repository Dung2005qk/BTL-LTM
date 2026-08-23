# Giao thức GeoDuel (TCP, JSON từng dòng, UTF-8)

Mỗi thông điệp là **một dòng JSON** kết thúc bằng `\n`:
```json
{"type":"<TÊN>", "data":{...}}
```
Hằng số tên thông điệp: `com.ltm.geoduel.common.Msg` — file này và tài liệu này phải luôn khớp nhau.

Ảnh manh mối được gửi dạng Base64 trong JSON (đã xoá sạch EXIF/GPS từ khâu seed).
Server không bao giờ gửi toạ độ đích trước khi lượt kết thúc.

## Client → Server

| type | data | ghi chú |
|---|---|---|
| `REGISTER` | `{username, password, displayName}` | đăng ký tài khoản mới |
| `LOGIN` | `{username, password}` | |
| `LOGOUT` | `{}` | về màn hình đăng nhập |
| `GET_ONLINE` | `{}` | xin danh sách online |
| `GET_LEADERBOARD` | `{}` | |
| `GET_HISTORY` | `{}` | lịch sử đấu của chính mình |
| `INVITE` | `{target}` | mời người chơi `target` (đang rảnh) |
| `INVITE_RESPONSE` | `{inviteId, accepted}` | trả lời lời mời |
| `GUESS` | `{matchId, round, lat, lng}` | gửi dự đoán; chỉ nhận 1 lần/lượt |
| `LEAVE_MATCH` | `{matchId}` | chủ động thoát giữa trận → xử thua |
| `REMATCH_CHOICE` | `{matchId, again}` | `again=true`: Chơi lại; `false`: Thoát |
| `PING` | `{}` | heartbeat mỗi 10 s |

## Server → Client

| type | data | ghi chú |
|---|---|---|
| `REGISTER_RESULT` | `{ok, message}` | |
| `LOGIN_RESULT` | `{ok, message, profile{username, displayName, elo, wins, losses, draws}}` | |
| `ONLINE_LIST` | `{players:[{username, displayName, elo, status}]}` | đẩy tự động khi có thay đổi; `status`: FREE/BUSY |
| `LEADERBOARD` | `{rows:[{rank, username, displayName, elo, wins, losses, draws, totalScore}]}` | |
| `HISTORY` | `{rows:[{matchId, opponent, time, myScore, oppScore, result, eloChange, endReason}]}` | |
| `INVITE_INCOMING` | `{inviteId, from, fromDisplayName, fromElo}` | |
| `INVITE_RESULT` | `{ok, message}` | báo cho người mời khi bị từ chối/lỗi |
| `MATCH_START` | `{matchId, opponent{username, displayName, elo}, totalRounds, youAre}` | `youAre`: 1 hoặc 2 |
| `ROUND_START` | `{matchId, round, images:[{b64, pano}...], durationSec}` | cùng bộ ảnh cho cả hai; `pano=true` = ảnh toàn cảnh 360° (client hiển thị bằng viewer xoay) |
| `GUESS_ACK` | `{round}` | xác nhận đã khoá dự đoán |
| `OPPONENT_GUESSED` | `{round}` | đối thủ đã gửi |
| `TIMER_SYNC` | `{round, remainingSec}` | server ép lại thời hạn lượt (một bên đã nộp → bên kia chỉ còn `round.snipe.sec` giây); client đặt lại đồng hồ hiển thị |
| `ROUND_RESULT` | `{matchId, round, locationName, country, targetLat, targetLng, mine{lat,lng,distKm,score,guessed}, opp{...}, myTotal, oppTotal, nextInSec}` | hiển thị 5 s |
| `MATCH_END` | `{matchId, result, reason, myTotal, oppTotal, myEloChange, myNewElo, oppEloChange, oppNewElo}` | `result`: WIN/LOSE/DRAW; `reason`: NORMAL/OPPONENT_LEFT/OPPONENT_DISCONNECTED/YOU_LEFT |
| `REMATCH_WAIT` | `{}` | mình đã chọn Chơi lại, chờ đối thủ |
| `SESSION_END` | `{message}` | phiên đấu kết thúc (một bên Thoát/mất kết nối) → về sảnh |
| `ERROR` | `{message}` | lỗi nghiệp vụ, hiển thị cho người dùng |
| `PONG` | `{}` | trả lời PING |

## Luồng chính

1. `LOGIN` → `LOGIN_RESULT(ok)` → server đẩy `ONLINE_LIST` cho tất cả.
2. A `INVITE{target:B}` → server kiểm tra A, B đều FREE → B nhận `INVITE_INCOMING`.
   - B từ chối → A nhận `INVITE_RESULT{ok:false}`.
   - B đồng ý → cả hai nhận `MATCH_START`, trạng thái BUSY, đẩy `ONLINE_LIST`.
3. Mỗi lượt: `ROUND_START` (server hẹn giờ `round.duration.sec` — mặc định 180 s) → client `GUESS` → `GUESS_ACK`; đối thủ nhận `OPPONENT_GUESSED`. Nếu bên kia còn hơn `round.snipe.sec` (15 s) thì server rút hạn xuống 15 s và phát `TIMER_SYNC` cho cả hai. Khi cả hai đã gửi hoặc hết giờ → `ROUND_RESULT` → sau 5 s lượt kế / hoặc `MATCH_END`.
4. `MATCH_END` → client hiện Chơi lại/Thoát → `REMATCH_CHOICE`. Cả hai `again=true` → `MATCH_START` mới. Một bên `false` → cả hai nhận `SESSION_END`, trạng thái FREE.
5. `LEAVE_MATCH` hoặc mất kết nối giữa trận → trận kết thúc ngay: người rời bị xử thua, Elo cập nhật, người còn lại nhận `MATCH_END{reason:OPPONENT_LEFT|OPPONENT_DISCONNECTED}` rồi `SESSION_END`, trạng thái FREE.

## Công thức (cài tại `common/`)

- Khoảng cách Haversine (R = 6371.0088 km).
- Điểm lượt: `round(5000 * exp(-d_km / decay))`; `decay` = `score.decay.km` trong config (2000 km cho phạm vi toàn thế giới); không gửi dự đoán → 0.
- Elo: khởi tạo 1000, K = 32, `E = 1/(1+10^((Rb-Ra)/400))`, `R' = round(R + K(S-E))`, S ∈ {1, 0.5, 0}.
