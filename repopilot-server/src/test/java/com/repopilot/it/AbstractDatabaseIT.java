package com.repopilot.it;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 集成测试基类：连真实 MySQL 8，不使用 H2/SQLite 替代。
 *
 * <p>连接参数来自 {@code application-it.yml} 中的环境变量；缺失时上下文启动失败，
 * 而不是把测试标成 skipped 后宣称通过。数据库由 Flyway 在上下文启动时迁移到最新版本，
 * 因此这些测试同时验证"空库能否建起来"。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
public abstract class AbstractDatabaseIT {
}
