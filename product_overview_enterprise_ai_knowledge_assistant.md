# PRODUCT OVERVIEW: ENTERPRISE AI KNOWLEDGE ASSISTANT (DOCUMIND AI)

## 1. Tổng quan sản phẩm (Executive Summary)

* **Tên dự án:** DocuMind AI – Enterprise Intelligent Document & Knowledge Base Assistant
* **Phiên bản:** v1.0 (MVP to Enterprise-Ready)
* **Đối tượng sử dụng mục tiêu:** Doanh nghiệp vừa và nhỏ (SMEs), các đội ngũ kỹ thuật, nhân sự (HR), vận hành cần tra cứu thông tin nội bộ nhanh chóng và bảo mật.
* **Tuyên bố giá trị (Value Proposition):** Biến kho tài liệu nội bộ rời rạc (PDF, DOCX, Markdown, TXT) thành một trợ lý ảo thông minh có khả năng trả lời tức thì bằng ngôn ngữ tự nhiên, đi kèm trích dẫn nguồn chính xác và tuân thủ nghiêm ngặt cơ chế phân quyền theo phòng ban/vai trò.

---

## 2. Bối cảnh & Vấn đề cần giải quyết (Problem Statement)

Trong môi trường doanh nghiệp hiện đại, nhân viên thường mất từ **20% - 30% thời gian làm việc** chỉ để tìm kiếm thông tin nằm rải rác trong các tài liệu quy trình, sổ tay nhân viên, tài liệu kỹ thuật (API docs, SOPs) hoặc hợp đồng cũ.

1. **Quá tải thông tin & Tìm kiếm kém hiệu quả:** Công cụ tìm kiếm từ khóa truyền thống (Ctrl+F hoặc tìm theo tên file) không hiểu được ngữ cảnh câu hỏi, trả về quá nhiều kết quả nhiễu hoặc bỏ sót thông tin nếu người tìm dùng từ đồng nghĩa.
2. **Rủi ro bảo mật dữ liệu (Data Leakage):** Việc nhân viên tự ý sao chép dữ liệu nội bộ lên các công cụ AI công cộng (như ChatGPT bản miễn phí) tiềm ẩn nguy cơ lộ lọt thông tin nhạy cảm của công ty.
3. **Ảo giác AI (AI Hallucination):** Các mô hình LLM thuần túy thường tự bịa ra câu trả lời nếu không có dữ liệu thực tế của doanh nghiệp, gây sai lệch trong vận hành.
4. **Chi phí API & Hiệu năng:** Gọi trực tiếp LLM cho mọi câu hỏi lặp lại gây tốn kém chi phí token và độ trễ phản hồi cao.

---

## 3. Giải pháp & Mục tiêu sản phẩm (Solution & Goals)

Xây dựng hệ thống **Retrieval-Augmented Generation (RAG)** khép kín trên nền tảng **Java 21 & Spring Boot 3**, giải quyết triệt để các vấn đề trên thông qua:

* **Truy xuất thông tin ngữ nghĩa chính xác:** Kết hợp Vector Search (`pgvector`) và lọc siêu dữ liệu (Metadata Filtering) để chỉ lấy đúng đoạn văn bản liên quan nhất làm ngữ cảnh cho AI.
* **Kiểm soát ảo giác & Minh bạch nguồn tin:** Ép buộc AI chỉ trả lời dựa trên tài liệu được cung cấp, đồng thời trả về tên file, số trang hoặc đoạn trích gốc để người dùng kiểm chứng.
* **Bảo mật phân quyền cấp độ tài liệu (Document-Level RBAC):** Đảm bảo nhân viên phòng ban nào chỉ nhận được câu trả lời từ tài liệu thuộc phạm vi quyền hạn của phòng ban đó.
* **Tối ưu hiệu năng & Chi phí doanh nghiệp:** Tận dụng **Java 21 Virtual Threads** cho luồng xử lý tài liệu lớn và **Redis** để lưu đệm ngữ nghĩa (Semantic Cache) cũng như kiểm soát giới hạn truy cập (Rate Limiting).

---

## 4. Chân dung người dùng (User Personas)

| Vai trò (Role) | Mô tả & Nhu cầu chính | Quyền hạn trong hệ thống |
| :--- | :--- | :--- |
| **System Admin** | Quản trị viên hệ thống IT, chịu trách nhiệm vận hành, giám sát chi phí token và quản lý tài khoản. | Toàn quyền quản lý user, phòng ban, cấu hình hệ thống, xem Audit Log & Dashboard thống kê chi phí. |
| **Department Manager / Editor** | Trưởng bộ phận (HR, Tech Lead, Sales) chịu trách nhiệm cập nhật tài liệu quy trình cho đội ngũ. | Tải lên, cập nhật, xóa tài liệu thuộc phòng ban của mình; đặt câu hỏi với toàn bộ tài liệu thuộc phòng ban. |
| **Standard Employee (Viewer)** | Nhân viên thông thường (lập trình viên, nhân viên mới) cần tra cứu quy định, tài liệu hướng dẫn. | Chỉ được đặt câu hỏi và xem trích dẫn từ các tài liệu công khai (Public) hoặc thuộc phòng ban của mình. |

---

## 5. Phạm vi tính năng cốt lõi (Core Features & Functional Requirements)

### 5.1. Quản lý Tài liệu & Luồng nạp dữ liệu (Document Ingestion Pipeline)
* **Hỗ trợ đa định dạng:** Upload tài liệu `.pdf`, `.docx`, `.txt`, `.md` (giới hạn dung lượng tối đa 50MB/file).
* **Xử lý bất đồng bộ (Async Processing):** Sử dụng Java 21 Virtual Threads để xử lý ngầm ngay sau khi upload, cập nhật trạng thái tài liệu theo thời gian thực (`PENDING` $\rightarrow$ `PROCESSING` $\rightarrow$ `COMPLETED` / `FAILED`).
* **Smart Chunking & Embedding:** Tự động trích xuất văn bản (Apache Tika), chia nhỏ thành các đoạn (Chunks) có độ gối đầu (Overlap) hợp lý, chuyển đổi thành Vector và lưu trữ vào PostgreSQL (`pgvector`) kèm Metadata (`department_id`, `access_level`, `file_name`, `page_number`).

### 5.2. Trợ lý Hỏi đáp Thông minh (AI Conversational RAG Engine)
* **Phản hồi thời gian thực (Streaming Response):** Sử dụng Server-Sent Events (SSE) để hiển thị từng cụm từ ngay khi AI sinh ra, mang lại trải nghiệm mượt mà.
* **Trích dẫn nguồn tham chiếu (Citations):** Mỗi câu trả lời đi kèm danh sách các thẻ nguồn (tên tài liệu, độ tương đồng, đoạn văn bản gốc) để người dùng đối chiếu.
* **Ngữ cảnh hội thoại (Conversation Memory):** Lưu trữ lịch sử chat theo từng phiên (Session), cho phép người dùng hỏi nối tiếp các câu hỏi phụ (Follow-up questions).
* **Chế độ từ chối an toàn (Fallback Mechanism):** Khi không tìm thấy thông tin phù hợp trong kho tài liệu (điểm tương đồng dưới ngưỡng cấu hình), hệ thống chủ động thông báo *"Không tìm thấy thông tin trong tài liệu nội bộ"* thay vì tự suy diễn.

### 5.3. Bảo mật & Phân quyền Dữ liệu (Security & Governance)
* **Xác thực Stateless:** Sử dụng Spring Security 6 + JWT (Access Token & Refresh Token).
* **Phân quyền truy xuất Vector (Metadata-based RBAC):** Tự động chèn bộ lọc quyền hạn vào câu truy vấn Vector dựa trên danh tính người dùng đang đăng nhập, ngăn chặn tuyệt đối việc rò rỉ dữ liệu chéo giữa các phòng ban.

### 5.4. Tối ưu Hiệu năng & Giám sát Hệ thống (Performance & Observability)
* **Semantic & Exact Caching (Redis):** Lưu trữ kết quả của các câu hỏi thường gặp. Nếu câu hỏi trùng lặp, trả về kết quả từ Redis trong $< 50\text{ms}$ mà không tốn chi phí gọi LLM.
* **Rate Limiting (Redis + Bucket4j):** Giới hạn số lượng câu hỏi/phút theo từng User/Role để chống spam và bảo vệ ngân sách API.
* **Token Usage & Audit Logging:** Ghi nhận chi tiết lịch sử truy vấn, số lượng Prompt Tokens, Completion Tokens tiêu thụ theo từng phòng ban để phục vụ báo cáo chi phí.

---

## 6. Yêu cầu phi chức năng (Non-Functional Requirements)

* **Hiệu năng (Performance):**
  * Thời gian nhận byte đầu tiên (Time To First Token - TTFT) cho câu hỏi không có trong cache: $< 2.5\text{ giây}$.
  * Thời gian phản hồi cho câu hỏi đã được cache trên Redis: $< 100\text{ms}$.
  * Khả năng xử lý đồng thời ít nhất 100 request hỏi đáp và 20 tác vụ upload file cùng lúc nhờ Virtual Threads.
* **Khả năng mở rộng (Scalability):** Kiến trúc Stateless ở tầng ứng dụng, dễ dàng mở rộng theo chiều ngang (Horizontal Scaling) thông qua Docker/Kubernetes.
* **Tính bảo trì (Maintainability):** Áp dụng kiến trúc phân tầng rõ ràng (Clean / Layered Architecture), tuân thủ chuẩn RESTful API, tài liệu hóa tự động qua OpenAPI / Swagger UI.

---

## 7. Kiến trúc Công nghệ (Technical Architecture Stack)

* **Ngôn ngữ & Framework:** Java 21 (LTS), Spring Boot 3.3+, Spring AI
* **Cơ sở dữ liệu & Vector Store:** PostgreSQL 16 + `pgvector` extension (HNSW Indexing)
* **In-Memory Cache & Rate Limit:** Redis 7
* **Bảo mật:** Spring Security 6, JJWT (JSON Web Token)
* **Trích xuất tài liệu:** Apache Tika / Spring AI Document Readers
* **Tích hợp AI Model:** Hỗ trợ linh hoạt OpenAI API / Google Gemini API / Ollama (Local LLM) thông qua abstraction của Spring AI
* **Triển khai & Vận hành:** Docker, Docker Compose, GitHub Actions (CI)
* **Giao diện Demo (Frontend):** React / Next.js + Tailwind CSS (tích hợp SSE client & Markdown renderer)

---

## 8. Chỉ số đo lường thành công (Success Metrics / KPIs)

Để chứng minh giá trị kỹ thuật của dự án với nhà tuyển dụng, sản phẩm được đánh giá qua các chỉ số định lượng sau:
1. **Độ chính xác truy xuất (Retrieval Precision):** $\ge 85\%$ câu hỏi kiểm thử (Golden Dataset) truy xuất đúng đoạn văn bản chứa câu trả lời trong Top-5 kết quả tìm kiếm.
2. **Tỷ lệ giảm thiểu chi phí API (Cache Hit Ratio):** Giảm ít nhất $30\% - 40\%$ số lần gọi LLM thực tế đối với bộ câu hỏi lặp lại nhờ tầng Redis Cache.
3. **Tỷ lệ ngăn chặn ảo giác (Hallucination Rate):** $0\%$ câu trả lời bịa đặt khi kiểm thử với các câu hỏi nằm ngoài phạm vi tài liệu nội bộ.
4. **Độ phủ kiểm thử (Test Coverage):** Đạt $\ge 75\%$ Unit & Integration Test coverage cho các luồng nghiệp vụ cốt lõi (Ingestion, Retrieval Filter, Security).

---

## 9. Lộ trình phát triển sản phẩm (Product Roadmap)

* **Giai đoạn 1 – Core RAG Engine (Tuần 1):** Thiết lập hạ tầng Docker (PostgreSQL + pgvector + Redis), xây dựng pipeline nạp file PDF/TXT, tích hợp Spring AI thực hiện hỏi đáp cơ bản.
* **Giai đoạn 2 – Enterprise Security & Streaming (Tuần 2):** Tích hợp Spring Security + JWT, triển khai phân quyền tài liệu theo phòng ban (Metadata Filtering), nâng cấp API hỏi đáp sang Streaming (SSE) và lưu lịch sử hội thoại.
* **Giai đoạn 3 – Optimization, Observability & UI (Tuần 3):** Tích hợp Redis Caching & Rate Limiting, xây dựng bảng Audit Log thống kê token, hoàn thiện giao diện Web Demo và viết tài liệu kỹ thuật hoàn chỉnh.
* **Giai đoạn 4 – Nâng cao / Mở rộng (Tương lai):** Hỗ trợ Hybrid Search (kết hợp BM25 Keyword Search + Vector Search), Reranking kết quả tìm kiếm và tích hợp Agentic Workflow (cho phép AI tự động gọi công cụ tra cứu dữ liệu thời gian thực).