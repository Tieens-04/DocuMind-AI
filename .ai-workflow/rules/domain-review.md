# DocuMind AI — Domain Review Checklist

Chỉ đối chiếu các nhóm quy tắc thực sự liên quan đến phạm vi của ticket hiện tại; không ép một ticket nhỏ phải kiểm tra mọi hạng mục ngoài phạm vi.

---

## 1. Vector RBAC & Zero Cross-Department Leakage (Bất biến số 1)

Khi ticket chạm `VectorStore`, `SearchRequest`, `Filter.Expression`, `document_chunks` hoặc phân quyền truy xuất:

- **Nguồn danh tính duy nhất**: Danh tính người gọi (`userId`, `departmentId`, `role`) **bắt buộc** lấy từ `SecurityContextHolder` (hoặc caller context đã xác thực ở tầng server), tuyệt đối không tin `departmentId` / `accessLevel` truyền từ query/body chưa kiểm quyền.
- **Cờ fallback dev**: `documind.security.dev-caller-fallback.enabled` bắt buộc mặc định là `false` trong `application.yml` và tuyệt đối không được bật trên `prod`.
- **Công thức `Filter.Expression` chuẩn theo vai trò**:
  - `STANDARD_EMPLOYEE` (`VIEWER`): `access_level == 'PUBLIC' || (department_id == <user.departmentId> && access_level == 'DEPARTMENT')` — **cấm tuyệt đối** lộ `CONFIDENTIAL` hoặc tài liệu `DEPARTMENT` của phòng ban khác, nhưng **không được bỏ sót** tài liệu `PUBLIC` toàn công ty.
  - `DEPARTMENT_MANAGER` (`EDITOR`): `access_level == 'PUBLIC' || department_id == <user.departmentId>` (bao gồm `DEPARTMENT` và `CONFIDENTIAL` của đúng phòng ban mình; ẩn phòng ban khác).
  - `SYSTEM_ADMIN`: Toàn quyền quản trị & kiểm toán trong phạm vi được ủy quyền.
  - Phải kiểm tra trường hợp user có `departmentId == null` (nếu có) không gây lỗi biểu thức SQL hoặc mở toang điều kiện `OR`.
- **Metadata bắt buộc & Tương thích Trigger PostgreSQL (`V4`, `V5`, `V7`)**:
  - Mọi chunk lưu vào `document_chunks` phải có `document_id`, `department_id`, `file_name`, `access_level` (`PUBLIC` | `DEPARTMENT` | `CONFIDENTIAL`), `doc_version`, `chunk_index` không rỗng và khớp 100% với bản ghi cha trong bảng `documents`.
  - **Cấm JSON `null` trong `Document.metadata`**: Spring AI `1.0.3` `PgVectorStore$DocumentRowMapper` gọi `Document.Builder` sẽ ném `IllegalArgumentException` nếu metadata chứa giá trị `null`. Với `.docx`/`.md`/`.txt` không có số trang, phải **bỏ hẳn key `page_number`** khỏi Map trước khi lưu (tương thích `jsonb_strip_nulls` của `V7`), còn `.pdf` phải lưu số nguyên `>= 1`.
- **HNSW Post-Filtering**: Giữ cấu hình phiên `SET hnsw.iterative_scan = 'relaxed_order'` (hoặc `SET LOCAL` tương ứng khi qua connection pooler) để bộ lọc RBAC chặt không làm hụt kết quả `topK`.

---

## 2. Anti-Hallucination, Citations & SSE Streaming

Khi ticket chạm luồng hỏi đáp RAG (`ChatClient`, `ChatModel`, controller SSE, lịch sử hội thoại):

- **Deterministic Refusal (`0` LLM Tokens)**:
  - Phải áp dụng `similarityThreshold` cấu hình được trên `SearchRequest`.
  - Khi `similaritySearch` trả về danh sách rỗng (`hits.isEmpty()`), **ngắt mạch ngay trong Java** và trả về chính xác chuỗi `"Không tìm thấy thông tin trong tài liệu nội bộ"` kèm cờ `abstained = true` — tuyệt đối không gọi LLM tự sinh câu từ chối (vừa tốn token vừa có nguy cơ bịa đặt).
- **Trích dẫn nguồn có cấu trúc (`Citations`)**:
  - Mọi câu trả lời có căn cứ phải đính kèm danh sách trích dẫn (`fileName`, `pageNumber` hoặc `sectionHeading`, `similarityScore`, `excerpt`) đồng nhất với các chunk thực tế đã nạp vào prompt.
- **Bảo toàn Token Accounting khi Stream**:
  - Stream qua `chatClient.prompt()...stream().chatResponse()` (không dùng `.stream().content()` làm rơi metadata `Usage`), thu thập `promptTokens` và `completionTokens` khi luồng hoàn tất để ghi chính xác vào `audit_logs`.
- **Thứ tự sự kiện SSE & Kiểm thử Reactive**:
  - Giữ đúng thứ tự sự kiện SSE (`citations` -> `token` -> `done`), xử lý sạch lỗi giữa chừng (timeout / upstream LLM 429/5xx) mà không treo kết nối.
  - Kiểm thử endpoint `text/event-stream` bằng `WebTestClient` hoặc `MockMvc` `asyncDispatch` (không assert đồng bộ trước khi `Flux` kết thúc).

---

## 3. Document Upload, Extraction, Smart Chunking & Virtual Threads

Khi ticket chạm upload MinIO, trích xuất văn bản, chia chunk hoặc xử lý bất đồng bộ:

- **Validation & Staging trước HTTP `202 Accepted`**:
  - Kiểm tra định dạng cho phép (`.pdf`, `.docx`, `.txt`, `.md`), magic bytes/MIME hợp lệ và kích thước `<= 50MB` ngay trên luồng nhận request.
  - Lưu luồng dữ liệu lên MinIO (`documind-documents`) hoặc file tạm an toàn **trước khi** trả `202 Accepted` (tránh lỗi `NoSuchFileException` do Servlet xóa `MultipartFile` sau khi kết thúc request).
- **An toàn File Lock trên Windows & Giới hạn Tài nguyên**:
  - Mọi `InputStream`, `PDDocument` (PDFBox), `TikaInputStream` và file tạm (`Files.createTempFile`) bắt buộc được đóng và xóa sạch trong khối `try-with-resources` / `finally` (tránh giữ khóa file trên Windows hoặc tràn Heap khi chạy 20 tác vụ `50MB` song song trên Virtual Threads).
  - `.docx` bắt buộc áp giới hạn chống OOXML ZIP-bomb (`ZipSecureFile` `minInflateRatio`, `maxEntrySize`, `maxFileCount`) và trần ký tự trích xuất (`max-extracted-chars`).
- **Định tuyến Reader & Chuẩn hóa Tiếng Việt**:
  - Không bao giờ đưa `.pdf` nhiều trang qua `TikaDocumentReader` thuần (gộp mất `page_number`); phải dùng pipeline bảo toàn từng trang (`PagePdfDocumentReader` / PDFBox per-page / Tika `<div class="page">` với `NO_OCR`), từ chối rõ ràng PDF khóa mật khẩu hoặc PDF ảnh scan `0` chữ.
  - Chuẩn hóa toàn bộ văn bản tiếng Việt về Unicode **`NFC`** (`Normalizer.Form.NFC`), loại bỏ `\u0000`, giữ nguyên dấu tiếng Việt.
- **Smart Chunking (`800 / 150`) & Idempotency**:
  - Cắt theo token `CL100K_BASE` có độ gối `chunkOverlap` và dò điểm ngắt tự nhiên (`\n\n` -> `\n` -> câu -> khoảng trắng), không bao giờ cắt đôi ký tự UTF-8 tiếng Việt sinh ra `\uFFFD` và không gộp lẫn nội dung khác trang / khác mục.
  - Sinh `Document.id` tất định (`ChunkIdGenerator` theo `documentId + docVersion + chunkIndex`) để retry ở worker không gây trùng lặp hoặc vỡ ràng buộc `UNIQUE (document_id, doc_version, chunk_index)`.
- **Vòng đời Trạng thái Tài liệu**:
  - Chuyển trạng thái nguyên tử: `PENDING -> PROCESSING -> COMPLETED` hoặc `FAILED` (ghi rõ mã/lý do lỗi để UI và người vận hành tra cứu; dọn dẹp chunk cũ khi re-index / xóa tài liệu).

---

## 4. Redis Semantic/Exact Cache, Rate Limiting & Audit Governance

Khi ticket chạm Redis, `semantic_cache`, Bucket4j hoặc `audit_logs`:

- **Chống Cache Poisoning Chéo Phòng Ban / Cấp Mật (CRITICAL)**:
  - Khóa cache (cả L1 Redis Exact Cache lẫn L2 Semantic Cache) **bắt buộc** ràng buộc chặt với phạm vi quyền đọc `(departmentId, allowedAccessLevels)` và `scope_version` (`V6`).
  - Câu hỏi giống hệt nhau giữa `DEPARTMENT_MANAGER` phòng `HR` và `STANDARD_EMPLOYEE` phòng `FIN` (hoặc nhân viên thường phòng `HR` khi nguồn là `CONFIDENTIAL`) **phải Cache MISS** ở phía quyền thấp hơn — tuyệt đối không trả lại câu trả lời đã cache của cấp cao hơn.
  - Khi tài liệu trong phòng ban hoặc tài liệu `PUBLIC` được thêm/sửa/xóa/đổi quyền, cơ chế tăng `scope_version` / vô hiệu hóa cache phải hoạt động nhất quán.
- **Hiệu năng & Rate Limiting**:
  - Tra cứu Redis cache `< 50ms`, phản hồi HTTP từ cache `< 100ms`, không gọi `ChatModel` khi cache hit (`0` token LLM phát sinh).
  - Bucket4j + Redis giới hạn đúng quota theo từng user / từng `Role`, trả HTTP `429 Too Many Requests` kèm header `Retry-After` chuẩn RFC.
- **Tính toàn vẹn `audit_logs` (`V4`)**:
  - Ghi nhận đầy đủ `user_id`, `department_id`, `prompt_tokens`, `completion_tokens`, `latency_ms`, `cache_hit`, `cache_tier`.
  - Phải thỏa ràng buộc DB `chk_audit_logs_cache_consistency`: `(cache_hit = FALSE AND cache_tier = 'NONE') OR (cache_hit = TRUE AND cache_tier IN ('L1', 'L2'))`.

---

## 5. Spring Security 6, REST API, Flyway & Hạ tầng Testcontainers

- **JWT & Quản lý Tài liệu theo Phòng ban**:
  - Stateless JWT (Access + Refresh token rotation/revocation), mật khẩu băm an toàn, không bao giờ ghi secret/token/password vào log.
  - `DEPARTMENT_MANAGER` chỉ được upload/cập nhật/xóa tài liệu thuộc đúng `departmentId` của mình (`403 Forbidden` nếu khác phòng ban); `STANDARD_EMPLOYEE` không có quyền ghi/xóa (`403`).
- **Clean Architecture & Error Handling**:
  - Tách bạch `controller` (DTO `record` + validation) -> `service` -> `domain` / `repository` -> `infrastructure`.
  - `GlobalExceptionHandler` giữ đúng chuẩn RFC 9110 / ProblemDetail và các HTTP headers cần thiết (như `WWW-Authenticate`, `Retry-After`), không lộ stack trace hay chi tiết SQL nội bộ ra client.
- **Flyway & JPA Safety**:
  - Giữ bất biến các bản migration đã phát hành (`V1..V7`); mọi thay đổi schema mới phải tạo file `V<N>__*.sql` kế tiếp trong `db/migration`, còn dữ liệu mẫu dev nằm tách biệt ở `db/seed` (cô lập hoàn toàn khỏi profile `prod`).
  - `spring.jpa.open-in-view: false` — không để phát sinh `LazyInitializationException` ngoài transaction; cẩn trọng khi so sánh proxy JPA (`JpaProxyUtils`).
- **Kỷ luật Testcontainers & Coexistence với FlintFlow**:
  - **Cấm mock** `VectorStore`, `JdbcTemplate`, `StringRedisTemplate`, hay Bucket4j `ProxyManager` trong integration tests; bắt buộc chạy trên Testcontainers thật (`pgvector/pgvector:pg16`, `redis:7-alpine`, MinIO) kèm `EmbeddingModel` stub tất định đúng chiều `1024`.
  - Không đụng chạm container `flintflow-*`; luôn giữ đúng dải cổng `.env` chuẩn của DocuMind AI (`5432`, `6380`, `9000/9001`, `8081`, `3002`, `11434`).

---

## 6. Next.js Frontend, SSE Client & Playwright Headed E2E

Khi ticket chạm `frontend/` hoặc kiểm thử trình duyệt:

- **SSE Streaming & Trạng thái Bất đồng bộ**:
  - Dùng `AbortController` hủy stream cũ khi người dùng chuyển phiên chat, rời trang hoặc bấm dừng, tránh race condition ghi đè tin nhắn giữa hai phiên.
  - Render Markdown an toàn (chống XSS từ nội dung tài liệu trích xuất), hiển thị rõ thẻ Citation (`file_name`, `page_number` / `#page=X` hoặc `section_heading`, `similarity_score`, `excerpt`).
  - Đầy đủ trạng thái Loading / Streaming cursor / Empty state / Error banner / Thông báo `429` thân thiện và vô hiệu hóa nút gửi chống double-submit.
- **Playwright E2E**:
  - Bắt buộc chạy ở chế độ hiển thị trình duyệt (**`--headed` / `headless: false`**) theo quy tắc bất biến của dự án.

---

## 7. Đánh giá Chất lượng RAG (`eval/`)

Khi ticket chạm bộ đánh giá Golden Dataset hoặc thay đổi thuật toán truy xuất / chunking / prompt:

- Không neo ground truth vào `chunk_id` biến động; luôn neo theo `(expectedFile, expectedPage, expectedKeyPhrase)` và ngữ cảnh RBAC của người hỏi.
- Dùng công thức nhị phân cấp câu hỏi cho **`Hit@5 >= 85%`** (không chia cho `5.0` khi chỉ 1 chunk chứa đáp án), kiểm tra cả `OOS_EXTERNAL` lẫn `OOS_RBAC` cho **`Hallucination Rate == 0%`**, và tách biệt truy vấn lặp hợp lệ với truy vấn chéo phòng ban cho **`Cache Hit Ratio >= 30%-40%`**.
