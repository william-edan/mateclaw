# 设计：腾讯云短信发送 + 注册手机号短信验证

日期：2026-06-25
分支：claude/naughty-joliot-a96893

> 本稿已经过多代理对抗式核查（代码库事实 / Spring 装配 / 腾讯 SDK 真实 API / 规格一致性），并据核查结论修订：装配方案由 `@ConditionalOnExpression` 改为**工厂 `@Bean`**，dev 旁路前提、腾讯成功码大小写、i18n key、限流/迁移确认等均已落定。

## 背景与现状

现有短信能力集中在 `mateclaw-server/src/main/java/vip/mate/auth/sms/`：

- `SmsCodeSender`（接口）：`void send(String phone, String code)`，屏蔽具体服务商，失败抛 `SmsSendException`。
- `AliyunSmsCodeSender`（`@Component`）：阿里云 dysmsapi 实现。`@ConditionalOnProperty(prefix="mateclaw.sms", name="mock", havingValue="false", matchIfMissing=true)` —— `mock=false`（默认）时装配。Client 懒加载（空密钥不在 Bean 创建期抛栈）。成功码判定为大写 `"OK"`（`AliyunSmsCodeSender.java:71`）。
- `MockSmsCodeSender`（`@Component`）：`@ConditionalOnProperty(... mock, havingValue="true")`，仅打日志。
- `VerificationCodeService`：生成 6 位码、多维原子限流（手机号/IP/全局 + 60s 重发锁）、发送、`verifyAndConsume(phone,code)` 一次性消费校验、`releaseSendLock(phone)` 补偿。注入**单个** `SmsCodeSender sender`（`VerificationCodeService.java:25`）。这三个方法**均未**标 `@Deprecated`。
- `VerificationCodeStore` / `InMemoryVerificationCodeStore`：进程内内存存储（符合"避免 Redis、保桌面形态"约束）。
- `SmsProperties`（`@ConfigurationProperties(prefix="mateclaw.sms")`）：限流参数 + 内嵌 `Aliyun { accessKeyId, accessKeySecret, endpoint, signName, templateCode }`。密钥**仅环境变量注入、禁止入库**。
- `SmsAutoConfiguration`：`@EnableConfigurationProperties(SmsProperties.class)`（当前为空配置类）。
- `SmsStartupValidator`（`@Component implements InitializingBean`）：启动期硬校验。当前逻辑：prod profile 禁止 `mock=true`；`mock=false` 时校验阿里云 AK/SK 齐全。**当前尚无 provider 合法性校验**。

注册现状：

- `AuthController.register` → `AuthService.register(RegisterRequest)`。`RegisterRequest { phone, password, nickname }`，**无验证码字段**。
- `AuthService.register` 校验顺序（`AuthService.java`）：手机号格式（`PhoneNumbers`）→ 密码非空 → 密码 ≥6 位 → 手机号唯一（`username` 列存手机号，`AuthService.java:84-88`）→ 插库（`DuplicateKeyException` 兜底 `:101`）→ 建 Workspace。**当前 `AuthService` 未注入 `VerificationCodeService`**。
- `AuthController.send-register-code` 端点**标了** `@Deprecated`（`AuthController.java:53`，注释原文："注册已不再需要验证码，保留以便日后做\"可选验证码\"开关。"）；它内部已做"非法号码 400 / 已注册 409"前置校验，并调用 `verificationCodeService.sendRegisterCode(phone, ip)`。
- `SecurityConfig`：`/api/v1/auth/register`、`/api/v1/auth/send-register-code` 均 `permitAll`（匿名可调，`SecurityConfig.java:59-60`）；限流全靠 `VerificationCodeService`。
- 前端 `mateclaw-ui/src/views/Login.vue` register tab 仅有 手机号/密码/确认密码/昵称，**无验证码输入框与"获取验证码"按钮**。`authApi.register({phone,password,nickname})`（`api/index.ts:127`，无 `sendRegisterCode`）。后端 `MateClawException` 的 msg 经 `e?.message` 直接展示（`Login.vue:221`）。

配置体系：yml `mateclaw.sms.*`（密钥走 env）+ 数据库 `mate_system_setting`（`SystemSettingService`）。**本需求经决策走 yml/env，不动 `mate_system_setting`。** 默认激活 profile：`dev,mysql`（`application.yml:20`）；存在 `application-desktop.yml`、`application-mysql.yml`，**均未覆盖 `mateclaw.sms.mock`**，故默认 `mock=${SMS_MOCK:false}=false`。

技术栈：Spring Boot 3.5.14、MyBatis-Plus 3.5.16、JDK 21、Flyway。

## 目标

1. 新增腾讯云短信发送实现，与阿里云并存。
2. 后端配置（yml/环境变量）可选择用阿里云还是腾讯云发送：`mateclaw.sms.provider=aliyun|tencent`，启动时确定。
3. 注册时强制手机号短信验证码校验：注册接口必须带正确验证码才放行。
4. dev/集成环境可通过显式开启 mock（`mateclaw.sms.mock=true`）以万能码 `888888` 旁路真实发送（见"dev 旁路前提"）。

## 决策（已与用户确认）

- **provider 切换位置**：后端 yml/环境变量（非后台 UI、不入 `mate_system_setting`）。启动时确定，切换服务商需重启。
- **注册校验强度**：强制必填。

## 架构

复用 `SmsCodeSender` 接口，仅新增一个实现 + 一个配置开关。运行时只有**一个** `SmsCodeSender` Bean，`VerificationCodeService` 注入完全不变（最小爆破面）。

三个发送器按 `(mock, provider)` 选择：

| 条件 | 实现 |
|---|---|
| `mock=true` | `MockSmsCodeSender`（不变）|
| `mock=false` 且 `provider=aliyun`（或缺省/null）| `AliyunSmsCodeSender` |
| `mock=false` 且 `provider=tencent` | `TencentSmsCodeSender`（新增）|
| `mock=false` 且 `provider` 非法 | 启动失败（见校验）|

### 选择手段：工厂 `@Bean`（不用 `@ConditionalOnExpression`）

> 核查发现 `@ConditionalOnExpression` 内是 SpEL **精确字符串比较**，不享受 Spring relaxed binding：若运维设 `SMS_MOCK=FALSE`（大小写变体），SpEL 比较 `'FALSE'=='false'` 不成立 → Aliyun/Tencent 条件双双落空 → **0 个真实发送器**；而 `@ConfigurationProperties` 的 boolean 仍把 `FALSE` 绑成 `false`，导致 `SmsStartupValidator` 兜底失效、运行期 `NoSuchBean`。故弃用表达式条件，改由 Java 代码在工厂里决断。

在 `SmsAutoConfiguration` 用单个工厂方法产出唯一 `SmsCodeSender`，由 Java 决定、对取值做归一化：

```java
@Bean
public SmsCodeSender smsCodeSender(SmsProperties props) {
    if (props.isMock()) {
        return new MockSmsCodeSender();
    }
    String provider = props.getProvider() == null ? "aliyun" : props.getProvider().trim().toLowerCase();
    return switch (provider) {
        case "aliyun" -> new AliyunSmsCodeSender(props);
        case "tencent" -> new TencentSmsCodeSender(props);
        // 防御性兜底：正常情况下 SmsStartupValidator 已先给出友好报错
        default -> throw new IllegalStateException(
                "[SMS] 未知短信 provider: '" + props.getProvider() + "'，仅支持 aliyun / tencent");
    };
}
```

配套改动：三个发送器类**去掉类级 `@Component` 与 `@ConditionalOnProperty`**，改为由工厂 `new` 出来的普通类（保留各自构造器；Aliyun 已有的包级测试构造器保留）。这样：

- 任意合法配置下容器内**恒有且仅有一个** `SmsCodeSender` Bean，杜绝 0 个 / 多个；
- `mock` 走绑定后的布尔 `isMock()`、`provider` 经 `trim().toLowerCase()` 归一，对 `FALSE`/`Tencent`/含空格等变体鲁棒；
- 非法 provider 在工厂处 fail-fast，且因工厂 Bean 是 `VerificationCodeService` 的依赖、创建时机靠前，报错信息得以保留（不会被 `NoSuchBean` 掩盖）。

## 后端改动

### 依赖（`mateclaw-server/pom.xml`）

新增腾讯云 SMS **专用子包**（非全量 `tencentcloud-sdk-java`，控制体积），版本显式 pin（遵循本仓依赖纪律，勿留空白 `<version>`）：

```xml
<!-- ===== Tencent Cloud SMS (短信专用子包，非全量 SDK) =====
     与阿里云 dysmsapi 并存，由 mateclaw.sms.provider 选择。 -->
<dependency>
    <groupId>com.tencentcloudapi</groupId>
    <artifactId>tencentcloud-sdk-java-sms</artifactId>
    <version>3.1.1451</version>  <!-- 撰写时 Maven Central 最新稳定；落地时可取当时最新 3.1.x -->
</dependency>
```

来源核实：`com.tencentcloudapi:tencentcloud-sdk-java-sms` 确为存在于 Maven Central 的短信子包；短信 v3 包路径 `com.tencentcloudapi.sms.v20210111`。

### `SmsProperties` 新增

```java
/** 短信服务商：aliyun | tencent。启动时确定，切换需重启。 */
private String provider = "aliyun";

private Tencent tencent = new Tencent();

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

### 新增 `TencentSmsCodeSender implements SmsCodeSender`

位置：`mateclaw-server/src/main/java/vip/mate/auth/sms/TencentSmsCodeSender.java`。结构照搬 `AliyunSmsCodeSender`（懒加载 client、日志脱敏、异常映射）。

腾讯云 SMS v3（`com.tencentcloudapi.sms.v20210111`）调用要点（已对官方源码/文档核实）：

```java
Credential cred = new Credential(secretId, secretKey);
SmsClient client = new SmsClient(cred, region);            // 两参构造合法；如需自定义超时/接入点再用三参 ClientProfile 重载
SendSmsRequest req = new SendSmsRequest();
req.setSmsSdkAppId(sdkAppId);
req.setSignName(signName);
req.setTemplateId(templateId);
req.setTemplateParamSet(new String[]{ code });             // 位置参数数组，模板内写 {1}
req.setPhoneNumberSet(new String[]{ toE164(phone) });      // 必须带国家码 +86...
SendSmsResponse resp = client.SendSms(req);
// resp.getSendStatusSet()[0].getCode() 期望字符串 "Ok"，否则抛 SmsSendException
```

与阿里云的关键差异（实现必须处理）：

1. **号码格式**：腾讯要求 E.164 带国家码；阿里云收裸号。注册号码经 `PhoneNumbers`（正则 `^\+?\d{6,20}$`，允许可选 `+` 与 6–20 位）→ `toE164`：以 `+` 开头按原样；否则前缀 `tencent.defaultCountryCode`（默认 `+86`）。
2. **模板参数**：位置数组 `templateParamSet=[code]`（模板里 `{1}`），区别于阿里命名 JSON `{"code":...}`。
3. **成功码大小写不同（易踩坑）**：阿里云成功码是大写 `"OK"`，腾讯云是首字母大写 `"Ok"`。**不要复用阿里云的 `"OK"` 常量比较**，否则永远判失败。用腾讯各自的常量（或 `equalsIgnoreCase`），并在测试里固化断言。其余（非 `"Ok"` / `SendStatusSet` 为空或 null）一律抛 `SmsSendException`，日志脱敏手机号（保留前 3 后 4）。
4. **客户端初始化失败**（凭据/网络）：捕获并包成 `SmsSendException`，与阿里云一致。

为单测可注入：提供包级构造器 `TencentSmsCodeSender(SmsProperties props, SmsClient client)` 跳过懒加载（对应阿里云已有的测试构造器）。

### `SmsStartupValidator` 扩展（友好启动门）

`SmsStartupValidator` 不依赖任何发送器、作为 `InitializingBean` 在启动期校验，给出聚合的友好报错（与工厂的防御性 throw 互补）。`mock=false` 时：

- 先校验 `provider`（`trim().toLowerCase()` 后）∈ `{aliyun, tencent}`，否则抛 `IllegalStateException` 拒绝启动并指明非法值。
- `provider=aliyun`：校验 `aliyun.accessKeyId` / `accessKeySecret` 非空（现有逻辑）。
- `provider=tencent`：校验 `tencent.secretId` / `secretKey` / `sdkAppId` / `templateId` / `signName` 均非空，缺任一拒绝启动并明确缺项。

prod profile 禁止 `mock=true` 的现有规则保留不变。

### 注册流程接回验证码

- `RegisterRequest` 增加字段 `String code`（仅 DTO，不入库）。
- `AuthService` 注入 `VerificationCodeService`。`register` 调整为按以下**顺序**校验（把现有唯一性检查从"密码校验后"前移到"消费验证码前"）：
  1. 手机号 `normalize` + `isValid`（不变）
  2. 密码非空 + ≥6 位（不变）
  3. **验证码非空**（缺失 → `MateClawException("err.auth.verification_code_required", 400, "请输入验证码")`）
  4. **手机号唯一性检查**（命中复用现有 `MateClawException("err.auth.username_exists", 409, ...)`，与 `AuthService.java:87` 一致）—— 先于消费验证码，避免对已注册号码白白消费有效码
  5. `verificationCodeService.verifyAndConsume(phone, code)`（错误/过期/超次数由其抛 400）
  6. 落库（保留 `DuplicateKeyException` 兜底）+ 建 Workspace（不变）
- `AuthController.send-register-code`：去掉 `@Deprecated`，重新启用（前端用它请求验证码）。原有"非法号码 400 / 已注册 409"前置校验保留。该端点为 `permitAll` 匿名端点，限流完全依赖 `VerificationCodeService` 的 phone/ip/global 三维计数 + 60s 重发锁，本次不改这些参数，故重新启用不削弱限流。
  - 提醒：IP 维度限流依赖 `ClientIp.from` 取到的真实 IP，反向代理下需正确透传 `X-Forwarded-For`，否则 IP 维度会退化。
- `VerificationCodeService` 的方法本就无 `@Deprecated`，无需"移除标记"。

> 竞态说明：若 `verifyAndConsume` 成功后落库遇到并发 `DuplicateKeyException`（极少），验证码已被消费，用户需重新获取；可接受。唯一性前置已将其压到最小。

## 前端改动（`mateclaw-ui`）

### `Login.vue`（register tab）

在手机号输入框下方新增一行：验证码输入框 + "获取验证码"按钮。

- `registerForm` 增加 `code`；提交时随 payload 带上，并增加 `code` 非空客户端校验。
- "获取验证码"按钮：点击前本地校验手机号非空/格式 → 调 `authApi.sendRegisterCode({phone})` → 成功后启动 60s 倒计时（倒计时内按钮禁用并显示剩余秒数）；发送中禁用。
- **倒计时仅为组件内 `ref`，不持久化**（YAGNI）；切 tab 或刷新页面会丢失倒计时——属可接受，再次点击由后端 60s 重发锁（429）兜底。
- 后端错误（429 太频繁/超限、409 已注册、400 非法号码/验证码错等）经 `e?.message` 直接走现有 `errorMsg` 展示，**无需前端 i18n 映射**；仅纯客户端本地校验文案走 i18n。

### `api/index.ts`

- `register` 入参类型增加 `code: string`。
- 新增 `sendRegisterCode: (data: { phone: string }) => http.post('/auth/send-register-code', data)`。

### i18n（`zh-CN.ts` + `en-US.ts` 同步增键）

在现有 `login.*` 段（与 `login.fields.*` / `login.placeholders.*` / `login.passwordTooShort` 同级）新增确定 key：

- `login.fields.code`（"验证码" / "Verification code"）
- `login.placeholders.code`（"短信验证码" / "SMS code"）
- `login.getCode`（"获取验证码" / "Get code"）
- `login.resendCountdown`（"{n}s 后重发" / "Resend in {n}s"）
- `login.codeRequired`（"请输入验证码" / "Please enter the code"）

zh-CN 与 en-US 两边必须同步，避免漏键。

## 配置示例（`application.yml`）

```yaml
mateclaw:
  sms:
    mock: ${SMS_MOCK:false}                 # dev 旁路需显式置 true，见下
    provider: ${SMS_PROVIDER:aliyun}        # aliyun | tencent
    # ... 限流 / aliyun 段保持不变 ...
    tencent:
      secret-id:   ${TENCENT_SMS_SECRET_ID:}    # 仅 env，禁止入库
      secret-key:  ${TENCENT_SMS_SECRET_KEY:}   # 仅 env，禁止入库
      sdk-app-id:  ${TENCENT_SMS_SDK_APP_ID:}
      sign-name:   ${TENCENT_SMS_SIGN_NAME:}
      template-id: ${TENCENT_SMS_TEMPLATE_ID:}
      region:      ${TENCENT_SMS_REGION:ap-guangzhou}
      default-country-code: ${TENCENT_SMS_CC:+86}
```

### dev 旁路前提（重要）

万能码 `888888` 仅在 `mateclaw.sms.mock=true` 时旁路（`VerificationCodeService.java:101`）。**默认 profile（dev,mysql）与 desktop 均不开 mock**，即开箱即默认 `mock=false`，注册会真打短信。故本地/集成验证"用 888888"前，必须显式 `SMS_MOCK=true`（或在 `application-dev.yml` 自行加 `mateclaw.sms.mock: true`）。本设计**不**自动为任何 profile 开启 mock（保持 prod 安全简单），是否在 dev profile 默认开启留给部署方决定。

## 测试（遵循项目 TDD）

后端（注意：`-am` 跑单测需加 `-Dsurefire.failIfNoSpecifiedTests=false`；构建走 JDK 21）：

- `TencentSmsCodeSenderTest`：注入 mock `SmsClient`，断言请求拼装（裸号补 `+86`、已带 `+` 不重复补、`templateParamSet=[code]`、signName/templateId/sdkAppId 透传）；`SendStatusSet[0].code="Ok"` 成功；**固化"Ok"判定**（防误用阿里 "OK"）；非 `"Ok"` / 空数组 / client 抛异常 → `SmsSendException`。
- `SmsStartupValidatorTest`：`provider` 非法拒启；`provider=tencent` 缺各密钥分支拒启；`provider=aliyun` 维持原校验；`mock=true` 跳过密钥校验；prod + mock=true 拒启。
- 工厂装配测试：用 `ApplicationContextRunner` + `withUserConfiguration(SmsAutoConfiguration)` + `withPropertyValues(...)` 走真实工厂路径，断言 `hasSingleBean(SmsCodeSender.class)` 且类型正确：
  - `mock=true` → `MockSmsCodeSender`
  - `mock=false, provider` 缺省/`aliyun` → `AliyunSmsCodeSender`
  - `mock=false, provider=tencent` → `TencentSmsCodeSender`
  - `mock=false, provider=非法` → 上下文启动失败
  - **不要**在同一 runner 里 `withBean` 同时注册多个发送器（会造 `NoUniqueBeanDefinitionException` 误判）。现有 `AliyunSmsCodeSenderTest` 的 `withBean` 用法仅作"可实例化"验证，保留即可。
- `AuthServiceTest`（注册）：缺验证码 → 400 且不建用户；验证码错 → 400 且不建用户；已注册号码命中唯一性 → 409 且**不消费**验证码；正确码 → 建用户成功。

前端：

- 若有现成测试框架，补 `Login.vue` 注册态的验证码字段必填与"获取验证码"倒计时禁用逻辑测试。
- 注：worktree 内前端验证以 `vue-tsc` 类型检查为准（`pnpm build` 因缺脚本会中止）；`onboardingBrand` 测试已知必失败，与本改动无关。

## 不做（YAGNI）

- 不引 Redis；验证码继续走内存 store。
- 不做后台 UI 切换、不动 `mate_system_setting`。
- **不改任何表、无 Flyway 迁移**：手机号继续存 `username`，`code` 仅 DTO 字段不入库，验证码仍在内存。
- 不实现 provider 自动 failover、不做多国家号段适配（`default-country-code` 默认 `+86`；当前 `PhoneNumbers` 虽放行带 `+` 的多位号码，但实际仍以国内号为主，country-code 作为前瞻预留/可配项）。
- 不改验证码长度/有效期/限流等既有参数。
- 不为任何 profile 自动开启 `mock`。

## 风险与缓解

- **切换服务商需重启**：决策为 yml/env，符合预期；工厂 + 启动校验对非法 provider / 缺密钥快速失败并给出明确信息。
- **腾讯模板/签名/SdkAppId 由部署方在 env 填**：设计不写死值；缺失时启动期报错而非运行期静默失败。
- **成功码大小写 / 号码国家码 / 模板位置参数**：三处与阿里云不同，已在差异清单显式列出并由测试固化。
- **dev 默认不开 mock**：已在"dev 旁路前提"显式说明，避免验收踩空。
