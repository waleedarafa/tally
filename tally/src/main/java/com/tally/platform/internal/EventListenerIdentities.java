package com.tally.platform.internal;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * The identity of every event listener this release has, as Spring Modulith stores it with each pending publication:
 * {@code class.method(parameter types)} (CLAUDE.md rule 7; the same form as PinnedListenersTest). Read once all
 * beans exist. {@link EventHealth} counts the incomplete publications whose listener is not among them (ADR-028).
 */
@Component
class EventListenerIdentities implements SmartInitializingSingleton {

    private final ListableBeanFactory beans;
    private volatile Set<String> identities = Set.of();

    EventListenerIdentities(ListableBeanFactory beans) {
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> found = new TreeSet<>();
        for (String name : beans.getBeanDefinitionNames()) {
            Class<?> type = beans.getType(name, false);
            if (type == null) {
                continue;
            }
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(ClassUtils.getUserClass(type))) {
                if (AnnotatedElementUtils.hasAnnotation(method, EventListener.class)) {
                    found.add(identity(method));
                }
            }
        }
        identities = Set.copyOf(found);
    }

    static String identity(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName() + Arrays.stream(method.getParameterTypes())
                .map(Class::getName).collect(Collectors.joining(",", "(", ")"));
    }

    /** Empty until every bean exists. */
    Set<String> identities() {
        return identities;
    }
}
