package vip.mate.auth.sms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 启动期硬校验，防止短信配置带病上线：
 * - 生产 profile 禁止 mock=true（万能码不可在生产可用）；
 * - mock=false 但 AccessKey/Secret 为空 → 拒绝启动。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsStartupValidator implements InitializingBean {

    private final SmsProperties props;
    private final Environment env;

    @Override
    public void afterPropertiesSet() {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (prod && props.isMock()) {
            throw new IllegalStateException(
                    "[SMS] 生产环境(prod) 不允许 mateclaw.sms.mock=true（万能码必须关闭）");
        }
        if (!props.isMock()) {
            if (isBlank(props.getAliyun().getAccessKeyId()) || isBlank(props.getAliyun().getAccessKeySecret())) {
                throw new IllegalStateException(
                        "[SMS] mock=false 但阿里云 AccessKey/Secret 未配置（ALIYUN_SMS_AK/SK），拒绝启动");
            }
        } else {
            log.warn("==== [SMS] mock=true：短信走 Mock 不真实发送，万能码可用。仅限非生产环境！ ====");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
