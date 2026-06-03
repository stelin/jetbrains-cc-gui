import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';

export interface SyncConfig {
  enabled: boolean;
  name: string;
  localPath: string;
  transport: SyncTransport;
  dockerContainer: string;
  remoteUser: string;
  remoteHost: string;
  remotePort: number;
  remotePath: string;
  mode: SyncMode;
  remoteOs: RemoteOs;
}

export type SyncMode = 'two-way-safe' | 'two-way-resolved' | 'one-way-replica';
export type RemoteOs = 'auto' | 'windows' | 'unix';
export type SyncTransport = 'ssh' | 'docker';

const TRANSPORTS: { value: SyncTransport; labelKey: string }[] = [
  { value: 'ssh',    labelKey: 'settings.remoteSync.form.transport.ssh' },
  { value: 'docker', labelKey: 'settings.remoteSync.form.transport.docker' },
];

interface Props {
  config: SyncConfig;
  hasPassword: boolean;
  sdkInstalled: boolean;
  onPersist: (patch: Partial<SyncConfig>) => void;
  onPersistPassword: (password: string) => void;
}

const SYNC_MODES: { value: SyncMode; labelKey: string }[] = [
  { value: 'two-way-safe',     labelKey: 'settings.remoteSync.form.mode.twoWaySafe' },
  { value: 'two-way-resolved', labelKey: 'settings.remoteSync.form.mode.twoWayResolved' },
  { value: 'one-way-replica',  labelKey: 'settings.remoteSync.form.mode.oneWayReplica' },
];

const REMOTE_OS: { value: RemoteOs; labelKey: string }[] = [
  { value: 'auto',    labelKey: 'settings.remoteSync.form.remoteOs.auto' },
  { value: 'windows', labelKey: 'settings.remoteSync.form.remoteOs.windows' },
  { value: 'unix',    labelKey: 'settings.remoteSync.form.remoteOs.unix' },
];

export function SyncConfigForm({ config, hasPassword, sdkInstalled, onPersist, onPersistPassword }: Props) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<SyncConfig>(config);
  const [password, setPassword] = useState<string>('');
  const [showPwHint, setShowPwHint] = useState<boolean>(false);

  // Pull external config updates back into the draft, but ignore the
  // sentinel update we trigger ourselves via onPersist.
  useEffect(() => {
    setDraft(config);
  }, [config]);

  const disabled = !draft.enabled;
  const isDocker = draft.transport === 'docker';

  const update = useCallback(<K extends keyof SyncConfig>(key: K, value: SyncConfig[K]) => {
    setDraft((d) => ({ ...d, [key]: value }));
  }, []);

  const persistField = useCallback(<K extends keyof SyncConfig>(key: K, value: SyncConfig[K]) => {
    onPersist({ [key]: value } as Partial<SyncConfig>);
  }, [onPersist]);

  const onBlurField = <K extends keyof SyncConfig>(key: K) => () => {
    if (draft[key] !== config[key]) persistField(key, draft[key]);
  };

  const onPortChange = (raw: string) => {
    const n = parseInt(raw, 10);
    update('remotePort', Number.isFinite(n) ? n : 0);
  };

  const onPortBlur = () => {
    const clamped = Math.min(65535, Math.max(1, draft.remotePort || 22));
    if (clamped !== draft.remotePort) update('remotePort', clamped);
    if (clamped !== config.remotePort) persistField('remotePort', clamped);
  };

  const onPasswordSave = () => {
    onPersistPassword(password);
    setPassword('');
    setShowPwHint(true);
    setTimeout(() => setShowPwHint(false), 3000);
  };

  const onPasswordClear = () => {
    onPersistPassword('');
    setPassword('');
  };

  return (
    <div className={styles.card}>
      <div className={styles.cardHeader}>
        <span className={styles.cardTitle}>{t('settings.remoteSync.form.title')}</span>
      </div>

      {!sdkInstalled && draft.enabled && (
        <div className={styles.warnText}>{t('settings.remoteSync.form.needSdkWarning')}</div>
      )}

      <div className={styles.formRow}>
        <label className={styles.formLabel}>{t('settings.remoteSync.form.transport.title')}</label>
        <div className={styles.segmented}>
          {TRANSPORTS.map((tr) => (
            <button
              key={tr.value}
              type="button"
              className={`${styles.segmentBtn} ${draft.transport === tr.value ? styles.segmentBtnActive : ''}`}
              disabled={disabled}
              onClick={() => {
                if (draft.transport === tr.value) return;
                update('transport', tr.value);
                persistField('transport', tr.value);
              }}
            >
              {t(tr.labelKey)}
            </button>
          ))}
        </div>
      </div>
      <div className={styles.formHint}>
        {isDocker
          ? t('settings.remoteSync.form.transport.dockerHint')
          : t('settings.remoteSync.form.transport.sshHint')}
      </div>

      <div className={styles.formRow}>
        <label className={styles.formLabel}>{t('settings.remoteSync.form.name')}</label>
        <input
          type="text"
          className={styles.input}
          value={draft.name}
          disabled={disabled}
          onChange={(e) => update('name', e.target.value)}
          onBlur={onBlurField('name')}
        />
      </div>

      <div className={styles.formRow}>
        <label className={styles.formLabel}>{t('settings.remoteSync.form.localPath')}</label>
        <input
          type="text"
          className={styles.input}
          placeholder="/Users/you/projects/my-project"
          value={draft.localPath}
          disabled={disabled}
          onChange={(e) => update('localPath', e.target.value)}
          onBlur={onBlurField('localPath')}
          spellCheck={false}
        />
      </div>

      {isDocker && (
        <div className={styles.formRow}>
          <label className={styles.formLabel}>{t('settings.remoteSync.form.dockerContainer')}</label>
          <input
            type="text"
            className={styles.input}
            placeholder="my-container  /  f9803c0471fb"
            value={draft.dockerContainer}
            disabled={disabled}
            onChange={(e) => update('dockerContainer', e.target.value)}
            onBlur={onBlurField('dockerContainer')}
            spellCheck={false}
          />
        </div>
      )}

      <div className={styles.formRow}>
        <label className={styles.formLabel}>
          {isDocker
            ? t('settings.remoteSync.form.dockerUser')
            : t('settings.remoteSync.form.remoteUser')}
        </label>
        <input
          type="text"
          className={styles.input}
          placeholder={isDocker ? 'root（可选 / optional）' : 'Administrator'}
          value={draft.remoteUser}
          disabled={disabled}
          onChange={(e) => update('remoteUser', e.target.value)}
          onBlur={onBlurField('remoteUser')}
          spellCheck={false}
        />
      </div>

      {!isDocker && (
        <div className={styles.formRow}>
          <label className={styles.formLabel}>{t('settings.remoteSync.form.remoteHost')}</label>
          <input
            type="text"
            className={styles.input}
            placeholder="172.20.1.210"
            value={draft.remoteHost}
            disabled={disabled}
            onChange={(e) => update('remoteHost', e.target.value)}
            onBlur={onBlurField('remoteHost')}
            spellCheck={false}
          />
          <span className={styles.formInlineLabel}>:</span>
          <input
            type="number"
            className={styles.inputNarrow}
            min={1}
            max={65535}
            value={draft.remotePort}
            disabled={disabled}
            onChange={(e) => onPortChange(e.target.value)}
            onBlur={onPortBlur}
          />
        </div>
      )}

      <div className={styles.formRow}>
        <label className={styles.formLabel}>{t('settings.remoteSync.form.remoteOs.title')}</label>
        <select
          className={styles.input}
          value={draft.remoteOs}
          disabled={disabled}
          onChange={(e) => {
            const next = e.target.value as RemoteOs;
            update('remoteOs', next);
            persistField('remoteOs', next);
          }}
        >
          {REMOTE_OS.map((o) => (
            <option key={o.value} value={o.value}>{t(o.labelKey)}</option>
          ))}
        </select>
      </div>

      <div className={styles.formRow}>
        <label className={styles.formLabel}>{t('settings.remoteSync.form.remotePath')}</label>
        <input
          type="text"
          className={styles.input}
          placeholder={draft.remoteOs === 'windows' ? 'D:\\server-code\\my-project' : '/home/user/projects/my-project'}
          value={draft.remotePath}
          disabled={disabled}
          onChange={(e) => update('remotePath', e.target.value)}
          onBlur={onBlurField('remotePath')}
          spellCheck={false}
        />
      </div>

      {!isDocker && (
        <div className={styles.formRow}>
          <label className={styles.formLabel}>{t('settings.remoteSync.form.password')}</label>
          <input
            type="password"
            className={styles.input}
            placeholder={hasPassword
              ? (t('settings.remoteSync.form.passwordSaved') as string)
              : ''}
            value={password}
            disabled={disabled}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="off"
          />
          <button
            type="button"
            className={styles.btn}
            disabled={disabled || password.length === 0}
            onClick={onPasswordSave}
          >
            {t('settings.remoteSync.form.passwordSave')}
          </button>
          {hasPassword && (
            <button
              type="button"
              className={styles.btn}
              disabled={disabled}
              onClick={onPasswordClear}
            >
              {t('settings.remoteSync.form.passwordClear')}
            </button>
          )}
        </div>
      )}
      {!isDocker && showPwHint && (
        <div className={styles.formHint}>{t('settings.remoteSync.form.passwordHintSaved')}</div>
      )}

      <div className={styles.formRow}>
        <label className={styles.formLabel}>{t('settings.remoteSync.form.mode.title')}</label>
        <select
          className={styles.input}
          value={draft.mode}
          disabled={disabled}
          onChange={(e) => {
            const next = e.target.value as SyncMode;
            update('mode', next);
            persistField('mode', next);
          }}
        >
          {SYNC_MODES.map((m) => (
            <option key={m.value} value={m.value}>{t(m.labelKey)}</option>
          ))}
        </select>
      </div>

    </div>
  );
}
