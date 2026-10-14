package ec.unisfera.identity.security;

import ec.unisfera.shared.domain.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Deny-by-default a nivel de método: todo handler declara @RequiresPermission, @AuthenticatedOnly
 * o @PublicEndpoint; si no declara nada, se rechaza. Corre antes de leer el cuerpo.
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (find(method, PublicEndpoint.class) != null) {
            return true;
        }
        CurrentUser user = SecurityUtils.requireUser();
        RequiresPermission required = find(method, RequiresPermission.class);
        if (required != null) {
            if (Arrays.stream(required.value()).noneMatch(user::has)) {
                throw new ForbiddenException(request.getRequestURI());
            }
            return true;
        }
        if (find(method, AuthenticatedOnly.class) != null) {
            return true;
        }
        throw new ForbiddenException(request.getRequestURI());
    }

    private static <A extends java.lang.annotation.Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }
}
