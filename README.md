# AI Dev Workflow

AI 驅動的開發工作流中間件，整合 Claude API 與 Azure DevOps，自動分析 CI/CD 測試失敗並建立 Work Item。

## 運作原理

```
Azure DevOps Pipeline 測試失敗
        │
        │  Service Hook (HTTP POST)
        ▼
┌──────────────────────────────┐
│  Spring Boot Middleware       │
│                              │
│  1. 接收 webhook event       │
│  2. 擷取 build log + diff    │  ←── Azure DevOps REST API
│  3. 智慧截斷 log              │
│  4. 組裝 prompt              │
│  5. 呼叫 Claude API 分析      │  ←── Anthropic API
│  6. 解析 AI 回應 (JSON)       │
│  7. 建立 Bug Work Item       │  ──→ Azure DevOps Boards
│  8. 發送 Teams 通知           │  ──→ Teams Webhook
└──────────────────────────────┘
```

**完整流程：**

1. Azure DevOps Pipeline 跑完測試，如果失敗，Service Hook 會發 POST 到 `/webhook/pipeline-failure`
2. `WebhookController` 接收 event，驗證是 failed build，交給 `FailureAnalysisService` 非同步處理
3. `PipelineService` 透過 Azure DevOps REST API 擷取 build timeline，找到失敗的 task log
4. `LogTruncator` 智慧截斷 log（優先保留含 error/exception 的行），控制在 Claude API token 限制內
5. `PromptBuilder` 從外部 template 檔案組裝 prompt（log + code diff + build info）
6. `ClaudeApiService` 呼叫 Claude API（預設用 Sonnet 4.5），帶 rate limiting（Bucket4j）
7. `ResponseParser` 解析 AI 回應的 JSON（root cause、severity、affected files、suggested fix）
8. `WorkItemService` 透過 Azure DevOps REST API 建立 Bug Work Item，附上完整分析報告
9. `TeamsNotificationService` 發送 Adaptive Card 通知到 Teams channel

## 專案結構

```
src/main/java/com/team/aiworkflow/
├── AiWorkflowApplication.java          # Spring Boot 入口
├── config/
│   ├── ClaudeApiConfig.java            # Claude API 設定 (model, token, timeout)
│   ├── AzureDevOpsConfig.java          # Azure DevOps 連線 + WebClient bean
│   ├── RateLimitConfig.java            # Bucket4j rate limiter
│   └── AsyncConfig.java               # 非同步 thread pool
├── controller/
│   ├── WebhookController.java          # POST /webhook/pipeline-failure
│   └── AnalysisController.java         # POST /api/analyze-failure (手動觸發)
├── service/
│   ├── claude/
│   │   ├── ClaudeApiService.java       # Claude API 呼叫 (WebClient + rate limit)
│   │   ├── PromptBuilder.java          # Prompt template 讀取與組裝
│   │   └── ResponseParser.java         # AI JSON 回應解析
│   ├── azuredevops/
│   │   ├── PipelineService.java        # 擷取 build log
│   │   ├── WorkItemService.java        # 建立 Bug Work Item
│   │   ├── GitRepoService.java         # 讀取 repo code/diff
│   │   └── PullRequestService.java     # PR 管理 (Phase 2/3)
│   ├── analysis/
│   │   └── FailureAnalysisService.java # 主流程編排
│   └── notification/
│       └── TeamsNotificationService.java
├── model/
│   ├── AnalysisResult.java             # AI 分析結果
│   └── dto/
│       ├── PipelineEvent.java          # Azure DevOps webhook payload
│       └── WorkItemCreateRequest.java
└── util/
    ├── LogTruncator.java               # 智慧 log 截斷
    └── DiffParser.java                 # Git diff 解析

src/main/resources/
├── application.yml                     # 主設定
├── application-dev.yml                 # 開發環境
├── application-prod.yml                # 生產環境
└── prompts/                            # AI Prompt templates (可不重新編譯直接修改)
    ├── failure-analysis.txt
    ├── test-generation.txt
    └── bug-fix.txt
```

## 快速開始

### 前置需求

- Java 17+
- Maven 3.9+
- Anthropic Claude API Key
- Azure DevOps PAT (Personal Access Token)

### 1. 設定環境變數

```bash
export CLAUDE_API_KEY=your-claude-api-key
export AZURE_DEVOPS_ORG=your-organization
export AZURE_DEVOPS_PROJECT=your-project
export AZURE_DEVOPS_PAT=your-personal-access-token
export TEAMS_WEBHOOK_URL=your-teams-incoming-webhook-url  # 選填
```

### 2. 編譯並啟動

```bash
# 編譯
mvn clean compile

# 啟動（開發模式）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 或打包後執行
mvn package -DskipTests
java -jar target/ai-dev-workflow-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

服務啟動後監聽 `http://localhost:8080`。

### 3. 驗證服務

```bash
# Health check
curl http://localhost:8080/api/health

# 手動觸發分析（測試用）
curl -X POST http://localhost:8080/api/analyze-failure \
  -H "Content-Type: application/json" \
  -d '{"buildId":"123","buildNumber":"20240115.1","branch":"refs/heads/develop","commitId":"abc123"}'
```

### 4. 設定 Azure DevOps Service Hook

1. 進入 Azure DevOps → **Project Settings** → **Service Hooks**
2. 點 **Create Subscription** → 選 **Web Hooks**
3. Trigger 選 **Build completed**，Status 設為 **Failed**
4. URL 填入：`https://your-deployed-url/webhook/pipeline-failure`
5. 測試連線確認 webhook 正常

## Docker 部署

```bash
# 建置 image
docker build -t ai-dev-workflow .

# 執行
docker run -p 8080:8080 \
  -e CLAUDE_API_KEY=your-key \
  -e AZURE_DEVOPS_ORG=your-org \
  -e AZURE_DEVOPS_PROJECT=your-project \
  -e AZURE_DEVOPS_PAT=your-pat \
  -e TEAMS_WEBHOOK_URL=your-webhook \
  ai-dev-workflow
```

## API Endpoints

| Method | Path | 說明 |
|--------|------|------|
| POST | `/webhook/pipeline-failure` | 接收 Azure DevOps build failure event |
| POST | `/api/analyze-failure` | 手動觸發分析 |
| GET | `/api/health` | Health check |

## 設定說明

### Rate Limiting

Claude API 呼叫有頻率限制，避免費用暴衝：

```yaml
rate-limit:
  claude-api:
    requests-per-minute: 10    # 每分鐘最多 10 次
    requests-per-hour: 100     # 每小時最多 100 次
```

### Branch 過濾

只分析特定 branch 的失敗，避免 feature branch 頻繁觸發：

```yaml
workflow:
  failure-analysis:
    branches: main,develop     # 只分析這些 branch
```

### Prompt 自訂

Prompt templates 在 `src/main/resources/prompts/` 下，可以根據團隊需求調整 AI 分析的行為，修改後重新部署即可生效。

## 技術棧

- **Spring Boot 3.4** - Web framework
- **WebClient** - 非阻塞式 HTTP client (呼叫 Azure DevOps & Claude API)
- **Anthropic Claude API** - AI 分析引擎 (預設 Sonnet 4.5，複雜場景切 Opus)
- **Bucket4j** - Rate limiting
- **Lombok** - 減少 boilerplate code
- **Docker** - 容器化部署

## Roadmap

- [x] **Phase 1**: 測試失敗自動分析 (CI failure → AI analysis → Work Item)
- [ ] **Phase 2**: 自動產生測試 (PR 提交時 AI 建議測試案例)
- [ ] **Phase 3**: AI 輔助修 Bug (Teams 指令 / Work Item 觸發 → AI 產生修復 PR)
