import { useCallback } from 'react';
import { usePairContext } from './PairContext';
import { sendBridgeEvent } from '../../utils/bridge';
import styles from './style.module.less';

export type AutonomyMode = 'strict' | 'mixed' | 'full';

/** Switch the autonomy level for the current pair.
 *  strict = legacy modal escalate;
 *  mixed = C1/C2 auto fallback + alert toast, C3 真停;
 *  full (default, 2026-05-25) = C1/C2/C3 全自决, alert 仍 toast 但不阻塞。 */
export default function AutonomyToggle() {
  const { pairId, autonomyMode, setAutonomyMode } = usePairContext();

  const onChange = useCallback((mode: AutonomyMode) => {
    if (!pairId) return;
    setAutonomyMode(mode);
    sendBridgeEvent('pair_set_autonomy_mode', JSON.stringify({ pairId, mode }));
  }, [pairId, setAutonomyMode]);

  if (!pairId) return null;
  const current: AutonomyMode = autonomyMode ?? 'full';

  return (
    <div className={styles.autonomyToggle}>
      <label>自治等级</label>
      <select value={current} onChange={(e) => onChange(e.target.value as AutonomyMode)}>
        <option value="strict">严格 (escalate 弹窗)</option>
        <option value="mixed">混合 (C1/C2 自决+提示，C3 停)</option>
        <option value="full">全自治 (仅 C3 停)</option>
      </select>
    </div>
  );
}
