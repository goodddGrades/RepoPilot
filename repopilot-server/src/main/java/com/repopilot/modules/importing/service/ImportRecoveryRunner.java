package com.repopilot.modules.importing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 崩溃恢复：启动后把停在 PENDING/RUNNING 的导入批次重新排队。
 *
 * <p>为什么需要它：逐行事务保证"已提交的行不会丢"，但**没有进程会在崩溃后自己继续**
 * ——如果不扫描，批次会永远停在 RUNNING，实验也就永远不能启动（启动要求批次全部终态）。
 *
 * <p>可以关掉（`repopilot.import.resume-on-startup=false`）：集成测试需要自己控制
 * 处理时机，后台线程在用例之间偷偷推进批次会让断言不可复现。
 */
@Component
@ConditionalOnProperty(name = "repopilot.import.resume-on-startup", matchIfMissing = true)
public class ImportRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRecoveryRunner.class);

    private static final int LIMIT = 100;

    private final ImportService importService;

    public ImportRecoveryRunner(ImportService importService) {
        this.importService = importService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeUnfinished() {
        try {
            importService.resumeUnfinished(LIMIT);
            log.info("已重新排队未完成的导入批次（最多 {} 个）", LIMIT);
        } catch (RuntimeException ex) {
            // 恢复失败不应阻止应用启动：批次仍留在 RUNNING，下一次重启或人工触发还能继续
            log.error("恢复未完成导入批次失败", ex);
        }
    }
}
