# neton-application-module-member

会员（C 端用户）模块：注册 / 登录（用户名密码、手机验证码、社交）、资料、邀请码与奖励、邮箱绑定与找回密码、设备与会话。
moduleId `member`。

- 注册策略由应用装配层 bind `port.MemberAuthPolicy`（默认只开短信注册；NetonAPI 打开了用户名密码）。
- 验证码（短信 / 邮件）由 module-system 的 `MessageSendLogic` 发送，本模块只负责场景判定与校验；
  邮箱找回密码对未绑定的邮箱静默返回，不泄露账号存在与否。
- 迁移 `sql/postgresql/V001–V003`（V003 邮箱）。规范见 `docs/MEMBER_IDENTITY_MODE_SPEC.md` 与 neton-application `docs/ENGINEERING_RULES.md`。
