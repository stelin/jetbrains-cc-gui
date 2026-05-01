import { useState, useEffect, useCallback } from 'react';
import styles from './style.module.less';

/* ──────────────────────────────────────────────────────────────────
 * RemoteServerSection
 *
 * Switches the daemon between local-spawn mode and a remote
 * ai-bridge-server (HTTP + SSE). Shows connection-test feedback and
 * a notice listing features that are downgraded in remote mode.
 *
 * Wires to Java handlers:
 *   - get_remote_mode             → window.updateRemoteMode
 *   - set_remote_mode             → window.updateRemoteMode
 *   - test_remote_connection      → window.updateRemoteConnectionTest
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

declare global {
  interface Window {
    updateRemoteMode?: (json: string) => void;
    updateRemoteConnectionTest?: (json: string) => void;
    sendToJava?: (msg: string) => void;
  }
}

export function RemoteServerSection() {
  const [state, setState] = useState<RemoteModeState>({
    daemonMode: 'local',
    remoteServerUrl: 'http://localhost:3284',
  });
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'ok' | 'fail'>('idle');
  const [testMsg, setTestMsg] = useState<string>('');
  const [rebuildHint, setRebuildHint] = useState<string>('');

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
    sendToJava('get_remote_mode:');
    return () => {
      delete window.updateRemoteMode;
      delete window.updateRemoteConnectionTest;
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
