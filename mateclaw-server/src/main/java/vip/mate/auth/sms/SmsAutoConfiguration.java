package vip.mate.auth.sms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短信模块自动配置。项目无 @ConfigurationPropertiesScan，必须显式 @EnableConfigurationProperties。
 * 发送器由工厂方法按 (mock, provider) 决断，保证容器内恒有且仅有一个 SmsCodeSender。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(SmsProperties.class)
public class SmsAutoConfiguration {

    /**
     * 选择短信发送器：mock=true → Mock；否则按 provider 选 aliyun/tencent。
     * provider 经 trim().toLowerCase() 归一，对大小写/空格鲁棒（不依赖 SpEL 字符串比较）。
     * 非法 provider 直接 fail-fast（SmsStartupValidator 通常已先给出更友好的报错）。
     */
    @Bean
    public SmsCodeSender smsCodeSender(SmsProperties props) {
        if (props.isMock()) {
            return new MockSmsCodeSender();
        }
        String provider = props.getProvider() == null ? "aliyun" : props.getProvider().trim().toLowerCase();
        return switch (provider) {
            case "aliyun" -> new AliyunSmsCodeSender(props);
            case "tencent" -> new TencentSmsCodeSender(props);
            default -> throw new IllegalStateException(
                    "[SMS] 未知短信 provider: '" + props.getProvider() + "'，仅支持 aliyun / tencent");
        };
    }
}
