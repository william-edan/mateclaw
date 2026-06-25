# 腾讯云短信 + 注册手机号短信验证 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增腾讯云短信发送实现，与阿里云并存、由 `mateclaw.sms.provider` 启动时选择；注册接口强制手机号短信验证码校验。

**Architecture:** 复用现有 `SmsCodeSender` 接口。把"按 mock/provider 选发送器"的逻辑从类级 `@ConditionalOnProperty`/`@ConditionalOnExpression` 改为 `SmsAutoConfiguration` 里的单个工厂 `@Bean`（Java 侧 `isMock()` + `provider.trim().toLowerCase()`，杜绝 SpEL 字符串比较对 `SMS_MOCK=FALSE` 的脆弱性、保证恒有且仅有一个 Bean）。注册侧把现有唯一性检查前移、在其后调用 `VerificationCodeService.verifyAndConsume`。

**Tech Stack:** Java 21、Spring Boot 3.5.14、MyBatis-Plus、JUnit5 + Mockito、腾讯云 SMS SDK 子包 `tencentcloud-sdk-java-sms`；前端 Vue 3 + TS + vue-i18n。

**Spec:** `docs/superpowers/specs/2026-06-25-tencent-sms-and-register-verification-design.md`

> **构建环境（务必）：** 本仓库构建必须用 **JDK 21**（默认 mvn 走 JDK 23 会让 Lombok 静默失效报"找不到符号"）。运行任何 `mvn` 前确认 `JAVA_HOME` 指向 JDK 21。跑指定单测带 `-am` 时必须加 `-Dsurefire.failIfNoSpecifiedTests=false`，否则上游模块 BUILD FAILURE。

---

## File Structure

**后端（`mateclaw-server/`）**

- Modify `pom.xml` — 新增腾讯云 SMS 依赖。
- Modify `src/main/java/vip/mate/auth/sms/SmsProperties.java` — 加 `provider` 字段与内嵌 `Tencent`。
- Create `src/main/java/vip/mate/auth/sms/TencentSmsCodeSender.java` — 腾讯云发送器（普通类，由工厂实例化）。
- Modify `src/main/java/vip/mate/auth/sms/AliyunSmsCodeSender.java` — 去掉 `@Component`/`@ConditionalOnProperty`（保留 `@Autowired` 构造器）。
- Modify `src/main/java/vip/mate/auth/sms/MockSmsCodeSender.java` — 去掉 `@Component`/`@ConditionalOnProperty`。
- Modify `src/main/java/vip/mate/auth/sms/SmsAutoConfiguration.java` — 新增工厂 `@Bean SmsCodeSender`。
- Modify `src/main/java/vip/mate/auth/sms/SmsStartupValidator.java` — provider 合法性 + 按 provider 校验密钥。
- Modify `src/main/java/vip/mate/auth/model/RegisterRequest.java` — 加 `code` 字段。
- Modify `src/main/java/vip/mate/auth/service/AuthService.java` — 注入 `VerificationCodeService`，注册时校验验证码。
- Modify `src/main/java/vip/mate/auth/controller/AuthController.java` — `send-register-code` 去 `@Deprecated`。
- Modify `src/main/resources/application.yml` — 加 `provider` 与 `tencent` 配置。

**后端测试**

- Create `src/test/java/vip/mate/auth/sms/TencentSmsCodeSenderTest.java`
- Create `src/test/java/vip/mate/auth/sms/SmsCodeSenderFactoryTest.java`
- Modify `src/test/java/vip/mate/auth/sms/SmsStartupValidatorTest.java`
- Modify `src/test/java/vip/mate/auth/sms/SmsPropertiesTest.java`
- Modify `src/test/java/vip/mate/auth/service/AuthServiceRegisterTest.java`
- （`AliyunSmsCodeSenderTest`、`AuthControllerRegisterTest`、`AuthControllerSendCodeTest` 经核对**不需要改动**，去注解/去 deprecation 不破坏它们。）

**前端（`mateclaw-ui/`）**

- Modify `src/api/index.ts` — `register` 类型加 `code`，新增 `sendRegisterCode`。
- Modify `src/i18n/locales/zh-CN.ts` + `src/i18n/locales/en-US.ts` — 新增 login 文案 key。
- Modify `src/views/Login.vue` — 验证码输入 + 获取验证码按钮 + 倒计时 + 提交带 code。

---

## Task 1: SmsProperties 增加 provider 与 Tencent 子配置

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/sms/SmsProperties.java`
- Test: `mateclaw-server/src/test/java/vip/mate/auth/sms/SmsPropertiesTest.java`

- [ ] **Step 1: 写失败测试** — 在 `SmsPropertiesTest` 末尾（`}` 之前）追加：

```java
    @Test
    void providerAndTencentDefaults() {
        SmsProperties p = new SmsProperties();
        assertEquals("aliyun", p.getProvider(), "provider 默认 aliyun");
        assertEquals("ap-guangzhou", p.getTencent().getRegion());
        assertEquals("+86", p.getTencent().getDefaultCountryCode());
        assertEquals("", p.getTencent().getSecretId());
        assertEquals("", p.getTencent().getSecretKey());
        assertEquals("", p.getTencent().getSdkAppId());
        assertEquals("", p.getTencent().getSignName());
        assertEquals("", p.getTencent().getTemplateId());
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=SmsPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`
（仓库根用系统 `mvn`，无 wrapper；`-pl mateclaw-server -am` 从根目录构建该模块及其上游 `mateclaw-plugin-api`。务必 JDK 21。下同。）
Expected: 编译失败 / FAIL — `getProvider()`/`getTencent()` 不存在。

- [ ] **Step 3: 实现** — 在 `SmsProperties.java` 中，把 `private Aliyun aliyun = new Aliyun();` 替换为下面这段（新增 `provider` 与 `tencent`，并在文件内 `Aliyun` 静态类之后追加 `Tencent` 静态类）：

把：
```java
    private Aliyun aliyun = new Aliyun();
```
改为：
```java
    /** 短信服务商：aliyun | tencent。启动时确定，切换需重启。 */
    private String provider = "aliyun";

    private Aliyun aliyun = new Aliyun();

    private Tencent tencent = new Tencent();
```

并在 `Aliyun` 静态类的右花括号 `}` 之后、`SmsProperties` 类结束 `}` 之前，追加：
```java
    @Data
    public static class Tencent {
        /** 仅环境变量注入，禁止入库。 */
        private String secretId = "";
        /** 仅环境变量注入，禁止入库。 */
        private String secretKey = "";
        /** 短信应用 SdkAppId（控制台），如 1400xxxxxx。 */
        private String sdkAppId = "";
        private String signName = "";
        private String templateId = "";
        /** 地域，默认广州。 */
        private String region = "ap-guangzhou";
        /** 裸号码默认补的国家码（E.164）。 */
        private String defaultCountryCode = "+86";
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=SmsPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（含原有 `defaultsAreFailSafe` 与新增 `providerAndTencentDefaults`）。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/sms/SmsProperties.java \
        mateclaw-server/src/test/java/vip/mate/auth/sms/SmsPropertiesTest.java
git commit -m "feat(sms): SmsProperties 增加 provider 与 tencent 子配置"
```

---

## Task 2: 引入腾讯云短信 SDK 依赖

**Files:**
- Modify: `mateclaw-server/pom.xml`（阿里云 SMS 依赖块之后，约 `pom.xml:148-156`）

- [ ] **Step 1: 添加依赖** — 在 `pom.xml` 里阿里云 `dysmsapi20170525` 依赖块（`</dependency>`）之后，紧接着追加：

```xml
<!-- ===== Tencent Cloud SMS (短信专用子包，非全量 tencentcloud-sdk-java) =====
     与阿里云 dysmsapi 并存，运行时由 mateclaw.sms.provider 选择。 -->
<dependency>
    <groupId>com.tencentcloudapi</groupId>
    <artifactId>tencentcloud-sdk-java-sms</artifactId>
    <version>3.1.1451</version>
</dependency>
```

> 版本说明：`3.1.1451` 为撰写时 Maven Central 稳定版；如解析失败，去 https://central.sonatype.com/artifact/com.tencentcloudapi/tencentcloud-sdk-java-sms 取当时最新 `3.1.x` 并替换。

- [ ] **Step 2: 验证依赖可解析、可编译**

Run: `mvn -q -pl mateclaw-server -am dependency:resolve -Dincludes=com.tencentcloudapi:tencentcloud-sdk-java-sms && mvn -q -pl mateclaw-server -am test-compile`
Expected: 依赖下载成功；`test-compile` 通过（无新代码，仅验证 pom 合法 + SDK 在 classpath）。

- [ ] **Step 3: 提交**

```bash
git add mateclaw-server/pom.xml
git commit -m "build(sms): 引入腾讯云短信 SDK tencentcloud-sdk-java-sms"
```

---

## Task 3: TencentSmsCodeSender（TDD）

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/auth/sms/TencentSmsCodeSender.java`
- Test: `mateclaw-server/src/test/java/vip/mate/auth/sms/TencentSmsCodeSenderTest.java`

- [ ] **Step 1: 写失败测试** — 新建 `TencentSmsCodeSenderTest.java`：

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=TencentSmsCodeSenderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 — `TencentSmsCodeSender` 不存在。

- [ ] **Step 3: 实现** — 新建 `TencentSmsCodeSender.java`：

```java
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=TencentSmsCodeSenderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 个用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/sms/TencentSmsCodeSender.java \
        mateclaw-server/src/test/java/vip/mate/auth/sms/TencentSmsCodeSenderTest.java
git commit -m "feat(sms): 新增腾讯云短信发送器 TencentSmsCodeSender"
```

---

## Task 4: 装配改为工厂 @Bean，发送器去类级条件注解

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/sms/AliyunSmsCodeSender.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/sms/MockSmsCodeSender.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/sms/SmsAutoConfiguration.java`
- Test: `mateclaw-server/src/test/java/vip/mate/auth/sms/SmsCodeSenderFactoryTest.java`

- [ ] **Step 1: 写失败测试** — 新建 `SmsCodeSenderFactoryTest.java`：

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=SmsCodeSenderFactoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 当前无工厂、且 `mock=false` 下 Aliyun 仍由 `@Component` 装配（`tencent`/非法 provider 用例不符预期）。

- [ ] **Step 3a: 去掉 AliyunSmsCodeSender 的类级条件注解** — 编辑 `AliyunSmsCodeSender.java`：

删除这两行 import：
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
```
把类声明上方的注解：
```java
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mateclaw.sms", name = "mock", havingValue = "false", matchIfMissing = true)
public class AliyunSmsCodeSender implements SmsCodeSender {
```
改为（保留 `@Slf4j`，去掉 `@Component`/`@ConditionalOnProperty`；**保留构造器上的 `@Autowired`**）：
```java
@Slf4j
public class AliyunSmsCodeSender implements SmsCodeSender {
```
> 保留 `@Autowired import` 与构造器注解不动 —— `AliyunSmsCodeSenderTest.springCanInstantiateAliyunSenderBean` 依赖它选对构造器。

- [ ] **Step 3b: 去掉 MockSmsCodeSender 的类级条件注解** — 编辑 `MockSmsCodeSender.java`：

删除这两行 import：
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
```
把：
```java
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mateclaw.sms", name = "mock", havingValue = "true")
public class MockSmsCodeSender implements SmsCodeSender {
```
改为：
```java
@Slf4j
public class MockSmsCodeSender implements SmsCodeSender {
```

- [ ] **Step 3c: 在 SmsAutoConfiguration 加工厂 @Bean** — 把 `SmsAutoConfiguration.java` 整体替换为：

```java
package vip.mate.auth.sms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短信模块自动配置。项目无 @ConfigurationPropertiesScan，必须显式 @EnableConfigurationProperties。
 * 发送器由工厂方法按 (mock, provider) 决断，保证容器内恒有且仅有一个 SmsCodeSender。
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(SmsProperties.class)
public class SmsAutoConfiguration {

    /**
     * 选择短信发送器：mock=true → Mock；否则按 provider 选 aliyun/tencent。
     * provider 经 trim().toLowerCase() 归一，对大小写/空格鲁棒（不依赖 SpEL 字符串比较）。
     * 非法 provider 直接 fail-fast（SmsStartupValidator 通常已先给出更友好的报错）。
     */
    @Bean
    public SmsCodeSender smsCodeSender(SmsProperties props) {
        if (props.isMock()) {
            return new MockSmsCodeSender();
        }
        String provider = props.getProvider() == null ? "aliyun" : props.getProvider().trim().toLowerCase();
        return switch (provider) {
            case "aliyun" -> new AliyunSmsCodeSender(props);
            case "tencent" -> new TencentSmsCodeSender(props);
            default -> throw new IllegalStateException(
                    "[SMS] 未知短信 provider: '" + props.getProvider() + "'，仅支持 aliyun / tencent");
        };
    }
}
```

- [ ] **Step 4: 运行工厂测试与既有发送器测试，确认全绿**

Run: `mvn -q -pl mateclaw-server -am test -Dtest='SmsCodeSenderFactoryTest,AliyunSmsCodeSenderTest,MockSmsCodeSender*,TencentSmsCodeSenderTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。重点：`SmsCodeSenderFactoryTest` 6 用例全绿；`AliyunSmsCodeSenderTest.springCanInstantiateAliyunSenderBean` 仍绿。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/sms/AliyunSmsCodeSender.java \
        mateclaw-server/src/main/java/vip/mate/auth/sms/MockSmsCodeSender.java \
        mateclaw-server/src/main/java/vip/mate/auth/sms/SmsAutoConfiguration.java \
        mateclaw-server/src/test/java/vip/mate/auth/sms/SmsCodeSenderFactoryTest.java
git commit -m "refactor(sms): 发送器装配改为工厂 @Bean，按 mock/provider 选择"
```

---

## Task 5: SmsStartupValidator 校验 provider 与对应密钥

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/sms/SmsStartupValidator.java`
- Test: `mateclaw-server/src/test/java/vip/mate/auth/sms/SmsStartupValidatorTest.java`

- [ ] **Step 1: 写失败测试** — 在 `SmsStartupValidatorTest` 末尾 `}` 之前追加：

```java
    @Test
    void illegalProviderFailsFast() {
        SmsProperties p = new SmsProperties();
        p.setProvider("nexmo");
        p.getAliyun().setAccessKeyId("ak");
        p.getAliyun().setAccessKeySecret("sk");
        SmsStartupValidator v = new SmsStartupValidator(p, env("prod"));
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void tencentProviderWithEmptyKeysFailsFast() {
        SmsProperties p = new SmsProperties();
        p.setProvider("tencent");
        SmsStartupValidator v = new SmsStartupValidator(p, env("prod"));
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void tencentProviderWithAllKeysOk() {
        SmsProperties p = new SmsProperties();
        p.setProvider("Tencent"); // 大小写不敏感
        p.getTencent().setSecretId("sid");
        p.getTencent().setSecretKey("skey");
        p.getTencent().setSdkAppId("1400000000");
        p.getTencent().setTemplateId("2000001");
        p.getTencent().setSignName("迪伍科技");
        SmsStartupValidator v = new SmsStartupValidator(p, env("prod"));
        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    void aliyunProviderStillChecksAliyunKeys() {
        SmsProperties p = new SmsProperties();
        p.setProvider("aliyun");
        p.getTencent().setSecretId("sid"); // 配了腾讯也不顶用
        SmsStartupValidator v = new SmsStartupValidator(p, env("dev"));
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }
```

> 注：原有 `mockFalseWithKeysOk` / `mockFalseWithEmptyKeysFailsFast` 在默认 `provider=aliyun` 下行为不变，无需改动。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=SmsStartupValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `tencentProviderWithEmptyKeysFailsFast`/`illegalProviderFailsFast` 未拒绝（当前只查阿里云）。

- [ ] **Step 3: 实现** — 把 `SmsStartupValidator.afterPropertiesSet()` 中的 `if (!props.isMock()) { ... }` 分支替换为按 provider 校验。即把：

```java
        if (!props.isMock()) {
            if (isBlank(props.getAliyun().getAccessKeyId()) || isBlank(props.getAliyun().getAccessKeySecret())) {
                throw new IllegalStateException(
                        "[SMS] mock=false 但阿里云 AccessKey/Secret 未配置（ALIYUN_SMS_AK/SK），拒绝启动");
            }
        } else {
            log.warn("==== [SMS] mock=true：短信走 Mock 不真实发送，万能码可用。仅限非生产环境！ ====");
        }
```

替换为：

```java
        if (!props.isMock()) {
            String provider = props.getProvider() == null ? "aliyun" : props.getProvider().trim().toLowerCase();
            switch (provider) {
                case "aliyun" -> {
                    if (isBlank(props.getAliyun().getAccessKeyId()) || isBlank(props.getAliyun().getAccessKeySecret())) {
                        throw new IllegalStateException(
                                "[SMS] provider=aliyun 但 AccessKey/Secret 未配置（ALIYUN_SMS_AK/SK），拒绝启动");
                    }
                }
                case "tencent" -> {
                    SmsProperties.Tencent t = props.getTencent();
                    if (isBlank(t.getSecretId()) || isBlank(t.getSecretKey()) || isBlank(t.getSdkAppId())
                            || isBlank(t.getTemplateId()) || isBlank(t.getSignName())) {
                        throw new IllegalStateException(
                                "[SMS] provider=tencent 但凭据不全（需 TENCENT_SMS_SECRET_ID/SECRET_KEY/SDK_APP_ID"
                                + "/TEMPLATE_ID/SIGN_NAME），拒绝启动");
                    }
                }
                default -> throw new IllegalStateException(
                        "[SMS] 未知短信 provider: '" + props.getProvider() + "'，仅支持 aliyun / tencent");
            }
        } else {
            log.warn("==== [SMS] mock=true：短信走 Mock 不真实发送，万能码可用。仅限非生产环境！ ====");
        }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=SmsStartupValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（原 5 + 新 4 = 9 用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/sms/SmsStartupValidator.java \
        mateclaw-server/src/test/java/vip/mate/auth/sms/SmsStartupValidatorTest.java
git commit -m "feat(sms): 启动校验 provider 合法性并按 provider 校验密钥"
```

---

## Task 6: RegisterRequest 增加 code 字段

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/model/RegisterRequest.java`

- [ ] **Step 1: 实现** — 把 `RegisterRequest.java` 的类体改为：

```java
@Data
public class RegisterRequest {
    private String phone;
    private String password;
    private String nickname;
    /** 手机号短信验证码（注册强制必填）。 */
    private String code;
}
```

- [ ] **Step 2: 编译确认通过**

Run: `mvn -q -pl mateclaw-server -am test-compile`
Expected: 通过（无行为变化，供 Task 7 使用）。

- [ ] **Step 3: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/model/RegisterRequest.java
git commit -m "feat(auth): RegisterRequest 增加 code 字段"
```

---

## Task 7: AuthService.register 强制校验验证码

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/service/AuthService.java`
- Test: `mateclaw-server/src/test/java/vip/mate/auth/service/AuthServiceRegisterTest.java`

- [ ] **Step 1: 先改测试（TDD：让现有测试反映新契约 + 加新用例）** — 编辑 `AuthServiceRegisterTest.java`：

(a) 新增 import（在现有 `import static org.mockito.Mockito.when;` 附近补）：
```java
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
```
并补类型 import：
```java
import vip.mate.auth.sms.VerificationCodeService;
```

(b) 在 `@Mock private AccountEntitlementService entitlementService;` 之后新增：
```java
    @Mock
    private VerificationCodeService verificationCodeService;
```

(c) 把 `validRequest()` 改为带 code：
```java
    private RegisterRequest validRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setPhone("13800138000");
        request.setPassword("pass1234");
        request.setCode("123456");
        return request;
    }
```

(d) 三个"前置失败"用例的 `verifyNoInteractions(...)` 加上 `verificationCodeService`：
- `registerRejectsInvalidPhone`：`verifyNoInteractions(userMapper, passwordEncoder, workspaceService, verificationCodeService);`
- `registerRejectsBlankPassword`：`verifyNoInteractions(userMapper, passwordEncoder, workspaceService, verificationCodeService);`（替换原 `verify(userMapper, never()).insert(...)` 之外，把 `verifyNoInteractions(workspaceService)` 行改为这一行；保留断言 msgKey）

> 精确做法：把 `registerRejectsBlankPassword`、`registerRejectsShortPassword` 末尾的
> `verify(userMapper, never()).insert(any(UserEntity.class));` 与 `verifyNoInteractions(workspaceService);`
> 两行，替换为单行 `verifyNoInteractions(userMapper, passwordEncoder, workspaceService, verificationCodeService);`

(e) 把 `registerRejectsDuplicatePhone` 改为（断言唯一性命中时**不消费**验证码）：
```java
    @Test
    void registerRejectsDuplicatePhoneWithoutConsumingCode() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.username_exists", ex.getMsgKey());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(verificationCodeService, never()).verifyAndConsume(anyString(), anyString());
        verifyNoInteractions(workspaceService);
    }
```

(f) 新增两个用例（放在 `registerRejectsShortPassword` 之后）：
```java
    @Test
    void registerRejectsMissingCode() {
        RegisterRequest request = validRequest();
        request.setCode("  ");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.verification_code_required", ex.getMsgKey());
        verifyNoInteractions(userMapper, passwordEncoder, workspaceService, verificationCodeService);
    }

    @Test
    void registerRejectsWrongCode() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        doThrow(new MateClawException("err.auth.invalid_verification_code", 400, "验证码错误"))
                .when(verificationCodeService).verifyAndConsume("13800138000", "123456");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.invalid_verification_code", ex.getMsgKey());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verifyNoInteractions(workspaceService);
    }
```

(g) 在两个成功路径用例里，确认 happy path 会调用 `verifyAndConsume`（可选断言，放在 `registerCreatesUserDedicatedWorkspaceAndReturnsToken` 末尾）：
```java
        verify(verificationCodeService).verifyAndConsume("13800138000", "123456");
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=AuthServiceRegisterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`AuthService` 无 `verificationCodeService` 构造参数 → `@InjectMocks` 不匹配）或断言失败。

- [ ] **Step 3: 实现** — 编辑 `AuthService.java`：

(a) 加 import：
```java
import vip.mate.auth.sms.VerificationCodeService;
```

(b) 在字段 `private final AccountEntitlementService accountEntitlementService;` 之后新增：
```java
    private final VerificationCodeService verificationCodeService;
```

(c) 把 `register(...)` 方法体中"密码长度校验之后、`Long count = ...` 唯一性查询之前"插入验证码非空校验，并在唯一性检查通过后、`new UserEntity()` 之前插入消费验证码。即把：

```java
        if (request.getPassword().trim().length() < 6) {
            throw new MateClawException("err.auth.password_too_short", 400, "密码长度至少6位");
        }

        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, phone));
        if (count > 0) {
            throw new MateClawException("err.auth.username_exists", 409, "手机号已注册: " + phone);
        }

        UserEntity user = new UserEntity();
```

替换为：

```java
        if (request.getPassword().trim().length() < 6) {
            throw new MateClawException("err.auth.password_too_short", 400, "密码长度至少6位");
        }
        String code = request.getCode() == null ? "" : request.getCode().trim();
        if (code.isEmpty()) {
            throw new MateClawException("err.auth.verification_code_required", 400, "请输入验证码");
        }

        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, phone));
        if (count > 0) {
            throw new MateClawException("err.auth.username_exists", 409, "手机号已注册: " + phone);
        }

        // 唯一性通过后再消费验证码，避免对已注册号码白白消费一个有效码。
        verificationCodeService.verifyAndConsume(phone, code);

        UserEntity user = new UserEntity();
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=AuthServiceRegisterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（含新增的 missing/wrong code、不消费码等用例）。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/service/AuthService.java \
        mateclaw-server/src/test/java/vip/mate/auth/service/AuthServiceRegisterTest.java
git commit -m "feat(auth): 注册强制校验手机号短信验证码"
```

---

## Task 8: 重新启用 send-register-code 端点

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/auth/controller/AuthController.java`

- [ ] **Step 1: 去掉 @Deprecated** — 把 `AuthController.java` 中：

```java
    @Deprecated // 注册已不再需要验证码，保留以便日后做"可选验证码"开关。
    @Operation(summary = "发送注册验证码")
    @PostMapping("/send-register-code")
```

改为：

```java
    @Operation(summary = "发送注册验证码")
    @PostMapping("/send-register-code")
```

- [ ] **Step 2: 运行相关控制器测试确认仍绿**

Run: `mvn -q -pl mateclaw-server -am test -Dtest='AuthControllerSendCodeTest,AuthControllerRegisterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（行为未变，仅去掉弃用标记）。

- [ ] **Step 3: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/auth/controller/AuthController.java
git commit -m "feat(auth): 重新启用 send-register-code 端点（注册验证码回归）"
```

---

## Task 9: application.yml 增加 provider 与 tencent 配置

**Files:**
- Modify: `mateclaw-server/src/main/resources/application.yml`（`mateclaw.sms` 段，约 `:130-145`）

- [ ] **Step 1: 改配置** — 在 `mateclaw.sms` 段，`mock: ...` 之后新增 `provider`，并在 `aliyun:` 块之后新增 `tencent:` 块。即把：

```yaml
  sms:
    mock: ${SMS_MOCK:false}                         # fail-safe；生产强制 false（启动校验）
    universal-code: ${SMS_UNIVERSAL_CODE:888888}    # 仅 mock=true 生效
```

改为：

```yaml
  sms:
    mock: ${SMS_MOCK:false}                         # fail-safe；生产强制 false（启动校验）
    provider: ${SMS_PROVIDER:aliyun}                # 短信服务商：aliyun | tencent（启动时确定）
    universal-code: ${SMS_UNIVERSAL_CODE:888888}    # 仅 mock=true 生效
```

并在 `aliyun:` 块（以 `template-code: ...` 结尾那行）之后、`# 搜索配置已迁移...` 注释之前，新增：

```yaml
    tencent:
      secret-id: ${TENCENT_SMS_SECRET_ID:}                  # 仅环境变量，禁止入库
      secret-key: ${TENCENT_SMS_SECRET_KEY:}                # 仅环境变量，禁止入库
      sdk-app-id: ${TENCENT_SMS_SDK_APP_ID:}
      sign-name: ${TENCENT_SMS_SIGN_NAME:}
      template-id: ${TENCENT_SMS_TEMPLATE_ID:}
      region: ${TENCENT_SMS_REGION:ap-guangzhou}
      default-country-code: ${TENCENT_SMS_CC:+86}
```

- [ ] **Step 2: 校验 yml 可加载（启动到上下文构建即可）**

Run: `mvn -q -pl mateclaw-server -am test -Dtest=SmsPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（确保未破坏属性绑定；yml 语法错误会在含上下文的测试中暴露）。

- [ ] **Step 3: 提交**

```bash
git add mateclaw-server/src/main/resources/application.yml
git commit -m "chore(sms): application.yml 增加 provider 与腾讯云配置占位"
```

---

## Task 10: 前端 api — register 带 code + sendRegisterCode

**Files:**
- Modify: `mateclaw-ui/src/api/index.ts`（`authApi`，约 `:124-134`）

- [ ] **Step 1: 改 authApi** — 把：

```ts
  register: (data: { phone: string; password: string; nickname?: string }) =>
    http.post<LoginResponse>('/auth/register', data),
  me: () => http.get<AccountStatus>('/auth/me'),
```

改为：

```ts
  register: (data: { phone: string; password: string; code: string; nickname?: string }) =>
    http.post<LoginResponse>('/auth/register', data),
  sendRegisterCode: (data: { phone: string }) =>
    http.post('/auth/send-register-code', data),
  me: () => http.get<AccountStatus>('/auth/me'),
```

- [ ] **Step 2: 类型检查**

Run: `cd mateclaw-ui && npx vue-tsc --noEmit`
Expected: 此时 `Login.vue` 调用 `authApi.register` 缺 `code` 会**报错**（预期，Task 12 修）。先确认 `api/index.ts` 本身无语法/类型错误（错误仅来自 Login.vue）。

- [ ] **Step 3: 提交**

```bash
git add mateclaw-ui/src/api/index.ts
git commit -m "feat(ui-api): register 入参加 code，新增 sendRegisterCode"
```

---

## Task 11: 前端 i18n — 新增验证码相关文案

**Files:**
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`（login 段，约 `:2792-2814`）
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`（login 段，约 `:2780-2802`）

- [ ] **Step 1: 改 zh-CN** — 在 `login.fields` 里 `nickname: '昵称',` 之后加 `code: '验证码',`；在 `login.placeholders` 里 `nickname: '昵称（可选）',` 之后加 `code: '短信验证码',`；在 `login` 段 `passwordMismatch: '两次输入的密码不一致',` 之后、`login` 段闭合 `},` 之前，加三行：

```ts
    getCode: '获取验证码',
    resendCountdown: '{n}s 后重发',
    codeRequired: '请输入验证码',
```

- [ ] **Step 2: 改 en-US** — 对称地，在 `login.fields` 里 `nickname: 'Nickname',` 之后加 `code: 'Verification code',`；在 `login.placeholders` 里 `nickname: 'Nickname (optional)',` 之后加 `code: 'SMS code',`；在 `passwordMismatch: 'The two passwords do not match',` 之后加：

```ts
    getCode: 'Get code',
    resendCountdown: 'Resend in {n}s',
    codeRequired: 'Please enter the code',
```

- [ ] **Step 3: 类型检查（仅确认 i18n 文件无语法错误）**

Run: `cd mateclaw-ui && npx vue-tsc --noEmit 2>&1 | grep -E 'zh-CN|en-US' || echo 'no i18n errors'`
Expected: 输出 `no i18n errors`（Login.vue 的报错仍在，下个任务修）。

- [ ] **Step 4: 提交**

```bash
git add mateclaw-ui/src/i18n/locales/zh-CN.ts mateclaw-ui/src/i18n/locales/en-US.ts
git commit -m "feat(ui-i18n): 新增注册验证码相关文案"
```

---

## Task 12: Login.vue — 验证码输入 + 获取按钮 + 倒计时

**Files:**
- Modify: `mateclaw-ui/src/views/Login.vue`

- [ ] **Step 1: 模板加验证码行** — 在 register 模式的手机号输入块（`<div v-else class="input-wrap">...</div>`，约 `:45-55`）之后、共享密码块（`<div class="input-wrap">` 含 `v-model="activePassword"`，约 `:57`）之前，插入：

```html
        <div v-if="mode === 'register'" class="input-wrap code-row">
          <input
            v-model="registerForm.code"
            type="text"
            inputmode="numeric"
            class="form-input"
            :placeholder="t('login.placeholders.code')"
            :aria-label="t('login.fields.code')"
            autocomplete="one-time-code"
            required
          />
          <button
            type="button"
            class="get-code-btn"
            :disabled="codeCountdown > 0 || sendingCode"
            @click="sendCode"
          >
            {{ codeCountdown > 0 ? t('login.resendCountdown', { n: codeCountdown }) : t('login.getCode') }}
          </button>
        </div>
```

- [ ] **Step 2: 脚本加状态与逻辑** — 编辑 `<script setup>`：

(a) 把 import 行 `import { computed, reactive, ref } from 'vue'` 改为：
```ts
import { computed, onUnmounted, reactive, ref } from 'vue'
```

(b) 把 `const registerForm = reactive({ phone: '', password: '', confirmPassword: '', nickname: '' })` 改为：
```ts
const registerForm = reactive({ phone: '', password: '', confirmPassword: '', nickname: '', code: '' })
const codeCountdown = ref(0)
const sendingCode = ref(false)
let countdownTimer: ReturnType<typeof setInterval> | null = null
```

(c) 在 `switchMode` 函数之后新增 `sendCode` 与清理逻辑：
```ts
async function sendCode() {
  if (codeCountdown.value > 0 || sendingCode.value) return
  const phone = registerForm.phone.trim()
  if (!phone) {
    errorMsg.value = t('login.placeholders.phone')
    return
  }
  sendingCode.value = true
  errorMsg.value = ''
  try {
    await authApi.sendRegisterCode({ phone })
    codeCountdown.value = 60
    countdownTimer = setInterval(() => {
      codeCountdown.value -= 1
      if (codeCountdown.value <= 0 && countdownTimer) {
        clearInterval(countdownTimer)
        countdownTimer = null
      }
    }, 1000)
  } catch (e: any) {
    errorMsg.value = e?.message || t('login.registerFailed')
  } finally {
    sendingCode.value = false
  }
}

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})
```

(d) 把 `handleRegister` 的首行守卫与 payload 改为带 code。把：
```ts
  if (!registerForm.phone || !registerForm.password || !registerForm.confirmPassword) return
```
改为：
```ts
  if (!registerForm.phone || !registerForm.password || !registerForm.confirmPassword) return
  if (!registerForm.code.trim()) {
    errorMsg.value = t('login.codeRequired')
    return
  }
```
并把 payload：
```ts
    const payload = {
      phone: registerForm.phone,
      password: registerForm.password,
      nickname: registerForm.nickname || undefined,
    }
```
改为：
```ts
    const payload = {
      phone: registerForm.phone,
      password: registerForm.password,
      code: registerForm.code.trim(),
      nickname: registerForm.nickname || undefined,
    }
```

- [ ] **Step 3: 加样式** — 在 `<style scoped>` 内（如 `.input-wrap { ... }` 之后）追加：

```css
.code-row {
  display: flex;
  gap: 8px;
}

.code-row .form-input {
  flex: 1;
}

.get-code-btn {
  flex-shrink: 0;
  padding: 0 14px;
  border: 1.5px solid var(--mc-border);
  border-radius: 12px;
  background: var(--mc-bg-sunken);
  color: var(--mc-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.2s, color 0.2s, opacity 0.2s;
}

.get-code-btn:hover:not(:disabled) {
  border-color: var(--mc-primary);
}

.get-code-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
```

- [ ] **Step 4: 类型检查通过**

Run: `cd mateclaw-ui && npx vue-tsc --noEmit`
Expected: 通过（`Login.vue` 不再报 `code` 缺失；全仓库类型干净，除既有无关报错）。
> 注（worktree）：`pnpm build` 因缺脚本会中止，**以 `vue-tsc --noEmit` 为准**；`onboardingBrand` 相关测试为已知失败，与本改动无关。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-ui/src/views/Login.vue
git commit -m "feat(ui): 注册表单增加短信验证码输入与获取按钮（60s 倒计时）"
```

---

## Task 13: 全量回归与收尾

- [ ] **Step 1: 后端 sms + auth 全量测试**

Run: `mvn -q -pl mateclaw-server -am test -Dtest='vip.mate.auth.**' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿（含 `SmsCodeSenderFactoryTest`、`TencentSmsCodeSenderTest`、`SmsStartupValidatorTest`、`SmsPropertiesTest`、`AuthServiceRegisterTest`、`AliyunSmsCodeSenderTest`、`AuthController*Test`、`VerificationCodeServiceTest` 等）。

- [ ] **Step 2: 后端整模块编译 + 测试（确保无连带破坏）**

Run: `mvn -q -pl mateclaw-server -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS（如个别既有无关失败用例，记录但不在本次范围；本次新增/修改的用例必须全绿）。

- [ ] **Step 3: 前端类型检查**

Run: `cd mateclaw-ui && npx vue-tsc --noEmit`
Expected: 通过。

- [ ] **Step 4: 前端预览验证（preview 工具）**

启动 dev server，打开登录页 → 切到"注册" → 确认出现"验证码 + 获取验证码"行；点击"获取验证码"在 mock 关闭时应展示后端错误（或在 `SMS_MOCK=true` 下成功并进入 60s 倒计时）。用 preview_snapshot/preview_screenshot 留证。
> dev 旁路前提：要用 `888888` 万能码，需后端以 `SMS_MOCK=true` 启动；默认 `dev,mysql` profile 不开 mock。

- [ ] **Step 5: 结束开发分支**

调用 superpowers:finishing-a-development-branch 决定合并/PR/清理。

---

## Self-Review（计划作者自检）

- **Spec 覆盖**：腾讯发送器(Task 3)、provider 切换/工厂(Task 1,4)、启动校验(Task 5)、注册强制验证码(Task 6,7)、端点重启用(Task 8)、yml(Task 9)、前端 api/i18n/UI(Task 10,11,12)、dev 旁路前提(Task 13 Step4 备注) —— 均有对应任务。无 DB 迁移（符合 spec）。
- **占位符**：仅腾讯 SDK 版本 `3.1.1451` 给了确定值与回退指引；无 TBD/TODO。
- **类型/签名一致性**：`verifyAndConsume(phone, code)`、`sendRegisterCode(phone)`(前端)/`sendRegisterCode(phone, ip)`(后端 VCS)、`TencentSmsCodeSender(SmsProperties[, SmsClient])`、`smsCodeSender(SmsProperties)`、成功码常量 `"Ok"` —— 前后任务一致。
- **既有测试**：`AuthServiceRegisterTest` 已在 Task 7 同步改造；`AliyunSmsCodeSenderTest` 的 `withBean` 装配回归因保留 `@Autowired` 构造器不受影响；controller 两测试无需改。
