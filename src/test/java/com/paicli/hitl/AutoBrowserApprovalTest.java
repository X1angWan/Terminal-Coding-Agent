package com.paicli.hitl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.browser.BrowserGuard;
import com.paicli.browser.BrowserSession;
import com.paicli.browser.SensitivePagePolicy;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.policy.AuditLog;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.tool.TurnToolPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoBrowserApprovalTest {
    private static final String URL = "https://mp.weixin.qq.com/s/test-article";
    @TempDir Path root;
    private String previousAuditDir;

    @BeforeEach
    void useTemporaryAuditDirectory() {
        previousAuditDir = System.getProperty("paicli.audit.dir");
        System.setProperty("paicli.audit.dir", root.resolve("audit").toString());
    }

    @AfterEach
    void restoreAuditDirectory() {
        if (previousAuditDir == null) System.clearProperty("paicli.audit.dir");
        else System.setProperty("paicli.audit.dir", previousAuditDir);
    }

    @Test
    void autoModeOpensAndReadsGroundedPageWithoutApprovalOrClassifier() {
        Fixture f = fixture(false);
        register(f, "chrome-devtools", "new_page");
        register(f, "chrome-devtools", "take_snapshot");
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("帮我用 Chrome 打开 " + URL);

        var opened = policy.execute(f.registry, List.of(new ToolInvocation(
                "open", "mcp__chrome-devtools__new_page", "{\"url\":\"" + URL + "\"}")),
                policy.expose(f.registry.getToolDefinitions()));
        var read = policy.execute(f.registry, List.of(new ToolInvocation(
                "read", "mcp__chrome-devtools__take_snapshot", "{}")),
                policy.expose(f.registry.getToolDefinitions()));

        assertTrue(opened.get(0).successful(), opened.get(0).result());
        assertTrue(read.get(0).successful(), read.get(0).result());
        assertEquals(List.of("new_page", "take_snapshot"), f.executed);
        assertTrue(f.session.hasAgentOwnedCurrentPage());
        assertEquals(0, f.handler.requests);
        var decisions = f.registry.getAuditLog().readRecent(10).stream()
                .filter(e -> AuditLog.APPROVER_AUTO_BROWSER.equals(e.approver())).toList();
        assertEquals(2, decisions.size());
        assertNotNull(decisions.get(0).metadata());
    }

    @Test
    void autoModeStillRejectsUngroundedUrlBeforeMcpExecution() {
        Fixture f = fixture(false);
        register(f, "chrome-devtools", "new_page");
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开 " + URL);
        var results = policy.execute(f.registry, List.of(new ToolInvocation(
                "open", "mcp__chrome-devtools__new_page", "{\"url\":\"https://example.com/guessed\"}")),
                policy.expose(f.registry.getToolDefinitions()));

        assertFalse(results.get(0).successful());
        assertTrue(results.get(0).result().contains("UNGROUNDED_URL"), results.get(0).result());
        assertTrue(f.executed.isEmpty());
        assertEquals(0, f.handler.requests);
    }

    @Test
    void autoModeCannotNavigateUserOwnedSharedTab() {
        Fixture f = fixture(false);
        f.session.switchToShared("autoConnect");
        register(f, "chrome-devtools", "navigate_page");
        String result = f.registry.executeTool("mcp__chrome-devtools__navigate_page",
                "{\"url\":\"" + URL + "\"}");

        assertTrue(result.contains("不是 PaiCLI 创建"), result);
        assertTrue(f.executed.isEmpty());
        assertEquals(0, f.handler.requests);
    }

    @ParameterizedTest
    @CsvSource({
            "chrome-devtools, click, {}",
            "chrome-devtools, evaluate_script, {}",
            "chrome-devtools, unknown_read, {}",
            "other-browser, new_page, {}",
            "chrome-devtools, take_snapshot, '{\"filePath\":\"/tmp/snapshot.txt\"}'",
            "chrome-devtools, take_screenshot, '{\"filePath\":\"/tmp/screenshot.png\"}'"
    })
    void otherMcpOperationsStillRequireApproval(String server, String tool, String args) {
        Fixture f = fixture(false);
        register(f, server, tool);
        String result = f.registry.executeTool(McpToolDescriptor.namespaced(server, tool), args);

        assertTrue(result.startsWith("[AUTO]"), result);
        assertTrue(f.executed.isEmpty());
        assertEquals(0, f.handler.requests);
    }

    @Test
    void askModeStillConfirmsBrowserNavigation() {
        Fixture f = fixture(true);
        register(f, "chrome-devtools", "new_page");
        String result = f.registry.executeTool("mcp__chrome-devtools__new_page",
                "{\"url\":\"" + URL + "\"}");

        assertTrue(result.startsWith("[HITL]"), result);
        assertEquals(1, f.handler.requests);
        assertTrue(f.executed.isEmpty());
    }

    @Test
    void browserApprovalResetsAutoDenialStreak() {
        Fixture f = fixture(false);
        register(f, "chrome-devtools", "new_page");
        f.registry.executeTool("mcp__other__write", "{}");
        f.registry.executeTool("mcp__other__write", "{}");
        f.registry.executeTool("mcp__chrome-devtools__new_page", "{\"url\":\"" + URL + "\"}");
        f.registry.executeTool("mcp__other__write", "{}");
        f.registry.executeTool("mcp__other__write", "{}");

        assertEquals(0, f.handler.requests);
        assertEquals(List.of("new_page"), f.executed);
    }

    private Fixture fixture(boolean ask) {
        RecordingHandler handler = new RecordingHandler();
        SwitchableHitlHandler switchable = new SwitchableHitlHandler(handler);
        switchable.setHighRiskConfirmationEnabled(true);
        if (ask) switchable.switchConfirmationMode("on");
        HitlToolRegistry registry = new HitlToolRegistry(switchable);
        registry.setProjectPath(root.toString());
        registry.setAutoApprovalReviewer((tool, args) -> {
            throw new AssertionError("浏览器工具不应调用 Shell 分类器");
        });
        BrowserSession session = new BrowserSession();
        registry.setBrowserGuard(new BrowserGuard(session, new SensitivePagePolicy(root.resolve("rules"))));
        return new Fixture(registry, handler, session, new ArrayList<>());
    }

    private static void register(Fixture f, String server, String tool) {
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        f.registry.registerMcpTool(new McpToolDescriptor(server, tool,
                McpToolDescriptor.namespaced(server, tool), "test tool", schema), args -> {
            f.executed.add(tool);
            return "new_page".equals(tool) ? "# Pages\n1: " + URL + " [selected]" : "article content";
        });
    }

    private record Fixture(HitlToolRegistry registry, RecordingHandler handler,
                           BrowserSession session, List<String> executed) {}

    private static class RecordingHandler implements HitlHandler {
        private boolean enabled;
        private int requests;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requests++;
            return ApprovalResult.reject("test rejection");
        }
    }
}
