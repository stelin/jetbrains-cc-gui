import { useCallback, useEffect, useState } from 'react';
import { sendBridgeEvent } from '../utils/bridge';

/* ──────────────────────────────────────────────────────────────────
 * useYunxiaoBugs
 *
 * Drives the「我的缺陷」list (需求2): loads the project dropdown, then
 * the bugs assigned to the current user within the selected project,
 * with「加载更多」pagination that monotonically appends.
 *
 * Wires to Java handlers (routed via SettingsHandler):
 *   - load_yunxiao_projects → window.onYunxiaoProjects
 *   - load_yunxiao_bugs     → window.onYunxiaoBugs (carries page + hasMore)
 * ──────────────────────────────────────────────────────────────── */

const PER_PAGE = 50;

export interface YunxiaoProject {
  id: string;
  name: string;
}

/** A bug list-item. Display uses serialNumber; the【建监督者】flow uses identifier (= workitem id). */
export interface YunxiaoBug {
  /** 云效 workitem id — the GetWorkitem key. Java maps it onto `identifier` too. */
  id?: string;
  identifier?: string;
  serialNumber?: string | number;
  subject?: string;
  status?: string | { id?: string; displayName?: string; name?: string } | null;
  /** 负责人 — may be a userId string, a {displayName|name} object, or an array of either. */
  assignedTo?: string | { id?: string; userId?: string; displayName?: string; name?: string } | unknown[] | null;
  /** Workitem type ({id} needed to fetch the status workflow for in-list status change). */
  workitemType?: { id?: string; name?: string } | null;
  [key: string]: unknown;
}

interface ProjectsPayload {
  ok: boolean;
  error?: string;
  projects?: Array<Record<string, unknown>>;
  /** Configured default project — auto-selected on open so no manual pick is needed. */
  defaultProjectId?: string;
  /** Configured prompt auto-appended to the「建会话」/「建监督者」prefill text. */
  appendPrompt?: string;
}

interface BugsPayload {
  ok: boolean;
  error?: string;
  page?: number;
  hasMore?: boolean;
  bugs?: YunxiaoBug[];
}

export interface UseYunxiaoBugsResult {
  projects: YunxiaoProject[];
  projectsError: string | null;
  selectedProjectId: string;
  selectProject: (id: string) => void;
  bugs: YunxiaoBug[];
  bugsError: string | null;
  loading: boolean;
  hasMore: boolean;
  loadMore: () => void;
  /** Configured prompt appended to the「建会话」/「建监督者」prefill (empty if unset). */
  appendPrompt: string;
  /** Patch a bug's status in-place after a successful status change. */
  updateBugStatus: (bugId: string, status: { id?: string; name?: string }) => void;
  /** Patch a bug's 负责人 in-place after a successful reassign. */
  updateBugAssignee: (bugId: string, assignee: { userId?: string; name?: string }) => void;
}

function normalizeProject(p: Record<string, unknown>): YunxiaoProject {
  const id = (p?.id ?? p?.identifier ?? '') as string | number;
  const name = (p?.name ?? p?.displayName ?? id) as string | number;
  return { id: String(id), name: String(name) };
}

export function useYunxiaoBugs(): UseYunxiaoBugsResult {
  const [projects, setProjects] = useState<YunxiaoProject[]>([]);
  const [projectsError, setProjectsError] = useState<string | null>(null);
  const [selectedProjectId, setSelectedProjectId] = useState<string>('');
  const [bugs, setBugs] = useState<YunxiaoBug[]>([]);
  const [bugsError, setBugsError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [page, setPage] = useState(1);
  const [defaultProjectId, setDefaultProjectId] = useState('');
  const [appendPrompt, setAppendPrompt] = useState('');

  // Register window callbacks for the lifetime of the view.
  useEffect(() => {
    window.onYunxiaoProjects = (json: string) => {
      try {
        const p = JSON.parse(json) as ProjectsPayload;
        if (p.ok && Array.isArray(p.projects)) {
          setProjects(p.projects.map(normalizeProject).filter((x) => !!x.id));
          setProjectsError(null);
          setDefaultProjectId(p.defaultProjectId || '');
          setAppendPrompt(p.appendPrompt || '');
        } else {
          setProjects([]);
          setProjectsError(p.error || 'load failed');
        }
      } catch {
        setProjectsError('parse failed');
      }
    };
    window.onYunxiaoBugs = (json: string) => {
      try {
        const p = JSON.parse(json) as BugsPayload;
        setLoading(false);
        if (p.ok) {
          setBugsError(null);
          setHasMore(!!p.hasMore);
          const incoming = Array.isArray(p.bugs) ? p.bugs : [];
          // page<=1 replaces (fresh project select); page>1 appends monotonically.
          if ((p.page ?? 1) <= 1) {
            setBugs(incoming);
          } else {
            setBugs((prev) => [...prev, ...incoming]);
          }
        } else {
          setBugsError(p.error || 'load failed');
          setHasMore(false);
        }
      } catch {
        setLoading(false);
        setBugsError('parse failed');
      }
    };
    return () => {
      delete window.onYunxiaoProjects;
      delete window.onYunxiaoBugs;
    };
  }, []);

  // Load the project dropdown when the view opens.
  useEffect(() => {
    sendBridgeEvent('load_yunxiao_projects');
  }, []);

  const fetchBugs = useCallback((projectId: string, targetPage: number) => {
    if (!projectId) return;
    setLoading(true);
    sendBridgeEvent(
      'load_yunxiao_bugs',
      JSON.stringify({ projectId, page: targetPage, perPage: PER_PAGE }),
    );
  }, []);

  const selectProject = useCallback(
    (id: string) => {
      setSelectedProjectId(id);
      setBugs([]);
      setBugsError(null);
      setHasMore(false);
      setPage(1);
      if (id) {
        fetchBugs(id, 1);
      }
    },
    [fetchBugs],
  );

  // Auto-select the configured default project once it (and the list) are available,
  // so the list opens straight onto the default instead of an empty「请选择项目」state.
  useEffect(() => {
    if (!selectedProjectId && defaultProjectId && projects.some((p) => p.id === defaultProjectId)) {
      selectProject(defaultProjectId);
    }
  }, [defaultProjectId, projects, selectedProjectId, selectProject]);

  const updateBugStatus = useCallback((bugId: string, status: { id?: string; name?: string }) => {
    setBugs((prev) => prev.map((b) => ((b.id === bugId || b.identifier === bugId) ? { ...b, status } : b)));
  }, []);

  const updateBugAssignee = useCallback((bugId: string, assignee: { userId?: string; name?: string }) => {
    setBugs((prev) => prev.map((b) => ((b.id === bugId || b.identifier === bugId) ? { ...b, assignedTo: assignee } : b)));
  }, []);

  const loadMore = useCallback(() => {
    if (!hasMore || loading || !selectedProjectId) return;
    const next = page + 1;
    setPage(next);
    fetchBugs(selectedProjectId, next);
  }, [hasMore, loading, selectedProjectId, page, fetchBugs]);

  return {
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
  };
}
