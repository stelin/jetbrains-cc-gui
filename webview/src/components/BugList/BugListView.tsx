import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import VirtualList from '../history/VirtualList';
import { sendBridgeEvent } from '../../utils/bridge';
import { useYunxiaoBugs, type YunxiaoBug } from '../../hooks/useYunxiaoBugs';
import BugDetailModal from './BugDetailModal';
import styles from './style.module.less';

/* ──────────────────────────────────────────────────────────────────
 * BugListView
 *
 * 需求2「我的缺陷」全页视图: 顶部项目下拉 → 列出指派给我的 Bug →
 * 每项一个【建监督者】按钮。仿 HistoryView 用 VirtualList 渲染；分页
 * 「加载更多」单调追加。
 *
 * Step 3 范围: 仅查询展示。【建监督者】按钮先占位 —— 点击只发不带
 * payload 的 create_new_supervised_tab(等价现状新建监督者标签页);
 * Step 5 再扩展为携带 bug 预填文案的定向建会话。
 * ──────────────────────────────────────────────────────────────── */

interface BugListViewProps {
  /** Back to the chat view. */
  onBack: () => void;
  /** Jump to the「云效设置」tab when token/orgId is missing. */
  onOpenYunxiaoSettings: () => void;
}

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

/** Current status id (needed to compute valid workflow transitions). */
const statusId = (bug: YunxiaoBug): string => {
  const s = bug.status;
  return s && typeof s === 'object' ? s.id || '' : '';
};

const bugKey = (bug: YunxiaoBug): string => bug.id || bug.identifier || '';

interface StatusOption {
  id: string;
  name: string;
  color?: string;
}

/** A member option for the 改负责人 picker. */
interface MemberOption {
  userId: string;
  name: string;
}

/** Coerce 云效 assignedTo (string | {displayName|name} | array) into a display name; '' when id-like/empty. */
const assigneeText = (assignee: YunxiaoBug['assignedTo']): string => {
  const one = Array.isArray(assignee) ? assignee[0] : assignee;
  if (!one) return '';
  if (typeof one === 'string') {
    // A bare userId (hex/numeric) isn't a readable name — don't surface it.
    return /^[0-9a-f]{16,}$/i.test(one) || /^\d+$/.test(one) ? '' : one;
  }
  if (typeof one === 'object') {
    const o = one as { displayName?: string; name?: string };
    return o.displayName || o.name || '';
  }
  return '';
};

/** Status filter defaults — checked every time the view opens (per requirement). */
const DEFAULT_STATUSES = ['处理中', '再次打开', '待确认'];

/** Max bugs selectable into a single batch (one shared supervisor/session). */
const MAX_BATCH = 10;

export function BugListView({ onBack, onOpenYunxiaoSettings }: BugListViewProps) {
  const { t } = useTranslation();
  const {
    projects,
    projectsError,
    selectedProjectId,
    selectProject,
    bugs,
    bugsError,
    loading,
    hasMore,
    loadMore,
    appendPrompt,
    updateBugStatus,
    updateBugAssignee,
  } = useYunxiaoBugs();

  // Status multi-select filter. Resets to DEFAULT_STATUSES every time the view
  // mounts (= every open), per requirement. Filtering is client-side on the
  // loaded page (status display names), matching what the list shows.
  const [selectedStatuses, setSelectedStatuses] = useState<string[]>(DEFAULT_STATUSES);
  const [filterOpen, setFilterOpen] = useState(false);
  // identifier of the bug whose「查看详情」modal is open (null = closed).
  const [detailBugId, setDetailBugId] = useState<string | null>(null);

  // Batch mode: pick multiple bugs (checkbox per row) and fix them in ONE shared
  // supervisor/session. selectedIds is keyed by bugKey(bug). Capped at MAX_BATCH.
  const [batchMode, setBatchMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  // In-list status change. A single menu open at a time, positioned fixed at the
  // clicked chip (so it works over the virtualized list). `forBugId` scopes the
  // async statuses/update responses to the bug whose menu is open.
  const [statusMenu, setStatusMenu] = useState<{
    bugId: string;
    top: number;
    left: number;
    /** Current status of the bug, so the menu can mark it as selected. */
    currentStatusId: string;
    currentStatusName: string;
  } | null>(null);
  const [statusMenuOptions, setStatusMenuOptions] = useState<StatusOption[]>([]);
  const [statusLoading, setStatusLoading] = useState(false);
  const [statusUpdating, setStatusUpdating] = useState(false);
  const [statusError, setStatusError] = useState<string | null>(null);
  // Whether the current statusError came from the update PUT (vs the initial load),
  // so the menu shows "failed to change" instead of "failed to load". Reset on each open.
  const [statusErrorFromUpdate, setStatusErrorFromUpdate] = useState(false);

  // In-list 改负责人 (reassign). Fixed-positioned picker at the clicked chip with a
  // search box + member list (reuses the member search, callback onYunxiaoAssigneeMembers).
  const [assigneeMenu, setAssigneeMenu] = useState<{ bugId: string; top: number; left: number } | null>(null);
  const [assigneeQuery, setAssigneeQuery] = useState('');
  const [assigneeMembers, setAssigneeMembers] = useState<MemberOption[]>([]);
  const [assigneeLoading, setAssigneeLoading] = useState(false);
  const [assigneeUpdating, setAssigneeUpdating] = useState(false);
  const [assigneeError, setAssigneeError] = useState<string | null>(null);
  const assigneeQueryRef = useRef(''); // latest query, for race-guarding async responses
  const assigneeSearchRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    window.onYunxiaoStatuses = (json: string) => {
      try {
        const r = JSON.parse(json) as { ok: boolean; bugId?: string; statuses?: StatusOption[]; error?: string };
        setStatusLoading(false);
        if (!r.ok) {
          setStatusError(r.error || '加载失败');
          setStatusMenuOptions([]);
        } else {
          setStatusError(null);
          setStatusMenuOptions(Array.isArray(r.statuses) ? r.statuses : []);
        }
      } catch {
        setStatusLoading(false);
        setStatusError('解析失败');
        setStatusMenuOptions([]);
      }
    };
    window.onYunxiaoStatusUpdated = (json: string) => {
      try {
        const r = JSON.parse(json) as {
          ok: boolean;
          bugId?: string;
          statusId?: string;
          statusName?: string;
          error?: string;
        };
        setStatusUpdating(false);
        if (r.ok && r.bugId && r.statusId) {
          setStatusError(null);
          updateBugStatus(r.bugId, { id: r.statusId, name: r.statusName });
          setStatusMenu(null);
        } else if (!r.ok) {
          // Surface the failure instead of silently doing nothing (was: "no reaction").
          // Keep the menu open so the error shows and the user can retry.
          setStatusErrorFromUpdate(true);
          setStatusError(r.error || '更新失败');
        }
      } catch {
        setStatusUpdating(false);
        setStatusErrorFromUpdate(true);
        setStatusError('更新失败');
      }
    };
    window.onYunxiaoAssigneeMembers = (json: string) => {
      try {
        const r = JSON.parse(json) as { ok: boolean; query?: string; members?: MemberOption[]; error?: string };
        if ((r.query ?? '') !== assigneeQueryRef.current) return; // stale response for an older query
        setAssigneeLoading(false);
        if (r.ok) {
          setAssigneeError(null);
          setAssigneeMembers(Array.isArray(r.members) ? r.members : []);
        } else {
          setAssigneeError(r.error || '加载失败');
          setAssigneeMembers([]);
        }
      } catch {
        setAssigneeLoading(false);
      }
    };
    window.onYunxiaoAssigneeUpdated = (json: string) => {
      try {
        const r = JSON.parse(json) as { ok: boolean; bugId?: string; userId?: string; name?: string; error?: string };
        setAssigneeUpdating(false);
        if (r.ok && r.bugId) {
          updateBugAssignee(r.bugId, { userId: r.userId, name: r.name });
          setAssigneeMenu(null);
        } else if (!r.ok) {
          setAssigneeError(r.error || '转交失败');
        }
      } catch {
        setAssigneeUpdating(false);
        setAssigneeError('转交失败');
      }
    };
    return () => {
      delete window.onYunxiaoStatuses;
      delete window.onYunxiaoStatusUpdated;
      delete window.onYunxiaoAssigneeMembers;
      delete window.onYunxiaoAssigneeUpdated;
    };
  }, [updateBugStatus, updateBugAssignee]);

  // Debounced member fetch while the 改负责人 picker is open (empty query = all members).
  useEffect(() => {
    if (!assigneeMenu) return;
    assigneeQueryRef.current = assigneeQuery;
    setAssigneeLoading(true);
    const id = setTimeout(() => {
      sendBridgeEvent('load_yunxiao_members', JSON.stringify({ query: assigneeQuery, callback: 'onYunxiaoAssigneeMembers' }));
    }, 180);
    return () => clearTimeout(id);
  }, [assigneeMenu, assigneeQuery]);

  // JCEF: focus the search box a frame after the picker opens (autoFocus is unreliable).
  useEffect(() => {
    if (!assigneeMenu) return;
    const raf = requestAnimationFrame(() => assigneeSearchRef.current?.focus());
    const fb = setTimeout(() => assigneeSearchRef.current?.focus(), 80);
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(fb);
    };
  }, [assigneeMenu]);

  // The bug set is replaced when the project changes → drop any stale selection
  // so the batch count never reflects bugs from another project.
  useEffect(() => {
    setSelectedIds(new Set());
  }, [selectedProjectId]);

  const openStatusMenu = (e: React.MouseEvent, bug: YunxiaoBug) => {
    e.stopPropagation();
    const typeId = bug.workitemType?.id;
    if (!typeId || !selectedProjectId) return; // need a type id to resolve the workflow
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    setStatusMenu({
      bugId: bugKey(bug),
      top: rect.bottom + 4,
      left: rect.left,
      currentStatusId: statusId(bug),
      currentStatusName: statusText(bug.status),
    });
    setStatusMenuOptions([]);
    setStatusError(null);
    setStatusErrorFromUpdate(false);
    setStatusLoading(true);
    setStatusUpdating(false);
    sendBridgeEvent(
      'load_yunxiao_statuses',
      JSON.stringify({
        bugId: bugKey(bug),
        projectId: selectedProjectId,
        workItemTypeId: typeId,
        currentStatusId: statusId(bug),
      }),
    );
  };

  // Is this option the bug's current status? Match by id, falling back to the
  // display name when the bug's status carried no id.
  const isCurrentStatus = (opt: StatusOption): boolean => {
    if (!statusMenu) return false;
    if (statusMenu.currentStatusId) return opt.id === statusMenu.currentStatusId;
    return !!statusMenu.currentStatusName && opt.name === statusMenu.currentStatusName;
  };

  const pickStatus = (opt: StatusOption) => {
    if (!statusMenu || statusUpdating) return;
    // Clicking the already-current status is a no-op — just close, skip the PUT.
    if (isCurrentStatus(opt)) {
      setStatusMenu(null);
      return;
    }
    setStatusUpdating(true);
    sendBridgeEvent(
      'update_yunxiao_status',
      JSON.stringify({ bugId: statusMenu.bugId, statusId: opt.id, statusName: opt.name }),
    );
  };

  // 改负责人: open the fixed-positioned member picker at the clicked chip. The initial
  // fetch (and every query change) is driven by the debounced effect above.
  const openAssigneeMenu = (e: React.MouseEvent, bug: YunxiaoBug) => {
    e.stopPropagation();
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    setAssigneeMenu({ bugId: bugKey(bug), top: rect.bottom + 4, left: rect.left });
    setAssigneeQuery('');
    assigneeQueryRef.current = '';
    setAssigneeMembers([]);
    setAssigneeError(null);
    setAssigneeUpdating(false);
    setAssigneeLoading(true);
  };

  const pickAssignee = (mem: MemberOption) => {
    if (!assigneeMenu || assigneeUpdating) return;
    setAssigneeUpdating(true);
    setAssigneeError(null);
    sendBridgeEvent(
      'update_yunxiao_assignee',
      JSON.stringify({ bugId: assigneeMenu.bugId, userId: mem.userId, name: mem.name }),
    );
  };

  const toggleStatus = (s: string) => {
    setSelectedStatuses((prev) => (prev.includes(s) ? prev.filter((x) => x !== s) : [...prev, s]));
  };

  // Options = the two defaults ∪ whatever statuses are present in the loaded bugs,
  // so the defaults are always togglable even before any matching bug loads.
  const statusOptions = useMemo(() => {
    const set = new Set<string>(DEFAULT_STATUSES);
    for (const b of bugs) {
      const s = statusText(b.status);
      if (s) set.add(s);
    }
    return Array.from(set);
  }, [bugs]);

  // Empty selection = no filter (show all), so the user is never stuck with a blank list.
  const filteredBugs = useMemo(() => {
    if (selectedStatuses.length === 0) return bugs;
    return bugs.filter((b) => selectedStatuses.includes(statusText(b.status)));
  }, [bugs, selectedStatuses]);

  // Shared prefill text for both「建会话」and「建监督者」. 展示用 serialNumber、工具入参用
  // identifier(双标识)。末尾追加用户在云效设置里配置的 appendPrompt(若有)。
  const buildPrefill = (bug: YunxiaoBug): string => {
    const serial = serialText(bug);
    const subject = bug.subject || '';
    const status = statusText(bug.status);
    // 工作项内部标识 = identifier(Java 已从云效 `id` 兜底映射);再兜一层 bug.id 防漂移。
    const identifier = bug.identifier || bug.id || '';
    const base =
      `请帮我诊断并修复云效缺陷 BUG-${serial}（标题：${subject}，状态：${status}）。\n` +
      `第一步必须调用 query_bug_details 工具，传入 bug id「${identifier}」拉取完整的基础信息、\n` +
      `所有评论和附件。该工具会把描述/评论里的所有截图下载到本地并返回路径，\n` +
      `你必须用 Read 工具逐个查看这些截图（看清实际画面/报错），完全理解问题后再制定修复方案并动手修复。`;
    const extra = appendPrompt.trim();
    return extra ? `${base}\n\n${extra}` : base;
  };

  // Multi-bug prefill (批量): all selected bugs in one supervisor/session. Asks the
  // model to group related bugs (same page / API / feature point) and fix each group
  // together. Same identifier/serialNumber 双标识 convention + appendPrompt tail as the
  // single-bug version.
  const buildPrefillMulti = (list: YunxiaoBug[]): string => {
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
      `3. 按分组顺序逐组推进，每修完一组再进行下一组，不要遗漏任何一个缺陷。`;
    const extra = appendPrompt.trim();
    return extra ? `${base}\n\n${extra}` : base;
  };

  // 建会话: 新开一个【普通】标签页并把预填文案写入 composer(不发送)。
  const handleCreateSession = (e: React.MouseEvent, bug: YunxiaoBug) => {
    e.stopPropagation();
    sendBridgeEvent('create_new_tab', JSON.stringify({ initialComposerText: buildPrefill(bug) }));
  };

  // 建监督者: 新建一个监督者标签页(自动选缺陷监督者)并预填(不发送)。
  const handleCreateSupervisor = (e: React.MouseEvent, bug: YunxiaoBug) => {
    e.stopPropagation();
    sendBridgeEvent(
      'create_new_supervised_tab',
      JSON.stringify({ agentId: 'bug-supervisor', initialComposerText: buildPrefill(bug) }),
    );
  };

  // ── 批量 ───────────────────────────────────────────────────────────
  const enterBatch = () => setBatchMode(true);
  const exitBatch = () => {
    setBatchMode(false);
    setSelectedIds(new Set());
  };

  const atCap = selectedIds.size >= MAX_BATCH;

  const toggleSelect = (bug: YunxiaoBug) => {
    const key = bugKey(bug);
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else if (next.size < MAX_BATCH) {
        next.add(key); // at cap → ignore (the checkbox is also disabled)
      }
      return next;
    });
  };

  // Select-all over the currently visible (filtered) bugs, capped at MAX_BATCH.
  // Toggles off when everything selectable is already selected.
  const toggleSelectAll = () => {
    const visibleKeys = filteredBugs.map(bugKey);
    const capped = visibleKeys.slice(0, MAX_BATCH);
    const allSelected = capped.length > 0 && capped.every((k) => selectedIds.has(k));
    setSelectedIds(allSelected ? new Set() : new Set(capped));
  };

  // Resolve selection to bug objects from the full loaded set (so toggling the status
  // filter never silently drops a checked bug). Preserves list order.
  const selectedBugs = useMemo(
    () => bugs.filter((b) => selectedIds.has(bugKey(b))),
    [bugs, selectedIds],
  );

  // 批量建会话: 选中的多个缺陷拼成一段预填，开【一个】普通标签页(不发送)。
  const handleBatchSession = () => {
    if (selectedBugs.length === 0) return;
    sendBridgeEvent('create_new_tab', JSON.stringify({ initialComposerText: buildPrefillMulti(selectedBugs) }));
    exitBatch();
  };

  // 批量建监督者: 选中的多个缺陷拼成一段预填，开【一个】缺陷监督者标签页(不发送)。
  const handleBatchSupervisor = () => {
    if (selectedBugs.length === 0) return;
    sendBridgeEvent(
      'create_new_supervised_tab',
      JSON.stringify({ agentId: 'bug-supervisor', initialComposerText: buildPrefillMulti(selectedBugs) }),
    );
    exitBatch();
  };

  const renderBugItem = (bug: YunxiaoBug) => {
    const serial = serialText(bug);
    const status = statusText(bug.status);
    const assignee = assigneeText(bug.assignedTo);
    const selected = selectedIds.has(bugKey(bug));
    return (
      <div className={`${styles.bugItem} ${batchMode && selected ? styles.bugItemSelected : ''}`}>
        {batchMode && (
          <input
            type="checkbox"
            className={styles.bugCheckbox}
            checked={selected}
            disabled={!selected && atCap}
            title={!selected && atCap ? t('bugList.batchCapHint', { max: MAX_BATCH }) : undefined}
            onClick={(e) => e.stopPropagation()}
            onChange={(e) => {
              e.stopPropagation();
              toggleSelect(bug);
            }}
          />
        )}
        <div className={styles.bugMain}>
          <div className={styles.bugTitleRow}>
            {serial && <span className={styles.bugSerial}>BUG-{serial}</span>}
            {status && (
              <span
                className={`${styles.bugStatus} ${bug.workitemType?.id ? styles.bugStatusClickable : ''}`}
                onClick={bug.workitemType?.id ? (e) => openStatusMenu(e, bug) : undefined}
                title={bug.workitemType?.id ? t('bugList.changeStatus') : undefined}
              >
                {status}
                {bug.workitemType?.id && <span className={styles.statusCaret}> ▾</span>}
              </span>
            )}
            <span
              className={styles.bugAssignee}
              onClick={(e) => openAssigneeMenu(e, bug)}
              title={t('bugList.reassign')}
            >
              <span className="codicon codicon-account" />
              {assignee || t('bugList.assigneeUnset')}
              <span className={styles.statusCaret}> ▾</span>
            </span>
          </div>
          <div className={styles.bugSubject} title={bug.subject || ''}>
            {bug.subject || t('bugList.untitled')}
          </div>
        </div>
        <div className={styles.bugActions}>
          {!batchMode && (
            <>
              <button type="button" className={styles.sessionBtn} onClick={(e) => handleCreateSession(e, bug)}>
                {t('bugList.createSession')}
              </button>
              <button type="button" className={styles.createBtn} onClick={(e) => handleCreateSupervisor(e, bug)}>
                {t('bugList.createSupervisor')}
              </button>
            </>
          )}
          <button
            type="button"
            className={styles.detailBtn}
            onClick={(e) => {
              e.stopPropagation();
              setDetailBugId(bug.identifier || bug.id || '');
            }}
          >
            {t('bugList.viewDetail')}
          </button>
        </div>
      </div>
    );
  };

  const renderBody = () => {
    // Most likely cause of a projects error is missing token/orgId → guide to settings.
    if (projectsError) {
      return (
        <div className={styles.emptyState}>
          <div className={styles.emptyIcon}>⚠️</div>
          <div>{t('bugList.notConfigured')}</div>
          <div className={styles.emptyDesc}>{projectsError}</div>
          <button type="button" className={styles.linkBtn} onClick={onOpenYunxiaoSettings}>
            {t('bugList.goToSettings')}
          </button>
        </div>
      );
    }

    if (!selectedProjectId) {
      return (
        <div className={styles.emptyState}>
          <div className={styles.emptyIcon}>📁</div>
          <div>{t('bugList.selectProjectFirst')}</div>
        </div>
      );
    }

    if (loading && bugs.length === 0) {
      return (
        <div className={styles.emptyState}>
          <div>{t('bugList.loading')}</div>
        </div>
      );
    }

    if (bugsError) {
      return (
        <div className={styles.emptyState}>
          <div className={styles.emptyIcon}>⚠️</div>
          <div>{t('bugList.loadFailed')}</div>
          <div className={styles.emptyDesc}>{bugsError}</div>
        </div>
      );
    }

    if (bugs.length === 0) {
      return (
        <div className={styles.emptyState}>
          <div className={styles.emptyIcon}>📭</div>
          <div>{t('bugList.empty')}</div>
        </div>
      );
    }

    // Bugs loaded but none on this page match the selected statuses. Still offer
    // 「加载更多」(client-side filter only sees loaded pages) so matches on later
    // pages remain reachable.
    if (filteredBugs.length === 0) {
      return (
        <div className={styles.listWrap}>
          <div className={styles.emptyState}>
            <div className={styles.emptyIcon}>🔍</div>
            <div>{t('bugList.noMatch')}</div>
          </div>
          {hasMore && (
            <div className={styles.loadMoreRow}>
              <button type="button" className={styles.loadMoreBtn} onClick={loadMore} disabled={loading}>
                {loading ? t('bugList.loading') : t('bugList.loadMore')}
              </button>
            </div>
          )}
        </div>
      );
    }

    const listHeight = Math.max(240, (window.innerHeight || 600) - 160);
    return (
      <div className={styles.listWrap}>
        <VirtualList
          items={filteredBugs}
          itemHeight={64}
          height={listHeight}
          renderItem={renderBugItem}
          getItemKey={(bug, index) => bug.identifier || serialText(bug) || index}
          className={styles.list}
        />
        {hasMore && (
          <div className={styles.loadMoreRow}>
            <button type="button" className={styles.loadMoreBtn} onClick={loadMore} disabled={loading}>
              {loading ? t('bugList.loading') : t('bugList.loadMore')}
            </button>
          </div>
        )}
      </div>
    );
  };

  return (
    <div className={styles.view}>
      <div className={styles.header}>
        <button type="button" className={styles.backBtn} onClick={onBack}>
          <span className="codicon codicon-arrow-left" /> {t('common.back')}
        </button>
        <span className={styles.title}>{t('bugList.title')}</span>

        <div className={styles.batchWrap}>
          {!batchMode ? (
            <button type="button" className={styles.batchToggle} onClick={enterBatch}>
              {t('bugList.batch')}
            </button>
          ) : (
            <>
              <button type="button" className={styles.batchToggle} onClick={exitBatch}>
                {t('bugList.batchCancel')}
              </button>
              <button type="button" className={styles.batchSelectAll} onClick={toggleSelectAll}>
                {t('bugList.selectAll')}
              </button>
              <span className={styles.batchCount}>
                {t('bugList.batchSelected', { count: selectedIds.size, max: MAX_BATCH })}
              </span>
              <button
                type="button"
                className={styles.createBtn}
                disabled={selectedBugs.length === 0}
                onClick={handleBatchSupervisor}
              >
                {t('bugList.createSupervisor')}
              </button>
              <button
                type="button"
                className={styles.sessionBtn}
                disabled={selectedBugs.length === 0}
                onClick={handleBatchSession}
              >
                {t('bugList.createSession')}
              </button>
            </>
          )}
        </div>

        <div className={styles.filterWrap}>
          <button
            type="button"
            className={styles.filterBtn}
            onClick={() => setFilterOpen((o) => !o)}
            title={t('bugList.statusFilter')}
          >
            {t('bugList.statusFilter')}
            {selectedStatuses.length > 0 && <span className={styles.filterCount}>{selectedStatuses.length}</span>}
            <span className="codicon codicon-chevron-down" />
          </button>
          {filterOpen && (
            <>
              <div className={styles.filterBackdrop} onClick={() => setFilterOpen(false)} />
              <div className={styles.filterPanel}>
                {statusOptions.map((s) => (
                  <label key={s} className={styles.filterOption}>
                    <input
                      type="checkbox"
                      checked={selectedStatuses.includes(s)}
                      onChange={() => toggleStatus(s)}
                    />
                    <span>{s}</span>
                  </label>
                ))}
              </div>
            </>
          )}
        </div>

        <select
          className={styles.projectSelect}
          value={selectedProjectId}
          onChange={(e) => selectProject(e.target.value)}
          disabled={projects.length === 0}
        >
          <option value="">{t('bugList.selectProject')}</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </div>
      {renderBody()}
      {detailBugId && <BugDetailModal bugId={detailBugId} onClose={() => setDetailBugId(null)} />}

      {statusMenu && (
        <>
          <div className={styles.statusBackdrop} onClick={() => setStatusMenu(null)} />
          <div className={styles.statusMenu} style={{ top: statusMenu.top, left: statusMenu.left }}>
            {statusLoading && <div className={styles.statusMenuHint}>{t('bugList.loading')}</div>}
            {!statusLoading && statusError && (
              <div className={styles.statusMenuHint} title={statusError}>
                {t(statusErrorFromUpdate ? 'bugList.statusUpdateFailed' : 'bugList.statusFailed', { error: statusError })}
              </div>
            )}
            {!statusLoading && !statusError && statusMenuOptions.length === 0 && (
              <div className={styles.statusMenuHint}>{t('bugList.noStatuses')}</div>
            )}
            {!statusLoading &&
              statusMenuOptions.map((s) => {
                const current = isCurrentStatus(s);
                return (
                  <button
                    key={s.id}
                    type="button"
                    className={`${styles.statusOption} ${current ? styles.statusOptionCurrent : ''}`}
                    disabled={statusUpdating}
                    aria-current={current}
                    onClick={() => pickStatus(s)}
                  >
                    <span className={styles.statusDot} style={{ background: s.color || '#888' }} />
                    {s.name}
                    {current && <span className={styles.statusCheck}>✓</span>}
                  </button>
                );
              })}
          </div>
        </>
      )}

      {assigneeMenu && (
        <>
          <div className={styles.statusBackdrop} onClick={() => setAssigneeMenu(null)} />
          <div className={styles.assigneeMenu} style={{ top: assigneeMenu.top, left: assigneeMenu.left }}>
            <input
              ref={assigneeSearchRef}
              type="text"
              className={styles.assigneeSearch}
              value={assigneeQuery}
              onChange={(e) => setAssigneeQuery(e.target.value)}
              placeholder={t('bugList.assigneeSearch')}
              disabled={assigneeUpdating}
            />
            <div className={styles.assigneeList}>
              {assigneeLoading && assigneeMembers.length === 0 && (
                <div className={styles.statusMenuHint}>{t('bugList.loading')}</div>
              )}
              {!assigneeLoading && assigneeError && (
                <div className={styles.statusMenuHint} title={assigneeError}>
                  {t('bugList.reassignFailed', { error: assigneeError })}
                </div>
              )}
              {!assigneeLoading && !assigneeError && assigneeMembers.length === 0 && (
                <div className={styles.statusMenuHint}>{t('bugList.assigneeEmpty')}</div>
              )}
              {assigneeMembers.map((m) => (
                <button
                  key={m.userId || m.name}
                  type="button"
                  className={styles.statusOption}
                  disabled={assigneeUpdating}
                  onClick={() => pickAssignee(m)}
                >
                  <span className="codicon codicon-account" />
                  {m.name}
                </button>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default BugListView;
