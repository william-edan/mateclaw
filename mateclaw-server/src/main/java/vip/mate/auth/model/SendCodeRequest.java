package vip.mate.auth.model;

import lombok.Data;

/** 发送注册验证码请求。 */
@Data
public class SendCodeRequest {
    private String phone;
}
