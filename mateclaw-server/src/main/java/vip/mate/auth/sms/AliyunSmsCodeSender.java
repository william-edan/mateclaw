package vip.mate.auth.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云 dysmsapi 发送器：mock=false（默认）时装配。Client 懒加载，
 * 使空密钥时由 SmsStartupValidator 给出友好启动错误，而非 bean 创建期抛栈。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mateclaw.sms", name = "mock", havingValue = "false", matchIfMissing = true)
public class AliyunSmsCodeSender implements SmsCodeSender {

    private final SmsProperties props;
    private volatile Client client;

    public AliyunSmsCodeSender(SmsProperties props) {
        this.props = props;
    }

    /** 测试注入 Client。 */
    AliyunSmsCodeSender(SmsProperties props, Client client) {
        this.props = props;
        this.client = client;
    }

    private Client client() {
        Client c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    try {
                        Config config = new Config()
                                .setAccessKeyId(props.getAliyun().getAccessKeyId())
                                .setAccessKeySecret(props.getAliyun().getAccessKeySecret())
                                .setEndpoint(props.getAliyun().getEndpoint());
                        client = c = new Client(config);
                    } catch (Exception e) {
                        throw new SmsSendException("阿里云短信客户端初始化失败", e);
                    }
                }
            }
        }
        return c;
    }

    @Override
    public void send(String phone, String code) {
        SendSmsRequest req = new SendSmsRequest()
                .setPhoneNumbers(phone)
                .setSignName(props.getAliyun().getSignName())
                .setTemplateCode(props.getAliyun().getTemplateCode())
                .setTemplateParam("{\"code\":\"" + code + "\"}");
        try {
            SendSmsResponse resp = client().sendSms(req);
            SendSmsResponseBody body = resp != null ? resp.getBody() : null;
            String respCode = body != null ? body.getCode() : null;
            if (!"OK".equals(respCode)) {
                String message = body != null ? body.getMessage() : null;
                String bizId = body != null ? body.getBizId() : null;
                log.warn("[SMS-ALIYUN] send failed phone={} code={} message={} bizId={}",
                        phone, respCode, message, bizId);
                throw new SmsSendException("阿里云短信发送失败: " + respCode);
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[SMS-ALIYUN] send error phone={}: {}", phone, e.getMessage());
            throw new SmsSendException("阿里云短信发送异常", e);
        }
    }
}
