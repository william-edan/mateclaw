package vip.mate.auth.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliyunSmsCodeSenderTest {

    private SmsProperties props() {
        SmsProperties p = new SmsProperties();
        p.getAliyun().setSignName("迪伍科技");
        p.getAliyun().setTemplateCode("SMS_508735089");
        return p;
    }

    @Test
    void sendBuildsCorrectRequest() throws Exception {
        Client client = mock(Client.class);
        SendSmsResponse ok = new SendSmsResponse();
        ok.setBody(new SendSmsResponseBody().setCode("OK").setBizId("biz-1"));
        when(client.sendSms(any(SendSmsRequest.class))).thenReturn(ok);

        AliyunSmsCodeSender sender = new AliyunSmsCodeSender(props(), client);
        sender.send("13800138000", "123456");

        ArgumentCaptor<SendSmsRequest> captor = ArgumentCaptor.forClass(SendSmsRequest.class);
        org.mockito.Mockito.verify(client).sendSms(captor.capture());
        SendSmsRequest req = captor.getValue();
        assertEquals("13800138000", req.getPhoneNumbers());
        assertEquals("迪伍科技", req.getSignName());
        assertEquals("SMS_508735089", req.getTemplateCode());
        assertEquals("{\"code\":\"123456\"}", req.getTemplateParam());
    }

    @Test
    void sendThrowsOnNonOkCode() throws Exception {
        Client client = mock(Client.class);
        SendSmsResponse bad = new SendSmsResponse();
        bad.setBody(new SendSmsResponseBody().setCode("isv.BUSINESS_LIMIT_CONTROL").setMessage("触发流控"));
        when(client.sendSms(any(SendSmsRequest.class))).thenReturn(bad);

        AliyunSmsCodeSender sender = new AliyunSmsCodeSender(props(), client);
        assertThrows(SmsSendException.class, () -> sender.send("13800138000", "123456"));
    }

    @Test
    void sendWrapsUnderlyingException() throws Exception {
        Client client = mock(Client.class);
        when(client.sendSms(any(SendSmsRequest.class))).thenThrow(new RuntimeException("timeout"));

        AliyunSmsCodeSender sender = new AliyunSmsCodeSender(props(), client);
        SmsSendException ex = assertThrows(SmsSendException.class, () -> sender.send("13800138000", "123456"));
        assertNotNull(ex.getCause());
    }

    /**
     * 回归：Spring 通过构造器自动装配创建该 bean（mock=false 时的生产路径）。
     * 该类有两个构造器，必须用 @Autowired 指明注入构造器，否则 Spring 回退找无参构造器报
     * "No default constructor found"。单测直接 new 双参构造器无法覆盖这条装配路径。
     */
    @Test
    void springCanInstantiateAliyunSenderBean() {
        new ApplicationContextRunner()
                .withBean(SmsProperties.class)
                .withBean(AliyunSmsCodeSender.class)
                .run(ctx -> assertThat(ctx)
                        .hasNotFailed()
                        .hasSingleBean(AliyunSmsCodeSender.class));
    }
}
