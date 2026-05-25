import { useEffect, useState } from 'react';
import styles from './style.module.less';

interface AlertItem {
  id: number;
  severity: 'warn' | 'alert' | 'error';
  category?: string;
  fallbackChoice?: string;
  reason?: string;
  question?: string;
  ts: number;
}

const AUTO_DISMISS_MS = 5000;
let nextId = 1;

/** Listens to window.onPairAlert and shows non-blocking toasts in the bottom-right. */
export default function AlertNotifier() {
  const [items, setItems] = useState<AlertItem[]>([]);

  useEffect(() => {
    const prev = window.onPairAlert;
    window.onPairAlert = (json: string) => {
      try {
        const o = JSON.parse(json);
        const item: AlertItem = {
          id: nextId++,
          severity: (o.severity as 'warn' | 'alert' | 'error') ?? 'warn',
          category: o.category,
          fallbackChoice: o.fallback_choice,
          reason: o.reason,
          question: o.question,
          ts: o.ts ?? Date.now(),
        };
        setItems((curr) => [...curr, item].slice(-5));
        setTimeout(() => {
          setItems((curr) => curr.filter((x) => x.id !== item.id));
        }, AUTO_DISMISS_MS);
      } catch { /* ignore */ }
    };
    return () => { window.onPairAlert = prev; };
  }, []);

  if (items.length === 0) return null;

  return (
    <div className={styles.alertNotifier}>
      {items.map((item) => (
        <div
          key={item.id}
          className={styles.alertToast}
          data-severity={item.severity}
          onClick={() => setItems((curr) => curr.filter((x) => x.id !== item.id))}
        >
          <div className={styles.alertHeader}>
            <span className={styles.alertSeverity}>{item.severity.toUpperCase()}</span>
            {item.category && <span className={styles.alertCategory}>{item.category}</span>}
          </div>
          <div className={styles.alertBody}>
            {item.question || item.reason || '(no message)'}
          </div>
          {item.fallbackChoice && (
            <div className={styles.alertFallback}>fallback: {item.fallbackChoice}</div>
          )}
        </div>
      ))}
    </div>
  );
}
