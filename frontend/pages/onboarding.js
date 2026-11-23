import Head from 'next/head';
import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';

export default function Onboarding() {
  const token = useRef('');
  const [invitation, setInvitation] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [complete, setComplete] = useState(false);

  async function call(action, body) {
    const response = await fetch('/api/onboarding/' + action, {
      method: 'POST', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'X-Portal-Request': '1' },
      body: JSON.stringify(body)
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.message || 'Could not process this invitation. Try again or contact your administrator.');
    return result;
  }

  useEffect(() => {
    token.current = new URLSearchParams(window.location.hash.slice(1)).get('token') || '';
    // Remove the token from the address bar after reading it.
    window.history.replaceState(null, '', window.location.pathname);
    if (!token.current) { setError('Open the invitation link from your email to continue.'); return; }
    let active = true;
    call('inspect', { token: token.current })
      .then(result => { if (active) setInvitation(result); })
      .catch(failure => { if (active) setError(failure.message); });
    return () => { active = false; };
  }, []);

  async function accept(event) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setError('');
    if (form.get('password') !== form.get('confirmation')) {
      setError('The passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      await call('accept', { token: token.current, email: form.get('email'), password: form.get('password') });
      setComplete(true);
      token.current = '';
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  }

  return <>
    <Head><title>Accept invitation · API Portal</title><meta name="referrer" content="no-referrer"/></Head>
    <header><div className="header-inner"><Link className="brand login-brand" href="/">API Portal</Link></div></header>
    <main className="login">
      <div className="login-intro"><h1>{complete ? 'Your account is ready' : 'Welcome to API Portal'}</h1>
        <p>{complete ? 'Sign in with your invited email address and the password you just created.'
          : 'Accept your organization’s invitation to start using your Sandbox workspace.'}</p></div>
      <section className="panel">
        {error && <p className="error" role="alert">{error}</p>}
        {complete ? <Link className="landing-button solid" href="/login">Continue to sign in</Link> :
          invitation ? <form className="form" onSubmit={accept}>
            <h2>{invitation.companyName}</h2>
            <p>Invited as Project Manager. You can view and submit Sandbox and Production requests.</p>
            <label>Invited email<input name="email" type="email" defaultValue={invitation.email} required autoComplete="username"/></label>
            <label>Create password<input name="password" type="password" minLength={12} maxLength={64} required autoComplete="new-password"/></label>
            <small>Use 12–64 characters.</small>
            <label>Confirm password<input name="confirmation" type="password" minLength={12} maxLength={64} required autoComplete="new-password"/></label>
            <button className="primary" disabled={busy}>{busy ? 'Activating…' : 'Activate account'}</button>
          </form> : !error && <p role="status">Checking your invitation…</p>}
      </section>
    </main>
  </>;
}
