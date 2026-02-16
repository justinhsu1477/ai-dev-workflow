package com.team.aiworkflow.service.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.team.aiworkflow.model.e2e.TestStep;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.Base64;

/**
 * 封裝 Playwright 瀏覽器操作。
 * 管理瀏覽器生命週期，並提供方法讓 AI Agent 與頁面互動。
 *
 * 截圖以 byte[] 回傳，不寫入磁碟，
 * 由 WorkItemService 直接上傳到 Azure DevOps Work Item 附件。
 */
@Service
@Slf4j
public class PlaywrightService {

    @Value("${playwright.chromium-path:}")
    private String chromiumExecutablePath;

    @Value("${playwright.headless:true}")
    private boolean headless;

    @Value("${e2e.staging-url:http://localhost:8080}")
    private String stagingUrl;

    private Playwright playwright;
    private Browser browser;

    /**
     * 建立新的瀏覽器工作階段，用於 E2E 測試。
     * 回傳 BrowserContext，可用來建立頁面。
     */
    public BrowserContext createSession() {
        if (playwright == null) {
            log.info("正在初始化 Playwright...（headless={}）", headless);
            playwright = Playwright.create();

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(headless)
                    .setTimeout(30000);

            // 在 Docker 中使用系統安裝的 Chromium
            if (chromiumExecutablePath != null && !chromiumExecutablePath.isBlank()) {
                launchOptions.setExecutablePath(Paths.get(chromiumExecutablePath));
                log.info("使用系統 Chromium：{}", chromiumExecutablePath);
            }

            browser = playwright.chromium().launch(launchOptions);
            log.info("Playwright 瀏覽器已啟動（headless={}）", headless);
        }

        return browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 720)
                .setLocale("zh-TW"));
    }

    /**
     * 導航到指定 URL。
     * 如果傳入的是相對路徑（以 "/" 開頭但不含 "://"），自動加上 stagingUrl 作為 base。
     */
    public void navigate(Page page, String url) {
        String fullUrl = url;
        if (url != null && url.startsWith("/") && !url.contains("://")) {
            fullUrl = stagingUrl.replaceAll("/+$", "") + url;
            log.debug("相對路徑轉完整 URL：{} → {}", url, fullUrl);
        }
        log.debug("導航至：{}", fullUrl);
        page.navigate(fullUrl, new Page.NavigateOptions().setTimeout(15000));
        page.waitForLoadState(LoadState.NETWORKIDLE);
        // Vaadin 頁面在 NETWORKIDLE 後可能還有 server-push 更新（API 回應、refreshView 等）
        page.waitForTimeout(2000);
    }

    /**
     * 透過 CSS 選擇器點擊元素。
     * 如果一般 click 被 overlay 攔截（Vaadin loading indicator、dialog 覆蓋層），
     * 會先嘗試關閉 overlay，再用 dispatchEvent 繞過。
     */
    public void click(Page page, String selector) {
        log.debug("點擊：{}", selector);
        Locator locator = page.locator(selector).first();

        try {
            locator.click(new Locator.ClickOptions().setTimeout(10000));
            // Vaadin 點擊後需要等待 server-side handler 執行完畢 + UI push 回來
            page.waitForTimeout(500);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";

            if (errorMsg.contains("intercepts pointer events")) {
                log.warn("元素被覆蓋層攔截，嘗試關閉 Vaadin overlay 後用 dispatchEvent 點擊：{}", selector);

                // 嘗試關閉 Vaadin 的 loading indicator 和 overlay
                page.evaluate("""
                    () => {
                        document.querySelectorAll('vaadin-connection-indicator, [loading]')
                            .forEach(el => el.removeAttribute('loading'));
                        document.querySelectorAll('.vaadin-overlay-content, vaadin-dialog-overlay')
                            .forEach(el => { if (el.parentNode) el.parentNode.removeChild(el); });
                    }
                """);
                page.waitForTimeout(300);
                locator.dispatchEvent("click");

            } else if (errorMsg.contains("Timeout")) {
                // Timeout：用 JS 等待按鈕出現且 enabled，然後點擊
                log.warn("Playwright locator timeout，嘗試 JS fallback 點擊：{}", selector);

                // 步驟 1：從 selector 提取目標文字（例如 :has-text('編輯') → '編輯'）
                String targetText = null;
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile(":has-text\\(['\"]([^'\"]+)['\"]\\)")
                        .matcher(selector);
                if (m.find()) {
                    targetText = m.group(1);
                }

                if (targetText != null) {
                    final String searchText = targetText;

                    try {
                        // 步驟 2：等待包含目標文字且 enabled 的按鈕出現（最多 20 秒）
                        // Vaadin 的 disabled 可能是 property 而非 attribute，所以兩種都檢查
                        page.waitForFunction("""
                            (text) => {
                                function getBtnText(btn) {
                                    // 方法 1：直接 childNodes 的 TEXT_NODE
                                    let t = '';
                                    for (const node of btn.childNodes) {
                                        if (node.nodeType === 3) t += node.textContent;
                                    }
                                    if (t.trim()) return t.trim();

                                    // 方法 2：Vaadin button shadow DOM 的 label part
                                    if (btn.shadowRoot) {
                                        const labelPart = btn.shadowRoot.querySelector('[part="label"]');
                                        if (labelPart) {
                                            const slotEl = labelPart.querySelector('slot:not([name])');
                                            if (slotEl) {
                                                const assigned = slotEl.assignedNodes();
                                                for (const node of assigned) {
                                                    if (node.nodeType === 3) t += node.textContent;
                                                }
                                                if (t.trim()) return t.trim();
                                            }
                                            t = labelPart.textContent || '';
                                            if (t.trim()) return t.trim();
                                        }
                                    }

                                    // 方法 3：innerText（排除 icon 文字）
                                    t = btn.innerText || btn.textContent || '';
                                    return t.trim();
                                }
                                function isEnabled(btn) {
                                    // Vaadin 同時用 property 和 attribute 設定 disabled
                                    if (btn.disabled === true) return false;
                                    if (btn.hasAttribute('disabled')) return false;
                                    // Vaadin 還可能用 aria-disabled
                                    if (btn.getAttribute('aria-disabled') === 'true') return false;
                                    return true;
                                }

                                // 搜尋整個 DOM 中的按鈕
                                const buttons = document.querySelectorAll('vaadin-button, button, [role="button"]');
                                for (const btn of buttons) {
                                    if (getBtnText(btn).includes(text) && isEnabled(btn)) return true;
                                }
                                // 也搜尋 overlay/dialog 中的按鈕（含 Shadow DOM）
                                const overlays = document.querySelectorAll(
                                    'vaadin-confirm-dialog-overlay, vaadin-dialog-overlay, vaadin-confirm-dialog');
                                for (const overlay of overlays) {
                                    const roots = [overlay];
                                    if (overlay.shadowRoot) roots.push(overlay.shadowRoot);
                                    overlay.querySelectorAll('*').forEach(el => {
                                        if (el.shadowRoot) roots.push(el.shadowRoot);
                                    });
                                    for (const root of roots) {
                                        for (const btn of root.querySelectorAll('vaadin-button, button')) {
                                            if (getBtnText(btn).includes(text) && isEnabled(btn)) return true;
                                        }
                                    }
                                }
                                return false;
                            }
                        """, searchText, new Page.WaitForFunctionOptions().setTimeout(20000));

                        // 步驟 3：找到 enabled 的按鈕並點擊
                        Boolean clicked = (Boolean) page.evaluate("""
                            (text) => {
                                function getBtnText(btn) {
                                    let t = '';
                                    for (const node of btn.childNodes) {
                                        if (node.nodeType === 3) t += node.textContent;
                                    }
                                    if (t.trim()) return t.trim();
                                    if (btn.shadowRoot) {
                                        const labelPart = btn.shadowRoot.querySelector('[part="label"]');
                                        if (labelPart) {
                                            const slotEl = labelPart.querySelector('slot:not([name])');
                                            if (slotEl) {
                                                const assigned = slotEl.assignedNodes();
                                                let st = '';
                                                for (const node of assigned) {
                                                    if (node.nodeType === 3) st += node.textContent;
                                                }
                                                if (st.trim()) return st.trim();
                                            }
                                            t = labelPart.textContent || '';
                                            if (t.trim()) return t.trim();
                                        }
                                    }
                                    t = btn.innerText || btn.textContent || '';
                                    return t.trim();
                                }
                                function isEnabled(btn) {
                                    if (btn.disabled === true) return false;
                                    if (btn.hasAttribute('disabled')) return false;
                                    if (btn.getAttribute('aria-disabled') === 'true') return false;
                                    return true;
                                }

                                // 搜尋整個 DOM
                                const allButtons = document.querySelectorAll('vaadin-button, button, [role="button"]');
                                for (const btn of allButtons) {
                                    if (getBtnText(btn).includes(text) && isEnabled(btn)) {
                                        btn.click();
                                        return true;
                                    }
                                }
                                // overlay 中搜尋
                                const overlays = document.querySelectorAll(
                                    'vaadin-confirm-dialog-overlay, vaadin-dialog-overlay, vaadin-confirm-dialog');
                                for (const overlay of overlays) {
                                    const roots = [overlay];
                                    if (overlay.shadowRoot) roots.push(overlay.shadowRoot);
                                    overlay.querySelectorAll('*').forEach(el => {
                                        if (el.shadowRoot) roots.push(el.shadowRoot);
                                    });
                                    for (const root of roots) {
                                        for (const btn of root.querySelectorAll('vaadin-button, button')) {
                                            if (getBtnText(btn).includes(text) && isEnabled(btn)) {
                                                btn.click();
                                                return true;
                                            }
                                        }
                                    }
                                }
                                return false;
                            }
                        """, searchText);

                        if (Boolean.TRUE.equals(clicked)) {
                            log.info("透過 JS fallback 成功點擊：{}", selector);
                            // 等待 Vaadin server-side handler 執行 + UI push
                            page.waitForTimeout(500);
                        } else {
                            throw new RuntimeException("JS fallback 找到按鈕但點擊失敗：" + selector);
                        }

                    } catch (Exception waitError) {
                        if (waitError.getMessage() != null && waitError.getMessage().contains("Timeout")) {
                            throw new RuntimeException(
                                    String.format("按鈕 '%s' 未在 30 秒內出現或啟用（Playwright 10s + JS 20s）：請檢查 debug log 中的按鈕列表", searchText));
                        }
                        throw waitError;
                    }
                } else {
                    throw new RuntimeException("無法點擊元素（Playwright timeout + 無法提取按鈕文字）：" + selector);
                }

            } else {
                throw e;
            }
        }
    }

    /**
     * 在輸入欄位中填入文字。
     * 支援 Vaadin Shadow DOM 元件（vaadin-text-field、vaadin-integer-field 等），
     * 若一般 fill() 失敗，會 fallback 用 JavaScript 深入 Shadow DOM 設值。
     */
    public void type(Page page, String selector, String text) {
        log.debug("在 {} 輸入 '{}'", selector, text);

        try {
            // 方法 1：嘗試一般 Playwright fill()（5 秒 timeout）
            Locator locator = page.locator(selector).first();
            locator.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            locator.fill(text, new Locator.FillOptions().setTimeout(3000));
        } catch (Exception fillError) {
            log.warn("一般 fill() 失敗（{}），嘗試 Vaadin Shadow DOM fallback", fillError.getMessage());

            try {
                // 方法 2：用 JavaScript 深入 Shadow DOM 找到實際 input 並設值
                Boolean success = (Boolean) page.evaluate("""
                    (args) => {
                        const rawSelector = args[0];
                        const value = args[1];

                        // 清理 Playwright 特有語法，轉為合法 CSS selector
                        function cleanSelector(sel) {
                            // 移除 :has-text('...') — Playwright 語法，非標準 CSS
                            sel = sel.replace(/:has-text\\(['"][^'"]*['"]\\)/g, '');
                            // 移除 :text('...') — Playwright 語法
                            sel = sel.replace(/:text\\(['"][^'"]*['"]\\)/g, '');
                            // 移除 >> 深層組合器 — Playwright 語法
                            sel = sel.replace(/\\s*>>\\s*/g, ' ');
                            return sel.trim();
                        }

                        // 嘗試多種方式找到 Vaadin 欄位
                        let field = null;

                        // 2-1: 逗號分隔的多個 selector，逐一清理並嘗試
                        const selectors = rawSelector.split(',').map(s => cleanSelector(s)).filter(s => s.length > 0);
                        for (const sel of selectors) {
                            try {
                                field = document.querySelector(sel);
                                if (field) break;
                            } catch(e) { /* 跳過無效 selector */ }
                        }

                        // 2-2: 從 selector 提取 tag name 關鍵字來搜尋
                        if (!field) {
                            const tagHints = ['vaadin-integer-field', 'vaadin-number-field', 'vaadin-text-field'];
                            for (const tag of tagHints) {
                                if (rawSelector.includes(tag)) {
                                    // 先找非 readonly 的
                                    field = document.querySelector(tag + ':not([readonly])');
                                    if (!field) field = document.querySelector(tag);
                                    if (field) break;
                                }
                            }
                        }

                        // 2-3: 在 vaadin-grid 內的 cell 裡找可編輯欄位
                        if (!field) {
                            const editableFields = document.querySelectorAll(
                                'vaadin-grid vaadin-text-field:not([readonly]), ' +
                                'vaadin-grid vaadin-integer-field:not([readonly]), ' +
                                'vaadin-grid vaadin-number-field:not([readonly]), ' +
                                'vaadin-grid input[type="text"]:not([readonly]), ' +
                                'vaadin-grid input[type="number"]:not([readonly])'
                            );
                            if (editableFields.length > 0) {
                                field = editableFields[0];
                            }
                        }

                        // 2-4: 用 data-row/data-col 屬性找
                        if (!field) {
                            field = document.querySelector('[data-row][data-col]:not([readonly])');
                        }

                        if (!field) return false;

                        // 設定值：根據 Vaadin 元件類型處理
                        const tagName = field.tagName.toLowerCase();

                        if (tagName === 'vaadin-text-field' || tagName === 'vaadin-integer-field' ||
                            tagName === 'vaadin-number-field' || tagName === 'vaadin-password-field') {
                            // Vaadin Web Component：設定 value 屬性並觸發事件
                            field.value = value;

                            // 取得 Shadow DOM 內的實際 input
                            const innerInput = field.inputElement || field.shadowRoot?.querySelector('input');
                            if (innerInput) {
                                innerInput.value = value;
                                innerInput.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                                innerInput.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                            }

                            // 觸發 Vaadin 的 value-changed 事件
                            field.dispatchEvent(new CustomEvent('value-changed', {
                                detail: { value: value },
                                bubbles: true,
                                composed: true
                            }));
                            field.dispatchEvent(new Event('change', { bubbles: true, composed: true }));

                            return true;

                        } else if (tagName === 'input' || tagName === 'textarea') {
                            // 原生 input：設值並觸發事件
                            field.value = value;
                            field.dispatchEvent(new Event('input', { bubbles: true }));
                            field.dispatchEvent(new Event('change', { bubbles: true }));
                            return true;

                        } else {
                            // 其他：嘗試找內部 input
                            const input = field.querySelector('input') ||
                                          (field.shadowRoot && field.shadowRoot.querySelector('input'));
                            if (input) {
                                input.value = value;
                                input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                                input.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                                if (field.value !== undefined) field.value = value;
                                field.dispatchEvent(new CustomEvent('value-changed', {
                                    detail: { value: value },
                                    bubbles: true, composed: true
                                }));
                                return true;
                            }
                        }

                        return false;
                    }
                """, new Object[]{ selector, text });

                if (Boolean.TRUE.equals(success)) {
                    log.info("透過 Shadow DOM JavaScript 成功在 {} 輸入 '{}'", selector, text);
                    page.waitForTimeout(300); // 等待 Vaadin 處理 value change
                } else {
                    // 方法 3：最後手段 — 直接找 Grid 內所有可編輯 input 並 focus + keyboard 輸入
                    log.warn("Shadow DOM 設值也失敗，嘗試 keyboard 輸入方式");
                    page.evaluate("""
                        () => {
                            // 找到第一個非 readonly 的可編輯欄位並 focus
                            const fields = document.querySelectorAll(
                                'vaadin-grid vaadin-text-field:not([readonly]), ' +
                                'vaadin-grid vaadin-integer-field:not([readonly]), ' +
                                'vaadin-grid [data-row][data-col]:not([readonly])'
                            );
                            if (fields.length > 0) {
                                const f = fields[0];
                                f.focus();
                                const inner = f.inputElement || f.shadowRoot?.querySelector('input');
                                if (inner) {
                                    inner.focus();
                                    inner.select();
                                }
                            }
                        }
                    """);
                    page.waitForTimeout(200);
                    // 用鍵盤模擬輸入：先全選清除，再打字
                    page.keyboard().press("Control+a");
                    page.keyboard().type(text);
                    page.waitForTimeout(200);
                    log.info("透過 keyboard 模擬在 Grid 欄位輸入 '{}'", text);
                }

            } catch (Exception jsError) {
                log.error("所有輸入方式皆失敗：selector={}, error={}", selector, jsError.getMessage());
                throw new RuntimeException(
                        String.format("無法在 '%s' 輸入文字：一般 fill 和 Shadow DOM fallback 都失敗", selector),
                        jsError);
            }
        }
    }

    /**
     * 從下拉選單中選擇選項。
     */
    public void select(Page page, String selector, String value) {
        log.debug("在 {} 選擇 '{}'", selector, value);
        page.locator(selector).first().selectOption(value);
    }

    /**
     * 截圖並回傳 byte[] 二進位資料。
     * 不寫入磁碟，由呼叫端決定要上傳到 Azure DevOps 或其他用途。
     */
    public byte[] takeScreenshot(Page page) {
        byte[] data = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
        log.debug("已擷取截圖（{} bytes）", data.length);
        return data;
    }

    /**
     * 截圖並回傳 Base64 字串（用於傳送給 Claude Vision API）。
     */
    public String takeScreenshotAsBase64(Page page) {
        byte[] bytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 取得頁面的無障礙樹狀結構（文字內容 + 互動元素 + Vaadin 元件）。
     * AI 透過這些資訊理解頁面結構並決定操作。
     * 特別包含 Vaadin Web Components 的識別（vaadin-button、vaadin-grid、vaadin-text-field 等）。
     */
    public String getAccessibilityTree(Page page) {
        // 取得頁面文字內容
        String tree = page.locator("body").evaluate(
                "el => el.innerText"
        ).toString();

        // 取得所有互動元素（包含 Vaadin Web Components）
        String interactiveElements = page.evaluate("""
            () => {
                const elements = [];

                // 標準 HTML 互動元素
                const standardSelectors = 'a, button, input, select, textarea, [role="button"], [onclick]';

                // Vaadin Web Components
                const vaadinSelectors = [
                    'vaadin-button', 'vaadin-text-field', 'vaadin-integer-field',
                    'vaadin-number-field', 'vaadin-password-field', 'vaadin-combo-box',
                    'vaadin-date-picker', 'vaadin-select', 'vaadin-checkbox',
                    'vaadin-radio-button', 'vaadin-grid', 'vaadin-tabs', 'vaadin-tab',
                    'vaadin-notification', 'vaadin-dialog'
                ].join(', ');

                const allSelectors = standardSelectors + ', ' + vaadinSelectors;

                document.querySelectorAll(allSelectors).forEach((el, i) => {
                    const tag = el.tagName.toLowerCase();
                    const info = {
                        tag: tag,
                        type: el.type || '',
                        id: el.id || '',
                        name: el.name || '',
                        text: (el.textContent || '').trim().substring(0, 80),
                        placeholder: el.placeholder || '',
                        href: el.href || '',
                        disabled: el.disabled || el.hasAttribute('disabled') || false,
                        readonly: el.readOnly || el.hasAttribute('readonly') || false,
                        visible: el.offsetParent !== null
                    };

                    // Vaadin 特有屬性
                    if (tag.startsWith('vaadin-')) {
                        info.label = el.label || '';
                        info.value = (el.value || '').toString().substring(0, 50);
                        info.theme = el.getAttribute('theme') || '';

                        // vaadin-grid 額外資訊
                        if (tag === 'vaadin-grid') {
                            info.rowCount = el.items ? el.items.length : (el._cache ? el._cache.size : 0);
                        }

                        // data-row/data-col 屬性（Grid 內嵌編輯欄位）
                        if (el.hasAttribute('data-row')) {
                            info.dataRow = el.getAttribute('data-row');
                            info.dataCol = el.getAttribute('data-col');
                        }
                    }

                    elements.push(info);
                });

                return JSON.stringify(elements, null, 2);
            }
        """).toString();

        // 額外取得 Vaadin Grid 內的可編輯欄位資訊
        String gridEditableFields = page.evaluate("""
            () => {
                const fields = [];
                // 尋找 Grid 內所有帶 data-row/data-col 的可編輯欄位
                document.querySelectorAll('[data-row][data-col]').forEach(el => {
                    fields.push({
                        tag: el.tagName.toLowerCase(),
                        dataRow: el.getAttribute('data-row'),
                        dataCol: el.getAttribute('data-col'),
                        value: (el.value || '').toString().substring(0, 30),
                        readonly: el.readOnly || el.hasAttribute('readonly') || false,
                        label: el.label || ''
                    });
                });
                return fields.length > 0 ? JSON.stringify(fields, null, 2) : '';
            }
        """).toString();

        StringBuilder result = new StringBuilder();
        result.append(String.format("""
                == 頁面文字內容 ==
                %s

                == 互動元素（含 Vaadin Web Components） ==
                %s
                """,
                truncate(tree, 3000),
                truncate(interactiveElements, 4000)));

        if (gridEditableFields != null && !gridEditableFields.isBlank()) {
            result.append(String.format("""

                == Grid 可編輯欄位 ==
                %s
                """, truncate(gridEditableFields, 1000)));
        }

        return result.toString();
    }

    /**
     * 取得頁面的 console 錯誤訊息。
     */
    public String getConsoleErrors(Page page) {
        StringBuilder errors = new StringBuilder();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                errors.append(msg.text()).append("\n");
            }
        });
        // 等待一小段時間以收集待處理的 console 訊息
        page.waitForTimeout(500);
        return errors.toString();
    }

    /**
     * 取得目前頁面的 URL。
     */
    public String getCurrentUrl(Page page) {
        return page.url();
    }

    /**
     * 取得頁面標題。
     */
    public String getPageTitle(Page page) {
        return page.title();
    }

    /**
     * 檢查頁面上是否存在指定元素。
     */
    public boolean elementExists(Page page, String selector) {
        return page.locator(selector).count() > 0;
    }

    /**
     * 等待指定元素出現。
     */
    public boolean waitForElement(Page page, String selector, int timeoutMs) {
        try {
            page.locator(selector).first().waitFor(
                    new Locator.WaitForOptions().setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 執行單一測試步驟。
     * 根據步驟的 Action 類型執行對應的瀏覽器操作，並在每步後截圖。
     * 截圖以 byte[] 存在 TestStep 中，不寫入磁碟。
     */
    public TestStep executeStep(Page page, TestStep step, String testRunId) {
        long start = System.currentTimeMillis();
        step.setStatus(TestStep.StepStatus.RUNNING);

        try {
            switch (step.getAction()) {
                case NAVIGATE -> navigate(page, step.getTarget());
                case CLICK -> click(page, step.getTarget());
                case TYPE -> type(page, step.getTarget(), step.getValue());
                case SELECT -> select(page, step.getTarget(), step.getValue());
                case WAIT -> {
                    boolean found = waitForElement(page, step.getTarget(), 10000);
                    if (found) {
                        // WAIT 成功後，額外等待 Vaadin server-push 處理完畢
                        // 特別是「查詢」後的 refreshView() 包含 API 呼叫
                        page.waitForTimeout(2000);
                    }
                }
                case ASSERT -> {
                    // 等待元素出現並可見（最多 5 秒），而非瞬間檢查
                    // 適用於等待通知、對話框、動態載入的內容
                    try {
                        page.locator(step.getTarget()).first()
                                .waitFor(new Locator.WaitForOptions()
                                        .setState(WaitForSelectorState.VISIBLE)
                                        .setTimeout(5000));
                    } catch (Exception e) {
                        throw new RuntimeException("ASSERT 失敗 — 元素未在 5 秒內出現：" + step.getTarget());
                    }
                }
                case SCREENSHOT -> { /* 截圖在下方統一處理 */ }
            }

            step.setStatus(TestStep.StepStatus.PASSED);
        } catch (Exception e) {
            step.setStatus(TestStep.StepStatus.FAILED);
            step.setErrorMessage(e.getMessage());
            log.warn("步驟 {} 失敗：{}", step.getStepNumber(), e.getMessage());
        }

        // 每個步驟執行後都截圖（存為 byte[]）
        try {
            byte[] screenshot = takeScreenshot(page);
            step.setScreenshotData(screenshot);
        } catch (Exception e) {
            log.warn("步驟 {} 截圖失敗：{}", step.getStepNumber(), e.getMessage());
        }

        step.setDurationMs(System.currentTimeMillis() - start);

        return step;
    }

    /**
     * 清理 Playwright 資源（應用程式關閉時自動呼叫）。
     */
    @PreDestroy
    public void cleanup() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        log.info("Playwright 資源已清理");
    }

    /**
     * 截斷文字至指定長度。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
