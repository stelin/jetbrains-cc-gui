import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

/* ──────────────────────────────────────────────────────────────────
 * YunxiaoSection
 *
 * Configure Alibaba Cloud DevOps (云效) credentials — access token +
 * organizationId (+ optional domain) — used by the "我的缺陷" list to
 * fetch bugs assigned to the current user. Mirrors RemoteServerSection's
 * load → edit → save / test-connection pattern.
 *
 * Wires to Java handlers:
 *   - get_yunxiao_config        → window.updateYunxiaoConfig
 *   - set_yunxiao_config        → window.updateYunxiaoConfig
 *   - yunxiao_test_connection   → window.onYunxiaoTestResult
 * ──────────────────────────────────────────────────────────────── */

const sendToJava = (msg: string) => {
  if (window.sendToJava) window.sendToJava(msg);
};

interface YunxiaoConfigState {
  token: string;
  organizationId: string;
  domain: string;
  /** Resolved current-user id (云效 hex string). Read-only; only set after a successful test. */
  userId: string;
  /** Default project for the「我的缺陷」list (id = workitem-search spaceId). */
  defaultProjectId: string;
  defaultProjectName: string;
  /** Prompt auto-appended to the「建会话」/「建监督者」prefill text. */
  appendPrompt: string;
}

interface YunxiaoProjectOption {
  id: string;
  name: string;
}

interface TestResult {
  ok: boolean;
  userId?: string; // 云效 user id is an ObjectId-style hex string, not a number
  error?: string;
}

const DEFAULT_DOMAIN = 'openapi-rdc.aliyuncs.com';

export function YunxiaoSection() {
  const { t } = useTranslation();

  const [state, setState] = useState<YunxiaoConfigState>({
    token: '',
    organizationId: '',
    domain: DEFAULT_DOMAIN,
    userId: '',
    defaultProjectId: '',
    defaultProjectName: '',
    appendPrompt: '',
  });
  const [projects, setProjects] = useState<YunxiaoProjectOption[]>([]);
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'ok' | 'fail'>('idle');
  const [testMsg, setTestMsg] = useState<string>('');
  const [saveHint, setSaveHint] = useState<string>('');

  // Initial load + register window callbacks
  useEffect(() => {
    window.updateYunxiaoConfig = (json: string) => {
      try {
        const next = JSON.parse(json) as Partial<YunxiaoConfigState>;
        setState({
          token: next.token || '',
          organizationId: next.organizationId || '',
          domain: next.domain || DEFAULT_DOMAIN,
          userId: next.userId || '',
          defaultProjectId: next.defaultProjectId || '',
          defaultProjectName: next.defaultProjectName || '',
          appendPrompt: next.appendPrompt || '',
        });
      } catch (e) {
        console.error('[YunxiaoSection] updateYunxiaoConfig parse failed', e);
      }
    };
    window.onYunxiaoTestResult = (json: string) => {
      try {
        const r = JSON.parse(json) as TestResult;
        if (r.ok) {
          setTestStatus('ok');
          setTestMsg(t('settings.yunxiao.testOk', { userId: r.userId ?? '?' }));
          // Persisted server-side; reflect it in the read-only field too.
          if (r.userId) setState((s) => ({ ...s, userId: String(r.userId) }));
        } else {
          setTestStatus('fail');
          setTestMsg(t('settings.yunxiao.testFail', { error: r.error || '' }));
        }
      } catch {
        setTestStatus('fail');
        setTestMsg(t('settings.yunxiao.testFail', { error: '' }));
      }
    };
    sendToJava('get_yunxiao_config:');
    return () => {
      delete window.updateYunxiaoConfig;
      delete window.onYunxiaoTestResult;
    };
  }, [t]);

  // Load projects for the default-project picker — only once connected (a resolved
  // userId means token+orgId are valid). Reloads whenever the user re-tests.
  useEffect(() => {
    if (!state.userId) {
      setProjects([]);
      return;
    }
    window.onYunxiaoProjects = (json: string) => {
      try {
        const p = JSON.parse(json) as { ok: boolean; projects?: Array<Record<string, unknown>> };
        if (p.ok && Array.isArray(p.projects)) {
          setProjects(
            p.projects
              .map((x) => ({
                id: String(x.id ?? x.identifier ?? ''),
                name: String(x.name ?? x.displayName ?? x.id ?? ''),
              }))
              .filter((x) => !!x.id),
          );
        }
      } catch {
        /* ignore — picker just stays empty */
      }
    };
    sendToJava('load_yunxiao_projects:');
    return () => {
      delete window.onYunxiaoProjects;
    };
  }, [state.userId]);

  const onFieldChange = useCallback(
    <K extends keyof YunxiaoConfigState>(key: K, value: YunxiaoConfigState[K]) => {
      setState((s) => ({
        ...s,
        [key]: value,
        // editing credentials invalidates the resolved user id + default project until re-tested
        ...(key === 'token' || key === 'organizationId'
          ? { userId: '', defaultProjectId: '', defaultProjectName: '' }
          : {}),
      }));
      setTestStatus('idle');
      setTestMsg('');
    },
    [],
  );

  const onSave = useCallback(() => {
    // userId is server-resolved/read-only — never part of the editable save payload.
    const payload: Omit<YunxiaoConfigState, 'userId'> = {
      token: state.token.trim(),
      organizationId: state.organizationId.trim(),
      domain: state.domain.trim() || DEFAULT_DOMAIN,
      defaultProjectId: state.defaultProjectId,
      defaultProjectName: state.defaultProjectName,
      appendPrompt: state.appendPrompt,
    };
    sendToJava(`set_yunxiao_config:${JSON.stringify(payload)}`);
    setSaveHint(t('settings.yunxiao.saved'));
    setTimeout(() => setSaveHint(''), 4000);
  }, [state, t]);

  const onTest = useCallback(() => {
    setTestStatus('testing');
    setTestMsg(t('settings.yunxiao.testing'));
    sendToJava(
      `yunxiao_test_connection:${JSON.stringify({
        token: state.token.trim(),
        organizationId: state.organizationId.trim(),
        domain: state.domain.trim() || DEFAULT_DOMAIN,
      })}`,
    );
  }, [state, t]);

  return (
    <div className={styles.section}>
      <div className={styles.header}>
        <span className={styles.title}>{t('settings.yunxiao.title')}</span>
        <span className={styles.subtitle}>{t('settings.yunxiao.subtitle')}</span>
      </div>

      <div className={styles.row}>
        <label className={styles.label}>{t('settings.yunxiao.token')}</label>
        <input
          type="password"
          className={styles.input}
          placeholder={t('settings.yunxiao.tokenPlaceholder')}
          value={state.token}
          onChange={(e) => onFieldChange('token', e.target.value)}
        />
      </div>
      <div className={styles.hint}>{t('settings.yunxiao.tokenHint')}</div>

      <div className={styles.row}>
        <label className={styles.label}>{t('settings.yunxiao.orgId')}</label>
        <input
          type="text"
          className={styles.input}
          placeholder={t('settings.yunxiao.orgIdPlaceholder')}
          value={state.organizationId}
          onChange={(e) => onFieldChange('organizationId', e.target.value)}
        />
      </div>

      {state.userId && (
        <div className={styles.row}>
          <label className={styles.label}>{t('settings.yunxiao.userId')}</label>
          <input
            type="text"
            className={styles.input}
            value={state.userId}
            readOnly
            title={t('settings.yunxiao.userIdHint')}
          />
        </div>
      )}

      {state.userId && (
        <>
          <div className={styles.row}>
            <label className={styles.label}>{t('settings.yunxiao.defaultProject')}</label>
            <select
              className={styles.input}
              value={state.defaultProjectId}
              onChange={(e) => {
                const id = e.target.value;
                const name = projects.find((p) => p.id === id)?.name || '';
                setState((s) => ({ ...s, defaultProjectId: id, defaultProjectName: name }));
              }}
            >
              <option value="">{t('settings.yunxiao.defaultProjectNone')}</option>
              {/* keep the saved default selectable even before the list loads / if it's gone */}
              {state.defaultProjectId && !projects.some((p) => p.id === state.defaultProjectId) && (
                <option value={state.defaultProjectId}>
                  {state.defaultProjectName || state.defaultProjectId}
                </option>
              )}
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div className={styles.hint}>{t('settings.yunxiao.defaultProjectHint')}</div>
        </>
      )}

      <div className={styles.row}>
        <label className={styles.label}>{t('settings.yunxiao.domain')}</label>
        <input
          type="text"
          className={styles.input}
          placeholder={t('settings.yunxiao.domainPlaceholder')}
          value={state.domain}
          onChange={(e) => onFieldChange('domain', e.target.value)}
        />
      </div>
      <div className={styles.hint}>{t('settings.yunxiao.domainHint')}</div>

      <div className={styles.promptRow}>
        <label className={styles.label}>{t('settings.yunxiao.appendPrompt')}</label>
        <textarea
          className={styles.textarea}
          rows={6}
          placeholder={t('settings.yunxiao.appendPromptPlaceholder')}
          value={state.appendPrompt}
          onChange={(e) => setState((s) => ({ ...s, appendPrompt: e.target.value }))}
        />
        <div className={styles.promptHint}>{t('settings.yunxiao.appendPromptHint')}</div>
      </div>

      <div className={styles.row}>
        <button type="button" className={styles.btn} onClick={onSave}>
          {t('settings.yunxiao.save')}
        </button>
        <button
          type="button"
          className={styles.btn}
          onClick={onTest}
          disabled={testStatus === 'testing' || !state.token.trim() || !state.organizationId.trim()}
        >
          {testStatus === 'testing' ? t('settings.yunxiao.testing') : t('settings.yunxiao.test')}
        </button>
        {saveHint && <span className={styles.testOk}>{saveHint}</span>}
      </div>

      {testStatus !== 'idle' && testStatus !== 'testing' && (
        <div className={testStatus === 'ok' ? styles.testOk : styles.testFail}>{testMsg}</div>
      )}
    </div>
  );
}

export default YunxiaoSection;
