package vip.mate.auth.sms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 注册短信验证码配置。前缀 mateclaw.sms（与 mateclaw.jwt 同族，勿写成 mate.sms）。
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.sms")
public class SmsProperties {

    /** true=不真实发送，走 Mock，dev/集成用；默认 false（fail-safe）。生产强制 false（SmsStartupValidator）。 */
    private boolean mock = false;

    /** 仅 mock=true 生效的万能验证码。 */
    private String universalCode = "888888";

    /** 验证码有效期（秒）。验证码固定 6 位数字，长度不可配。 */
    private int codeTtlSeconds = 300;

    /** 同一手机号两次发送的最小间隔（秒）。 */
    private int resendIntervalSeconds = 60;

    /** 发送失败后的短冷却（秒），失败可重试但非无间隔。 */
    private int failureCooldownSeconds = 10;

    /** 单个验证码允许的最大错误次数，超过即作废。 */
    private int maxVerifyAttempts = 5;

    /** 单手机号滚动 24h 发送上限。 */
    private int dailyLimitPerPhone = 10;

    /** 单 IP 滚动 24h 发送上限。 */
    private int dailyLimitPerIp = 30;

    /** 全平台滚动 60s 发送上限（账单熔断）。 */
    private int globalLimitPerMinute = 200;

    private Aliyun aliyun = new Aliyun();

    @Data
    public static class Aliyun {
        /** 仅环境变量注入，禁止入库。 */
        private String accessKeyId = "";
        /** 仅环境变量注入，禁止入库。 */
        private String accessKeySecret = "";
        private String endpoint = "dysmsapi.aliyuncs.com";
        private String signName = "迪伍科技";
        private String templateCode = "SMS_508735089";
    }
}
