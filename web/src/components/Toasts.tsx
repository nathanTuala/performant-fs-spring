import { dismiss, useToasts } from '../lib/toast';

export function Toasts() {
  const toasts = useToasts();
  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.tone}`}>
          <span>{t.message}</span>
          {t.action && (
            <button className="link" onClick={() => { t.action!.run(); dismiss(t.id); }}>
              {t.action.label}
            </button>
          )}
          <button className="icon-btn" aria-label="Dismiss" onClick={() => dismiss(t.id)}>×</button>
        </div>
      ))}
    </div>
  );
}
