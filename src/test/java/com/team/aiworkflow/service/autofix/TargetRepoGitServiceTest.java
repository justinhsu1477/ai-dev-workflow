package com.team.aiworkflow.service.autofix;

import com.team.aiworkflow.config.AutoFixConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TargetRepoGitService Git 操作測試")
class TargetRepoGitServiceTest {

    private TargetRepoGitService gitService;
    private AutoFixConfig config;

    @TempDir
    Path tempDir;

    /** 原始 bare repo，用來當 remote */
    @TempDir
    Path bareRepo;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        config = new AutoFixConfig();
        config.setBaseBranch("main");
        config.setBranchPrefix("ai-fix/");

        // 1. 建立 bare remote repo
        runGit(bareRepo, "init", "--bare");

        // 2. 建立本地工作 repo (clone from bare)
        //    因為 @TempDir 已建好 tempDir，我們手動 init + 設 remote
        runGit(tempDir, "init");
        runGit(tempDir, "config", "user.email", "test@test.com");
        runGit(tempDir, "config", "user.name", "Test");
        runGit(tempDir, "remote", "add", "origin", bareRepo.toString());

        // 3. 建立初始 commit 並 push
        Files.writeString(tempDir.resolve("README.md"), "# Test Repo");
        runGit(tempDir, "add", ".");
        runGit(tempDir, "commit", "-m", "initial");
        runGit(tempDir, "branch", "-M", "main");
        runGit(tempDir, "push", "-u", "origin", "main");

        config.setTargetRepoPath(tempDir.toString());
        gitService = new TargetRepoGitService(config);
    }

    private void runGit(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes(); // 消耗輸出
        p.waitFor();
    }

    private String runGitOutput(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return output;
    }

    @Nested
    @DisplayName("createFixBranch — 建立修復分支")
    class CreateFixBranchTests {

        @Test
        @DisplayName("應建立 ai-fix/{workItemId} 分支並切換到該分支")
        void shouldCreateAndSwitchToBranch() {
            String branchName = gitService.createFixBranch(123);

            assertThat(branchName).isEqualTo("ai-fix/123");
            String currentBranch = gitService.getCurrentBranch();
            assertThat(currentBranch).isEqualTo("ai-fix/123");
        }
    }

    @Nested
    @DisplayName("commitAndPush — Commit + Push")
    class CommitAndPushTests {

        @Test
        @DisplayName("有變更時應成功 commit 並 push 到 remote")
        void shouldCommitAndPushChanges() throws IOException, InterruptedException {
            gitService.createFixBranch(456);
            Files.writeString(tempDir.resolve("fix.java"), "// AI fix");

            gitService.commitAndPush("ai-fix/456", "[AI Auto-Fix] 修復 (WI #456)");

            // 驗證 commit 存在
            String log = runGitOutput(tempDir, "log", "--oneline", "-1");
            assertThat(log).contains("[AI Auto-Fix]");

            // 驗證已 push 到 remote
            String remoteBranches = runGitOutput(bareRepo, "branch");
            assertThat(remoteBranches).contains("ai-fix/456");
        }
    }

    @Nested
    @DisplayName("abandonBranch — 放棄分支")
    class AbandonBranchTests {

        @Test
        @DisplayName("應切回 main branch")
        void shouldSwitchBackToMainBranch() {
            gitService.createFixBranch(789);
            assertThat(gitService.getCurrentBranch()).isEqualTo("ai-fix/789");

            gitService.abandonBranch("ai-fix/789");

            assertThat(gitService.getCurrentBranch()).isEqualTo("main");
        }
    }

    @Nested
    @DisplayName("getCurrentBranch — 取得當前分支")
    class GetCurrentBranchTests {

        @Test
        @DisplayName("初始狀態應在 main branch")
        void shouldReturnMainBranch() {
            assertThat(gitService.getCurrentBranch()).isEqualTo("main");
        }
    }

    @Nested
    @DisplayName("安全性檢查")
    class SecurityTests {

        @Test
        @DisplayName("target-repo-path 未設定時應拋出異常")
        void shouldThrowWhenRepoPathNotSet() {
            config.setTargetRepoPath(null);
            assertThatThrownBy(() -> gitService.getCurrentBranch())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("target-repo-path 未設定");
        }

        @Test
        @DisplayName("空白 repo path 應拋出異常")
        void shouldThrowWhenRepoPathIsBlank() {
            config.setTargetRepoPath("   ");
            assertThatThrownBy(() -> gitService.getCurrentBranch())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("target-repo-path 未設定");
        }
    }
}
