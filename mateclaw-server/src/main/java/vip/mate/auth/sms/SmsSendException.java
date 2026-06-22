package vip.mate.auth.sms;

/** 短信发送失败（服务商返回非 OK 或调用异常）。 */
public class SmsSendException extends RuntimeException {
    public SmsSendException(String message) {
        super(message);
    }

    public SmsSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
