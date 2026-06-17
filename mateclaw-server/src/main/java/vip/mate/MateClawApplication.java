package vip.mate;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import vip.mate.config.WorkspaceTenantLineHandler;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MateClaw - Personal AI Assistant
 * Powered by Spring AI Alibaba
 *
 * @author MateClaw Team
 */
@SpringBootApplication(exclude = {
    // Disable Spring AI MCP Client auto-configuration (lifecycle owned by McpClientManager).
    org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration.class,
    org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration.class,
    org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration.class,
    // DashScopeAgent is the Bailian "Application Agent" (Bailian-hosted prompt+tool app),
    // not the chat model. We don't use it — model configuration is admin-UI driven and
    // built by DashScopeChatModelBuilder. Its auto-config strictly requires
    // spring.ai.dashscope.api-key to be non-empty at startup, which makes the whole
    // ApplicationContext fail when users deploy via Docker without setting the key.
    com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration.class,
})
@EnableScheduling
@MapperScan("vip.mate.**.repository")
public class MateClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(MateClawApplication.class, args);
    }

    /**
     * MyBatis Plus pagination plugin.
     *
     * <p>DbType is auto-detected from the JDBC connection at runtime rather
     * than hardcoded. Hardcoding H2 here meant the MySQL deployment used
     * the H2 dialect for the count query, which silently returned 0 —
     * frontends saw records but total=0 and couldn't paginate (RFC-042 P0).
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            @Value("${mateclaw.tenant.line-interceptor-enabled:false}") boolean tenantLineEnabled,
            @Value("${mateclaw.tenant.line-interceptor-tables:}") String tenantLineTables) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // Workspace tenant isolation — OFF by default. Enabling it rewrites SQL for
        // the whitelisted tables (mateclaw.tenant.line-interceptor-tables, comma list)
        // and requires every read/write path to have the workspace bound (Part 1
        // off-request + Part 5 reactive) plus a full per-table regression. Registered
        // BEFORE pagination so the tenant predicate is applied before the count rewrite.
        if (tenantLineEnabled) {
            Set<String> tables = Arrays.stream(tenantLineTables.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            interceptor.addInnerInterceptor(
                    new TenantLineInnerInterceptor(new WorkspaceTenantLineHandler(tables)));
        }
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
