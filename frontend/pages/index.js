import Head from 'next/head';
import Link from 'next/link';
import { PublicHeader, PublicFooter } from '../components/PublicLayout';

const steps = [
  ['♙', 'Get access', 'Contact your organization’s API administrator for a Portal account. Sign in to open your Sandbox workspace.'],
  ['‹/›', 'Create application', 'Enter your application details, select the API scopes you need, and choose your credential method.'],
  ['▤', 'Track your request', 'Follow the status and comments from Backstage. Backstage handles credential delivery separately.']
];

const environments = [
  ['♧', 'Sandbox', ['Test your integration in a separate environment', 'Choose the scopes your application needs', 'Request a client secret or submit a public key', 'Track requests and comments in the Portal']],
  ['↗', 'Production', ['Submit a request for Backstage review', 'Use a customer-managed public key', 'Follow the status of your request', 'Receive credentials through the Backstage delivery process']]
];

const security = [
  ['♧', 'Scoped access', 'Select the API permissions your application needs when submitting your request.'],
  ['♙', 'Separate credential delivery', 'Backstage handles issued credentials separately from the Portal.'],
  ['✓', 'Production review', 'Backstage reviews Production requests and provides status updates and comments.'],
  ['↻', 'Request tracking', 'The Portal refreshes status and comments while you’re viewing a request.']
];

function Actions() {
  return <div className="landing-actions">
    <Link className="landing-button solid" href="/login?access=1">Start building <span aria-hidden="true">→</span></Link>
    <Link className="landing-button outline" href="/login">Sign in</Link>
  </div>;
}

export default function Landing() {
  return <div className="landing">
    <Head><title>API Portal</title><meta name="description" content="Build Sandbox integrations and track Production API requests."/></Head>
    <PublicHeader />
    <main>
      <section className="landing-hero"><div className="landing-container">
        <p className="landing-label">Developer self-service portal</p>
        <h1>Create Sandbox integrations in minutes</h1>
        <p className="landing-lead">Configure your application, choose the API scopes you need, and submit your request. When you’re ready for Production, track your request through Backstage review.</p>
        <Actions />
      </div></section>
      <section className="landing-section" id="how-it-works"><div className="landing-container">
        <h2>Three steps to your first integration</h2>
        <ol className="landing-steps">
          {steps.map(([icon, title, description], index) => <li key={title}>
            <span className="landing-icon filled" aria-hidden="true">{icon}</span>
            <p className="landing-step">Step {index + 1}</p>
            <h3>{title}</h3><p>{description}</p>
          </li>)}
        </ol>
      </div></section>
      <section className="landing-section landing-compare"><div className="landing-container">
        <h2>Sandbox vs Production</h2>
        <div className="landing-environments">
          {environments.map(([icon, title, items]) => <article key={title} className="landing-card">
            <h3><span aria-hidden="true">{icon}</span> {title}</h3>
            <ul>{items.map(item => <li key={item}>{item}</li>)}</ul>
          </article>)}
        </div>
      </div></section>
      <section className="landing-section" id="security"><div className="landing-container">
        <h2>Security built in</h2>
        <div className="landing-security">
          {security.map(([icon, title, description]) => <article className="landing-card" key={title}>
            <span className="landing-icon" aria-hidden="true">{icon}</span>
            <h3>{title}</h3><p>{description}</p>
          </article>)}
        </div>
      </div></section>
      <section className="landing-section landing-cta"><div className="landing-container">
        <h2>Ready to start building?</h2>
        <p>Open your Sandbox workspace and submit your first application.</p>
        <Actions />
      </div></section>
    </main>
    <PublicFooter />
  </div>;
}
