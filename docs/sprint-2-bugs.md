# Bug và khoảng trống nghiệm thu Sprint 2 — Báo cáo Xử lý & Nghiệm thu

Review ban đầu ngày 23/11/2026 trên `main` tại commit `5ccdd5a`, phạm vi `DA-12` đến `DA-18`. [Board DA](https://nguyentien191004.atlassian.net/jira/software/projects/DA/boards/73/backlog)

> **Trạng thái tổng thể (Cập nhật ngày 23/11/2026):** **ĐÃ ĐÓNG TOÀN BỘ 6/6 HẠNG MỤC** (`4` bug bảo mật/RBAC `P1`–`P2` + `2` hạng mục hợp đồng thu hồi phiên & SLA độ trễ SSE thực tế). Toàn bộ suite `.\mvnw.cmd clean verify` đạt **451 test, 0 lỗi, 0 thất bại** (JaCoCo **89,38% dòng**, **89,01% instruction**), kèm benchmark live `-Dsmoke=true` với `Ollama (bge-m3)` + `Groq (openai/gpt-oss-120b)` đạt `BUILD SUCCESS`.

---

## Lỗi đã xác định qua review code & Kết quả khắc phục

### 1. [ĐÃ ĐÓNG] P1 · DA-12/13 · Reset mật khẩu có thể bỏ sót refresh token

Login kiểm tra mật khẩu trước transaction lưu refresh token. Khi reset mật khẩu và login xảy ra đồng thời, một phiên đã xác thực bằng mật khẩu cũ có thể được ghi sau đợt thu hồi. Refresh chỉ kiểm tra trạng thái token và tài khoản, nên phiên đó vẫn có thể nhận access token mới.

- **Bản sửa đã triển khai:**
  - Bổ sung khóa bi quan `SELECT u.id FROM users u WHERE u.id = :id FOR UPDATE` tại [UserRepository.java](../backend/src/main/java/com/documind/repository/UserRepository.java#L27) và chuẩn hóa thứ tự khóa `users -> refresh_tokens` xuyên suốt `login`, `refresh`, `logout` (khớp với `AdminUserService`) để triệt tiêu nguy cơ deadlock.
  - Trong transaction cấp phiên của `AuthService.login`, khóa dòng `users`, đọc lại `UserEntity` mới nhất và xác minh `isActive == true`, `email` khớp và `Objects.equals(freshUser.getPasswordHash(), verifiedPasswordHash)` trước khi phát hành Access JWT và lưu `RefreshTokenEntity`: [AuthService.java](../backend/src/main/java/com/documind/service/auth/AuthService.java#L140).
- **Bằng chứng kiểm thử:**
  - [AuthServiceTest.java](../backend/src/test/java/com/documind/service/auth/AuthServiceTest.java) & [AuthAndJwtSecurityIntegrationTest.java](../backend/src/test/java/com/documind/controller/AuthAndJwtSecurityIntegrationTest.java): kiểm thử chặn cửa sổ TOCTOU bằng latch dừng ngay sau `passwordEncoder.matches` kết hợp tải đồng thời 10 Virtual Threads (`login` mật khẩu cũ + `refresh` + `resetPassword`), chứng minh 0 refresh token mật khẩu cũ còn hoạt động sau khi reset hoàn tất.

### 2. [ĐÃ ĐÓNG] P1 · DA-13/17/18 · URL preview đã cấp còn hiệu lực sau khi siết quyền

Thay đổi phòng ban hoặc mức truy cập cập nhật quyền của tài liệu trong ứng dụng. URL MinIO đã ký trước đây trỏ trực tiếp tới object S3 và tiếp tục có hiệu lực đến hết TTL (`15 phút`).

- **Bản sửa đã triển khai (Thu hồi tức thì `0 ms` tại thời điểm tải):**
  - Xây dựng [DocumentPreviewTokenService.java](../backend/src/main/java/com/documind/infrastructure/security/DocumentPreviewTokenService.java) (tách miền khóa `SHA-256("documind-preview-v1:" + secret)`, header `typ = "doc_preview"`, gắn `docId`, `ver`, `sub`, `deptId`, `role`, `ajti`, `iatMs`) và [PreviewBaseUrlResolver.java](../backend/src/main/java/com/documind/infrastructure/security/PreviewBaseUrlResolver.java).
  - Chuyển toàn bộ 3 điểm phát hành URL (`DocumentService.getDocumentPreviewUrl`, `VectorRetrievalService.retrieve`, `ChatSessionService.hydrateCitations`) sang endpoint nội bộ `GET /api/v1/documents/{id}/content?token=...` (vẫn hỗ trợ `#page=N` và `Range` `206 Partial Content` cho PDF viewer).
  - Thực thi kiểm tra RBAC thời gian thực, trạng thái hoạt động/phòng ban của user, danh sách thu hồi Redis (`TokenBlacklistService`) và khớp `currentVersion == token.ver` ngay trước khi stream byte từ MinIO trên Virtual Thread mà không giữ kết nối DB: [DocumentController.java](../backend/src/main/java/com/documind/controller/DocumentController.java#L118) · [DocumentService.java](../backend/src/main/java/com/documind/service/document/DocumentService.java#L370).
- **Bằng chứng kiểm thử:**
  - [DocumentPreviewTokenServiceTest.java](../backend/src/test/java/com/documind/infrastructure/security/DocumentPreviewTokenServiceTest.java), [DocumentReindexAndPreviewIntegrationTest.java](../backend/src/test/java/com/documind/controller/DocumentReindexAndPreviewIntegrationTest.java) và [Week2CrossGateRbacIntegrationTest.java](../backend/src/test/java/com/documind/integration/Week2CrossGateRbacIntegrationTest.java): xác nhận URL preview và URL citation đã cấp bị từ chối `403` tức thì ngay sau khi nâng mức mật (`PUBLIC/DEPARTMENT -> CONFIDENTIAL`), chuyển phòng ban tài liệu, chuyển phòng ban user, vô hiệu hóa tài khoản hoặc logout/thu hồi phiên, và trả `404` sau reindex/xóa.

### 3. [ĐÃ ĐÓNG] P1 · DA-15/17 · Lịch sử chat ghép nội dung phiên bản cũ với quyền của phiên bản mới

Citation trước đây lưu `documentId` và excerpt nhưng thiếu `docVersion`. Sau reindex (ví dụ `v2` lược bỏ thông tin mật rồi hạ mức truy cập xuống `PUBLIC`), lịch sử xét quyền trên tài liệu hiện tại có thể mở lại câu trả lời/excerpt của `v1` hoặc nạp vào bước viết lại câu hỏi.

- **Bản sửa đã triển khai:**
  - Bổ sung `@JsonProperty("docVersion") Integer docVersion` vào [CitationPayload.java](../backend/src/main/java/com/documind/domain/model/CitationPayload.java#L16) và [CitationDto.java](../backend/src/main/java/com/documind/controller/dto/CitationDto.java#L16); gắn `doc_version` ngay khi truy xuất chunk tại [VectorRetrievalService.java](../backend/src/main/java/com/documind/service/rag/VectorRetrievalService.java).
  - Siết điều kiện `isCitationAuthorized` tại [ChatSessionService.java](../backend/src/main/java/com/documind/service/chat/ChatSessionService.java#L549): bắt buộc `docVersion != null && docVersion >= 1 && Objects.equals(parent.getCurrentVersion(), docVersion)` cùng `parent.getStatus() == COMPLETED` và RBAC hợp lệ — áp dụng đồng nhất cho cả `listMessages` và `loadAuthorizedHistoryForTurn` (chặn rò rỉ sang `QueryContextualizerService`).
- **Bằng chứng kiểm thử:**
  - [ChatSessionServiceTest.java](../backend/src/test/java/com/documind/service/chat/ChatSessionServiceTest.java), [MultiTurnSessionChatIntegrationTest.java](../backend/src/test/java/com/documind/controller/MultiTurnSessionChatIntegrationTest.java) và [Week2CrossGateRbacIntegrationTest.java](../backend/src/test/java/com/documind/integration/Week2CrossGateRbacIntegrationTest.java): kịch bản tài liệu `CONFIDENTIAL` `v1` được reindex sang `PUBLIC` `v2` xác nhận lượt trả lời cũ từ `v1` vẫn bị che hoàn toàn trong lịch sử và không được đưa vào prompt viết lại câu hỏi ở lượt hỏi nối tiếp trên cùng phiên.

### 4. [ĐÃ ĐÓNG] P2 · DA-15/18 · Lịch sử vẫn trả metadata của nguồn đã mất quyền

Phần lịch sử trước đây che câu trả lời, excerpt và URL của nguồn bị hạn chế, nhưng mảng `citations` vẫn giữ `documentId`, `fileName`, `sectionHeading` và `pageNumber`.

- **Bản sửa đã triển khai:**
  - Cập nhật `ChatSessionService.hydrateCitations` tại [ChatSessionService.java](../backend/src/main/java/com/documind/service/chat/ChatSessionService.java#L574): loại bỏ hoàn toàn (`continue;`) mọi phần tử citation không còn quyền đọc, đã xóa, khác `COMPLETED`, thiếu `docVersion` hoặc lệch phiên bản — không trả về bất kỳ `documentId`, `fileName`, `sectionHeading`, `pageNumber`, `excerpt` hay `presignedUrl` nào của nguồn bị hạn chế, đồng thời vẫn giữ nguyên các citation còn hợp lệ trong câu trả lời đa nguồn.
- **Bằng chứng kiểm thử:**
  - [MultiTurnSessionChatIntegrationTest.java](../backend/src/test/java/com/documind/controller/MultiTurnSessionChatIntegrationTest.java) và [Week2CrossGateRbacIntegrationTest.java](../backend/src/test/java/com/documind/integration/Week2CrossGateRbacIntegrationTest.java): kiểm tra chuyển phòng ban user, nâng mức `CONFIDENTIAL`, và lượt chat đa nguồn (`PUBLIC` + `DEPARTMENT`) xác nhận metadata của nguồn mất quyền bị loại bỏ 100% khỏi JSON phản hồi trong khi nguồn `PUBLIC` còn quyền vẫn hiển thị bình thường.

---

## Điểm cần chốt yêu cầu & Xác minh thực tế

### 5. [ĐÃ CHỐT & ĐÓNG] DA-12 · Phạm vi thu hồi khi phát hiện refresh token bị dùng lại

- **Hợp đồng đã chốt:** Thu hồi tức thì toàn bộ phiên (`0 giây` cửa sổ trễ), khớp chính xác với thông báo `"Refresh token reuse detected; all sessions have been revoked"`.
- **Bản sửa đã triển khai:** Trong `AuthService.refresh` ([AuthService.java](../backend/src/main/java/com/documind/service/auth/AuthService.java#L219)), ngay sau khi transaction độc lập (`REQUIRES_NEW`) commit lệnh thu hồi toàn bộ refresh token trong PostgreSQL, hệ thống gọi ngay `tokenBlacklistService.revokeAllUserTokens(userId, jwtService.accessTokenTtl())` trên Redis (fail-closed `503` nếu Redis mất kết nối).
- **Bằng chứng kiểm thử:** [AuthServiceTest.java](../backend/src/test/java/com/documind/service/auth/AuthServiceTest.java) và [AuthAndJwtSecurityIntegrationTest.java](../backend/src/test/java/com/documind/controller/AuthAndJwtSecurityIntegrationTest.java) chứng minh Access JWT đang còn hạn bị từ chối `401 TOKEN_REVOKED` ngay lập tức sau khi phát hiện refresh token bị dùng lại.

### 6. [ĐÃ CHỐT & XÁC MINH LIVE] DA-16 · Ngưỡng độ trễ SSE với provider thật

- **Phạm vi SLA đã chốt và ghi nhận trong Javadoc:** ([RagChatStreamService.java](../backend/src/main/java/com/documind/service/rag/RagChatStreamService.java#L64) · [QueryContextualizerService.java](../backend/src/main/java/com/documind/service/rag/QueryContextualizerService.java#L33))
  - **Turn 1 (Luồng trực tiếp không viết lại câu hỏi — `recentHistory.isEmpty()`):** Áp dụng ngưỡng đầu-cuối `citations < 500 ms` và token đầu tiên `TTFT < 2.500 ms` trên `bge-m3` đã làm nóng + live LLM provider.
  - **Turn 2+ (Luồng hội thoại nối tiếp có viết lại câu hỏi độc lập):** Thực hiện 1 vòng gọi đồng bộ `QueryContextualizerService` trước khi nhúng vector `bge-m3`; ngưỡng `< 500 ms` áp dụng cho pha truy xuất sau rewrite (`postRewriteCitationsMs < 500 ms`) và `< 2.500 ms` áp dụng cho pha sinh token đầu sau khi phát `citations` (`postCitationsFirstTokenMs < 2.500 ms`).
- **Kết quả đo thực tế (`.\mvnw.cmd surefire:test -Dsmoke=true -Dtest=LiveProviderSseLatencyBenchmarkTest`):**
  - Hạ tầng thực tế: `documind-postgres` (`pgvector:pg16`), `documind-redis` (`redis:7-alpine`), `documind-minio`, local `Ollama` (`bge-m3`, 1024 chiều) và live `Groq` (`openai/gpt-oss-120b`).
  - **Turn 1 (`0`-Rewrite):** `citations = 126 ms` (ngưỡng `< 500 ms`), `TTFT = 799 ms` (ngưỡng `< 2.500 ms`), tổng hoàn tất stream `= 913 ms` (`918` prompt / `121` completion tokens).
  - **Turn 2 (`With Rewrite`):** câu hỏi nối tiếp được viết lại chính xác thành câu độc lập tiếng Việt; nhúng `bge-m3` sau rewrite `= 101 ms` (`< 500 ms`), `citations = 1.236 ms` (`~1.135 ms` rewrite + `101 ms` retrieval), `post-citations TTFT = 717 ms` (`< 2.500 ms`), tổng `TTFT` đầu-cuối `= 1.953 ms` (`< 2.500 ms`), tổng hoàn tất stream `= 2.089 ms`.
  - Suite benchmark tự động: [LiveProviderSseLatencyBenchmarkTest.java](../backend/src/test/java/com/documind/integration/LiveProviderSseLatencyBenchmarkTest.java).

---

## Kết quả kiểm thử nghiệm thu sau sửa lỗi

- **Maven Wrapper `.\mvnw.cmd clean verify`:** `BUILD SUCCESS` với Testcontainers (`pgvector/pgvector:pg16`, `redis:7-alpine`, `minio`). Đạt **451 test** (`440` thực thi offline + `11` smoke test được cổng hóa qua `-Dsmoke=true`), **0 thất bại, 0 lỗi** (tăng thêm **+17** ca kiểm thử hồi quy mới so với mốc `434` ban đầu).
- **Độ bao phủ JaCoCo (191 class):** **89,38% dòng** (`6.428 / 7.192`) và **89,01% instruction** (`28.239 / 31.727`), vượt xa ngưỡng cổng chất lượng `>= 75%`.
- **Hồi quy DA-18 & SSE Concurrency:** Toàn bộ `Week2CrossGateRbacIntegrationTest`, `TypedSseChatStreamingIntegrationTest` và `Week2SseConcurrencyAndResilienceIntegrationTest` đều xanh 100%.
