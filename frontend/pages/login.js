import Head from 'next/head';
import { PublicHeader, PublicFooter } from '../components/PublicLayout';
import Link from 'next/link';
import { useRouter } from 'next/router';
import { useEffect, useRef, useState } from 'react';

const scopeLabels = {
  ACCOUNTS_READ: 'Account information',
  PAYMENTS_CREATE: 'Payment initiation',
  PAYMENTS_READ: 'Payment status'
};
const statusLabels = {
  SUBMITTED: 'Submitted', IN_REVIEW: 'In review', CHANGES_REQUESTED: 'Changes requested',
  APPROVED: 'Approved', REJECTED: 'Rejected', ACTIVE: 'Active'
};
const steps = ['Application details', 'Services and scopes', 'Credential setup', 'Review and submit'];
const format = date => date ? new Date(date).toLocaleString() : '—';
const environmentName = value => value === 'PRODUCTION' ? 'Production' : 'Sandbox';

function Badge({ status }) {
  return <span className={'badge ' + status.toLowerCase()}>{statusLabels[status] ?? status}</span>;
}

function RequestForm({ environment, cancel, submit }) {
  const [step, setStep] = useState(0);
  const [form, setForm] = useState({
    applicationName: '', description: '', useCase: '', contactName: '', contactEmail: '',
    callbackUrl: '', scopes: [], credentialMethod: environment === 'PRODUCTION' ? 'PUBLIC_KEY' : 'CLIENT_SECRET',
    publicKey: '', environment
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const attempt = useRef(null);
  const change = e => setForm({ ...form, [e.target.name]: e.target.value });
  const field = (name, label, props = {}) => <label>{label}<input name={name} value={form[name]} onChange={change} {...props}/></label>;
  const area = (name, label, maxLength) => <label>{label}<textarea name={name} value={form[name]} onChange={change} required maxLength={maxLength}/></label>;

  async function advance(event) {
    event.preventDefault();
    setError('');
    if (step < 3) {
      if (step === 1 && !form.scopes.length) return setError('Select at least one API scope.');
      setStep(step + 1);
      return;
    }
    setBusy(true);
    const input = { ...form, publicKey: form.credentialMethod === 'PUBLIC_KEY' ? form.publicKey : null };
    const payload = JSON.stringify(input);
    if (attempt.current?.payload !== payload) attempt.current = { payload, key: crypto.randomUUID() };
    try {
      await submit(input, attempt.current.key);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return <>
    <div className="page-title"><div><button className="quiet" onClick={cancel} disabled={busy}>← Back to requests</button><h1>Create application</h1></div><Badge status={environmentName(environment)}/></div>
    <div className="wizard">
      <div>
        <ol className="steps">{steps.map((label, index) => <li key={label} aria-current={index === step ? 'step' : undefined}><span>{index + 1}</span>{label}</li>)}</ol>
        <form className="panel form" onSubmit={advance}>
          <h2>{steps[step]}</h2>
          {step === 0 && <>
            {field('applicationName', 'Application name', { required: true, minLength: 3, maxLength: 60, placeholder: 'My Integration App' })}
            {area('description', 'Description', 300)}
            {area('useCase', 'Use case', 1000)}
            <div className="pair">{field('contactName', 'Technical contact name', { required: true, maxLength: 100 })}{field('contactEmail', 'Technical contact email', { type: 'email', required: true, maxLength: 254 })}</div>
            {field('callbackUrl', 'Callback URL (optional)', { type: 'url', maxLength: 2048, placeholder: 'https://example.com/callback' })}
          </>}
          {step === 1 && Object.entries(scopeLabels).map(([scope, label]) => <label className="choice" key={scope}>
            <input type="checkbox" checked={form.scopes.includes(scope)} onChange={e => setForm({ ...form, scopes: e.target.checked ? [...form.scopes, scope] : form.scopes.filter(item => item !== scope) })}/>
            <span>{label}<small>{scope.toLowerCase()}</small></span>
          </label>)}
          {step === 2 && <>
            <label>Credential method<select name="credentialMethod" value={form.credentialMethod} onChange={change}>
              {environment === 'SANDBOX' && <option value="CLIENT_SECRET">Client ID + secret</option>}
              <option value="PUBLIC_KEY">Customer-managed public key</option>
            </select></label>
            {form.credentialMethod === 'PUBLIC_KEY' && area('publicKey', 'Public key (PEM)', 8000)}
            <p className="muted">Backstage handles credential delivery separately.</p>
          </>}
          {step === 3 && <>
            <p>Submit this application and its requested API scopes to Backstage.</p>
            <dl><dt>Description</dt><dd>{form.description}</dd><dt>Use case</dt><dd>{form.useCase}</dd><dt>Technical contact</dt><dd>{form.contactName} · {form.contactEmail}</dd><dt>Callback URL</dt><dd>{form.callbackUrl || '—'}</dd></dl>
          </>}
          {error && <p role="alert" className="error">{error}</p>}
          <div className="actions"><button type="button" disabled={busy} onClick={() => step ? setStep(step - 1) : cancel()}>Back</button>
            <button className="primary" disabled={busy}>{busy ? 'Submitting…' : step < 3 ? 'Next →' : 'Submit request'}</button></div>
        </form>
      </div>
      <aside className="panel"><h2>Summary</h2><dl>
        <dt>Environment</dt><dd>{environmentName(environment)}</dd>
        <dt>Application name</dt><dd>{form.applicationName || '—'}</dd>
        <dt>API scopes</dt><dd>{form.scopes.map(scope => scopeLabels[scope]).join(', ') || 'None selected'}</dd>
        <dt>Credential method</dt><dd>{form.credentialMethod === 'PUBLIC_KEY' ? 'Public key' : 'Client ID + secret'}</dd>
      </dl></aside>
    </div>
  </>;
}

export default function Portal() {
  const { query } = useRouter();
  const [auth, setAuth] = useState('');
  const [sampleAccounts, setSampleAccounts] = useState([]);
  const loginForm = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    fetch('/api/demo/accounts', { cache: 'no-store', signal: controller.signal })
      .then(response => response.ok ? response.json() : [])
      .then(accounts => { if (!controller.signal.aborted) setSampleAccounts(accounts); })
      .catch(() => {});
    return () => controller.abort();
  }, []);
  const loginAttempt = useRef(null);
  const [firstLogin, setFirstLogin] = useState(null);
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [membership, setMembership] = useState(null);
  const [productionAccess, setProductionAccess] = useState(false);
  const [environment, setEnvironment] = useState('SANDBOX');
  const [view, setView] = useState('Dashboard');
  const [rows, setRows] = useState([]);
  const [onboarding, setOnboarding] = useState(null);
  const [selected, setSelected] = useState(null);
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  async function api(path, options = {}, authorization = auth) {
    const response = await fetch('/api/requests' + path, {
      ...options, cache: 'no-store', headers: { Authorization: authorization, ...options.headers }
    });
    if (!response.ok) {
      if (response.status === 401 && auth && authorization === auth) {
        setAuth(''); setMembership(null); setProductionAccess(false); setEnvironment('SANDBOX');
        setRows([]); setOnboarding(null); setSelected(null); setView('Dashboard');
        setNotice('Please sign in again with your current password.');
      }
      const failure = await response.json().catch(() => ({}));
      throw new Error(failure.message || (response.status === 401 ? 'Check your username and password.' :
        response.status === 400 ? 'Check the request fields and try again.' : 'The Portal could not reach Backstage.'));
    }
    return response.json();
  }

  useEffect(() => {
    if (!auth || view === 'Create') return;
    const controller = new AbortController();
    let refreshing = false;
    async function refresh() {
      if (refreshing) return;
      refreshing = true;
      try {
        const path = view === 'Detail' ? '/' + selected.id : '?environment=' + environment;
        const [result, onboardingStatus] = await Promise.all([
          api(path, { signal: controller.signal }),
          view === 'Dashboard' ? api('/onboarding', { signal: controller.signal }) : Promise.resolve(null)
        ]);
        if (controller.signal.aborted) return;
        if (view === 'Detail') setSelected(result);
        else setRows(result);
        if (view === 'Dashboard') setOnboarding(onboardingStatus);
        setError('');
      } catch (e) {
        if (!controller.signal.aborted) {
          if (view === 'Dashboard') setOnboarding(null);
          setError(e.message);
        }
      } finally {
        refreshing = false;
      }
    }
    refresh();
    const timer = setInterval(refresh, 15000);
    return () => { controller.abort(); clearInterval(timer); };
  // Only changes in session or navigation restart the pull.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth, environment, view, selected?.id]);

  async function accountApi(path, authorization, body, eventId) {
    const response = await fetch('/api/account/' + path, {
      method: body || eventId ? 'POST' : 'GET', cache: 'no-store',
      headers: { Authorization: authorization, 'Content-Type': 'application/json', 'X-Portal-Request': '1',
        ...(eventId ? { 'Idempotency-Key': eventId } : {}) },
      body: body && JSON.stringify(body),
    });
    const value = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(value.message || 'Check your email and password.');
    return value;
  }

  async function changePassword(event) {
    event.preventDefault();
    setError('');
    if (newPassword !== confirmPassword) { setError('Passwords must match.'); return; }
    setBusy(true);
    try {
      await accountApi('password', firstLogin.authorization, { password: newPassword });
      setNewPassword('');
      setConfirmPassword('');
      setFirstLogin(null);
      setNotice('Password created. Sign in with your new password to open the dashboard.');
    } catch (error) { setError(error.message); }
    finally { setBusy(false); }
  }

  async function signIn(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    const data = new FormData(event.currentTarget);
    try {
      const credentials = new TextEncoder().encode(data.get('username') + ':' + data.get('password'));
      const authorization = 'Basic ' + btoa(String.fromCharCode(...credentials));
      const status = await accountApi('status', authorization);
      if (status.passwordChangeRequired) {
        setFirstLogin({ authorization, email: status.email });
        setNotice('');
        return;
      }
      const access = await api('/access', {}, authorization);
      if (loginAttempt.current?.authorization !== authorization) {
        loginAttempt.current = { authorization, eventId: crypto.randomUUID() };
      }
      await accountApi('login-success', authorization, undefined, loginAttempt.current.eventId);
      loginAttempt.current = null;
      setOnboarding(null);
      setMembership(access);
      setProductionAccess(access.productionAccess === true);
      setEnvironment(access.defaultEnvironment === 'PRODUCTION' && access.productionAccess ? 'PRODUCTION' : 'SANDBOX');
      setAuth(authorization);
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }

  function navigate(next) {
    setError('');
    setNotice('');
    setView(next);
  }

  async function submit(input, key) {
    const created = await api('', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Portal-Request': '1', 'Idempotency-Key': key },
      body: JSON.stringify(input)
    });
    setSelected(created);
    setOnboarding({ hasApplications: true });
    navigate('Detail');
    setNotice('Request submitted to Backstage.');
  }

  const filtered = rows.filter(row => (!status || row.status === status)
    && row.applicationName.toLowerCase().includes(search.toLowerCase()));

  return <div className={!auth ? 'public-auth' : environment === 'PRODUCTION' ? 'production-workspace' : undefined}>
    <Head><title>API Portal</title><meta name="description" content="Submit and track partner API requests."/></Head>
    {auth ? <header><div className="header-inner">
      {auth ? <button className="brand" onClick={() => navigate('Dashboard')}><span aria-hidden="true">⚿</span> API Portal</button>
        : <Link className="brand login-brand" href="/"><span aria-hidden="true">⚿</span> API Portal</Link>}
      {auth && <>
        <nav aria-label="Workspace">{['Dashboard', 'Applications', 'Requests'].map(item =>
          <button key={item} className={view === item || (item === 'Requests' && view === 'Detail') ? 'current' : ''} onClick={() => navigate(item)}>{item}</button>)}</nav>
        <div className="header-right"><div className="environment" aria-label="Environment">{['SANDBOX', 'PRODUCTION'].map(item =>
          <button key={item} aria-pressed={environment === item} onClick={() => {
            if (item !== environment) setRows([]);
            setEnvironment(item); navigate('Dashboard');
          }} disabled={view === 'Create' || (item === 'PRODUCTION' && !productionAccess)}
            title={item === 'PRODUCTION' && !productionAccess ? 'Production access is required' : undefined}>{environmentName(item)}</button>)}</div>
          <button className="quiet" onClick={() => { setAuth(''); setMembership(null); setProductionAccess(false); setEnvironment('SANDBOX'); setRows([]); setOnboarding(null); setSelected(null); navigate('Dashboard'); }}>Sign out</button>
        </div>
      </>}
    </div></header> : <PublicHeader />}
    {firstLogin ? <main className="login">
      <div className="login-intro"><h1>Create your password</h1><p>Set a new password for {firstLogin.email} to continue to API Portal.</p></div>
      <form className="panel form" onSubmit={changePassword}>
        <h2>First-time sign in</h2>
        <label>New password<input type="password" autoComplete="new-password" required minLength={12} maxLength={64}
          value={newPassword} onChange={event => setNewPassword(event.target.value)} /></label>
        <label>Confirm new password<input type="password" autoComplete="new-password" required minLength={12} maxLength={64}
          value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} /></label>
        <p className="muted">Use at least 12 characters and choose a password different from your initial password.</p>
        {error && <p role="alert" className="error">{error}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Saving…' : 'Create password'}</button>
        <button type="button" className="quiet" disabled={busy} onClick={() => {
          setFirstLogin(null); setNewPassword(''); setConfirmPassword(''); setError('');
        }}>Back to sign in</button>
      </form>
    </main> : !auth ? <main className="login sample-login"><div className="login-intro"><h1>Build your next API integration</h1><p>Request access in Sandbox or Production and track the status of your applications.</p>
      <p className="initial-login-note">Registered through Backstage? Use your project manager email and initial password <code>Portal123!</code>, then create your own password.</p></div>
      <section className="panel sample-login-card"><h2>Sign in to API Portal</h2>
      <p className="muted">Use a sample account to explore the workspace.</p>
      <form ref={loginForm} className="form" onSubmit={signIn}>
        {notice && <p role="status" className="banner">{notice}</p>}
        {query.access === '1' && <p className="muted">Ask your organization’s API administrator for Portal access. If you already have an account, sign in below.</p>}
        <label>Email or demo username<input name="username" autoComplete="username" required/></label>
        <label>Password<input name="password" type="password" autoComplete="current-password" required/></label>
        {error && <p role="alert" className="error">{error}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
        {sampleAccounts.length > 0 && <section className="sample-accounts" aria-labelledby="sample-accounts-title">
          <h3 id="sample-accounts-title">Sample accounts</h3>
          <p className="muted">Select an account to fill in the form, then sign in.</p>
          {sampleAccounts.map(sample => <button key={sample.username} type="button" className="sample-account"
            disabled={busy} onClick={() => {
              loginForm.current.elements.namedItem('username').value = sample.username;
              loginForm.current.elements.namedItem('password').value = sample.password;
              setError(''); setNotice('');
            }}>
            <strong><span>{sample.productionAccess ? 'Production access' : 'Sandbox only'}</span><code>{sample.username}</code></strong>
            <span>Password <code>{sample.password}</code></span>
          </button>)}
        </section>}

      </section></main> :
    <main className="workspace">
      {error && <p role="alert" className="error">{error}</p>}
      {notice && <p role="status" className="banner">{notice}</p>}
      {view === 'Create' ? <RequestForm environment={environment} cancel={() => navigate('Requests')} submit={submit}/> :
       view === 'Detail' && selected ? <>
        <button className="quiet" onClick={() => navigate('Requests')}>← Back to requests</button>
        <div className="page-title"><div><p className="muted">{environmentName(selected.environment)} request</p><h1>{selected.applicationName}</h1></div><Badge status={selected.status}/></div>
        <div className="detail-grid">
          <section className="panel"><h2>Request details</h2><p>{selected.description}</p><dl>
            <dt>Request ID</dt><dd>{selected.id}</dd><dt>API scopes</dt><dd>{selected.scopes.map(scope => scopeLabels[scope]).join(', ')}</dd>
            <dt>Updated</dt><dd>{format(selected.updatedAt)}</dd>
          </dl></section>
          <section className="panel"><h2>Backstage comments</h2>{selected.comments?.length ? selected.comments.map((comment, index) =>
            <article className="comment" key={index}><strong>{comment.author}</strong><small>{format(comment.createdAt)}</small><p>{comment.text}</p></article>
          ) : <p className="muted">No comments yet.</p>}</section>
        </div>
       </> : <>
        <div className="page-title"><div><h1>{view}</h1><p className="muted">{membership?.companyName} · {environmentName(environment)} workspace</p></div>
          {!(view === 'Dashboard' && onboarding?.hasApplications === false) &&
            <button className="primary" onClick={() => navigate('Create')}>＋ Create application</button>}</div>
        {view === 'Dashboard' && <>
          {onboarding?.hasApplications === false ? <section className="onboarding-prompt" aria-labelledby="onboarding-title">
            <div>
              <h2 id="onboarding-title">Finish your onboarding</h2>
              <p>You haven’t created an application yet. Create your first {environmentName(environment)} application to start your integration.</p>
            </div>
            <button className="primary" onClick={() => navigate('Create')}>Create your first application →</button>
          </section> :
            <p className="banner">{environment === 'PRODUCTION' ? 'Backstage reviews Production requests.' : 'Use Sandbox to test your API integration.'}</p>}
          <div className="stats">
            <section className="panel"><span>Requests</span><strong>{rows.length}</strong></section>
            <section className="panel"><span>In progress</span><strong>{rows.filter(row => ['SUBMITTED','IN_REVIEW','CHANGES_REQUESTED'].includes(row.status)).length}</strong></section>
            <section className="panel"><span>Active applications</span><strong>{rows.filter(row => row.status === 'ACTIVE').length}</strong></section>
          </div><h2>Recent requests</h2>
        </>}
        {view !== 'Dashboard' && <div className="filters"><input aria-label="Search applications" placeholder="Search by application name…" value={search} onChange={e => setSearch(e.target.value)}/>
          <select aria-label="Filter by status" value={status} onChange={e => setStatus(e.target.value)}><option value="">All statuses</option>{Object.entries(statusLabels).map(([key, label]) => <option value={key} key={key}>{label}</option>)}</select></div>}
        <div className="records">{(view === 'Dashboard' ? rows.slice(0, 5) : filtered).map(row =>
          <button className="record panel" key={row.id} onClick={() => { setSelected(row); navigate('Detail'); }}>
            <span><strong>{row.applicationName}</strong><small>{row.scopes.map(scope => scopeLabels[scope]).join(' · ')}</small></span><Badge status={row.status}/>
          </button>
        )}{!(view === 'Dashboard' ? rows.length : filtered.length) && <p className="panel muted">{error ? 'Request data unavailable.' : 'No matching requests.'}</p>}</div>
       </>}
    </main>}
    {!auth && <PublicFooter />}
  </div>;
}
