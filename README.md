# AI Dev Workflow

> 串接 **Azure DevOps × Anthropic Claude × Playwright** 的非同步反應式中介服務，將「CI 失敗分析 → AI E2E 測試 → AI 自動修復 → 自動驗證關閉」串成一條完整的開發品質閉環。

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen)]()
[![Anthropic](https://img.shields.io/badge/Claude-Sonnet%204.5%20%2B%20Opus%204.6-blueviolet)]()
[![Playwright](https://img.shields.io/badge/Playwright-1.49-2ea44f)]()

---

## 目錄

- [專案定位](#專案定位)
- [三大核心模組](#三大核心模組)
- [系統架構](#系統架構)
- [技術棧](#技術棧)
- [快速開始](#快速開始)
- [API 端點](#api-端點)
- [設定詳解](#設定詳解)
- [模組映射（`e2e-module-mapping.yml`）](#模組映射e2e-module-mappingyml)
- [Prompt 工程](#prompt-工程)
- [工程師績效追蹤子系統](#工程師績效追蹤子系統)
- [Vaadin 特化處理](#vaadin-特化處理)
- [部署](#部署)
- [測試](#測試)
- [專案結構](#專案結構)
- [限制與已知問題](#限制與已知問題)
- [Roadmap](#roadmap)

---

## 專案定位

這不是「呼叫一下 Claude API」的玩具專案，而是一套設計用來**接管「測試 → 修復 → 驗證」完整工程閉環**的中介服務。針對 Azure DevOps + Vaadin 技術棧的客製化開發團隊，把「改 A 壞 B」這類典型回歸 bug 的攔截與修復自動化。

**設計哲學**
- 一切由 **YAML 配置驅動**（[e2e-module-mapping.yml](src/main/resources/e2e-module-mapping.yml)），新增客戶 / 模組 / 測試流程不需改動 Java 程式碼。
- **非同步反應式**：webhook 立刻回 200，背景跑長時 I/O，避免 Azure DevOps webhook 逾時。
- **雙模型分工**：便宜的 Sonnet 做分析、貴的 Opus 做程式碼生成，控制成本。
- **精準測試優先**：用 git diff → 模組映射，只跑受影響的測試流程，不做全量回歸。

---

## 三大核心模組

### 模組 1：CI 失敗自動分析

```
Azure DevOps Build 失敗 webhook
    ↓ POST /webhook/pipeline-failure
擷取 Build Log + Git Diff（並行）
    ↓
Claude Sonnet 分析根因 → 結構化 JSON
    ↓
建立 Bug Work Item（含 ReproSteps、Severity、Affected Files）
    ↓
Teams Adaptive Card 通知
```

實作：[FailureAnalysisService.java](src/main/java/com/team/aiworkflow/service/analysis/FailureAnalysisService.java) · [WebhookController.java](src/main/java/com/team/aiworkflow/controller/WebhookController.java)

### 模組 2：AI E2E 測試

兩種觸發模式：

| 模式 | 觸發 | 範圍 | 典型耗時 | 穩定性 |
|------|------|------|----------|--------|
| **Scoped（推薦）** | `POST /webhook/push` / `/webhook/push/manual` | 依 git diff 比對 [e2e-module-mapping.yml](src/main/resources/e2e-module-mapping.yml)，**只測受影響模組** | 2–5 分鐘 | 高（程式碼自動登入） |
| **Deployment** | `POST /webhook/deployment-completed` | 所有 `critical=true` 模組 | 10–20 分鐘 | 高 |
| **Unscoped** | `POST /api/e2e/run` / `/run-sync` | AI 自己規劃整個 app | 10–20 分鐘 | **低**（AI 自行判斷登入易失敗） |

**Scoped 流程**：
```
git diff → glob 模式比對 → 受影響模組（如 order, inventory）
    ↓
依 required-role 用對應帳號登入（ADMIN/SALES/INVENTORY/CAPTAIN）
    ↓
for 每個 test-flow（按 priority 排序）:
    導航到 route → 抓 accessibility tree
    → AI Planner 規劃步驟（給 steps-hint 當提示）
    → Playwright 逐步執行（NAVIGATE / CLICK / TYPE / ASSERT / WAIT）
    → 每步截圖到 byte[]（不寫磁碟）
    → 任一步失敗 → 收集 failedSteps
        ↓
        flow 結束時：AI 把多個失敗步驟「歸納」為一個 bug
        產出兩段式描述：
          - summary：人話（PM/主管看）
          - technicalDetail：技術細節（給後續 AI 修 code 用）
        ↓
        建立 Bug Work Item
        → 上傳截圖到 Azure DevOps 附件
        → 更新 ReproSteps HTML（含截圖、測試資訊、操作流程）
        ↓
觸發自動修復（模組 3）
    ↓
Teams 通知（含每個 bug 的標題、Work Item ID、修復狀態）
```

實作：[E2ETestOrchestrator.java](src/main/java/com/team/aiworkflow/service/e2e/E2ETestOrchestrator.java) · [AITestPlanner.java](src/main/java/com/team/aiworkflow/service/e2e/AITestPlanner.java) · [PlaywrightService.java](src/main/java/com/team/aiworkflow/service/e2e/PlaywrightService.java)

### 模組 3：AI 自動修復（兩階段）

```
階段 1（自動執行）：
E2E 發現 Bug
    ↓
SourceCodeResolver 找相關源碼（三層優先級）：
  1. bug 描述中提取的元件名稱（PascalCase regex）
  2. test-flow 層級的 file-patterns（精準，如 **/views/order/d2/**）
  3. 模組層級的 file-patterns（補上下文）
    ↓
PromptBuilder 組裝 bug-fix prompt（含路由、模組、主要檔案、源碼上下文、suggestedFix）
    ↓
Claude Opus 產生修復方案（結構化 JSON：fixDescription / changes[] / explanation / testSuggestion）
    ↓
TargetRepoGitService（用 ProcessBuilder 跑 git，禁止 --force / --hard）：
  git checkout {baseBranch} → git pull → git checkout -b ai-fix/{workItemId}
    ↓
FixApplicator 套用 changes[]：
  精確替換 → 失敗則 fuzzy 替換（行 trim 比對）
    ↓
git add → git commit → git push -u origin ai-fix/{workItemId}
    ↓
建立 PR（關聯 Work Item，加 'ai-generated' label）
    ↓
Work Item 加註：分支名、PR 編號、修復說明、re-test 指令

階段 2（人類觸發）：
工程師在 IDE 切到 ai-fix/{workItemId} 分支 → 重啟目標 app
    ↓
POST /api/e2e/autofix/retest/{workItemId}
    ↓
跑 deployment scope（所有 critical 模組）
    ↓
通過 → resolveWorkItem（State=Resolved） + 記錄 AI 修復成功
失敗 → addComment（仍有 N 個 bug） + Work Item 保持開啟
```

實作：[AutoFixOrchestrator.java](src/main/java/com/team/aiworkflow/service/autofix/AutoFixOrchestrator.java) · [SourceCodeResolver.java](src/main/java/com/team/aiworkflow/service/autofix/SourceCodeResolver.java) · [FixApplicator.java](src/main/java/com/team/aiworkflow/service/autofix/FixApplicator.java) · [TargetRepoGitService.java](src/main/java/com/team/aiworkflow/service/autofix/TargetRepoGitService.java)

---

## 系統架構

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Azure DevOps（事件源 + 動作目標）                  │
│  Service Hooks → POST  /webhook/pipeline-failure / /push / /deployment   │
│  REST API ← Work Items / PRs / Attachments / Build Logs / Git Diff       │
└──────────────────────────┬───────────────────────────────────────────────┘
                           │
            ┌──────────────▼──────────────────────────────────────┐
            │   Spring Boot 3.4 (WebFlux + MVC, port 8081)        │
            │                                                      │
            │   Controllers ──► Orchestrators ──► Services         │
            │                                                      │
            │   ┌────────────────────────────────────────────┐    │
            │   │ FailureAnalysisService（模組 1）            │    │
            │   │   PipelineService ─┐                       │    │
            │   │                    │ parallel              │    │
            │   │   GitRepoService ──┘                       │    │
            │   │            ↓                               │    │
            │   │   ClaudeApiService.analyze() [Sonnet]      │    │
            │   │            ↓                               │    │
            │   │   WorkItemService.createBug()              │    │
            │   │            ↓                               │    │
            │   │   TeamsNotificationService                 │    │
            │   └────────────────────────────────────────────┘    │
            │                                                      │
            │   ┌────────────────────────────────────────────┐    │
            │   │ E2ETestOrchestrator（模組 2）              │    │
            │   │   GitDiffAnalysisService（glob 比對）       │    │
            │   │   TestScopeResolver（產 TestScope DTO）     │    │
            │   │   PlaywrightService（Vaadin Shadow DOM）    │    │
            │   │   AITestPlanner [Sonnet]                   │    │
            │   │   AI Bug 歸納（多失敗步驟 → 1 bug）          │    │
            │   │   WorkItemService（建 WI + 上傳截圖）        │    │
            │   └────────────────────────────────────────────┘    │
            │                                                      │
            │   ┌────────────────────────────────────────────┐    │
            │   │ AutoFixOrchestrator（模組 3）              │    │
            │   │   SourceCodeResolver（三層優先級找 code）   │    │
            │   │   ClaudeApiService.analyzeComplex() [Opus] │    │
            │   │   FixApplicator（精確 → fuzzy）              │    │
            │   │   TargetRepoGitService（ProcessBuilder git）│    │
            │   │   PullRequestService                       │    │
            │   └────────────────────────────────────────────┘    │
            │                                                      │
            │   ┌────────────────────────────────────────────┐    │
            │   │ EngineerStats 子系統（衍生功能）            │    │
            │   │   EngineerStatsRecorder（在主流程關鍵點記錄）│    │
            │   │   EngineerStatsService（DB 查詢 + 統計）    │    │
            │   │   EngineerStatsScheduler（@Scheduled 週/月報）│   │
            │   │   ↕ JPA + H2 (engineer_profile, push_record,│    │
            │   │     bug_record)                            │    │
            │   └────────────────────────────────────────────┘    │
            │                                                      │
            │   Cross-cutting：                                    │
            │   - Bucket4j Rate Limit（10/min, 100/hr）            │
            │   - @Async aiTaskExecutor（core=2, max=5, queue=25）│
            │   - PromptBuilder（5 個 template，含 placeholder）    │
            └──────────────────┬──────────────────┬─────────────────┘
                               │                  │
                ┌──────────────▼─┐    ┌──────────▼─────────┐
                │ Anthropic API   │    │ Microsoft Teams    │
                │  Sonnet 4.5     │    │  Incoming Webhook  │
                │  Opus 4.6       │    │  Adaptive Cards    │
                └─────────────────┘    └────────────────────┘
```

### 資料流關鍵設計

- **`TestScope`**（[TestScopeResolver.java](src/main/java/com/team/aiworkflow/service/e2e/TestScopeResolver.java)）：封裝「要跑哪些 flow + 用什麼角色登入 + 上下文描述」的完整測試計畫。從模組層、flow 層兩階段過濾。
- **`BugFound`**（[E2ETestResult.java](src/main/java/com/team/aiworkflow/model/e2e/E2ETestResult.java)）：bug 描述是**兩段式**的（`expectedBehavior` 給人看、`actualBehavior` 給 AI 修 code 用），這個拆分讓自動修復 prompt 品質明顯較高。
- **截圖以 `byte[]` 流動**：Playwright 截圖 → 存在 `TestStep.screenshotData` → 上傳到 Azure DevOps 附件 → 拿到 attachment URL → 嵌入 Work Item 的 ReproSteps HTML → 清掉 `byte[]` 釋放記憶體（不寫磁碟）。

---

## 技術棧

| 類別 | 選擇 | 為什麼 |
|------|------|--------|
| 語言 | Java 17 | Spring Boot 3.4 最低需求；text block 寫 prompt 很乾淨 |
| Web | Spring Boot 3.4.2 + **WebFlux** | webhook 必須立刻回 200，實際工作背景跑；對 Claude / Azure DevOps 都是 I/O bound |
| AI | Anthropic Java SDK 1.1 (`anthropic-java`) | 但實際呼叫走 `WebClient`，方便整合反應式流 |
| 模型 | `claude-sonnet-4-5-20250929` + `claude-opus-4-6` | Sonnet 分析便宜、Opus 程式碼生成準 |
| 瀏覽器 | Playwright for Java 1.49 (Chromium) | Selenium 對 Vaadin Web Components 不穩；Playwright 對 Shadow DOM 較友善 |
| 限流 | Bucket4j 8.10.1（雙窗口：10/min + 100/hr） | 保護 Claude API 配額，避免暴衝燒錢 |
| 持久層 | Spring Data JPA + H2（檔案型，`AUTO_SERVER=TRUE`） | 工程師績效統計用；輕量、零維運；多實例需換 PG |
| .env | spring-dotenv 4.0 | 啟動時自動載入 `.env` |
| 測試 | JUnit 5 + AssertJ + Reactor Test + MockWebServer 4.12 | 反應式測試用 `StepVerifier`；Claude API 用 MockWebServer 模擬 |
| 部署 | Multi-stage Docker (eclipse-temurin:17-jre-jammy + 系統 Chromium) | jammy 是 Playwright 官方支援 base；非 root 執行 |

---

## 快速開始

### 前置需求

- Java 17+、Maven 3.9+
- Anthropic API Key
- Azure DevOps PAT（需要 Work Items R/W、Code R/W、Build R）
- 一份**目標 app 的本機 clone**（AI 自動修復會在這個目錄跑 git 操作）

### 1. 設定環境變數

複製 [.env.example](.env.example) 為 `.env`：

```bash
cp .env.example .env
```

最小可運作的設定：

```bash
# Claude
CLAUDE_API_KEY=sk-ant-xxx

# Azure DevOps
AZURE_DEVOPS_ORG=your-org
AZURE_DEVOPS_PROJECT=your-project
AZURE_DEVOPS_PAT=xxx

# E2E 測試目標
E2E_STAGING_URL=http://localhost:8080
E2E_APP_DESCRIPTION=訂單管理系統
E2E_TEST_USERNAME=admin
E2E_TEST_PASSWORD=admin

# 自動修復（選填）
AUTO_FIX_ENABLED=true
AUTO_FIX_TARGET_REPO_PATH=/Users/you/Desktop/target-app
AUTO_FIX_TARGET_REPO_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx

# Teams 通知（選填）
TEAMS_WEBHOOK_URL=https://your-tenant.webhook.office.com/...

# 工程師績效追蹤（選填）
ENGINEER_STATS_ENABLED=true
MANAGER_TEAMS_WEBHOOK_URL=https://...
```

### 2. 編譯啟動

```bash
mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

服務啟動於 `http://localhost:8081`（dev profile 改用 8080）。

健康檢查：

```bash
curl http://localhost:8081/api/health
# {"status":"UP","service":"AI Dev Workflow","version":"0.0.1"}
```

H2 console（dev）：`http://localhost:8081/h2-console`，JDBC URL `jdbc:h2:file:./data/engineer-stats`。

### 3. 跑一個 Scoped E2E 測試（推薦的試水方法）

```bash
curl -X POST http://localhost:8081/webhook/push/manual \
  -H "Content-Type: application/json" \
  -d '{
    "changedFiles": [
      "src/main/java/com/soetek/ods/views/order/d2/components/D2GridComponent.java"
    ],
    "branch": "feature/test"
  }'
```

回傳會顯示**哪些模組受影響、跑幾個 flow**：

```json
{
  "status": "accepted",
  "message": "AI Test Agent 已觸發：1 個模組，1 個測試流程",
  "affectedModules": ["order"],
  "testFlows": ["業務員每日訂貨流程 (/order/d2)"]
}
```

如果只想**預覽會跑什麼測試而不真的執行**：

```bash
curl -X POST http://localhost:8081/webhook/push/analyze \
  -H "Content-Type: application/json" \
  -d '{"changedFiles":[...]}'
```

### 4. 設定 Azure DevOps Service Hooks

| 觸發事件 | URL |
|----------|-----|
| Build completed (Failed) | `https://your-host/webhook/pipeline-failure` |
| Code pushed | `https://your-host/webhook/push` |
| Release deployment completed | `https://your-host/webhook/deployment-completed` |

路徑：**Project Settings → Service Hooks → Create Subscription → Web Hooks**

---

## API 端點

### Webhook（接收 Azure DevOps 事件）

| Method | Path | 說明 | 觸發後行為 |
|--------|------|------|-----------|
| POST | `/webhook/pipeline-failure` | Build 失敗事件 | 模組 1：分析 → 建 WI → 通知 |
| POST | `/webhook/pipeline-failure/test` | Webhook 設定時的驗證 | 回傳 200 確認可達 |
| POST | `/webhook/push` | Push 事件 | 模組 2 Scoped：分析受影響模組 → 跑對應 flow |
| POST | `/webhook/deployment-completed` | 部署完成事件 | 模組 2：跑 unscoped 全量 |

### 手動 API

| Method | Path | 說明 |
|--------|------|------|
| POST | `/webhook/push/manual` | 手動觸發 Scoped E2E（要塞 `changedFiles[]` + `branch`） |
| POST | `/webhook/push/analyze` | **只分析不執行**，預覽 git diff 會觸發哪些 flow |
| POST | `/api/e2e/run` | 手動觸發 Unscoped E2E（非同步） |
| POST | `/api/e2e/run-sync` | 手動觸發 Unscoped E2E（同步等結果） |
| POST | `/api/e2e/autofix/retest/{workItemId}` | 觸發 AI 修復 re-test |
| POST | `/api/analyze-failure` | 手動觸發 CI 失敗分析 |
| GET | `/api/health` | 健康檢查 |

### 工程師績效統計

| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/report/engineer-stats?email=&project=&from=&to=` | 單一工程師統計 |
| GET | `/api/report/engineer-stats/summary?project=&from=&to=` | 專案內所有工程師摘要 |
| GET | `/api/report/engineer-stats/{email}/streak?project=` | 連續失敗次數 |
| GET | `/api/report/engineer-stats/projects` | 所有有記錄的專案 |

### Spring Actuator

| Method | Path | 說明 |
|--------|------|------|
| GET | `/actuator/health` | Spring health |
| GET | `/actuator/info` | App info |

---

## 設定詳解

### `application.yml`（主檔）

```yaml
server:
  port: 8081

claude:
  api-key: ${CLAUDE_API_KEY:your-api-key-here}
  model: claude-sonnet-4-5-20250929          # 預設模型（輕量分析）
  model-complex: claude-opus-4-6              # 自動修復用
  max-tokens: 4096
  timeout-seconds: 60

azure-devops:
  organization: ${AZURE_DEVOPS_ORG}
  project: ${AZURE_DEVOPS_PROJECT}
  pat: ${AZURE_DEVOPS_PAT}

rate-limit:
  claude-api:
    requests-per-minute: 10
    requests-per-hour: 100

playwright:
  chromium-path: ${PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH:}   # 留空 = Playwright 自動下載
  headless: ${PLAYWRIGHT_HEADLESS:true}                    # demo 時改 false 看瀏覽器

workflow:
  failure-analysis:
    enabled: true
    branches: main,develop                  # 只在這些分支跑
    max-log-lines: 500
  e2e-testing:
    enabled: true
    staging-url: ${E2E_STAGING_URL:}
    repository: BA.WeiChuan.OCDS.Web.v2     # 只接受這個 repo 的 push event
    branches: ai-dev-workflow                # 只在這些分支觸發
    max-steps: 30
    timeout-seconds: 300
    screenshot-each-step: true               # 每步截圖（上傳到 Azure DevOps）
  auto-fix:
    enabled: ${AUTO_FIX_ENABLED:false}
    target-repo-path: ${AUTO_FIX_TARGET_REPO_PATH:}
    target-repo-id: ${AUTO_FIX_TARGET_REPO_ID:}
    base-branch: ai-dev-workflow
    branch-prefix: ai-fix/
    max-files-to-read: 10                    # 控制 prompt token 用量
    source-base-path: src/main/java/com/soetek/ods
  engineer-stats:
    enabled: ${ENGINEER_STATS_ENABLED:false}
    consecutive-failure-threshold: 3         # 連續失敗 N 次通知主管
    manager-teams-webhook-url: ${MANAGER_TEAMS_WEBHOOK_URL:}
    cron-weekly-summary: "0 0 9 * * MON"     # 週一 09:00
    cron-monthly-summary: "0 0 9 1 * *"      # 每月 1 號 09:00
```

### Profile

- **default**（`application.yml`）：production-like 預設
- **dev**（[`application-dev.yml`](src/main/resources/application-dev.yml)）：port=8080、Rate Limit 較寬鬆（20/min, 200/hr）、DEBUG log、允許 `feature/*` 分支
- **prod**（[`application-prod.yml`](src/main/resources/application-prod.yml)）：所有 secret 走環境變數、INFO log

切換：

```bash
java -jar app.jar --spring.profiles.active=prod
# 或
SPRING_PROFILES_ACTIVE=prod java -jar app.jar
```

### 安全控制

[TargetRepoGitService.java:92-99](src/main/java/com/team/aiworkflow/service/autofix/TargetRepoGitService.java) 內建黑名單：

```java
if ("--force".equals(arg) || "-f".equals(arg) && "push".equals(args[0])) {
    throw new IllegalArgumentException("禁止 force push 操作");
}
if ("--hard".equals(arg)) {
    throw new IllegalArgumentException("禁止 reset --hard 操作");
}
```

---

## 模組映射（`e2e-module-mapping.yml`）

**這是整個系統最關鍵的配置檔。** 它定義了：

1. **登入設定**：URL、表單欄位選擇器、不同角色帳號（從環境變數注入）
2. **業務模組**：14 個模組，每個模組有：
   - `id`、`name`、`critical`（部署觸發是否必跑）
   - `file-patterns`：glob 模式陣列，決定哪些檔案變動算「影響此模組」
   - `required-role`：用哪個角色帳號登入測試
   - `test-flows[]`：該模組要跑的多個測試流程

3. **每個 test-flow**：
   - `id`、`name`、`description`（給 AI 看）、`route`、`priority`
   - `steps-hint`：給 AI 的精確步驟提示（用 `NAVIGATE / CLICK / TYPE / ASSERT` 動詞描述）
   - `file-patterns`（選填）：**flow 層級精準匹配**——只在變動檔案匹配時才跑這個 flow

### 兩層精準匹配

```
git diff 變動檔案
    ↓ glob 匹配
模組層 file-patterns  →  受影響模組（如 order）
    ↓
模組底下的每個 flow：
  - 有 flow-level file-patterns？
    Y → 變動檔案是否匹配？ Y → 跑；N → 跳過
    N → 跑（向下相容）
```

舉例（[e2e-module-mapping.yml:55-119](src/main/resources/e2e-module-mapping.yml)）：

```yaml
- id: order
  critical: true
  file-patterns:
    - "**/views/order/**"
  required-role: "ADMIN"
  test-flows:
    - id: order-daily
      route: "/order/d2"
      file-patterns:                          # ← flow 層級
        - "**/views/order/d2/**"               # 只有改 d2 才跑這個 flow
        - "**/views/order/*D2*"
    - id: order-summary
      route: "/order/summary"
      file-patterns:
        - "**/views/order/summary/**"          # 只有改 summary 才跑
```

→ 改 `views/order/d2/D2GridComponent.java` 只會跑 `order-daily`，不會跑其他 5 個 flow。

### 新增業務模組

1. 在 [e2e-module-mapping.yml](src/main/resources/e2e-module-mapping.yml) `modules:` 下新增一個模組
2. 寫好 `file-patterns`（決定哪些檔案變動會觸發）
3. 寫好 `test-flows`，每個 flow 寫詳細的 `steps-hint`（AI 會依照這個 hint 規劃步驟）
4. 重啟服務（無需重新編譯，但需要 reload config）

---

## Prompt 工程

5 個 prompt template 在 [src/main/resources/prompts/](src/main/resources/prompts/)：

| 檔案 | 用途 | 模型 | Placeholder |
|------|------|------|-------------|
| [failure-analysis.txt](src/main/resources/prompts/failure-analysis.txt) | CI 失敗分析 | Sonnet | `{{BUILD_INFO}}`、`{{TEST_LOG}}`、`{{CODE_DIFF}}` |
| [bug-fix.txt](src/main/resources/prompts/bug-fix.txt) | AI 自動修復 | **Opus** | `{{PAGE_ROUTE}}`、`{{MODULE_NAME}}`、`{{PRIMARY_FILES}}`、`{{BUG_DESCRIPTION}}`、`{{TEST_EVIDENCE}}`、`{{SUGGESTED_FIX}}`、`{{CODE_CONTEXT}}`、`{{STACK_TRACE}}` |
| [e2e-test-planner.txt](src/main/resources/prompts/e2e-test-planner.txt) | E2E 步驟規劃 | Sonnet | `{{APP_URL}}`、`{{APP_DESCRIPTION}}`、`{{PAGE_CONTENT}}`、`{{MAX_STEPS}}` |
| [e2e-step-executor.txt](src/main/resources/prompts/e2e-step-executor.txt) | 自適應下一步決策 | Sonnet | `{{OBJECTIVE}}`、`{{APP_URL}}`、`{{PAGE_CONTENT}}`、`{{CONSOLE_ERRORS}}`、`{{COMPLETED_STEPS}}` |
| [test-generation.txt](src/main/resources/prompts/test-generation.txt) | 單元測試生成（未啟用） | Sonnet | `{{CODE_DIFF}}`、`{{EXISTING_TESTS}}` |

**Prompt 工程的幾個關鍵設計**：

- **`bug-fix.txt`** 明確告訴 AI「The `originalCode` MUST be copied EXACTLY from the source code above」——避免 AI 重新格式化導致 `FixApplicator` 找不到對應字串。但即使如此，`FixApplicator` 還是準備了 `fuzzyReplace()`（每行 trim 後比對）作為 fallback。
- **AI Bug 歸納**用 inline prompt（[E2ETestOrchestrator.java:563-590](src/main/java/com/team/aiworkflow/service/e2e/E2ETestOrchestrator.java)），強制要求兩段式輸出：`summary`（給 PM 看，不能出現 CSS selector）+ `technicalDetail`（給後續 AI 修 code）。
- **E2E Planner** 用 inline prompt（[AITestPlanner.java:39-101](src/main/java/com/team/aiworkflow/service/e2e/AITestPlanner.java)），針對 Vaadin 給了 6 條 CRITICAL RULES，特別防止 AI 把 `CLICK 儲存` 偷換成 `ASSERT 儲存按鈕存在`。

---

## 工程師績效追蹤子系統

完全可選的衍生功能。啟用後（`ENGINEER_STATS_ENABLED=true`），系統會自動：

### 資料模型（H2）

```
engineer_profile（工程師主檔，以 email 唯一）
   ↓ 1:N
push_record（每次 push 一筆）
  - testOutcome: PENDING / PASSED / FAILED / ERROR / TIMEOUT
  - bugsFound: 此次發現的 bug 數
   ↓ 1:N
bug_record（每個 bug 一筆）
  - severity: CRITICAL / HIGH / MEDIUM / LOW
  - resolution: AI_AUTO_FIX / UNRESOLVED  ← 只追蹤 AI 修復
  - resolvedAt: AI 修復成功時間
```

### 記錄時機（[EngineerStatsRecorder.java](src/main/java/com/team/aiworkflow/service/stats/EngineerStatsRecorder.java)）

| 時機 | 呼叫 | 寫入 |
|------|------|------|
| Push webhook 進來 | `recordPush()` | `PushRecord` (PENDING) |
| E2E 測試完成 | `updateTestResult()` | 更新 `PushRecord.testOutcome` + `bugsFound` |
| 每建立一個 Bug WI | `recordBug()` | `BugRecord` (UNRESOLVED) |
| AI 修復通過 re-test | `recordAiFixSuccess()` | 更新 `BugRecord.resolution = AI_AUTO_FIX` |

### 自動推播

- **即時警報**：連續失敗 ≥ `consecutive-failure-threshold`（預設 3）次，即時推到主管 webhook
- **週報**：每週一 09:00（`cron-weekly-summary`），上週各專案的摘要表格
- **月報**：每月 1 號 09:00（`cron-monthly-summary`），上個月摘要

報告格式（Markdown 表格）：

```
| 工程師 | Push | 失敗 | Bug | AI 修復率 | 連續失敗 |
|--------|------|------|-----|----------|--------|
| Alice  | 12   | 2(17%) | 5 | 60%     | 0      |
| Bob    | 8    | 3(38%) | 8 | 25%     | 2 ⚠️   |
```

---

## Vaadin 特化處理

目標 app 是 Vaadin 24（Web Components + Shadow DOM），標準 Playwright API 對它不穩。[PlaywrightService.java](src/main/java/com/team/aiworkflow/service/e2e/PlaywrightService.java) 內建多層 fallback：

### `click()` 三層 fallback

```
1. Playwright locator.click() with 10s timeout
   ↓ 被 overlay 攔截（"intercepts pointer events"）
2. JS 強制移除 vaadin-overlay / loading indicator → locator.dispatchEvent("click")
   ↓ Timeout
3. 從 selector 提取 :has-text('xxx') 的文字
   → waitForFunction 找包含該文字且 enabled 的按鈕（穿透 Shadow DOM + overlay）
   → 直接 .click() 該 DOM 節點
```

### `type()` 三層 fallback

```
1. Playwright locator.fill() with 3s timeout
   ↓ 失敗
2. JS 找到 Vaadin field → inputElement / shadowRoot.querySelector('input')
   → 設 value + dispatch input/change/value-changed 事件
   ↓ 失敗
3. focus 第一個非 readonly Grid 欄位 → keyboard.press('Ctrl+A') + keyboard.type(text)
```

### `getAccessibilityTree()`

不只回傳 `innerText`，還會：

- 列出所有標準 HTML 互動元素
- 列出所有 Vaadin Web Components（`vaadin-button`、`vaadin-grid`、`vaadin-text-field` 等 13 種）
- 額外列出 `vaadin-grid` 內所有 `[data-row][data-col]` 可編輯欄位

這份結構化文字會餵給 AI Planner，讓它知道頁面上能操作什麼。

### Login

[E2ETestOrchestrator.java:393-455](src/main/java/com/team/aiworkflow/service/e2e/E2ETestOrchestrator.java) 用 JS 直接深入 Vaadin LoginForm 的 Shadow DOM 設值——`fill()` 對 Vaadin LoginForm 不可靠。

---

## 部署

### Docker

[Dockerfile](Dockerfile) 是 multi-stage build：

```bash
docker build -t ai-dev-workflow .
docker run -d \
  -p 8080:8080 \
  --env-file .env \
  -v /path/to/target-repo:/workspace/target-repo \
  --name ai-dev-workflow \
  ai-dev-workflow
```

Image 特性：
- Base: `eclipse-temurin:17-jre-jammy`（Playwright 官方推薦）
- 預裝系統 Chromium 與 CJK 字型（`fonts-noto-cjk`）
- 設定 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium`（不下載 Playwright 自帶瀏覽器，省 250MB）
- 非 root 執行（`appuser:appgroup`）
- Health check：`wget /api/health`

### Production checklist

- [ ] 改用 PostgreSQL 取代 H2（多實例部署）
- [ ] 配置 Azure Key Vault 注入 secret（不要用 .env）
- [ ] `PLAYWRIGHT_HEADLESS=true`、`E2E_TESTING_ENABLED=true`
- [ ] 配置 Azure DevOps Service Hook 指向 production URL
- [ ] 設定 Teams webhook URL
- [ ] 啟用 Spring Actuator `/actuator/prometheus`（需加 micrometer-registry-prometheus）

---

## 測試

```bash
mvn test
```

13 個測試檔，涵蓋：

- **Claude API**：[ClaudeApiServiceTest.java](src/test/java/com/team/aiworkflow/service/claude/ClaudeApiServiceTest.java) 用 MockWebServer 模擬 Anthropic API
- **PromptBuilder / ResponseParser**：[PromptBuilderTest.java](src/test/java/com/team/aiworkflow/service/claude/PromptBuilderTest.java) · [ResponseParserTest.java](src/test/java/com/team/aiworkflow/service/claude/ResponseParserTest.java)
- **CI 失敗分析**：[FailureAnalysisServiceTest.java](src/test/java/com/team/aiworkflow/service/analysis/FailureAnalysisServiceTest.java)
- **自動修復**：4 個檔案測試 [AutoFixOrchestrator](src/test/java/com/team/aiworkflow/service/autofix/AutoFixOrchestratorTest.java) / [SourceCodeResolver](src/test/java/com/team/aiworkflow/service/autofix/SourceCodeResolverTest.java) / [FixApplicator](src/test/java/com/team/aiworkflow/service/autofix/FixApplicatorTest.java) / [TargetRepoGitService](src/test/java/com/team/aiworkflow/service/autofix/TargetRepoGitServiceTest.java)
- **Teams 通知**：[TeamsNotificationServiceTest.java](src/test/java/com/team/aiworkflow/service/notification/TeamsNotificationServiceTest.java)
- **Controller**：[AutoFixControllerTest.java](src/test/java/com/team/aiworkflow/controller/AutoFixControllerTest.java)

**反應式測試**用 `StepVerifier`，模擬非同步 Claude API 用 `MockWebServer`。

**沒有**：整合測試、Playwright 端對端測試（瀏覽器自動化的 unit test 成本太高）、E2E orchestrator 的測試。

---

## 專案結構

```
ai-dev-workflow/
├── README.md
├── Dockerfile
├── pom.xml                                    # Maven 依賴
├── .env.example                               # 環境變數範本
├── docs/
│   └── presentation-notes.md                  # 商業簡報備忘錄（給老闆）
├── src/main/
│   ├── java/com/team/aiworkflow/
│   │   ├── AiWorkflowApplication.java         # @EnableAsync @EnableScheduling
│   │   ├── config/                            # 8 個 @Configuration
│   │   │   ├── AsyncConfig.java               # aiTaskExecutor (core=2 max=5 queue=25)
│   │   │   ├── AutoFixConfig.java             # workflow.auto-fix.*
│   │   │   ├── AzureDevOpsConfig.java         # 提供 azureDevOpsWebClient bean
│   │   │   ├── ClaudeApiConfig.java           # model / model-complex / max-tokens
│   │   │   ├── EngineerStatsConfig.java       # cron / threshold / 主管 webhook
│   │   │   ├── ModuleMappingConfig.java       # 載入 e2e-module-mapping.yml
│   │   │   └── RateLimitConfig.java           # Bucket4j claudeApiRateLimiter bean
│   │   ├── controller/                        # 7 個 controller
│   │   │   ├── WebhookController.java         # POST /webhook/pipeline-failure
│   │   │   ├── PushWebhookController.java     # /webhook/push, /push/manual, /push/analyze
│   │   │   ├── DeploymentWebhookController.java   # /webhook/deployment-completed
│   │   │   ├── E2ETestController.java         # /api/e2e/run, /run-sync
│   │   │   ├── AutoFixController.java         # /api/e2e/autofix/retest/{id}
│   │   │   ├── AnalysisController.java        # /api/analyze-failure, /api/health
│   │   │   └── EngineerStatsController.java   # /api/report/engineer-stats/*
│   │   ├── service/
│   │   │   ├── analysis/
│   │   │   │   └── FailureAnalysisService.java  # 模組 1 編排
│   │   │   ├── e2e/
│   │   │   │   ├── E2ETestOrchestrator.java     # 模組 2 編排（核心，949 行）
│   │   │   │   ├── AITestPlanner.java           # AI 規劃 + 自適應決策
│   │   │   │   ├── PlaywrightService.java       # Vaadin Shadow DOM 處理（729 行）
│   │   │   │   ├── TestScopeResolver.java       # 模組 ID → TestScope
│   │   │   │   └── GitDiffAnalysisService.java  # glob 模式比對
│   │   │   ├── autofix/
│   │   │   │   ├── AutoFixOrchestrator.java     # 模組 3 編排
│   │   │   │   ├── SourceCodeResolver.java      # bug → 相關源碼
│   │   │   │   ├── FixApplicator.java           # 精確 + fuzzy 替換
│   │   │   │   └── TargetRepoGitService.java    # ProcessBuilder 跑 git
│   │   │   ├── azuredevops/
│   │   │   │   ├── WorkItemService.java         # Work Item CRUD + 附件
│   │   │   │   ├── PipelineService.java         # Build log / timeline
│   │   │   │   ├── PullRequestService.java      # PR CRUD
│   │   │   │   └── GitRepoService.java          # Diff / 檔案內容
│   │   │   ├── claude/
│   │   │   │   ├── ClaudeApiService.java        # WebClient + Bucket4j
│   │   │   │   ├── PromptBuilder.java           # 載入 5 個 template
│   │   │   │   └── ResponseParser.java          # JSON 提取（含 markdown block）
│   │   │   ├── notification/
│   │   │   │   └── TeamsNotificationService.java  # Adaptive Card
│   │   │   └── stats/
│   │   │       ├── EngineerStatsRecorder.java   # 在關鍵點寫入 DB
│   │   │       ├── EngineerStatsService.java    # 統計查詢 + 連續失敗檢查
│   │   │       └── EngineerStatsScheduler.java  # 週/月報 @Scheduled
│   │   ├── model/
│   │   │   ├── AnalysisResult.java
│   │   │   ├── autofix/AutoFixResult.java
│   │   │   ├── e2e/
│   │   │   │   ├── E2ETestRequest.java
│   │   │   │   ├── E2ETestResult.java            # 含 BugFound 內部類
│   │   │   │   └── TestStep.java
│   │   │   ├── dto/
│   │   │   │   ├── PipelineEvent.java
│   │   │   │   └── WorkItemCreateRequest.java
│   │   │   ├── entity/
│   │   │   │   ├── EngineerProfile.java
│   │   │   │   ├── PushRecord.java
│   │   │   │   └── BugRecord.java
│   │   │   └── stats/EngineerStats.java
│   │   ├── repository/
│   │   │   ├── EngineerProfileRepository.java
│   │   │   ├── PushRecordRepository.java
│   │   │   └── BugRecordRepository.java
│   │   └── util/
│   │       ├── LogTruncator.java
│   │       └── DiffParser.java
│   └── resources/
│       ├── application.yml                    # 主設定
│       ├── application-dev.yml                # dev profile
│       ├── application-prod.yml               # prod profile
│       ├── e2e-module-mapping.yml             # ★ 模組映射核心配置
│       └── prompts/                           # 5 個 prompt template
│           ├── failure-analysis.txt
│           ├── bug-fix.txt
│           ├── e2e-test-planner.txt
│           ├── e2e-step-executor.txt
│           └── test-generation.txt
└── src/test/java/com/team/aiworkflow/         # 13 個測試類別
```

---

## 限制與已知問題

### 已知不穩定

| 問題 | 影響 | Workaround |
|------|------|-----------|
| **Unscoped 模式登入不穩** | AI 自行規劃登入步驟失敗率高 | 一律用 Scoped 模式（需提供 changedFiles） |
| AI 修復後的 originalCode 偶爾對不上 | `FixApplicator` 找不到要替換的字串 | 已內建 `fuzzyReplace()`，但仍有 < 10% 失敗率 |
| 多檔案修改可能不一致 | Opus 修 A 檔案但 B 檔案沒同步改 | 控制 `max-files-to-read` 別太大 |
| 並行 webhook 競爭 Playwright | 同時多個 webhook 進來會搶瀏覽器資源 | `aiTaskExecutor` queue=25，超過會丟錯；改 max-pool 要小心 RAM |

### 設計缺口

- **Prompt 寫死在 jar**：修改 prompt 要重新部署（沒有熱更新）
- **H2 不適合分散式**：多實例部署要換 PostgreSQL
- **Phase 4 未實現**：AI 自動產生單元測試還在 `test-generation.txt`，沒接到任何 controller
- **無 metrics export**：沒有 Prometheus / micrometer-registry 接入
- **截圖只存附件**：上傳到 Azure DevOps 後本機就丟，無法長期追蹤 bug 歷史截圖
- **無 CI/CD pipeline 設定**：repo 沒有 `.github/workflows/` 或 `azure-pipelines.yml`

### 安全考量

- ✅ 已禁止 `git --force` / `--hard`
- ✅ Bucket4j 限流避免 Claude API 失控
- ⚠️ 目標 repo 路徑沒有 chroot 隔離——`target-repo-path` 必須是可信任路徑
- ⚠️ Prompt injection 風險：CI log 內容直接餵給 AI；惡意 commit message 理論上可以操控 AI 行為

---

## Roadmap

- [x] **Phase 1**: CI 測試失敗自動分析 → AI 建立 Work Item
- [x] **Phase 2**: AI E2E 測試 → Push 觸發 → 偵測 Bug → 建立 Work Item（附截圖）
- [x] **Phase 3**: AI 自動修復 → 產生修復 PR → Re-test → 自動關閉 Work Item
- [x] **Phase 3.5**: 工程師績效追蹤（H2 + 週/月報 + 連續失敗警報）
- [ ] **Phase 4**: AI 自動產生單元測試建議（template 已備，待整合）
- [ ] **Phase 5**: Prompt 熱更新（資料庫存儲 + admin UI）
- [ ] **Phase 6**: PostgreSQL 替代 H2（多實例 + 跨環境統計）
- [ ] **Phase 7**: 多瀏覽器並行測試（worker pool）
