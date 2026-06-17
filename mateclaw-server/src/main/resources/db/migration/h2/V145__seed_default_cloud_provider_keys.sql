-- V145: 新增 DashScope/DeepSeek「默认版」provider（平台预置 key），对所有工作区补齐。
-- api_key 留空、enabled=FALSE，由 DefaultProviderKeyBootstrap 启动后回填配置文件里的平台 key。

-- ---- 默认版 provider（每工作区一份，幂等）----
INSERT INTO mate_model_provider (id, workspace_id, provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, auth_type, fallback_priority, enabled, create_time, update_time)
SELECT 907100000000000000 + ROW_NUMBER() OVER (ORDER BY w.id),
       w.id, 'dashscope-default', 'DashScope 默认', 'sk-', 'DashScopeChatModel', '', '', '{}',
       FALSE, FALSE, FALSE, TRUE, TRUE, TRUE, 'api_key', 0, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM mate_workspace w
 WHERE NOT EXISTS (SELECT 1 FROM mate_model_provider p WHERE p.workspace_id = w.id AND p.provider_id = 'dashscope-default');

INSERT INTO mate_model_provider (id, workspace_id, provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, auth_type, fallback_priority, enabled, create_time, update_time)
SELECT 907200000000000000 + ROW_NUMBER() OVER (ORDER BY w.id),
       w.id, 'dashscope-compat-default', 'DashScope 兼容模式 默认', 'sk-', 'OpenAIChatModel', '', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '{}',
       FALSE, FALSE, FALSE, TRUE, TRUE, TRUE, 'api_key', 0, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM mate_workspace w
 WHERE NOT EXISTS (SELECT 1 FROM mate_model_provider p WHERE p.workspace_id = w.id AND p.provider_id = 'dashscope-compat-default');

INSERT INTO mate_model_provider (id, workspace_id, provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, auth_type, fallback_priority, enabled, create_time, update_time)
SELECT 907300000000000000 + ROW_NUMBER() OVER (ORDER BY w.id),
       w.id, 'deepseek-default', 'DeepSeek 默认', 'sk-', 'OpenAIChatModel', '', 'https://api.deepseek.com', '{}',
       FALSE, FALSE, FALSE, TRUE, TRUE, TRUE, 'api_key', 0, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM mate_workspace w
 WHERE NOT EXISTS (SELECT 1 FROM mate_model_provider p WHERE p.workspace_id = w.id AND p.provider_id = 'deepseek-default');

-- ---- embedding 默认改挂 dashscope-default（managed，带平台 key）；原版保持空（BYO）。
-- 不改 id / is_default，仅改 provider 列：getDefaultEmbeddingModel 按工作区取 is_default 行，
-- 改挂后默认 embedding 自动落到带 key 的 provider，新注册工作区知识库 embedding 才开箱可用。----
UPDATE mate_model_config
   SET provider = 'dashscope-default'
 WHERE provider = 'dashscope' AND model_type = 'embedding' AND builtin = TRUE AND deleted = 0;

-- ---- 镜像 builtin 模型（同工作区内复制原版 → 默认版，幂等）----
INSERT INTO mate_model_config (id, workspace_id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, max_input_tokens, enable_search, search_strategy, model_type, modalities, request_timeout_seconds, create_time, update_time, deleted)
SELECT 907400000000000000 + ROW_NUMBER() OVER (ORDER BY m.workspace_id, m.id),
       m.workspace_id, m.name, 'dashscope-default', m.model_name, m.description, m.temperature, m.max_tokens, m.top_p,
       m.builtin, m.enabled, FALSE, m.max_input_tokens, m.enable_search, m.search_strategy, m.model_type, m.modalities, m.request_timeout_seconds,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, m.deleted
  FROM mate_model_config m
 WHERE m.provider = 'dashscope' AND m.builtin = TRUE AND m.deleted = 0 AND (m.model_type = 'chat' OR m.model_type IS NULL)
   AND NOT EXISTS (SELECT 1 FROM mate_model_config e WHERE e.workspace_id = m.workspace_id AND e.provider = 'dashscope-default' AND e.model_name = m.model_name AND e.deleted = 0);

INSERT INTO mate_model_config (id, workspace_id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, max_input_tokens, enable_search, search_strategy, model_type, modalities, request_timeout_seconds, create_time, update_time, deleted)
SELECT 907500000000000000 + ROW_NUMBER() OVER (ORDER BY m.workspace_id, m.id),
       m.workspace_id, m.name, 'dashscope-compat-default', m.model_name, m.description, m.temperature, m.max_tokens, m.top_p,
       m.builtin, m.enabled, FALSE, m.max_input_tokens, m.enable_search, m.search_strategy, m.model_type, m.modalities, m.request_timeout_seconds,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, m.deleted
  FROM mate_model_config m
 WHERE m.provider = 'dashscope-compat' AND m.builtin = TRUE AND m.deleted = 0 AND (m.model_type = 'chat' OR m.model_type IS NULL)
   AND NOT EXISTS (SELECT 1 FROM mate_model_config e WHERE e.workspace_id = m.workspace_id AND e.provider = 'dashscope-compat-default' AND e.model_name = m.model_name AND e.deleted = 0);

INSERT INTO mate_model_config (id, workspace_id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, max_input_tokens, enable_search, search_strategy, model_type, modalities, request_timeout_seconds, create_time, update_time, deleted)
SELECT 907600000000000000 + ROW_NUMBER() OVER (ORDER BY m.workspace_id, m.id),
       m.workspace_id, m.name, 'deepseek-default', m.model_name, m.description, m.temperature, m.max_tokens, m.top_p,
       m.builtin, m.enabled, FALSE, m.max_input_tokens, m.enable_search, m.search_strategy, m.model_type, m.modalities, m.request_timeout_seconds,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, m.deleted
  FROM mate_model_config m
 WHERE m.provider = 'deepseek' AND m.builtin = TRUE AND m.deleted = 0 AND (m.model_type = 'chat' OR m.model_type IS NULL)
   AND NOT EXISTS (SELECT 1 FROM mate_model_config e WHERE e.workspace_id = m.workspace_id AND e.provider = 'deepseek-default' AND e.model_name = m.model_name AND e.deleted = 0);

-- ---- 条件迁移默认模型：仅当某工作区当前默认仍是「未配置的原版」dashscope/qwen-plus ----
UPDATE mate_model_config
   SET is_default = TRUE
 WHERE provider = 'dashscope-default' AND model_name = 'qwen-plus'
   AND workspace_id IN (
       SELECT d.workspace_id FROM mate_model_config d
       JOIN mate_model_provider op ON op.workspace_id = d.workspace_id AND op.provider_id = 'dashscope'
       WHERE d.is_default = TRUE AND d.provider = 'dashscope' AND d.model_name = 'qwen-plus'
         AND (op.api_key IS NULL OR TRIM(op.api_key) = '' OR op.api_key LIKE '%*%' OR LOWER(op.api_key) = 'configure-in-admin-ui')
   );

UPDATE mate_model_config
   SET is_default = FALSE
 WHERE provider = 'dashscope' AND model_name = 'qwen-plus' AND is_default = TRUE
   AND workspace_id IN (
       SELECT x.workspace_id FROM mate_model_config x
       WHERE x.provider = 'dashscope-default' AND x.model_name = 'qwen-plus' AND x.is_default = TRUE
   );
