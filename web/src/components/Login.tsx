import type { User } from '../api/types';
import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../api/client';

export function Login({ onSignedIn }: { onSignedIn: (user: User) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Offered when the server has a demo account (404 otherwise, and the button never shows).
  const [demo, setDemo] = useState<{ email: string; password: string } | null>(null);
  useEffect(() => {
    api.demoCredentials().then(setDemo, () => setDemo(null));
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSignedIn(mode === 'login' ? await api.login(email, password) : await api.register(email, password));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <form className="login-card" onSubmit={submit}>
        <h1>{mode === 'login' ? 'Sign in' : 'Create your account'}</h1>
        <label>
          Email
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
        </label>
        <label>
          Password
          <input type="password" required minLength={8}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        {demo && mode === 'login' && (
          <p className="demo-hint small muted">
            Just looking?{' '}
            <button type="button" className="link" onClick={() => { setEmail(demo.email); setPassword(demo.password); }}>
              Use the demo account
            </button>{' '}
            ({demo.email} / {demo.password})
          </p>
        )}
        <button className="btn primary" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}</button>
        <button type="button" className="link" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null); }}>
          {mode === 'login' ? 'New here? Create an account' : 'Already have an account? Sign in'}
        </button>
      </form>
    </main>
  );
}
