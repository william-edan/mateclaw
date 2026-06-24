-- V146: 数字员工「是否内置」标记（builtin）+ 可见性 / 编辑权限基础。
--
-- builtin = TRUE  → 内置员工：所有用户可见，仅 admin（mate_user.role='admin'）可改。
--                   系统播种（内置数字员工、获客专家）与 admin 创建的都属此类。
-- builtin = FALSE → 用户私有：仅创建者本人可见、可改。
--
-- 列默认 TRUE（业务侧「默认内置」）；新建 Agent 由后端按创建者角色显式赋值
-- （admin → 内置，普通成员 → 非内置）。
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS builtin BOOLEAN NOT NULL DEFAULT TRUE;

-- 回填既有行：系统播种 / 无主（creator_user_id 为空）与 admin 创建的保持内置；
-- 其余普通用户自建的降级为非内置，避免把用户旧的私有 Agent 暴露给全工作区。
UPDATE mate_agent
SET builtin = FALSE
WHERE creator_user_id IS NOT NULL
  AND creator_user_id NOT IN (SELECT id FROM mate_user WHERE role = 'admin');
