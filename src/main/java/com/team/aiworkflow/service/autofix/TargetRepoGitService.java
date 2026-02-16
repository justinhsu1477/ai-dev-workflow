package com.team.aiworkflow.service.autofix;

import com.team.aiworkflow.config.AutoFixConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 在目標 app 的本機 repo 上執行 git 操作。
 * 用 ProcessBuilder 執行 git 指令，不依賴 JGit。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TargetRepoGitService {

    private final AutoFixConfig config;

    /**
     * 從 base branch 建立 fix 分支。
     * git checkout {baseBranch} → git pull → git checkout -b ai-fix/{workItemId}
     *
     * @return 建立的分支名稱
     */
    public String createFixBranch(int workItemId) {
        String branchName = config.getBranchPrefix() + workItemId;
        log.info("建立 fix 分支：{}", branchName);

        executeGit("checkout", config.getBaseBranch());
        executeGit("pull");
        executeGit("checkout", "-b", branchName);

        log.info("已建立並切換到分支：{}", branchName);
        return branchName;
    }

    /**
     * 將修改 commit 並 push 到遠端。
     */
    public void commitAndPush(String branchName, String commitMessage) {
        log.info("Commit 並 push 到分支：{}", branchName);

        executeGit("add", "-A");
        executeGit("commit", "-m", commitMessage);
        executeGit("push", "-u", "origin", branchName);

        log.info("已 push 到遠端分支：{}", branchName);
    }

    /**
     * 放棄 fix 分支，切回 base branch。
     * 不刪除遠端分支，保留供檢查。
     */
    public void abandonBranch(String fixBranchName) {
        log.info("放棄分支 {}，切回 {}", fixBranchName, config.getBaseBranch());
        try {
            executeGit("checkout", config.getBaseBranch());
        } catch (Exception e) {
            log.warn("切回 base branch 失敗，嘗試 reset：{}", e.getMessage());
            try {
                executeGit("checkout", "-f", config.getBaseBranch());
            } catch (Exception e2) {
                log.error("無法切回 base branch：{}", e2.getMessage());
            }
        }
    }

    /**
     * 取得目標 repo 當前的分支名稱。
     */
    public String getCurrentBranch() {
        return executeGit("rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    /**
     * 在目標 repo 目錄中執行 git 指令。
     */
    private String executeGit(String... args) {
        String repoPath = config.getTargetRepoPath();
        if (repoPath == null || repoPath.isBlank()) {
            throw new IllegalStateException("target-repo-path 未設定");
        }

        // 安全檢查：禁止危險操作
        for (String arg : args) {
            if ("--force".equals(arg) || "-f".equals(arg) && "push".equals(args[0])) {
                throw new IllegalArgumentException("禁止 force push 操作");
            }
            if ("--hard".equals(arg)) {
                throw new IllegalArgumentException("禁止 reset --hard 操作");
            }
        }

        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        log.debug("執行 git 指令：git {}", String.join(" ", args));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("git 指令逾時（30s）：git " + String.join(" ", args));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException(String.format(
                        "git 指令失敗（exit code %d）：git %s\n%s",
                        exitCode, String.join(" ", args), output));
            }

            log.debug("git 指令完成：{}", output.length() > 200 ? output.substring(0, 200) + "..." : output);
            return output;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("執行 git 指令失敗：" + e.getMessage(), e);
        }
    }
}
