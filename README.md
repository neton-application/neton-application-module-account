# neton-application-module-account

账号模块：很多业务的地基，要**干净而安全**。moduleId `account`。

负责：账号与身份（手机号、邮箱、用户名、密码）、注册与登录（用户名密码、短信验证码、社交）、
资料（昵称、头像、性别、简介、生日）、登录设备与会话、账号类型（普通 / 游客 / 机器人）、
注销、昵称词库、举报、注册后强制补全（required actions）的机制。

**不负责**任何业务能力。会员成长（等级、经验、积分、签到、邀请）在 `neton-application-module-member`，
它依赖本模块；本模块永远不反向依赖它。

## 扩展点

业务模块要在账号流程里接入，只能通过 `port.AccountExtensions` 登记（见 `port/AccountExtensions.kt`）：

- `AccountRegistrationHook` —— 注册前校验 / 注册后回调（member 的邀请码绑定、自动加好友、邀请奖励、欢迎语）
- `RequiredActionContributor` —— 登录后强制补全项（member 的「填写邀请码」）

## 装配

- 注册策略由应用装配层 bind `port.AccountAuthPolicy`（默认只开短信注册）。
- 身份后端经 `port.AccountIdentityAdapter`：默认内置账号；privchat 模式由 module-privchat override。
- 验证码（短信 / 邮件）由 module-system 的 `MessageSendLogic` 发送，本模块只负责场景判定与校验；
  邮箱找回密码对未绑定的邮箱静默返回，不泄露账号存在与否。

## 公开接口

App 路由保持拆分前的路径不变（已发出的客户端在用）：`/auth/*`、`/account/*`、`/member/user/*`、
`/member/nickname-suggest`、`/member/report`、`/social-user`。后台路由为 `/account/user/*`、
`/account/nickname-*`，权限 `account:user:*`、`account:nickname:*`。

## 迁移

1.0.0-beta 空库初始化使用 `sql/postgresql/V001__schema.sql` 和
`sql/postgresql/V002__data.sql`，按最终结构直接创建 account 的表。
发布前的 member 表接管历史已收敛进这套基线；旧测试库需重建，不能清空迁移记录
后原地套用。重新编译分发应用后走正式迁移命令，详见 `sql/README.md`。
