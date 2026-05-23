/**
 * Built-in description templates that user can insert when editing a Supervisor agent.
 * These are *snippets*, distinct from the 3 built-in seeded agents shipped in Java.
 */

export interface DescriptionTemplate {
  id: 'role-only' | 'role-with-rules' | 'complete';
  labelKey: string;
  body: string;
}

const TEMPLATE_ROLE_ONLY = `你是 {{角色名}}。
检查每步产出符合 {{规范来源}}。
违规立刻反馈，否则推进下一步。
`;

const TEMPLATE_ROLE_WITH_RULES = `你是 {{角色名}}。

# 强制规范
- 规范条目 1
- 规范条目 2
- 规范条目 3

# 行为
每步 verify 通过后扫描产出文件，违规反馈具体行号 + 修改建议。
通过 → approve_and_continue
不通过 → inject_prompt 反馈
`;

const TEMPLATE_COMPLETE = `你是 {{角色名}}。

# 强制规范
- 规范条目 1
- 规范条目 2

# 行为
每步 verify 通过后扫描产出文件，违规反馈具体行号 + 修改建议。

# 升级规则
- 同一文件违反同一规范 3 次以上：升级给用户
- 主 AI 申请偏离规范：直接拒绝（规范不可协商）
- API 错误：429/5xx 自动重试，其它升级
`;

export const DESCRIPTION_TEMPLATES: DescriptionTemplate[] = [
  { id: 'role-only', labelKey: 'settings.supervisor.templates.roleOnly', body: TEMPLATE_ROLE_ONLY },
  { id: 'role-with-rules', labelKey: 'settings.supervisor.templates.roleWithRules', body: TEMPLATE_ROLE_WITH_RULES },
  { id: 'complete', labelKey: 'settings.supervisor.templates.complete', body: TEMPLATE_COMPLETE },
];

/**
 * Allowed Claude models for Supervisor. The picker also exposes a "1M context"
 * toggle which, when enabled and the model supports it (currently opus-4-7),
 * appends a `[1m]` suffix at send time via {@code apply1MContextSuffix} — same
 * mechanism the main AI uses, so the daemon recognises it transparently.
 * Keep in sync with backend SupervisorAgentManager.DEFAULT_MODEL.
 */
export const SUPERVISOR_MODELS = [
  { id: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5 · 速度最快' },
  { id: 'claude-sonnet-4-6',         label: 'Claude Sonnet 4.6 · 默认推荐' },
  { id: 'claude-opus-4-7',           label: 'Claude Opus 4.7 · 最强' },
  { id: 'claude-opus-4-6',           label: 'Claude Opus 4.6 · 长会话模式' },
];

export const NAME_MAX_LENGTH = 30;
export const DESCRIPTION_MAX_LENGTH = 100_000;
