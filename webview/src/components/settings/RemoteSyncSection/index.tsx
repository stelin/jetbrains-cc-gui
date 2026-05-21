import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';
import { MutagenSdkCard, type MutagenSdkStatus } from './MutagenSdkCard';
import { SyncConfigForm, type SyncConfig, type SyncMode, type RemoteOs } from './SyncConfigForm';
import { HostKeyDialog, type HostKeyChallenge } from './HostKeyDialog';
import { SyncStatusPanel, type SyncStatus } from './SyncStatusPanel';
import {
  ensureRemoteSyncBridge,
  SYNC_FULL_STATE_EVENT,
  sendToJava,
} from '../../../lib/remoteSyncBridge';

interface TestResult {
  ok: boolean;
  message: string;
  at: number;
}

interface Diagnostics {
  text: string;
  at: number;
}

interface RemoteSyncStatePayload {
  sdk?: MutagenSdkStatus;
  githubProxy?: string;
  config?: SyncConfig;
  hasPassword?: boolean;
  syncStatus?: SyncStatus;
  hostKey?: HostKeyChallenge;
  testResult?: TestResult;
  diagnostics?: Diagnostics;
}

const INITIAL_SDK: MutagenSdkStatus = {
  installed: false,
  targetVersion: '',
  downloading: false,
  progress: 0,
  total: 0,
};

const INITIAL_CONFIG: SyncConfig = {
  enabled: false,
  name: 'codemoss-sync',
  localPath: '',
  remoteUser: '',
  remoteHost: '',
  remotePort: 22,
  remotePath: '',
  mode: 'two-way-safe',
  remoteOs: 'auto',
};

const INITIAL_STATUS: SyncStatus = {
  state: 'disabled',
  stagingProgress: 0,
  stagingTotal: 0,
  conflicts: 0,
  updatedAt: 0,
};

export default function RemoteSyncSection() {
  const { t } = useTranslation();
  const [sdk, setSdk] = useState<MutagenSdkStatus>(INITIAL_SDK);
  const [githubProxy, setGithubProxy] = useState<string>('');
  const [config, setConfig] = useState<SyncConfig>(INITIAL_CONFIG);
  const [hasPassword, setHasPassword] = useState<boolean>(false);
  const [status, setStatus] = useState<SyncStatus>(INITIAL_STATUS);
  const [hostKey, setHostKey] = useState<HostKeyChallenge | null>(null);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [busy, setBusy] = useState<'test' | 'start' | 'stop' | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState<boolean>(false);
  const lastSavedProxy = useRef<string>('');
  const lastTestResultAt = useRef<number>(0);
  const lastDiagAt = useRef<number>(0);

  useEffect(() => {
    ensureRemoteSyncBridge();
    const handler = (e: Event) => {
      const next = (e as CustomEvent<RemoteSyncStatePayload>).detail;
      if (!next) return;
      if (next.sdk) setSdk((prev) => ({ ...prev, ...next.sdk! }));
      if (typeof next.githubProxy === 'string') {
        setGithubProxy(next.githubProxy);
        lastSavedProxy.current = next.githubProxy;
      }
      if (next.config) {
        const c = next.config;
        setConfig({
          enabled:    !!c.enabled,
          name:        c.name        || INITIAL_CONFIG.name,
          localPath:   c.localPath   || '',
          remoteUser:  c.remoteUser  || '',
          remoteHost:  c.remoteHost  || '',
          remotePort:  c.remotePort  || 22,
          remotePath:  c.remotePath  || '',
          mode:       (c.mode as SyncMode) || 'two-way-safe',
          remoteOs:   (c.remoteOs as RemoteOs) || 'auto',
        });
      }
      if (typeof next.hasPassword === 'boolean') setHasPassword(next.hasPassword);
      if (next.syncStatus) setStatus(next.syncStatus);
      if (next.hostKey) setHostKey(next.hostKey);
      if (next.testResult && next.testResult.at > lastTestResultAt.current) {
        lastTestResultAt.current = next.testResult.at;
        setTestResult(next.testResult);
        setBusy(null);
      }
      if (next.diagnostics && next.diagnostics.at > lastDiagAt.current) {
        lastDiagAt.current = next.diagnostics.at;
        setDiagnostics(next.diagnostics);
        setDiagnosticsOpen(true);
      }
    };
    window.addEventListener(SYNC_FULL_STATE_EVENT, handler);
    sendToJava('get_remote_sync_state:');
    return () => window.removeEventListener(SYNC_FULL_STATE_EVENT, handler);
  }, []);

  const onDownload = useCallback(() => sendToJava('download_mutagen:'), []);
  const onCancel = useCallback(() => sendToJava('cancel_mutagen_download:'), []);

  const onProxyBlur = useCallback(() => {
    const trimmed = githubProxy.trim();
    if (trimmed === lastSavedProxy.current) return;
    lastSavedProxy.current = trimmed;
    sendToJava(`set_remote_sync_github_proxy:${JSON.stringify({ githubProxy: trimmed })}`);
  }, [githubProxy]);

  const onResetProxy = useCallback(() => {
    const dflt = 'https://ghfast.top';
    setGithubProxy(dflt);
    lastSavedProxy.current = dflt;
    sendToJava(`set_remote_sync_github_proxy:${JSON.stringify({ githubProxy: dflt })}`);
  }, []);

  const persistConfig = useCallback((patch: Partial<SyncConfig>) => {
    sendToJava(`set_remote_sync_config:${JSON.stringify(patch)}`);
  }, []);
  const persistPassword = useCallback((password: string) => {
    sendToJava(`set_remote_sync_password:${JSON.stringify({ password })}`);
  }, []);

  const onTest = useCallback(() => {
    setBusy('test');
    setTestResult(null);
    sendToJava('test_remote_sync_connection:');
  }, []);
  const onStart = useCallback(() => {
    setBusy('start');
    setTestResult(null);
    sendToJava('start_remote_sync:');
  }, []);
  const onStop = useCallback(() => {
    setBusy('stop');
    sendToJava('stop_remote_sync:');
  }, []);
  const onPause = useCallback(() => sendToJava('pause_remote_sync:'), []);
  const onResume = useCallback(() => sendToJava('resume_remote_sync:'), []);
  const onDiagnostics = useCallback(() => sendToJava('get_remote_sync_diagnostics:'), []);

  const onHostKeyConfirm = useCallback(() => {
    if (!hostKey) return;
    sendToJava(`confirm_host_key:${JSON.stringify({ id: hostKey.id, trust: true })}`);
    setHostKey(null);
  }, [hostKey]);
  const onHostKeyCancel = useCallback(() => {
    if (!hostKey) return;
    sendToJava(`confirm_host_key:${JSON.stringify({ id: hostKey.id, trust: false })}`);
    setHostKey(null);
  }, [hostKey]);

  const canTest = config.enabled && sdk.installed && hasPassword;
  const canStart = canTest && (status.state === 'disabled' || status.state === 'stopped'
                                || status.state === 'error');

  const onMasterToggle = (next: boolean) => {
    persistConfig({ enabled: next });
  };

  return (
    <div className={styles.section}>
      <div className={styles.header}>
        <span className={styles.title}>{t('settings.remoteSync.title')}</span>
        <span className={styles.subtitle}>{t('settings.remoteSync.subtitle')}</span>
      </div>

      <div className={styles.masterToggleRow}>
        <label className={styles.masterToggle}>
          <input
            type="checkbox"
            checked={config.enabled}
            onChange={(e) => onMasterToggle(e.target.checked)}
          />
          <span className={styles.masterToggleLabel}>
            {config.enabled
              ? t('settings.remoteSync.masterOn')
              : t('settings.remoteSync.masterOff')}
          </span>
        </label>
        <span className={styles.masterHint}>
          {t('settings.remoteSync.masterHint')}
        </span>
      </div>

      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.cardTitle}>{t('settings.remoteSync.proxy.title')}</span>
        </div>
        <div className={styles.path}>{t('settings.remoteSync.proxy.hint')}</div>
        <div className={styles.actions}>
          <input
            type="text"
            className={styles.input}
            placeholder="https://ghfast.top"
            value={githubProxy}
            onChange={(e) => setGithubProxy(e.target.value)}
            onBlur={onProxyBlur}
            spellCheck={false}
          />
          <button type="button" className={styles.btn} onClick={onResetProxy}>
            {t('settings.remoteSync.proxy.reset')}
          </button>
        </div>
      </div>

      <MutagenSdkCard sdk={sdk} onDownload={onDownload} onCancel={onCancel} />

      <SyncConfigForm
        config={config}
        hasPassword={hasPassword}
        sdkInstalled={sdk.installed}
        onPersist={persistConfig}
        onPersistPassword={persistPassword}
      />

      {config.enabled && (
        <div className={styles.card}>
          <div className={styles.cardHeader}>
            <span className={styles.cardTitle}>{t('settings.remoteSync.actions.title')}</span>
          </div>
          <div className={styles.actions}>
            <button
              type="button"
              className={styles.btn}
              disabled={!canTest || busy !== null}
              onClick={onTest}
            >
              {busy === 'test'
                ? t('settings.remoteSync.actions.testing')
                : t('settings.remoteSync.actions.test')}
            </button>
            <button
              type="button"
              className={`${styles.btn} ${styles.btnPrimary}`}
              disabled={!canStart || busy !== null}
              onClick={onStart}
            >
              {busy === 'start'
                ? t('settings.remoteSync.actions.starting')
                : t('settings.remoteSync.actions.start')}
            </button>
            <button type="button" className={styles.btn} onClick={onDiagnostics}>
              {t('settings.remoteSync.actions.diagnostics')}
            </button>
          </div>
          {testResult && (
            <div className={testResult.ok ? styles.successText : styles.errorText}>
              {testResult.ok
                ? `✓ ${testResult.message || t('settings.remoteSync.actions.testOk')}`
                : `✗ ${testResult.message}`}
            </div>
          )}
          {!hasPassword && (
            <div className={styles.formHint}>{t('settings.remoteSync.actions.needPassword')}</div>
          )}
        </div>
      )}

      <SyncStatusPanel
        status={status}
        enabled={config.enabled}
        onPause={onPause}
        onResume={onResume}
        onStop={onStop}
      />

      {hostKey && (
        <HostKeyDialog
          challenge={hostKey}
          onConfirm={onHostKeyConfirm}
          onCancel={onHostKeyCancel}
        />
      )}

      {diagnosticsOpen && diagnostics && (
        <div className={styles.modalBackdrop} onClick={() => setDiagnosticsOpen(false)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()} style={{ maxWidth: '720px' }}>
            <div className={styles.modalTitle}>
              {t('settings.remoteSync.diagnostics.title')}
            </div>
            <div className={styles.modalBody}>
              <pre className={styles.diagnosticsText}>{diagnostics.text}</pre>
            </div>
            <div className={styles.modalActions}>
              <button
                type="button"
                className={styles.btn}
                onClick={() => {
                  navigator.clipboard?.writeText(diagnostics.text);
                }}
              >
                {t('settings.remoteSync.diagnostics.copy')}
              </button>
              <button
                type="button"
                className={`${styles.btn} ${styles.btnPrimary}`}
                onClick={() => setDiagnosticsOpen(false)}
              >
                {t('common.close')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
