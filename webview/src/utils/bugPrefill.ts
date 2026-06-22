import type { YunxiaoBug } from '../hooks/useYunxiaoBugs';

/* ──────────────────────────────────────────────────────────────────
 * bugPrefill
 *
 * 云效缺陷「建会话」/「建监督者」的预填文案生成（纯函数）。从
 * BugListView 抽出，供批量工具栏（现状）与 BugAnalysisPanel 分组派单
 * （新）共用。入参为 bug(s) + appendPrompt（云效设置里配置的追加文案），
 * 不依赖任何组件状态。
 * ──────────────────────────────────────────────────────────────── */

/** Coerce 云效 status (string | {displayName|name} | null) into a display string. */
const statusText = (status: YunxiaoBug['status']): string => {
  if (!status) return '';
  if (typeof status === 'string') return status;
  return status.displayName || status.name || '';
};

const serialText = (bug: YunxiaoBug): string => {
  if (bug.serialNumber === undefined || bug.serialNumber === null) return '';
  return String(bug.serialNumber);
};

/**
 * Shared prefill text for both「建会话」and「建监督者」. 展示用 serialNumber、工具入参用
 * identifier(双标识)。末尾追加用户在云效设置里配置的 appendPrompt(若有)。
 */
export const buildPrefill = (bug: YunxiaoBug, appendPrompt: string): string => {
  const serial = serialText(bug);
  const subject = bug.subject || '';
  const status = statusText(bug.status);
  // 工作项内部标识 = identifier(Java 已从云效 `id` 兜底映射);再兜一层 bug.id 防漂移。
  const identifier = bug.identifier || bug.id || '';
  const base =
    `请帮我诊断并修复云效缺陷 BUG-${serial}（标题：${subject}，状态：${status}）。\n` +
    `第一步必须调用 query_bug_details 工具，传入 bug id「${identifier}」拉取完整的基础信息、\n` +
    `所有评论和附件。该工具会把描述/评论里的所有截图下载到本地并返回路径，\n` +
    `你必须用 Read 工具逐个查看这些截图（看清实际画面/报错），完全理解问题后再制定修复方案并动手修复。\n` +
    `修复并自测通过后，必须调用 comment_bug_fix 工具，传入 bug id「${identifier}」把修复结论评论回该缺陷，` +
    `必须包含三段：①缺陷产生的原因 ②如何修复 ③如何测试。`;
  const extra = appendPrompt.trim();
  return extra ? `${base}\n\n${extra}` : base;
};

/**
 * Multi-bug prefill (批量): all selected bugs in one supervisor/session. Asks the
 * model to group related bugs (same page / API / feature point) and fix each group
 * together. Same identifier/serialNumber 双标识 convention + appendPrompt tail as the
 * single-bug version.
 */
export const buildPrefillMulti = (list: YunxiaoBug[], appendPrompt: string): string => {
  const lines = list.map((bug, i) => {
    const serial = serialText(bug);
    const subject = bug.subject || '';
    const status = statusText(bug.status);
    const identifier = bug.identifier || bug.id || '';
    return `${i + 1}. BUG-${serial}（id「${identifier}」，标题：${subject}，状态：${status}）`;
  });
  const base =
    `请帮我诊断并修复以下 ${list.length} 个云效缺陷：\n\n` +
    `${lines.join('\n')}\n\n` +
    `要求：\n` +
    `1. 对每一个缺陷，第一步都必须调用 query_bug_details 工具并传入它对应的 bug id，拉取完整的基础信息、所有评论和附件；该工具会把描述/评论里的所有截图下载到本地并返回路径，你必须用 Read 工具逐个查看这些截图（看清实际画面/报错），完全理解后再制定修复方案。\n` +
    `2. 先分析这些缺陷之间的关联性，把涉及【同一个页面 / 同一个接口 / 同一个功能点 / 同一处根因】的缺陷归为一组；相关的缺陷放在一起修复（一次性改完），不相关的再逐个处理。\n` +
    `3. 按分组顺序逐组推进，每修完一组再进行下一组，不要遗漏任何一个缺陷。\n` +
    `4. 每修复并验证完一个缺陷（或一组相关缺陷），调用 comment_bug_fix 工具，用对应的 bug id 把该缺陷的修复结论` +
    `评论回去（含 ①缺陷产生的原因 ②如何修复 ③如何测试 三段），每个缺陷都要发，不要遗漏。`;
  const extra = appendPrompt.trim();
  return extra ? `${base}\n\n${extra}` : base;
};
