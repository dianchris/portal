import Link from 'next/link';

export function PublicBrand() {
  return <Link className="landing-brand" href="/"><span aria-hidden="true">⚿</span> API Portal</Link>;
}

export function PublicHeader() {
  return <header><div className="landing-container landing-header">
    <PublicBrand />
    <nav aria-label="Main navigation">
      <Link href="/#how-it-works">How it works</Link>
      <Link href="/#security">Security</Link>
      <Link href="/login">Sign in</Link>
      <Link className="landing-button solid" href="/login?access=1">Get access</Link>
    </nav>
  </div></header>;
}

export function PublicFooter() {
  return <footer><div className="landing-container landing-footer">
    <PublicBrand /><span>Partner developer portal</span>
  </div></footer>;
}
