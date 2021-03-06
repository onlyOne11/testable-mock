package com.alibaba.testable.core.annotation;

import java.lang.annotation.*;

/**
 * Use marked method to replace the ones in source class
 *
 * @author flin
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface TestableMock {

    /**
     * mock specified method instead of method with same name
     * @return target method name
     */
    String targetMethod() default "";

}
