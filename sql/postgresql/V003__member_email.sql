-- V003：会员邮箱。邮箱验证码走 system 的邮件通道（找回密码 / 绑定邮箱）。
-- 唯一索引带条件：NULL 不参与，历史账号没邮箱不受影响。
ALTER TABLE member_users ADD COLUMN IF NOT EXISTS email VARCHAR(255) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_member_users_email ON member_users (email) WHERE email IS NOT NULL;
