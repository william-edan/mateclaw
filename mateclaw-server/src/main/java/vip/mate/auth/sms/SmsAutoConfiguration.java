package vip.mate.auth.sms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 短信模块自动配置。项目无 @ConfigurationPropertiesScan，必须显式 @EnableConfigurationProperties。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(SmsProperties.class)
public class SmsAutoConfiguration {
}
