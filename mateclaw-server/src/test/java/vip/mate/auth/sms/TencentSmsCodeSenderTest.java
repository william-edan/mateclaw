package vip.mate.auth.sms;

import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20210111.models.SendStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TencentSmsCodeSenderTest {

    private SmsProperties props() {
        SmsProperties p = new SmsProperties();
        p.getTencent().setSdkAppId("1400000000");
        p.getTencent().setSignName("迪伍科技");
        p.getTencent().setTemplateId("2000001");
        p.getTencent().setDefaultCountryCode("+86");
        return p;
    }

    private SendSmsResponse respWithCode(String code) {
        SendStatus status = new SendStatus();
        status.setCode(code);
        status.setMessage("msg");
        SendSmsResponse resp = new SendSmsResponse();
        resp.setSendStatusSet(new SendStatus[]{status});
        return resp;
    }

    @Test
    void sendBuildsCorrectRequestAndPrependsCountryCode() throws Exception {
        SmsClient client = mock(SmsClient.class);
        when(client.SendSms(any(SendSmsRequest.class))).thenReturn(respWithCode("Ok"));

        TencentSmsCodeSender sender = new TencentSmsCodeSender(props(), client);
        sender.send("13800138000", "123456");

        ArgumentCaptor<SendSmsRequest> captor = ArgumentCaptor.forClass(SendSmsRequest.class);
        verify(client).SendSms(captor.capture());
        SendSmsRequest req = captor.getValue();
        assertEquals("1400000000", req.getSmsSdkAppId());
        assertEquals("迪伍科技", req.getSignName());
        assertEquals("2000001", req.getTemplateId());
        assertArrayEquals(new String[]{"123456"}, req.getTemplateParamSet());
        assertArrayEquals(new String[]{"+8613800138000"}, req.getPhoneNumberSet());
    }

    @Test
    void sendKeepsExplicitPlusPrefix() throws Exception {
        SmsClient client = mock(SmsClient.class);
        when(client.SendSms(any(SendSmsRequest.class))).thenReturn(respWithCode("Ok"));

        TencentSmsCodeSender sender = new TencentSmsCodeSender(props(), client);
        sender.send("+8513912345678", "654321");

        ArgumentCaptor<SendSmsRequest> captor = ArgumentCaptor.forClass(SendSmsRequest.class);
        verify(client).SendSms(captor.capture());
        assertArrayEquals(new String[]{"+8513912345678"}, captor.getValue().getPhoneNumberSet());
    }

    @Test
    void sendThrowsOnNonOkCode() throws Exception {
        SmsClient client = mock(SmsClient.class);
        when(client.SendSms(any(SendSmsRequest.class))).thenReturn(respWithCode("FailedOperation.SignatureIncorrectOrUnapproved"));

        TencentSmsCodeSender sender = new TencentSmsCodeSender(props(), client);
        assertThrows(SmsSendException.class, () -> sender.send("13800138000", "123456"));
    }

    @Test
    void sendThrowsOnEmptyStatusSet() throws Exception {
        SmsClient client = mock(SmsClient.class);
        SendSmsResponse empty = new SendSmsResponse();
        empty.setSendStatusSet(new SendStatus[]{});
        when(client.SendSms(any(SendSmsRequest.class))).thenReturn(empty);

        TencentSmsCodeSender sender = new TencentSmsCodeSender(props(), client);
        assertThrows(SmsSendException.class, () -> sender.send("13800138000", "123456"));
    }

    @Test
    void sendWrapsUnderlyingException() throws Exception {
        SmsClient client = mock(SmsClient.class);
        when(client.SendSms(any(SendSmsRequest.class))).thenThrow(new RuntimeException("network"));

        TencentSmsCodeSender sender = new TencentSmsCodeSender(props(), client);
        SmsSendException ex = assertThrows(SmsSendException.class, () -> sender.send("13800138000", "123456"));
        assertNotNull(ex.getCause());
    }
}
