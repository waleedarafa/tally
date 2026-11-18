package com.tally.platform.internal;

import com.tally.platform.Caller;
import com.tally.platform.CallerAccounts;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives a web handler its {@link Caller}: the authenticated token's subject, and the account {@code account} finds for
 * it. Found once for each request and kept on the request, however many parameters ask (brief rule 6).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class CallerResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private static final String ATTRIBUTE = Caller.class.getName();

    private final CallerAccounts accounts;

    CallerResolver(CallerAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binders) {
        Object found = request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (found instanceof Caller caller) {
            return caller;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            // Every /v1/ request is authenticated before it reaches a handler (ApiSecurity).
            throw new IllegalStateException("No authenticated caller");
        }
        String user = authentication.getName();
        Caller caller = new Caller(user, accounts.accountIdOf(user));
        request.setAttribute(ATTRIBUTE, caller, RequestAttributes.SCOPE_REQUEST);
        return caller;
    }
}
