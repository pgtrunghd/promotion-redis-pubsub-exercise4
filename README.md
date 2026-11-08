# Bài tập 4 — Cập nhật giá khuyến mãi thời gian thực với Redis Pub/Sub

Đề bài: https://portal.rikkei.edu.vn/learn/159/homework/8350

Java 21, Spring Boot 3.5.6, Spring Data Redis, PostgreSQL. Hai module độc lập: `promotion-service` (publisher) và `product-service` (subscriber, có thể chạy nhiều instance).

## Luồng xử lý

```text
PUT giá -> Promotion Service -> UPDATE PostgreSQL -> COMMIT
          -> PUBLISH promotion-updates {"product_id":1}
          -> các Product Service nhận tin -> xóa products::1 khỏi Redis
GET sản phẩm tiếp theo -> cache miss -> đọc giá mới trong DB -> ghi cache JSON
```

- `PromotionService.changePrice` dùng `TransactionTemplate` để cập nhật giá; chỉ publish bằng `StringRedisTemplate.convertAndSend` sau khi transaction commit. Giá dùng `BigDecimal`, không dùng floating point.
- `RedisMessageListenerContainer` đăng ký channel chính xác **`promotion-updates`** trên mỗi instance Product Service.
- `PromotionSubscriber` đọc JSON `product_id`, gọi `cacheManager.getCache("products").evict(id)` để xóa đúng key do Spring Cache tạo ra. Evict không ảnh hưởng các sản phẩm khác; nhận lặp vẫn an toàn.
- `ProductService.get` dùng `@Cacheable`, key `products::{id}`. Cache lưu JSON có kiểu `ProductPrice`, TTL 30 phút; không lưu null/lỗi 404. Tất cả instance dùng chung Redis và DB.
- Tin nhắn JSON sai hoặc ID thiếu/không dương được bỏ qua và ghi log. Lỗi xóa Redis được chuyển tới cơ chế ghi log của listener container.
- Trường `subscribers` trong kết quả API là số subscription Redis nhận được lệnh PUBLISH; không phải xác nhận xử lý xong của từng subscriber.

## Chạy

Cần Java 21 và Docker; Python 3 để chạy smoke test.

```bash
docker compose up -d --wait
./gradlew test bootJar
```

Mở terminal riêng ở thư mục gốc cho mỗi tiến trình:

```bash
./gradlew :promotion-service:bootRun
./gradlew :product-service:bootRun
# Product Service thứ hai (tùy chọn):
SERVER_PORT=8089 ./gradlew :product-service:bootRun
```

Trên macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

Cổng mặc định: Product **8087**, Promotion **8088**, PostgreSQL **5446**, Redis **6386**. Biến cấu hình: `SERVER_PORT`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`. Database được giữ trong Docker volume; tài khoản `postgres/postgres` chỉ dùng local.

## Thử API

```bash
# Tạo sản phẩm qua publisher để chuẩn bị dữ liệu.
curl -sS -X POST localhost:8088/api/products \
  -H 'Content-Type: application/json' -d '{"name":"Sale phone","price":1000000}'

# Thay 1 bằng ID vừa tạo. GET đầu ghi cache giá cũ.
curl -sS localhost:8087/api/products/1
docker compose exec redis redis-cli GET products::1

# Đổi giá, commit DB rồi phát {"product_id":1} lên channel.
curl -sS -X PUT localhost:8088/api/promotions/products/1/price \
  -H 'Content-Type: application/json' -d '{"price":799000}'

# Khi subscriber xử lý xong: EXISTS = 0, lần GET sau lấy giá mới.
docker compose exec redis redis-cli EXISTS products::1
curl -sS localhost:8087/api/products/1
curl -sS localhost:8089/api/products/1
```

Có thể quan sát channel từ terminal khác bằng `docker compose exec redis redis-cli SUBSCRIBE promotion-updates`.

## Kiểm thử

```bash
./gradlew test bootJar
python3 scripts/pubsub_test.py
```

6 unit test: chỉ xóa ID được yêu cầu và xử lý lặp; bỏ qua tin lỗi; không che lỗi eviction; phát payload sau transaction; không publish khi commit thất bại hoặc không tìm thấy sản phẩm.

Script tích hợp khởi động Promotion Service (18088) và hai Product Service (18087, 18089) bằng JAR. Dùng Redis và PostgreSQL thật, xác nhận hai subscription đã đăng ký, làm nóng cache, đổi giá 5 lần rồi kiểm tra cache cũ bị xóa, hai instance đọc giá mới, cache sản phẩm khác còn nguyên và mỗi vòng hoàn tất dưới 1000 ms. Kiểm tra giá âm trả 400, sản phẩm không tồn tại trả 404. Script tự dừng ba JVM, xóa hai key cache kiểm thử; giữ sản phẩm trong DB local để xem xét.

## Giới hạn của Pub/Sub

Redis Pub/Sub không lưu lịch sử hay giao lại tin cho subscriber đang offline. Nếu publish lỗi sau commit, giá trong DB đã đổi nhưng cache có thể chưa bị xóa; hai bước DB/PUBLISH không phải transaction nguyên tử. Cache TTL 30 phút là giới hạn tồn tại của cache trong demo, không đảm bảo cập nhật dưới 1 giây khi subscriber mất kết nối. Nếu cần giao tin bền vững, dùng outbox cùng Redis Streams/Kafka.

Cache-aside cũng có thể gặp race khi một GET đang đọc giá cũ trước commit nhưng ghi cache sau eviction; demo kiểm thử luồng tuần tự cập nhật/rồi đọc, chưa cung cấp cơ chế versioning cho race đó. API tạo sản phẩm và sửa giá phục vụ thực hành local, chưa có xác thực/phân quyền quản trị.

Tham khảo: [Spring — Messaging with Redis](https://spring.io/guides/gs/messaging-redis/).
