package com.team.aiworkflow.service.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.team.aiworkflow.model.e2e.TestStep;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

/**
 * Wraps Playwright browser operations.
 * Manages browser lifecycle and provides methods for AI Agent to interact with pages.
 *
 * In Docker, set PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH to use the system-installed Chromium.
 */
@Service
@Slf4j
public class PlaywrightService {

    @Value("${PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH:}")
    private String chromiumExecutablePath;

    private Playwright playwright;
    private Browser browser;

    /**
     * Create a new browser session for an E2E test run.
     * Returns a BrowserContext that can be used to create pages.
     */
    public BrowserContext createSession() {
        if (playwright == null) {
            log.info("Initializing Playwright...");
            playwright = Playwright.create();

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setTimeout(30000);

            // In Docker, use system-installed Chromium
            if (chromiumExecutablePath != null && !chromiumExecutablePath.isBlank()) {
                launchOptions.setExecutablePath(Paths.get(chromiumExecutablePath));
                log.info("Using system Chromium: {}", chromiumExecutablePath);
            }

            browser = playwright.chromium().launch(launchOptions);
            log.info("Playwright browser launched (headless Chromium)");
        }

        return browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 720)
                .setLocale("zh-TW"));
    }

    /**
     * Navigate to a URL.
     */
    public void navigate(Page page, String url) {
        log.debug("Navigating to: {}", url);
        page.navigate(url, new Page.NavigateOptions().setTimeout(15000));
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    /**
     * Click an element by selector.
     */
    public void click(Page page, String selector) {
        log.debug("Clicking: {}", selector);
        page.locator(selector).first().click(new Locator.ClickOptions().setTimeout(5000));
    }

    /**
     * Type text into an input element.
     */
    public void type(Page page, String selector, String text) {
        log.debug("Typing '{}' into: {}", text, selector);
        page.locator(selector).first().fill(text);
    }

    /**
     * Select an option from a dropdown.
     */
    public void select(Page page, String selector, String value) {
        log.debug("Selecting '{}' in: {}", value, selector);
        page.locator(selector).first().selectOption(value);
    }

    /**
     * Take a screenshot and return the file path.
     */
    public String takeScreenshot(Page page, String testRunId, int stepNumber) {
        String filename = String.format("e2e-%s-step%d.png", testRunId, stepNumber);
        Path screenshotPath = Paths.get(System.getProperty("java.io.tmpdir"), "e2e-screenshots", filename);
        screenshotPath.getParent().toFile().mkdirs();

        page.screenshot(new Page.ScreenshotOptions()
                .setPath(screenshotPath)
                .setFullPage(true));

        log.debug("Screenshot saved: {}", screenshotPath);
        return screenshotPath.toString();
    }

    /**
     * Take a screenshot and return as Base64 string (for sending to Claude Vision API).
     */
    public String takeScreenshotAsBase64(Page page) {
        byte[] bytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Get the page's accessibility tree as text.
     * This is what AI uses to understand the page structure.
     */
    public String getAccessibilityTree(Page page) {
        // Use Playwright's accessibility snapshot
        String tree = page.locator("body").evaluate(
                "el => el.innerText"
        ).toString();

        // Also get all interactive elements
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
                == Page Text Content ==
                %s

                == Interactive Elements ==
                %s
                """,
                truncate(tree, 3000),
                truncate(interactiveElements, 3000));
    }

    /**
     * Get console errors from the page.
     */
    public String getConsoleErrors(Page page) {
        StringBuilder errors = new StringBuilder();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                errors.append(msg.text()).append("\n");
            }
        });
        // Trigger a small wait to collect any pending console messages
        page.waitForTimeout(500);
        return errors.toString();
    }

    /**
     * Get the current page URL.
     */
    public String getCurrentUrl(Page page) {
        return page.url();
    }

    /**
     * Get the page title.
     */
    public String getPageTitle(Page page) {
        return page.title();
    }

    /**
     * Check if an element exists on the page.
     */
    public boolean elementExists(Page page, String selector) {
        return page.locator(selector).count() > 0;
    }

    /**
     * Wait for an element to appear.
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
     * Execute a single test step on the page.
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
                        throw new AssertionError("Element not found: " + step.getTarget());
                    }
                }
                case SCREENSHOT -> { /* screenshot taken below */ }
            }

            step.setStatus(TestStep.StepStatus.PASSED);
        } catch (Exception e) {
            step.setStatus(TestStep.StepStatus.FAILED);
            step.setErrorMessage(e.getMessage());
            log.warn("Step {} failed: {}", step.getStepNumber(), e.getMessage());
        }

        // Always take a screenshot after each step
        String screenshotPath = takeScreenshot(page, testRunId, step.getStepNumber());
        step.setScreenshotPath(screenshotPath);
        step.setDurationMs(System.currentTimeMillis() - start);

        return step;
    }

    @PreDestroy
    public void cleanup() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        log.info("Playwright resources cleaned up");
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
