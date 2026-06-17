package vip.mate;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the config-gated wiring of the workspace tenant interceptor: it is only
 * registered when {@code mateclaw.tenant.line-interceptor-enabled=true}, and when
 * registered it sits BEFORE pagination (so the tenant predicate is applied before
 * the count/paginate rewrite). Default-off keeps production unchanged.
 */
class MybatisPlusTenantInterceptorWiringTest {

    private final MateClawApplication app = new MateClawApplication();

    @Test
    void tenantInterceptorAbsentWhenDisabled() {
        List<InnerInterceptor> inner = app.mybatisPlusInterceptor(false, "").getInterceptors();
        assertThat(inner).noneMatch(i -> i instanceof TenantLineInnerInterceptor);
        assertThat(inner).anyMatch(i -> i instanceof PaginationInnerInterceptor);
    }

    @Test
    void tenantInterceptorRegisteredBeforePaginationWhenEnabled() {
        MybatisPlusInterceptor interceptor = app.mybatisPlusInterceptor(true, "mate_datasource, mate_fact");
        List<InnerInterceptor> inner = interceptor.getInterceptors();

        assertThat(inner).hasSize(2);
        assertThat(inner.get(0))
                .as("tenant interceptor must precede pagination")
                .isInstanceOf(TenantLineInnerInterceptor.class);
        assertThat(inner.get(1)).isInstanceOf(PaginationInnerInterceptor.class);
    }

    @Test
    void blankTableListStillRegistersInterceptorButScopesNothing() {
        // Enabled with an empty whitelist: interceptor present (mechanism on) but every
        // table is ignored, so no SQL is rewritten — the inert first step of rollout.
        List<InnerInterceptor> inner = app.mybatisPlusInterceptor(true, "  ").getInterceptors();
        assertThat(inner.get(0)).isInstanceOf(TenantLineInnerInterceptor.class);
    }
}
