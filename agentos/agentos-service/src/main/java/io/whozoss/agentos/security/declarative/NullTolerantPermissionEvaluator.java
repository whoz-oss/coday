package io.whozoss.agentos.security.declarative;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;

/**
 * Bridges Spring Security's {@link PermissionEvaluator} contract to null-tolerant checks.
 *
 * <p>Spring Security 7 declares {@code targetId} non-null, yet SpEL hands over whatever the expression
 * evaluates to: {@code hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')} passes {@code null} for a
 * platform-scoped resource, which is a legitimate request (a super-admin may write platform configs).
 * A Kotlin override with a non-null parameter would fail its generated null check before any permission
 * logic runs; this Java adapter forwards the raw values to explicitly nullable methods instead, so the
 * Kotlin implementation keeps strict null-safety everywhere else. The public entry points are annotated
 * {@code @Nullable} (widening a parameter's nullness in an override is safe) so callers, tests included,
 * can exercise the null paths without casts.
 */
public abstract class NullTolerantPermissionEvaluator implements PermissionEvaluator {

    @Override
    public final boolean hasPermission(
            @Nullable Authentication authentication,
            @Nullable Serializable targetId,
            @Nullable String targetType,
            @Nullable Object permission) {
        return checkPermission(authentication, targetId, targetType, permission);
    }

    @Override
    public final boolean hasPermission(
            @Nullable Authentication authentication,
            @Nullable Object targetDomainObject,
            @Nullable Object permission) {
        return checkDomainObjectPermission(authentication, targetDomainObject, permission);
    }

    protected abstract boolean checkPermission(
            @Nullable Authentication authentication,
            @Nullable Serializable targetId,
            @Nullable String targetType,
            @Nullable Object permission);

    protected abstract boolean checkDomainObjectPermission(
            @Nullable Authentication authentication,
            @Nullable Object targetDomainObject,
            @Nullable Object permission);
}
