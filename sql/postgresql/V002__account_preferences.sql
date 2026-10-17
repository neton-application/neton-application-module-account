-- 账号偏好（spec application/account-preferences.md）：按「用户 + 命名空间」存一份 JSON 文档。
-- value 存 JSON 文本（text，不用 jsonb：服务端不按字段查询，只整份读写）；version 做乐观并发，
-- 从 1 开始，每次写入 +1。账号注销时由 AccountPreferenceLogic.deleteAllFor 清理。
CREATE TABLE IF NOT EXISTS account_preferences (
    user_id    bigint                NOT NULL,
    namespace  character varying(64) NOT NULL,
    value      text                  NOT NULL,
    version    bigint                NOT NULL,
    created_at bigint                NOT NULL,
    updated_at bigint                NOT NULL,
    CONSTRAINT account_preferences_pkey PRIMARY KEY (user_id, namespace),
    CONSTRAINT account_preferences_version_positive CHECK (version > 0)
);
