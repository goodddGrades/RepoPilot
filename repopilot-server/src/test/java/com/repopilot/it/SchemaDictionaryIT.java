package com.repopilot.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把真实 DDL 与 {@code docs/contracts/database-schema.json} 逐项比对。
 *
 * <p>这是本任务最重要的一条测试：设计文档说"28 张表、47 条外键、每条外键都有显式索引"，
 * 但文档不会自己证明。这里读取 {@code information_schema}，逐表逐列比对，
 * 任何"迁移写漏了"或"InnoDB 偷偷建了索引"都会失败。
 *
 * <p>不比对的项：列默认值（BOOLEAN 的 true 在库里是 1，属类型别名差异）与索引/约束名称
 * （名称不承载语义，且生成器会随长度截断）。
 */
class SchemaDictionaryIT extends AbstractDatabaseIT {

    @Autowired
    private JdbcTemplate jdbc;

    private static JsonNode dictionary() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("docs/contracts/database-schema.json");
            if (Files.exists(candidate)) {
                try {
                    return new ObjectMapper().readTree(Files.readString(candidate));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 docs/contracts/database-schema.json");
    }

    @Test
    void tableNamesShouldMatchDictionary() {
        // flyway_schema_history 是迁移工具元数据，不属于设计字典
        Set<String> actual = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                String.class));
        Set<String> expected = new HashSet<>();
        dictionary().get("tables").fieldNames().forEachRemaining(expected::add);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void everyColumnShouldMatchDictionaryTypeAndNullability() {
        JsonNode tables = dictionary().get("tables");
        Map<String, Map<String, String[]>> actual = new HashMap<>();
        jdbc.query("SELECT table_name, column_name, column_type, is_nullable"
                        + " FROM information_schema.columns WHERE table_schema = DATABASE()",
                rs -> {
                    actual.computeIfAbsent(rs.getString(1).toLowerCase(Locale.ROOT), k -> new HashMap<>())
                            .put(rs.getString(2).toLowerCase(Locale.ROOT),
                                    new String[]{rs.getString(3).toLowerCase(Locale.ROOT), rs.getString(4)});
                });

        List<String> problems = new ArrayList<>();
        tables.fieldNames().forEachRemaining(table -> {
            for (JsonNode column : tables.get(table).get("columns")) {
                String name = column.get("name").asText();
                String[] found = actual.getOrDefault(table, Map.of()).get(name);
                if (found == null) {
                    problems.add(table + "." + name + " 在库中不存在");
                    continue;
                }
                String expectedType = normalizeType(column.get("type").asText());
                if (!expectedType.equals(found[0])) {
                    problems.add(table + "." + name + " 类型 " + found[0] + " != " + expectedType);
                }
                String expectedNullable = column.get("nullable").asBoolean() ? "YES" : "NO";
                if (!expectedNullable.equals(found[1])) {
                    problems.add(table + "." + name + " 可空 " + found[1] + " != " + expectedNullable);
                }
            }
        });

        assertThat(problems).isEmpty();
    }

    /** MySQL 对 BOOLEAN 的规范类型是 tinyint(1)，字典写的是语义类型。 */
    private static String normalizeType(String type) {
        return "BOOLEAN".equals(type) ? "tinyint(1)" : type.toLowerCase(Locale.ROOT);
    }

    @Test
    void primaryKeysAndUniqueKeysAndIndexesShouldMatchDictionary() {
        JsonNode tables = dictionary().get("tables");
        Map<String, List<String>> actual = readIndexes();
        List<String> problems = new ArrayList<>();

        tables.fieldNames().forEachRemaining(table -> {
            JsonNode definition = tables.get(table);
            Set<List<String>> expected = new LinkedHashSet<>();
            expected.add(toList(definition.get("primary_key")));
            definition.get("unique_keys").forEach(key -> expected.add(toList(key)));
            definition.get("indexes").forEach(key -> expected.add(toList(key)));

            List<List<String>> found = actual.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(table + "|"))
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());

            if (found.size() != expected.size()) {
                problems.add(table + " 索引数 " + found.size() + " != 设计 " + expected.size()
                        + "（差异说明有隐式索引或漏建索引）");
            }
            for (List<String> key : expected) {
                if (!found.contains(key)) {
                    problems.add(table + " 缺少索引/约束 " + key);
                }
            }
        });

        assertThat(problems).isEmpty();
    }

    @Test
    void everyForeignKeyShouldExistWithExplicitLeadingIndex() {
        JsonNode tables = dictionary().get("tables");
        Map<String, String> actualForeignKeys = readForeignKeys();
        Map<String, List<String>> indexes = readIndexes();
        List<String> problems = new ArrayList<>();

        tables.fieldNames().forEachRemaining(table -> {
            for (JsonNode fk : tables.get(table).get("foreign_keys")) {
                List<String> columns = toList(fk.get("columns"));
                List<String> referenced = toList(fk.get("references_columns"));
                String signature = table + "|" + String.join(",", columns) + "->"
                        + fk.get("references_table").asText() + "|" + String.join(",", referenced)
                        + "|" + fk.get("on_delete").asText();
                if (!signature.equals(actualForeignKeys.get(table + "|" + String.join(",", columns)))) {
                    problems.add("外键缺失或不一致: " + signature);
                }
                boolean hasLeadingIndex = indexes.values().stream()
                        .anyMatch(key -> key.size() >= columns.size()
                                && key.subList(0, columns.size()).equals(columns));
                if (!hasLeadingIndex) {
                    problems.add("外键缺显式前缀索引: " + table + " " + columns);
                }
            }
        });

        assertThat(problems).isEmpty();
    }

    @Test
    void checkConstraintsShouldMatchDictionaryExpressions() {
        JsonNode tables = dictionary().get("tables");
        Map<String, List<String>> actual = new HashMap<>();
        jdbc.query("SELECT tc.table_name, cc.check_clause FROM information_schema.check_constraints cc"
                        + " JOIN information_schema.table_constraints tc"
                        + "   ON tc.constraint_schema = cc.constraint_schema"
                        + "  AND tc.constraint_name = cc.constraint_name"
                        + " WHERE cc.constraint_schema = DATABASE()",
                rs -> {
                    actual.computeIfAbsent(rs.getString(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(canonical(rs.getString(2)));
                });

        List<String> problems = new ArrayList<>();
        tables.fieldNames().forEachRemaining(table -> {
            Set<String> expected = new TreeSet<>();
            tables.get(table).get("checks").forEach(node -> expected.add(canonical(node.asText())));
            Set<String> found = new TreeSet<>(actual.getOrDefault(table, List.of()));

            for (String expression : expected) {
                if (!found.remove(expression)) {
                    problems.add(table + " 缺少 CHECK: " + expression);
                }
            }
            if (!found.isEmpty()) {
                problems.add(table + " 存在设计外的 CHECK: " + found);
            }
        });

        assertThat(problems).isEmpty();
    }

    /**
     * 把 CHECK 表达式归一化成可比较的形式。
     *
     * <p>需要处理的差异：MySQL 会把引号存成 {@code \'}、给字符串加 {@code _utf8mb4} 前缀、
     * 给每个比较补括号。去掉这些装饰后，两边应当逐字符相同——比"列名集合相等"更严格，
     * 否则 {@code duplicate_count >= 0} 会被误判为匹配上 {@code duplicate_count <= success_count}。
     */
    private static String canonical(String expression) {
        String value = expression.replace("\\'", "'").replace("`", "");
        value = value.replace("_utf8mb4", "").toLowerCase(Locale.ROOT);
        return value.replaceAll("[\\s()]", "");
    }

    private Map<String, List<String>> readIndexes() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        jdbc.query("SELECT table_name, index_name, column_name, seq_in_index"
                        + " FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " ORDER BY table_name, index_name, seq_in_index",
                rs -> {
                    String key = rs.getString(1).toLowerCase(Locale.ROOT) + "|" + rs.getString(2);
                    grouped.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(rs.getString(3).toLowerCase(Locale.ROOT));
                });
        return grouped;
    }

    private Map<String, String> readForeignKeys() {
        Map<String, List<String[]>> grouped = new LinkedHashMap<>();
        jdbc.query("SELECT k.table_name, k.constraint_name, k.column_name, k.referenced_table_name,"
                        + " k.referenced_column_name, r.delete_rule"
                        + " FROM information_schema.key_column_usage k"
                        + " JOIN information_schema.referential_constraints r"
                        + "   ON r.constraint_schema = k.constraint_schema"
                        + "  AND r.constraint_name = k.constraint_name"
                        + " WHERE k.constraint_schema = DATABASE() AND k.referenced_table_name IS NOT NULL"
                        + " ORDER BY k.table_name, k.constraint_name, k.ordinal_position",
                rs -> {
                    String key = rs.getString(1).toLowerCase(Locale.ROOT) + "|" + rs.getString(2);
                    grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{
                            rs.getString(3).toLowerCase(Locale.ROOT),
                            rs.getString(4).toLowerCase(Locale.ROOT),
                            rs.getString(5).toLowerCase(Locale.ROOT),
                            rs.getString(6)});
                });

        Map<String, String> fks = new HashMap<>();
        grouped.forEach((key, rows) -> {
            String table = key.substring(0, key.indexOf('|'));
            String columns = rows.stream().map(row -> row[0]).collect(Collectors.joining(","));
            String referencedColumns = rows.stream().map(row -> row[2]).collect(Collectors.joining(","));
            fks.put(table + "|" + columns, table + "|" + columns + "->" + rows.get(0)[1]
                    + "|" + referencedColumns + "|" + rows.get(0)[3]);
        });
        return fks;
    }

    private static List<String> toList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
