package com.repopilot.common.state;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明枚举对应的数据库列约束。
 *
 * <p>用于让"Java 枚举"与"数据库 CHECK"之间的对应关系可被程序检查，
 * 而不是只写在注释里。集成测试据此读取 {@code information_schema} 的 CHECK 文本，
 * 逐值比对枚举常量。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(DictionaryChecks.class)
public @interface DictionaryCheck {

    String table();

    String column();
}
