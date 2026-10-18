package com.repopilot.it;

import com.repopilot.common.state.DictionaryCheck;
import com.repopilot.common.state.DictionaryChecks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 枚举与数据库 CHECK 的双向一致性。
 *
 * <p>只比"代码里有没有这个枚举"没有意义：真正会出问题的是两边悄悄不一致——
 * 数据库允许 {@code PARTIAL_FAILED} 而枚举没有，或者枚举多了一个库里不允许的值。
 * 这里从 {@code information_schema} 反向读出 CHECK 的取值集合，与枚举常量逐值比对。
 */
class EnumDictionaryIT extends AbstractDatabaseIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyAnnotatedEnumShouldMatchDatabaseCheck() throws Exception {
        Map<String, List<String>> clauses = new HashMap<>();
        jdbc.query("SELECT tc.table_name, cc.check_clause FROM information_schema.check_constraints cc"
                        + " JOIN information_schema.table_constraints tc"
                        + "   ON tc.constraint_schema = cc.constraint_schema"
                        + "  AND tc.constraint_name = cc.constraint_name"
                        + " WHERE cc.constraint_schema = DATABASE()",
                rs -> {
                    clauses.computeIfAbsent(rs.getString(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(rs.getString(2));
                });

        List<Class<?>> candidates = scan();
        assertThat(candidates).isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (Class<?> type : candidates) {
            List<DictionaryCheck> declarations = new ArrayList<>();
            DictionaryChecks container = type.getAnnotation(DictionaryChecks.class);
            if (container != null) {
                declarations.addAll(Arrays.asList(container.value()));
            }
            DictionaryCheck single = type.getAnnotation(DictionaryCheck.class);
            if (single != null) {
                declarations.add(single);
            }

            Set<String> constants = new TreeSet<>();
            Arrays.stream(type.getEnumConstants()).forEach(value -> constants.add(value.toString()));

            for (DictionaryCheck declaration : declarations) {
                String column = "`" + declaration.column() + "`";
                Set<String> allowed = clauses.getOrDefault(declaration.table(), List.of()).stream()
                        .filter(clause -> clause.toLowerCase(Locale.ROOT).contains(" in ("))
                        .filter(clause -> clause.replace("\\'", "'").contains(column))
                        .findFirst()
                        .map(this::literals)
                        .orElse(null);
                if (allowed == null) {
                    problems.add(type.getSimpleName() + ": 库中没有 " + declaration.table()
                            + "." + declaration.column() + " 的 IN 约束");
                } else if (!allowed.equals(constants)) {
                    problems.add(type.getSimpleName() + " 与 " + declaration.table() + "."
                            + declaration.column() + " 不一致: 枚举 " + constants + " / 数据库 " + allowed);
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    private Set<String> literals(String clause) {
        // MySQL 8.0.22 的 CHECK_CLAUSE 会把引号存成 \'，先还原再解析
        Matcher matcher = Pattern.compile("'([^']*)'").matcher(clause.replace("\\'", "'"));
        Set<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private List<Class<?>> scan() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        return definition.getMetadata().getClassName() != null;
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(DictionaryCheck.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(DictionaryChecks.class));

        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.repopilot.common.state")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (type.isEnum()) {
                types.add(type);
            }
        }
        return types;
    }
}
