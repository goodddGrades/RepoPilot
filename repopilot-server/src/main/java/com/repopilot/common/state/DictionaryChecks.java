package com.repopilot.common.state;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** {@link DictionaryCheck} 的容器，使一个枚举可以声明多个表列。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DictionaryChecks {

    DictionaryCheck[] value();
}
