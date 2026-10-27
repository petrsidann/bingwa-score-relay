package relay

/**
 * Admin portal v2 — SPA shell served by Ktor as static assets (no frontend build).
 * Stack: Tailwind (CDN) + htmx + Chart.js, Inter/Manrope fonts, dark-slate canvas,
 * brand blue #2563EB, 8pt spacing grid. The shell carries no data; all content is
 * fetched by /admin/assets/app.js from the gated "admin/api" JSON endpoints.
 */
object AdminPortal {
    val shell: String = SHELL

    private const val SHELL = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Bingwa Relay · Admin</title>
  <link rel="stylesheet" href="/admin/assets/app.css?v=3">
  <script defer src="/admin/assets/app.js?v=3"></script>
</head>
<body>

  <!-- login gate: shown when ?key= is missing; submitting reloads with the key -->
  <div id="login" class="hidden login-overlay">
    <div class="card login-card">
      <div class="brand-header">
        <span class="brand-logo">B</span>
        <div>
          <p class="t-title">Bingwa Relay</p>
          <p class="t-label">Admin Console v3</p>
        </div>
      </div>
      <form id="login-form">
        <label for="login-key" class="t-micro">Admin Key</label>
        <input id="login-key" type="password" name="key" placeholder="ADMIN_KEY" autocomplete="off" class="input-field">
        <button type="submit" class="btn btn-primary w-full">Sign in</button>
        <p class="t-label text-subtle">Key comes from <code>ADMIN_KEY</code> (fallback <code>RELAY_SERVER_KEY</code>). You can also open <code>?key=…</code> or send <code>X-Admin-Key</code>.</p>
      </form>
    </div>
  </div>

  <!-- app shell -->
  <div id="app" class="hidden app-layout">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <span class="brand-logo">B</span>
        <div>
          <p class="brand-title">Bingwa Relay</p>
          <p class="t-label">Admin Console</p>
        </div>
      </div>
      <nav class="nav-menu">
        <a class="nav-item" href="#/overview" data-route="overview">
          <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"><path stroke-linecap="round" stroke-linejoin="round" d="M3.75 3v11.25A2.25 2.25 0 0 0 6 16.5h2.25M3.75 3h-1.5m1.5 0h16.5m0 0h1.5m-1.5 0v11.25A2.25 2.25 0 0 1 16.5 16.5h-2.25m-7.5 0h7.5m-7.5 0-1 3m8.5-3 1 3m0 0 .5 1.5m-.5-1.5h-9.5m0 0-.5 1.5"/></svg>
          <span>Overview</span>
        </a>
        <a class="nav-item" href="#/users" data-route="users">
          <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"><path stroke-linecap="round" stroke-linejoin="round" d="M15 19.128a9.38 9.38 0 0 0 2.643.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21l-1.184-.173A4.125 4.125 0 0 1 9 12.75a3.375 3.375 0 0 1 6 .246m-8.602-.86a2.25 2.25 0 0 1 2.602-2.602M12 12.75a3.375 3.375 0 1 0-3.231-4.256"/></svg>
          <span>Users</span>
        </a>
        <a class="nav-item" href="#/payments" data-route="payments">
          <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"><path stroke-linecap="round" stroke-linejoin="round" d="M2.25 8.25h19.5M2.25 9h19.5m-16.5 5.25h6m-6 2.25h3m-3.75 3h15a2.25 2.25 0 0 0 2.25-2.25V6.75A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25v10.5A2.25 2.25 0 0 0 4.5 19.5Z"/></svg>
          <span>Payments</span>
        </a>
        <a class="nav-item" href="#/stores" data-route="stores">
          <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"><path stroke-linecap="round" stroke-linejoin="round" d="M13.5 21v-7.5a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 .75.75V21m-4.5 0H2.25A2.25 2.25 0 0 1 0 18.75V10.5M13.5 21h8.25A2.25 2.25 0 0 0 24 18.75V10.5M3 10.5 12 3l9 7.5M3 10.5v8.25M21 10.5v8.25"/></svg>
          <span>Stores</span>
        </a>
      </nav>
      <div class="sidebar-footer">
        <p class="t-label text-subtle">Bingwa Ops Console v3</p>
      </div>
    </aside>

    <div class="main-content">
      <header class="topbar">
        <div class="mobile-nav">
          <a class="nav-item-sm" href="#/overview" data-route="overview">Overview</a>
          <a class="nav-item-sm" href="#/users" data-route="users">Users</a>
          <a class="nav-item-sm" href="#/payments" data-route="payments">Payments</a>
          <a class="nav-item-sm" href="#/stores" data-route="stores">Stores</a>
        </div>
        <div class="desktop-title">
          <h1 class="t-section">Operations Console</h1>
        </div>
        <div class="topbar-actions">
          <span class="badge badge-ok"><span class="dot pulse"></span>System Online</span>
          <span class="avatar" title="Administrator">AD</span>
        </div>
      </header>
      <main id="main" class="view-area"></main>
    </div>
  </div>

  <div id="toast" class="hidden-toast toast"></div>
</body>
</html>
"""
}
