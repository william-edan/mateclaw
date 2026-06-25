package vip.mate.auth.sms;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20210111.models.SendStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云短信发送器：由 SmsAutoConfiguration 工厂在 mock=false 且 provider=tencent 时实例化。
 * Client 懒加载（空密钥不在构造期抛栈，由 SmsStartupValidator 给出友好启动错误）。
 *
 * 注意与阿里云差异：号码需 E.164 带国家码、模板参数为位置数组、成功码是 "Ok"（非阿里的 "OK"）。
 *
 * @author MateClaw Team
 */
@Slf4j
public class TencentSmsCodeSender implements SmsCodeSender {

    /** 腾讯云短信发送成功码（首字母大写，区别于阿里云 "OK"）。 */
    private static final String SUCCESS_CODE = "Ok";

    private final SmsProperties props;
    private volatile SmsClient client;

    public TencentSmsCodeSender(SmsProperties props) {
        this.props = props;
    }

    /** 仅供测试：注入 mock SmsClient，跳过懒加载真实客户端。 */
    TencentSmsCodeSender(SmsProperties props, SmsClient client) {
        this.props = props;
        this.client = client;
    }

    private SmsClient client() {
        SmsClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    try {
                        SmsProperties.Tencent t = props.getTencent();
                        Credential cred = new Credential(t.getSecretId(), t.getSecretKey());
                        client = c = new SmsClient(cred, t.getRegion());
                    } catch (Exception e) {
                        throw new SmsSendException("腾讯云短信客户端初始化失败", e);
                    }
                }
            }
        }
        return c;
    }

    @Override
    public void send(String phone, String code) {
        SmsProperties.Tencent t = props.getTencent();
        SendSmsRequest req = new SendSmsRequest();
        req.setSmsSdkAppId(t.getSdkAppId());
        req.setSignName(t.getSignName());
        req.setTemplateId(t.getTemplateId());
        // code 由 VerificationCodeService 保证为纯数字；模板内用 {1} 位置参数
        req.setTemplateParamSet(new String[]{code});
        req.setPhoneNumberSet(new String[]{toE164(phone, t.getDefaultCountryCode())});
        try {
            SendSmsResponse resp = client().SendSms(req);
            SendStatus[] statuses = resp != null ? resp.getSendStatusSet() : null;
            String respCode = (statuses != null && statuses.length > 0) ? statuses[0].getCode() : null;
            if (!SUCCESS_CODE.equals(respCode)) {
                String message = (statuses != null && statuses.length > 0) ? statuses[0].getMessage() : null;
                log.warn("[SMS-TENCENT] send failed phone={} code={} message={}",
                        maskPhone(phone), respCode, message);
                throw new SmsSendException("腾讯云短信发送失败: " + respCode);
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[SMS-TENCENT] send error phone={}: {}", maskPhone(phone), e.getMessage());
            throw new SmsSendException("腾讯云短信发送异常", e);
        }
    }

    /** 裸号补国家码（默认 +86）；已带 + 前缀按原样。 */
    private static String toE164(String phone, String defaultCountryCode) {
        if (phone == null) {
            return null;
        }
        if (phone.startsWith("+")) {
            return phone;
        }
        String cc = (defaultCountryCode == null || defaultCountryCode.isBlank()) ? "+86" : defaultCountryCode.trim();
        return cc + phone;
    }

    /** 日志脱敏：保留前 3 后 4 位。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
