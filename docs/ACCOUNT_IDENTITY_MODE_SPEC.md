# MEMBER-IDENTITY-ADAPTER-SPEC (v1 minimal)

> 状态:DRAFT(评审中,**未编码**)。立项 2026-06-15,2026-06-15 **收窄**(原 draft 过度设计,已废弃完整身份平台方案)。
> 真身仓:`privchat-application-module-member`(privchat-platform);`Neton/neton-application-module-member` 镜像**冻结不动**。

---

## 0. 一句话

member 仍是**一套**模块、`member_users.id` 仍是 member 主 ID。第一刀**只抽象一件事**:
> member 账号的**外部 ID/Token 来源** + **资料变更后的同步回调**。

- **内置 `builtin`**:member 原生行为——自己生成账号 ID、用现有 token 机制、资料变更无回调。
- **PrivChat `privchat`**:由 `neton-application-module-privchat` 实现 adapter,内部调 `PrivchatServiceClient`,资料变更同步到 privchat-server。

目的:**切断 `member → PrivchatServiceClient` 的直接依赖**。privchat 只是 member 定义的 adapter 的一个实现。

---

## 1. 明确不做(本刀砍掉,避免过度设计)

- ❌ 不建独立 `member_identity_binding` 表 → 直接用 `member_users` 字段。
- ❌ 不做 `application_deployment_setting` DB 部署锁 / 迁移工具。
- ❌ 不新做 builtin RS256 token issuer → 沿用内置账号现有 token 机制。
- ❌ 不抽 `neton-events` 框架级事件总线。
- ❌ 不把 QR login 纳入核心抽象(它是 PrivChat 专属功能,后续作为 capability 单独处理)。
- ❌ 不做多 provider(wechat/google/apple…)设计——未来真有再抽。

---

## 2. 当前耦合点(要切断的)

member `@Module(dependsOn=["system","privchat","infra"])`,直接依赖 privchat:
- `MemberAuthLogic`:`createUser` / `getUserByMobile` / `issueAuthToken` / `refreshAuthToken`
- `MemberProfileLogic` / `MemberRuntimeBootstrap`:`HookBus` + `MemberProfileChangedHook`/`MobileChangedHook`/`PasswordChangedHook`(类型来自 privchat 包)
- `QrLoginController`:privchat QR 全套 ——**本刀不动**,留待 capability 处理

---

## 3. 抽象(member 定义,放 member 内 `port/` 包)

```kotlin
interface MemberIdentityAdapter {
    /** 注册/登录时:产生或绑定底层账号,返回外部账号引用(builtin=本地算法生成;privchat=privchat user_id) */
    suspend fun createOrBindAccount(command: CreateMemberAccountCommand): MemberAccountRef

    /** 签发 token(builtin=内置机制;privchat=privchat-server 签发) */
    suspend fun issueToken(command: IssueMemberTokenCommand): MemberTokenBundle
    suspend fun refreshToken(command: RefreshMemberTokenCommand): MemberTokenBundle

    /** 资料变更回调:builtin 默认空;privchat 同步到 privchat-server */
    suspend fun onProfileChanged(event: MemberProfileChangedEvent) {}
    suspend fun onMobileChanged(event: MemberMobileChangedEvent) {}
    suspend fun onPasswordChanged(event: MemberPasswordChangedEvent) {}
}
```
- `MemberAccountRef(provider: String, memberId: Long, created: Boolean)` 等 model + 上述 3 个 event data class 一并定义在 member `port/`(事件作为回调入参,**不引入独立总线**)。**单 ID 模型**:`memberId` 始终是 `member_users.id`;privchat 模式下它**就是** privchat user_id(不存在外部 id 映射)。
- 回调用默认空实现 → builtin 不需要写任何同步代码,privchat override 才同步。这取代 member 对 privchat `HookBus` 的依赖。

---

## 4. 两个实现

| 实现 | 位置 | 行为 |
|---|---|---|
| `BuiltinMemberIdentityAdapter` | **member** 内 `impl/`(默认) | createOrBind→member 内置 ID 生成;issue/refresh→现有内置 token 机制;回调全空(无外部同步) |
| `PrivchatMemberIdentityAdapter` | **module-privchat** | createOrBind→`getUserByMobile`+`createUser`;issue/refresh→`issueAuthToken`/`refreshAuthToken`;回调→同步 profile/mobile/password 到 privchat-server |

member 业务层(MemberAuthLogic / MemberProfileLogic)**只调 `MemberIdentityAdapter`**,不再 import `PrivchatServiceClient` / privchat `HookBus`。

---

## 5. `member_users` 字段(单 ID 模型,只加 1 列)

**`member_users.id` 永远是唯一主 ID**,其来源由 `identity_provider` 决定:
| 模式 | `member_users.id` 来源 | 含义 |
|---|---|---|
| builtin | member 内置 ID 生成器 | Neton 内置账号 ID |
| privchat | privchat-server 返回的 user_id | **privchat user_id 直接就是 member id** |

只加一列(追溯来源):
```
member_users.identity_provider VARCHAR NOT NULL DEFAULT 'builtin'  -- 'builtin' | 'privchat'
```
铁律:
1. `member_users.id` 始终是 member 主 ID;privchat 模式下 `id == privchat user_id`。
2. **不加 `external_user_id`、不建 `member_identity_binding`**(单 ID,无映射)。
3. `identity_provider` v1 仅 `builtin`/`privchat`,创建后不可由普通业务接口修改。
4. builtin→privchat 迁移走**专门 migration**(主 ID 体系可能需重映射),不允许改配置/手工 update 混切。
> application 全层(game/payment/assistant/admin)只认一个 ID:`memberId == userId == privchatUserId`。

---

## 6. 配置 + 启动校验(无 DB 锁)

```toml
[member.identity]
mode = "builtin"   # builtin | privchat
```
启动时**只做 adapter 在位校验**(不做 DB 落锁):
- `mode=builtin` → 用 `BuiltinMemberIdentityAdapter`;
- `mode=privchat` → ctx 里必须有 `PrivchatMemberIdentityAdapter`(module-privchat 已装),否则 **启动失败,禁止 fallback builtin**。

> "部署前选定、不可运行期切换" 作为 **spec 约束 + 启动校验** 表达;DB 落锁留 backlog,不在 v1。

---

## 7. DI 装配

```
member:   bindIfAbsent(MemberIdentityAdapter, BuiltinMemberIdentityAdapter)   // 仅 mode=builtin 生效路径
privchat: bind(MemberIdentityAdapter, PrivchatMemberIdentityAdapter)          // mode=privchat 时 override
```
最终 ctx 有且仅一个 `MemberIdentityAdapter`;`mode=privchat` 而无 privchat adapter → fail-fast。

---

## 8. 依赖方向

```
module-member   ──> 只依赖自己的 port(+ system/infra),不碰 PrivchatServiceClient/privchat HookBus
module-privchat ──> 依赖 member,实现 PrivchatMemberIdentityAdapter
```
member `@Module` 改 `dependsOn=["system","infra"]`(去 privchat)。绑定顺序由 bind/bindIfAbsent 保证。

---

## 9. admin(顺带,不扩大)

member 通用化后 member admin 页通用。能力差异用一个 capability 标志即可(`memberIdentityMode: builtin|privchat`),privchat 模式才显示 IM/QR 相关入口。QR 本身留待后续。

---

## 10. 落地计划(细分拆刀,降风险;2026-06-15 重新拆小)

> 原则:**不把 登录链路 / token / DB 字段 / 依赖反转 混在一刀**。每刀编译通过后 commit,不 push。

| Commit | 范围 | 风险 | 状态 |
|---|---|---|---|
| 1 | member 定义 `MemberIdentityAdapter` + models + 3 event;`BuiltinMemberIdentityAdapter` 骨架(空回调,token TODO)。不改业务流 | 无 | ✅ **done** `28caee8` |
| 2 | **只接资料变更回调**:`MemberProfileLogic` / mobile / password 变更点调用 `adapter.onProfileChanged/onMobileChanged/onPasswordChanged`。**不改 MemberAuthLogic、不动 token/DB/QR、不去 `@Module` privchat、不实现 privchat adapter**。目的:验证回调模型可替代 HookBus 形态 | 低 | 待开 |
| 3 | module-privchat 实现 `PrivchatMemberIdentityAdapter` **仅回调同步**(onProfile/Mobile/Password);`createOrBind`/`issue`/`refresh` 先 TODO/未接。目的:用 adapter 回调替代 HookBus 同步,不碰登录 | 中 | 待开 |
| 4 | 改 `MemberAuthLogic`(createOrBind/issue/refresh)。**前置必须先明确**:① builtin 现有 token 机制到底是什么(没有就不硬造,builtin auth 可先 unsupported 或沿用现路径);② `member_users` 是否已有可存 `external_user_id` 的字段 | 高(登录+数据) | 待开 |
| 5 | 去 member 对 privchat 依赖:`@Module` dependsOn 去 privchat + 删 PrivchatServiceClient/privchat HookBus import。**前置**:2/3/4 全过 + 双模式 compile/smoke | 高(依赖反转) | 待开 |

> `[member.identity] mode` 配置 + 启动 adapter 在位校验,并入 Commit 4/5 一带处理(v1 无 DB 锁)。QR login 解耦 = 后续单独 capability 刀,不在本计划。

---

## 11. 评审拍板(GPT 已给方向,确认即可)

1. 绑定:`member_users` 字段,**不建独立表** ✅
2. builtin token:**沿用现有内置机制**,不新做 RS256 ✅
3. 事件:定义在 member 包(作为 adapter 回调入参),**不抽框架总线** ✅
4. 仓:在 `privchat-application-module-member` 推进,Neton 镜像冻结 ✅
5. mode:叫 `builtin`/`privchat`,v1 只做**配置选择 + adapter 校验**,无 DB 锁 ✅

---

## 12. backlog(不在 v1)

- QR login adapter 化(PrivChat capability);
- DB deployment 落锁 + builtin↔privchat 迁移工具;
- builtin 统一 RS256 issuer;多 provider 绑定表;HookBus/事件总线框架化。
