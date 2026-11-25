import { useEffect, useRef, useState, type FormEvent } from 'react';

interface DialogProps {
  title: string;
  message?: string;
  /** Present for a text prompt; absent for a confirmation. */
  initialValue?: string;
  confirmLabel: string;
  danger?: boolean;
  onClose: (result: string | boolean | null) => void;
}

/** Modal prompt/confirm built on <dialog>, which gives focus trapping and Escape for free. */
export function Dialog({ title, message, initialValue, confirmLabel, danger, onClose }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const [value, setValue] = useState(initialValue ?? '');
  const isPrompt = initialValue !== undefined;

  useEffect(() => {
    ref.current?.showModal();
  }, []);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (isPrompt && !value.trim()) return;
    onClose(isPrompt ? value.trim() : true);
  }

  return (
    <dialog ref={ref} className="dialog" onCancel={() => onClose(null)} aria-labelledby="dialog-title">
      <form onSubmit={submit}>
        <h2 id="dialog-title">{title}</h2>
        {message && <p className="muted">{message}</p>}
        {isPrompt && (
          <input autoFocus value={value} aria-label={title} onChange={(e) => setValue(e.target.value)}
            onFocus={(e) => e.currentTarget.select()} />
        )}
        <div className="dialog-actions">
          <button type="button" className="btn" onClick={() => onClose(null)}>Cancel</button>
          <button className={`btn ${danger ? 'danger-solid' : 'primary'}`} autoFocus={!isPrompt}>{confirmLabel}</button>
        </div>
      </form>
    </dialog>
  );
}

type Pending = Omit<DialogProps, 'onClose'> & { resolve: (v: string | boolean | null) => void };

/** Promise-style dialogs: `const name = await ask.prompt(...)`. Render `ask.element` once. */
export function useDialog() {
  const [pending, setPending] = useState<Pending | null>(null);
  const open = (props: Omit<DialogProps, 'onClose'>) =>
    new Promise<string | boolean | null>((resolve) => setPending({ ...props, resolve }));
  return {
    prompt: (title: string, initialValue: string, confirmLabel = 'Create') =>
      open({ title, initialValue, confirmLabel }) as Promise<string | null>,
    confirm: async (title: string, message: string, confirmLabel = 'Delete') =>
      (await open({ title, message, confirmLabel, danger: true })) === true,
    element: pending && (
      <Dialog {...pending} onClose={(result) => { pending.resolve(result); setPending(null); }} />
    ),
  };
}
