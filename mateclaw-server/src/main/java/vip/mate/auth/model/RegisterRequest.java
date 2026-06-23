package vip.mate.auth.model;

import lombok.Data;

/**
 * 手机号注册请求。
 */
@Data
public class RegisterRequest {
    private String phone;
    private String password;
    private String nickname;
}
