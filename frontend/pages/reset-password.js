import Head from 'next/head';
import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { PublicHeader, PublicFooter } from '../components/PublicLayout';

export default function ResetPassword() {
  const token = useRef('');
  const [reset, setReset] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [complete, setComplete] = useState(false);

  async function call(action, body, signal) {
    const response = await fetch('/api/password-resets/' + action, {
      method: 'POST', cache: 'no-store', signal,
      headers: { 'Content-Type': 'application/json', 'X-Portal-Request': '1' },
      body: JSON.stringify(body)
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.message || 'Could not process your reset link. Try again or contact your administrator.');
    return result;
  }

  useEffect(() => {
    if (!token.current) token.current = new URLSearchParams(window.location.hash.slice(1)).get('token') || '';
    window.history.replaceState(window.history.state, '', window.location.pathname);
    if (!token.current) {
      setError('Open the password reset link from your email to continue.');
      return;
    }
    const controller = new AbortController();
    call('inspect', { token: token.current }, controller.signal)
      .then(result => { if (!controller.signal.aborted) setReset(result); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, []);

  async function submit(event) {
    event.preventDefault();
    setError('');
    const form = new FormData(event.currentTarget);
    if (form.get('password') !== form.get('confirmation')) {
      setError('The passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      await call('complete', { token: token.current, password: form.get('password') });
      setComplete(true);
      token.current = '';
    } catch (failure) {
      setError(failure.message);
    } finally { setBusy(false); }
  }

  return <div className="public-auth">
    <Head><title>Reset password · API Portal</title><meta name="referrer" content="no-referrer"/></Head>
    <PublicHeader />
    <main className="login">
      <div className="login-intro">
        <h1>{complete ? 'Your password is updated' : 'Reset your password'}</h1>
        <p>{complete ? 'Sign in with your email and new password to continue.'
          : 'Choose a new password for your API Portal account.'}</p>
      </div>
      <section className="panel">
        {error && <p className="error" role="alert">{error}</p>}
        {complete ? <Link className="landing-button solid" href="/login">Continue to sign in</Link>
          : reset ? <form className="form" onSubmit={submit}>
            <h2>{reset.companyName}</h2>
            <p>{reset.email}</p>
            <label>New password<input name="password" type="password" autoComplete="new-password" required minLength={12} maxLength={64}/></label>
            <small>Use 12–64 characters and choose a password different from your current password.</small>
            <label>Confirm new password<input name="confirmation" type="password" autoComplete="new-password" required minLength={12} maxLength={64}/></label>
            <button className="primary" disabled={busy}>{busy ? 'Updating…' : 'Update password'}</button>
          </form> : !error && <p role="status">Checking your reset link…</p>}
        {!complete && error && <p><Link href="/login">Return to sign in</Link></p>}
      </section>
    </main>
    <PublicFooter />
  </div>;
}
