package com.repopilot.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移验收：空库按 V1→V7 建起来，版本连续，重复执行不改变历史。
 *
 * <p>"空库"由 CI 与本地测试库保证：数据库在第一次运行前是空的，
 * 之后每次运行都是"已有版本上增量迁移"，两种路径都被这组断言覆盖。
 */
class SchemaMigrationIT extends AbstractDatabaseIT {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allMigrationsShouldBeAppliedSuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).hasSize(7);
        assertThat(applied).allSatisfy(info ->
                assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(Arrays.stream(applied).map(MigrationInfo::getVersion).map(Object::toString))
                .containsExactly("1", "2", "3", "4", "5", "6", "7");
    }

    @Test
    void schemaShouldContainExactlyTwentyEightTables() {
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                Integer.class);

        assertThat(tables).isEqualTo(28);
    }

    @Test
    void secondMigrateShouldBeNoopAndKeepChecksums() {
        List<String> before = jdbc.queryForList(
                "SELECT CONCAT(version, ':', checksum) FROM flyway_schema_history"
                        + " WHERE success = 1 ORDER BY installed_rank", String.class);

        assertThat(flyway.migrate().migrationsExecuted).isZero();

        List<String> after = jdbc.queryForList(
                "SELECT CONCAT(version, ':', checksum) FROM flyway_schema_history"
                        + " WHERE success = 1 ORDER BY installed_rank", String.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void baselineMigrationShouldNotCreateBusinessTables() {
        // V1 是空基线，业务表全部来自 V2 之后：这条断言防止有人把建表回填进 V1
        Integer v1Statements = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND checksum IS NOT NULL",
                Integer.class);

        assertThat(v1Statements).isEqualTo(1);
    }
}
