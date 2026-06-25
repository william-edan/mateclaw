package vip.mate.auth.sms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SmsCodeSenderFactoryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SmsAutoConfiguration.class);

    @Test
    void mockTrueWiresMockSender() {
        runner.withPropertyValues("mateclaw.sms.mock=true")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .hasSingleBean(SmsCodeSender.class)
                        .getBean(SmsCodeSender.class).isInstanceOf(MockSmsCodeSender.class));
    }

    @Test
    void mockFalseDefaultProviderWiresAliyun() {
        runner.withPropertyValues("mateclaw.sms.mock=false")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .hasSingleBean(SmsCodeSender.class)
                        .getBean(SmsCodeSender.class).isInstanceOf(AliyunSmsCodeSender.class));
    }

    @Test
    void mockFalseAliyunWiresAliyun() {
        runner.withPropertyValues("mateclaw.sms.mock=false", "mateclaw.sms.provider=aliyun")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .hasSingleBean(SmsCodeSender.class)
                        .getBean(SmsCodeSender.class).isInstanceOf(AliyunSmsCodeSender.class));
    }

    @Test
    void mockFalseTencentWiresTencent() {
        runner.withPropertyValues("mateclaw.sms.mock=false", "mateclaw.sms.provider=tencent")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .hasSingleBean(SmsCodeSender.class)
                        .getBean(SmsCodeSender.class).isInstanceOf(TencentSmsCodeSender.class));
    }

    @Test
    void providerIsCaseAndWhitespaceInsensitive() {
        runner.withPropertyValues("mateclaw.sms.mock=false", "mateclaw.sms.provider=  Tencent ")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .getBean(SmsCodeSender.class).isInstanceOf(TencentSmsCodeSender.class));
    }

    @Test
    void illegalProviderFailsContext() {
        runner.withPropertyValues("mateclaw.sms.mock=false", "mateclaw.sms.provider=nexmo")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
