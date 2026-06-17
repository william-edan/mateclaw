package vip.mate.llm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;

/**
 * 启动后把配置文件里的平台默认 key 注入受管「默认版」provider。
 * Order 120：晚于 DatabaseBootstrapRunner(@Order(1)) 的种子加载，确保 provider 行已存在。
 * 幂等——空 key 才回填；桌面延迟 seed 由 SetupController 另行触发。
 */
@Slf4j
@Component
@Order(120)
@RequiredArgsConstructor
public class DefaultProviderKeyBootstrap implements ApplicationRunner {

    private final ModelProviderService modelProviderService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            modelProviderService.applyManagedDefaultProviderKeys();
        } catch (Exception e) {
            log.warn("[DefaultProviderKeyBootstrap] 注入平台默认 key 失败，跳过", e);
        }
    }
}
