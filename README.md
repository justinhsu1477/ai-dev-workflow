# AI Dev Workflow

AI 驅動的開發工作流中間件，整合 Claude API 與 Azure DevOps，提供三大核心功能：

1. **CI 測試失敗自動分析** — Pipeline 失敗 → AI 分析根因 → 建立 Work Item
2. **AI E2E 測試** — Push/部署後自動跑 E2E 測試 → 偵測 Bug → 建立 Work Item（附截圖）
3. **AI 自動修復** — 偵測到 Bug → AI 產生修復 → 建立 PR → Re-test 通過自動關閉 Work Item

## 完整流程架構

```
                    ┌─────────────────────┐
                    │  Azure DevOps       │
                    │  Push / Pipeline    │
                    └────────┬────────────┘
                             │ Service Hook (HTTP POST)
                             ▼
┌──────────────────────────────────────────────────────────────┐
│                    Spring Boot Middleware                      │
│                                                                │
│  ┌──────────────────┐   ┌───────────────────────────────────┐ │
│  │ Module 1:         │   │ Module 2: AI E2E Test Agent       │ │
│  │ CI 失敗分析       │   │                                   │ │
│  │                   │   │  1. 接收 push webhook             │ │
│  │ Pipeline 失敗     │   │  2. git diff → 分析受影響模組      │ │
│  │ → 擷取 log + diff │   │  3. AI 規劃測試步驟               │ │
│  │ → AI 分析根因     │   │  4. Playwright 逐步執行           │ │
│  │ → 建立 Work Item  │   │  5. AI 歸納失敗步驟為 Bug         │ │
│  │ → Teams 通知      │   │  6. 建立 Work Item（附截圖）      │ │
│  └──────────────────┘   │  7. AI 自動修復（見下方）          │ │
│                          │  8. Teams 通知                    │ │
│                          └───────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ Module 3: AI Auto-Fix                                     │ │
│  │                                                            │ │
│  │  階段 1（自動）：                                          │ │
│  │  Bug 偵測 → 讀取目標 repo 原始碼 → AI (Opus) 產生修復方案  │ │
│  │  → 建立 ai-fix/* 分支 → 套用修改 → commit + push → 建 PR  │ │
│  │                                                            │ │
│  │  階段 2（手動觸發）：                                       │ │
│  │  IDE 切到 ai-fix/* 分支 → 重啟 app                         │ │
│  │  → POST /api/e2e/autofix/retest/{workItemId}               │ │
│  │  → 通過 = 自動關閉 Work Item / 失敗 = 加註原因             │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  外部整合：                                                     │
│  ← Azure DevOps REST API（Work Item / PR / 附件）              │
│  ← Anthropic Claude API（Sonnet 分析 / Opus 修 code）          │
│  ← Playwright（瀏覽器自動化）                                   │
│  → Teams Webhook（通知）                                        │
└──────────────────────────────────────────────────────────────┘
```

## 專案結構

```
src/main/java/com/team/aiworkflow/
├── AiWorkflowApplication.java
├── config/
│   ├── ClaudeApiConfig.java            # Claude API 設定
│   ├── AzureDevOpsConfig.java          # Azure DevOps 連線
│   ├── AutoFixConfig.java              # AI 自動修復設定
│   ├── ModuleMappingConfig.java        # 模組→測試流程映射
│   ├── RateLimitConfig.java            # API 頻率限制
│   └── AsyncConfig.java               # 非同步 thread pool
├── controller/
│   ├── WebhookController.java          # POST /webhook/pipeline-failure
│   ├── PushWebhookController.java      # POST /webhook/push（E2E 觸發）
│   ├── DeploymentWebhookController.java# POST /webhook/deployment（E2E 觸發）
│   ├── E2ETestController.java          # POST /api/e2e/run（手動 E2E）
│   ├── AutoFixController.java          # POST /api/e2e/autofix/retest/{id}
│   └── AnalysisController.java         # POST /api/analyze-failure
├── service/
│   ├── claude/
│   │   ├── ClaudeApiService.java       # Claude API（Sonnet + Opus）
│   │   ├── PromptBuilder.java          # Prompt template 組裝
│   │   └── ResponseParser.java         # AI JSON 解析
│   ├── azuredevops/
│   │   ├── PipelineService.java        # Build log 擷取
│   │   ├── WorkItemService.java        # Work Item CRUD + resolve
│   │   ├── PullRequestService.java     # PR 建立
│   │   └── GitRepoService.java         # Repo diff
│   ├── e2e/
│   │   ├── E2ETestOrchestrator.java    # E2E 測試主流程
│   │   ├── AITestPlanner.java          # AI 規劃測試步驟
│   │   ├── PlaywrightService.java      # 瀏覽器操作
│   │   ├── TestScopeResolver.java      # Git diff → 測試範圍
│   │   └── GitDiffAnalysisService.java # Diff 分析
│   ├── autofix/
│   │   ├── AutoFixOrchestrator.java    # 自動修復主流程
│   │   ├── SourceCodeResolver.java     # Bug → 相關原始碼
│   │   ├── FixApplicator.java          # AI 修復方案 → 套用到檔案
│   │   └── TargetRepoGitService.java   # 目標 repo git 操作
│   ├── analysis/
│   │   └── FailureAnalysisService.java # CI 失敗分析主流程
│   └── notification/
│       └── TeamsNotificationService.java
├── model/
│   ├── AnalysisResult.java
│   ├── autofix/
│   │   └── AutoFixResult.java          # 自動修復結果 DTO
│   ├── e2e/
│   │   ├── E2ETestResult.java          # E2E 測試結果（含 BugFound）
│   │   ├── E2ETestRequest.java
│   │   └── TestStep.java
│   └── dto/
│       ├── PipelineEvent.java
│       └── WorkItemCreateRequest.java
└── util/
    ├── LogTruncator.java
    └── DiffParser.java

src/main/resources/
├── application.yml                     # 主設定
├── e2e-module-mapping.yml              # 模組→測試流程映射表
└── prompts/
    ├── failure-analysis.txt
    ├── test-generation.txt
    ├── bug-fix.txt                     # AI 修 bug 的 prompt template
    ├── e2e-test-planner.txt
    └── e2e-step-executor.txt
```

## 快速開始

### 前置需求

- Java 17+
- Maven 3.9+
- Anthropic Claude API Key
- Azure DevOps PAT (Personal Access Token)
- Node.js（Playwright 瀏覽器自動化需要）

### 1. 設定環境變數

```bash
# 必填
export CLAUDE_API_KEY=your-claude-api-key
export AZURE_DEVOPS_ORG=your-organization
export AZURE_DEVOPS_PROJECT=your-project
export AZURE_DEVOPS_PAT=your-personal-access-token

# E2E 測試
export E2E_STAGING_URL=http://localhost:8080    # 目標 app URL
export E2E_APP_DESCRIPTION="訂單管理系統"

# AI 自動修復（選填，預設關閉）
export AUTO_FIX_ENABLED=true
export AUTO_FIX_TARGET_REPO_PATH=/path/to/target/repo   # 目標 app 的本機 clone
export AUTO_FIX_TARGET_REPO_ID=azure-devops-repo-guid

# Teams 通知（選填）
export TEAMS_WEBHOOK_URL=your-teams-incoming-webhook-url
```

### 2. 編譯並啟動

```bash
mvn clean compile
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

服務啟動後監聽 `http://localhost:8081`。

### 3. 手動觸發 E2E 測試

```bash
# 非同步執行
curl -X POST http://localhost:8081/api/e2e/run \
  -H "Content-Type: application/json" \
  -d '{"appUrl":"http://localhost:8080","appDescription":"OCDS 訂單系統","maxSteps":20}'

# 同步執行（等待完成回傳結果）
curl -X POST http://localhost:8081/api/e2e/run-sync \
  -H "Content-Type: application/json" \
  -d '{"appUrl":"http://localhost:8080","appDescription":"OCDS 訂單系統","maxSteps":20}'
```

### 4. AI 自動修復 Re-test

當 AI Auto-Fix 產生修復分支後：

```bash
# 1. 在 IDE 切到 ai-fix/{workItemId} 分支
# 2. 重啟目標 app
# 3. 觸發 re-test
curl -X POST http://localhost:8081/api/e2e/autofix/retest/12345
```

測試通過 → Work Item 自動關閉；測試失敗 → Work Item 加註失敗原因。

### 5. 設定 Azure DevOps Service Hook

1. 進入 Azure DevOps → **Project Settings** → **Service Hooks**
2. 點 **Create Subscription** → 選 **Web Hooks**
3. 設定 Push event → URL 填入 `https://your-url/webhook/push`
4. 設定 Build completed (Failed) → URL 填入 `https://your-url/webhook/pipeline-failure`

## API Endpoints

| Method | Path | 說明 |
|--------|------|------|
| POST | `/webhook/pipeline-failure` | 接收 CI 測試失敗 event |
| POST | `/webhook/push` | 接收 git push event → 觸發 E2E |
| POST | `/webhook/deployment` | 接收部署完成 event → 觸發 E2E |
| POST | `/api/e2e/run` | 手動觸發 E2E 測試（非同步） |
| POST | `/api/e2e/run-sync` | 手動觸發 E2E 測試（同步） |
| POST | `/api/e2e/autofix/retest/{workItemId}` | 觸發 AI 修復驗證 |
| POST | `/api/analyze-failure` | 手動觸發 CI 失敗分析 |

## 設定說明

### AI 自動修復

```yaml
workflow:
  auto-fix:
    enabled: true                    # 啟用自動修復
    target-repo-path: /path/to/repo  # 目標 app 本機 clone 路徑
    target-repo-id: repo-guid        # Azure DevOps repo ID（建 PR 用）
    base-branch: ai-dev-workflow     # 主要開發分支
    branch-prefix: ai-fix/           # 修復分支前綴
    max-files-to-read: 10            # 每次分析最多讀幾個檔案
    source-base-path: src/main/java/com/soetek/ods  # Java 源碼根目錄
```

### Rate Limiting

```yaml
rate-limit:
  claude-api:
    requests-per-minute: 10
    requests-per-hour: 100
```

### Prompt 自訂

Prompt templates 在 `src/main/resources/prompts/` 下，修改後重新部署即可生效。

## 技術棧

- **Spring Boot 3.4** + Java 17
- **WebClient** — 非阻塞式 HTTP（Azure DevOps API + Claude API）
- **Anthropic Claude API** — Sonnet（分析/規劃）+ Opus（程式碼生成/修復）
- **Playwright** — 瀏覽器自動化 E2E 測試
- **Bucket4j** — Rate limiting
- **Lombok** — 減少 boilerplate

## Roadmap

- [x] **Phase 1**: CI 測試失敗自動分析 → AI 建立 Work Item
- [x] **Phase 2**: AI E2E 測試 → Push 觸發 → 偵測 Bug → 建立 Work Item（附截圖）
- [x] **Phase 3**: AI 自動修復 → 產生修復 PR → Re-test → 自動關閉 Work Item
- [ ] **Phase 4**: AI 自動產生單元測試建議
