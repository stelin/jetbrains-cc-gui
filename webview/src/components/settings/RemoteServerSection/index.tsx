import { useState, useEffect, useCallback } from 'react';
import styles from './style.module.less';

/* ──────────────────────────────────────────────────────────────────
 * RemoteServerSection
 *
 * Switches the daemon between local-spawn mode and a remote
 * ai-bridge-server (HTTP + SSE). Shows connection-test feedback,
 * the per-project path-mapping panel, and a notice listing features
 * that are downgraded in remote mode.
 *
 * Wires to Java handlers:
 *   - get_remote_mode             → window.updateRemoteMode
 *   - set_remote_mode             → window.updateRemoteMode
 *   - test_remote_connection      → window.updateRemoteConnectionTest
 *   - get_path_mapping            → window.updatePathMapping
 *   - set_path_mapping            → window.updatePathMapping
 *   - get_path_misses             → window.updatePathMisses
 *   - clear_path_misses           → window.updatePathMisses
 * ──────────────────────────────────────────────────────────────── */

const sendToJava = (msg: string) => {
  if (window.sendToJava) window.sendToJava(msg);
};

interface RemoteModeState {
  daemonMode: 'local' | 'remote';
  remoteServerUrl: string;
  rebuilt?: boolean;
}

interface ConnTestResult {
  ok: boolean;
  status?: number;
  body?: string;
  error?: string;
  version?: string;
}

type OsType = '' | 'WIN' | 'LINUX' | 'MAC';

interface PathMappingState {
  enabled: boolean;
  localOs: OsType;
  localRoot: string;
  remoteOs: OsType;
  remoteRoot: string;
}

interface PathMissesState {
  outboundCount: number;
  inboundCount: number;
  outboundSamples: string[];
}

declare global {
  interface Window {
    updateRemoteMode?: (json: string) => void;
    updateRemoteConnectionTest?: (json: string) => void;
    updatePathMapping?: (json: string) => void;
    updatePathMisses?: (json: string) => void;
    sendToJava?: (msg: string) => void;
  }
}

const OS_OPTIONS: { value: OsType; label: string }[] = [
  { value: '',      label: '请选择…' },
  { value: 'WIN',   label: 'Windows' },
  { value: 'LINUX', label: 'Linux' },
  { value: 'MAC',   label: 'macOS' },
];

export function RemoteServerSection() {
  const [state, setState] = useState<RemoteModeState>({
    daemonMode: 'local',
    remoteServerUrl: 'http://localhost:3284',
  });
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'ok' | 'fail'>('idle');
  const [testMsg, setTestMsg] = useState<string>('');
  const [rebuildHint, setRebuildHint] = useState<string>('');

  // Path mapping (per-project) — only meaningful in remote mode.
  const [mapping, setMapping] = useState<PathMappingState>({
    enabled: false,
    localOs: '',
    localRoot: '',
    remoteOs: '',
    remoteRoot: '',
  });
  const [mappingExpanded, setMappingExpanded] = useState<boolean>(false);
  const [mappingSaveHint, setMappingSaveHint] = useState<string>('');
  const [misses, setMisses] = useState<PathMissesState>({
    outboundCount: 0,
    inboundCount: 0,
    outboundSamples: [],
  });
  const [missesPanelOpen, setMissesPanelOpen] = useState<boolean>(false);

  // Initial load + register window callback
  useEffect(() => {
    window.updateRemoteMode = (json: string) => {
      try {
        const next = JSON.parse(json) as RemoteModeState;
        setState({
          daemonMode: next.daemonMode === 'remote' ? 'remote' : 'local',
          remoteServerUrl: next.remoteServerUrl || 'http://localhost:3284',
        });
        if (next.rebuilt) {
          setRebuildHint('✓ daemon 已重建，下次发送消息时使用新配置');
          setTimeout(() => setRebuildHint(''), 6000);
        }
      } catch (e) {
        console.error('[RemoteServerSection] updateRemoteMode parse failed', e);
      }
    };
    window.updateRemoteConnectionTest = (json: string) => {
      try {
        const r = JSON.parse(json) as ConnTestResult;
        if (r.ok) {
          setTestStatus('ok');
          setTestMsg(`✓ 连接成功${r.version ? ' · ' + r.version : ''}`);
        } else {
          setTestStatus('fail');
          setTestMsg(r.error || `HTTP ${r.status || '?'}: ${r.body || ''}`);
        }
      } catch {
        setTestStatus('fail');
        setTestMsg('响应解析失败');
      }
    };
    window.updatePathMapping = (json: string) => {
      try {
        const next = JSON.parse(json) as PathMappingState;
        setMapping({
          enabled:    !!next.enabled,
          localOs:   (next.localOs   as OsType) || '',
          localRoot:  next.localRoot  || '',
          remoteOs:  (next.remoteOs  as OsType) || '',
          remoteRoot: next.remoteRoot || '',
        });
      } catch (e) {
        console.error('[RemoteServerSection] updatePathMapping parse failed', e);
      }
    };
    window.updatePathMisses = (json: string) => {
      try {
        const next = JSON.parse(json) as PathMissesState;
        setMisses({
          outboundCount:    next.outboundCount    || 0,
          inboundCount:     next.inboundCount     || 0,
          outboundSamples:  next.outboundSamples  || [],
        });
      } catch (e) {
        console.error('[RemoteServerSection] updatePathMisses parse failed', e);
      }
    };
    sendToJava('get_remote_mode:');
    sendToJava('get_path_mapping:');
    sendToJava('get_path_misses:');
    return () => {
      delete window.updateRemoteMode;
      delete window.updateRemoteConnectionTest;
      delete window.updatePathMapping;
      delete window.updatePathMisses;
    };
  }, []);

  const persist = useCallback((next: RemoteModeState) => {
    sendToJava(`set_remote_mode:${JSON.stringify(next)}`);
  }, []);

  const onModeChange = (mode: 'local' | 'remote') => {
    const next = { ...state, daemonMode: mode };
    setState(next);
    persist(next);
    setTestStatus('idle');
    setTestMsg('');
  };

  const onUrlChange = (url: string) => {
    setState((s) => ({ ...s, remoteServerUrl: url }));
    setTestStatus('idle');
    setTestMsg('');
  };

  const onUrlBlur = () => {
    persist(state);
  };

  const onTest = () => {
    setTestStatus('testing');
    setTestMsg('测试中…');
    sendToJava(`test_remote_connection:${JSON.stringify({ remoteServerUrl: state.remoteServerUrl })}`);
  };

  const onMappingSave = () => {
    sendToJava(`set_path_mapping:${JSON.stringify(mapping)}`);
    setMappingSaveHint('✓ 已保存，下次发送消息时使用新映射');
    setTimeout(() => setMappingSaveHint(''), 5000);
  };

  const onMappingFieldChange = <K extends keyof PathMappingState>(key: K, value: PathMappingState[K]) => {
    setMapping((m) => ({ ...m, [key]: value }));
  };

  const onClearMisses = () => {
    sendToJava('clear_path_misses:');
  };

  const mappingValid =
    mapping.enabled
      ? !!mapping.localOs && !!mapping.localRoot && !!mapping.remoteOs && !!mapping.remoteRoot
      : true;

  return (
    <div className={styles.section}>
      <div className={styles.header}>
        <span className={styles.title}>远程模式</span>
        <span className={styles.subtitle}>将 daemon 部署到远程 ai-bridge-server</span>
      </div>

      <div className={styles.row}>
        <label className={styles.radio}>
          <input
            type="radio"
            name="daemonMode"
            value="local"
            checked={state.daemonMode === 'local'}
            onChange={() => onModeChange('local')}
          />
          <span>本地</span>
        </label>
        <label className={styles.radio}>
          <input
            type="radio"
            name="daemonMode"
            value="remote"
            checked={state.daemonMode === 'remote'}
            onChange={() => onModeChange('remote')}
          />
          <span>远程</span>
        </label>
      </div>

      {state.daemonMode === 'local' && rebuildHint && (
        <div className={styles.testOk}>{rebuildHint}</div>
      )}

      {state.daemonMode === 'remote' && (
        <>
          <div className={styles.row}>
            <label className={styles.label}>服务地址</label>
            <input
              type="url"
              className={styles.input}
              placeholder="http://192.168.1.100:3284"
              value={state.remoteServerUrl}
              onChange={(e) => onUrlChange(e.target.value)}
              onBlur={onUrlBlur}
            />
            <button
              type="button"
              className={styles.btn}
              onClick={onTest}
              disabled={testStatus === 'testing'}
            >
              {testStatus === 'testing' ? '测试中…' : '测试连接'}
            </button>
          </div>

          {testStatus !== 'idle' && (
            <div
              className={
                testStatus === 'ok'
                  ? styles.testOk
                  : testStatus === 'fail'
                  ? styles.testFail
                  : styles.testInfo
              }
            >
              {testMsg}
            </div>
          )}
          {rebuildHint && <div className={styles.testOk}>{rebuildHint}</div>}

          {/* Path mapping panel */}
          <div className={styles.subsection}>
            <div
              className={styles.subsectionHeader}
              onClick={() => setMappingExpanded((v) => !v)}
            >
              <span className={styles.subsectionTitle}>
                {mappingExpanded ? '▾' : '▸'} 路径映射（本项目）
              </span>
              {misses.outboundCount > 0 && (
                <span
                  className={styles.badge}
                  onClick={(e) => {
                    e.stopPropagation();
                    setMissesPanelOpen((v) => !v);
                    sendToJava('get_path_misses:');
                  }}
                  title="点击查看未命中映射的路径列表"
                >
                  ⚠ {misses.outboundCount} 条未命中
                </span>
              )}
            </div>

            {mappingExpanded && (
              <div className={styles.subsectionBody}>
                <div className={styles.row}>
                  <label className={styles.checkbox}>
                    <input
                      type="checkbox"
                      checked={mapping.enabled}
                      onChange={(e) => onMappingFieldChange('enabled', e.target.checked)}
                    />
                    <span>启用映射</span>
                  </label>
                </div>

                <div className={styles.row}>
                  <label className={styles.label}>本地系统</label>
                  <select
                    className={styles.input}
                    value={mapping.localOs}
                    onChange={(e) => onMappingFieldChange('localOs', e.target.value as OsType)}
                    disabled={!mapping.enabled}
                  >
                    {OS_OPTIONS.map((o) => (
                      <option key={o.value || 'none'} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                </div>

                <div className={styles.row}>
                  <label className={styles.label}>本地根目录</label>
                  <input
                    type="text"
                    className={styles.input}
                    placeholder="例如 D:\\www\\ai\\proj-x"
                    value={mapping.localRoot}
                    onChange={(e) => onMappingFieldChange('localRoot', e.target.value)}
                    disabled={!mapping.enabled}
                  />
                </div>

                <div className={styles.row}>
                  <label className={styles.label}>远端系统</label>
                  <select
                    className={styles.input}
                    value={mapping.remoteOs}
                    onChange={(e) => onMappingFieldChange('remoteOs', e.target.value as OsType)}
                    disabled={!mapping.enabled}
                  >
                    {OS_OPTIONS.map((o) => (
                      <option key={o.value || 'none'} value={o.value}>{o.label}</option>
                    ))}
                  </select>
                </div>

                <div className={styles.row}>
                  <label className={styles.label}>远端根目录</label>
                  <input
                    type="text"
                    className={styles.input}
                    placeholder="例如 /home/devuser/projects/proj-x"
                    value={mapping.remoteRoot}
                    onChange={(e) => onMappingFieldChange('remoteRoot', e.target.value)}
                    disabled={!mapping.enabled}
                  />
                </div>

                <div className={styles.row}>
                  <button
                    type="button"
                    className={styles.btn}
                    disabled={!mappingValid}
                    onClick={onMappingSave}
                  >
                    保存映射
                  </button>
                  {!mappingValid && (
                    <span className={styles.testFail}>请填完所有字段后再保存</span>
                  )}
                </div>

                {mappingSaveHint && <div className={styles.testOk}>{mappingSaveHint}</div>}

                {missesPanelOpen && (
                  <div className={styles.missesPanel}>
                    <div className={styles.missesHeader}>
                      <span>未命中映射的路径（最多展示 50 条）</span>
                      <button type="button" className={styles.btnSmall} onClick={onClearMisses}>清空</button>
                    </div>
                    {misses.outboundSamples.length === 0 ? (
                      <div className={styles.missesEmpty}>暂无</div>
                    ) : (
                      <ul className={styles.missesList}>
                        {misses.outboundSamples.map((s, i) => (
                          <li key={i}>{s}</li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>

          <div className={styles.notice}>
            <div className={styles.noticeTitle}>⚠ 远程模式下以下功能不可在 IDE 内编辑：</div>
            <ul>
              <li>Settings：API key / base URL（请在容器内 ~/.claude/settings.json 配置）</li>
              <li>MCP 服务器管理（容器内 ~/.claude.json）</li>
              <li>Skills 管理（容器内 ~/.codemoss/skills/）</li>
              <li>Provider 管理（容器内 ~/.codemoss/config.json）</li>
              <li>Checkpoint / Rewind</li>
              <li>文件附件 / 图片粘贴（一期不支持）</li>
            </ul>
            <div className={styles.noticeFoot}>
              切换模式后请重启 IDE 或重新打开 Chat 窗口以使配置生效。
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default RemoteServerSection;
