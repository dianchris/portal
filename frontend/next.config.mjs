const backend = process.env.PORTAL_BACKEND_URL || 'http://127.0.0.1:8080';
export default {
  poweredByHeader: false,
  devIndicators: false,
  async rewrites() {
    return [
      { source: '/api/password-resets/:path*', destination: backend + '/api/password-resets/:path*' },
      { source: '/api/demo/accounts', destination: backend + '/api/demo/accounts' },
      { source: '/api/account/:path*', destination: backend + '/api/account/:path*' },
      { source: '/api/requests/:path*', destination: backend + '/api/requests/:path*' },
      { source: '/api/onboarding/:path*', destination: backend + '/api/onboarding/:path*' }
    ];
  }
};
