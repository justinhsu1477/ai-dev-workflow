package com.team.aiworkflow.service.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
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
     */
    public void navigate(Page page, String url) {
        log.debug("導航至：{}", url);
        page.navigate(url, new Page.NavigateOptions().setTimeout(15000));
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    /**
     * 透過 CSS 選擇器點擊元素。
     */
    public void click(Page page, String selector) {
        log.debug("點擊：{}", selector);
        page.locator(selector).first().click(new Locator.ClickOptions().setTimeout(5000));
    }

    /**
     * 在輸入欄位中填入文字。
     */
    public void type(Page page, String selector, String text) {
        log.debug("在 {} 輸入 '{}'", selector, text);
        page.locator(selector).first().fill(text);
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
     * 取得頁面的無障礙樹狀結構（文字內容 + 互動元素）。
     * AI 透過這些資訊理解頁面結構並決定操作。
     */
    public String getAccessibilityTree(Page page) {
        // 取得頁面文字內容
        String tree = page.locator("body").evaluate(
                "el => el.innerText"
        ).toString();

        // 取得所有互動元素（按鈕、連結、輸入框等）
        String interactiveElements = page.evaluate("""
            () => {
                const elements = [];
                const selectors = 'a, button, input, select, textarea, [role="button"], [onclick]';
                document.querySelectorAll(selectors).forEach((el, i) => {
                    elements.push({
                        tag: el.tagName.toLowerCase(),
                        type: el.type || '',
                        id: el.id || '',
                        name: el.name || '',
                        text: (el.textContent || '').trim().substring(0, 50),
                        placeholder: el.placeholder || '',
                        href: el.href || '',
                        disabled: el.disabled || false,
                        visible: el.offsetParent !== null
                    });
                });
                return JSON.stringify(elements, null, 2);
            }
        """).toString();

        return String.format("""
                == 頁面文字內容 ==
                %s

                == 互動元素 ==
                %s
                """,
                truncate(tree, 3000),
                truncate(interactiveElements, 3000));
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
                case WAIT -> waitForElement(page, step.getTarget(), 5000);
                case ASSERT -> {
                    boolean exists = elementExists(page, step.getTarget());
                    if (!exists) {
                        throw new AssertionError("找不到元素：" + step.getTarget());
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
