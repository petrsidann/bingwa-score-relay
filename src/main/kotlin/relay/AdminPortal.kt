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
<html lang="en" class="h-full">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Bingwa Relay · Admin</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Manrope:wght@600;700;800&display=swap" rel="stylesheet">
  <!-- Tailwind Play CDN (internal admin tool; no frontend build by design) -->
  <script src="https://cdn.tailwindcss.com"></script>
  <script>
    tailwind.config = { theme: { extend: { colors: { brand: { DEFAULT: '#2563EB', hover: '#1D4ED8' } },
      fontFamily: { sans: ['Inter','ui-sans-serif','system-ui'], display: ['Manrope','Inter'] } } } };
  </script>
  <link rel="stylesheet" href="/admin/assets/app.css?v=2">
  <script defer src="https://unpkg.com/htmx.org@1.9.12"></script>
  <script defer src="https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js"></script>
  <script defer src="/admin/assets/app.js?v=2"></script>
</head>
<body class="h-full overflow-hidden bg-slate-900 text-slate-200 antialiased">

  <!-- login gate: shown when ?key= is missing; submitting reloads with the key -->
  <div id="login" class="hidden fixed inset-0 z-40 flex items-center justify-center bg-slate-900 px-4">
    <div class="card w-full max-w-sm p-8 shadow-2xl">
      <div class="mb-6 flex items-center gap-3">
        <span class="flex h-10 w-10 items-center justify-center rounded-xl bg-brand font-display text-lg font-extrabold text-white shadow-lg shadow-blue-600/30">B</span>
        <div>
          <p class="t-title leading-tight text-slate-100">Bingwa Relay</p>
          <p class="t-label text-slate-500">Admin Portal v2</p>
        </div>
      </div>
      <form id="login-form">
        <label for="login-key" class="t-micro mb-2 block text-slate-500">Admin key</label>
        <input id="login-key" type="password" name="key" placeholder="ADMIN_KEY" autocomplete="off"
          class="mb-4 w-full rounded-lg border border-slate-700 bg-slate-800 px-4 py-2.5 text-sm text-slate-100 placeholder-slate-500 outline-none transition-colors focus:border-blue-500 focus:ring-2 focus:ring-blue-500/30">
        <button type="submit" class="btn btn-primary h-10 w-full">Sign in</button>
        <p class="t-label mt-4 leading-relaxed text-slate-500">Key comes from <code class="text-slate-400">ADMIN_KEY</code> (fallback <code class="text-slate-400">RELAY_SERVER_KEY</code>). You can also open <code class="text-slate-400">?key=…</code> or send the <code class="text-slate-400">X-Admin-Key</code> header to the API.</p>
      </form>
    </div>
  </div>

  <!-- app shell -->
  <div id="app" class="hidden h-full lg:grid lg:grid-cols-[16rem_1fr]">
    <aside class="hidden border-r border-slate-800 bg-slate-950/60 lg:flex lg:flex-col">
      <div class="flex items-center gap-3 px-6 py-6">
        <span class="flex h-9 w-9 items-center justify-center rounded-lg bg-brand font-display text-base font-extrabold text-white shadow-lg shadow-blue-600/30">B</span>
        <div>
          <p class="font-display text-sm font-bold text-slate-100">Bingwa Relay</p>
          <p class="t-label text-slate-500">Admin Portal</p>
        </div>
      </div>
      <nav class="flex-1 space-y-1 px-4">
        <a class="nav-item" href="#/overview" data-route="overview">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M3.75 3v11.25A2.25 2.25 0 0 0 6 16.5h2.25M3.75 3h-1.5m1.5 0h16.5m0 0h1.5m-1.5 0v11.25A2.25 2.25 0 0 1 16.5 16.5h-2.25m-7.5 0h7.5m-7.5 0-1 3m8.5-3 1 3m0 0 .5 1.5m-.5-1.5h-9.5m0 0-.5 1.5"/></svg>
          Overview
        </a>
        <a class="nav-item" href="#/users" data-route="users">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M15 19.128a9.38 9.38 0 0 0 2.643.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21l-1.184-.173A4.125 4.125 0 0 1 9 12.75a3.375 3.375 0 0 1 6 .246m-8.602-.86a2.25 2.25 0 0 1 2.602-2.602M12 12.75a3.375 3.375 0 1 0-3.231-4.256"/></svg>
          Users
        </a>
        <a class="nav-item" href="#/offers" data-route="offers">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M9.568 3H5.25A2.25 2.25 0 0 0 3 5.25v4.318c0 .597.237 1.17.659 1.591l9.585 9.585c.421.422.994.659 1.591.659H18a2.25 2.25 0 0 0 2.25-2.25v-4.318c0-.597-.237-1.17-.659-1.591L9.99 3.66A2.252 2.252 0 0 0 8.4 3h1.168ZM6 6h.008v.008H6V6Z"/></svg>
          Offers
        </a>
        <a class="nav-item" href="#/payments" data-route="payments">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" d="M2.25 8.25h19.5M2.25 9h19.5m-16.5 5.25h6m-6 2.25h3m-3.75 3h15a2.25 2.25 0 0 0 2.25-2.25V6.75A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25v10.5A2.25 2.25 0 0 0 4.5 19.5Z"/></svg>
          Payments
        </a>
      </nav>
      <div class="border-t border-slate-800 px-6 py-4">
        <p class="t-label text-slate-600">Relay · live over SSE</p>
      </div>
    </aside>

    <div class="flex h-full min-w-0 flex-col">
      <header class="flex h-16 shrink-0 items-center justify-between border-b border-slate-800 bg-slate-900/80 px-6 backdrop-blur">
        <div class="flex gap-1 lg:hidden">
          <a class="nav-item !px-3" href="#/overview" data-route="overview">Overview</a>
          <a class="nav-item !px-3" href="#/users" data-route="users">Users</a>
          <a class="nav-item !px-3" href="#/offers" data-route="offers">Offers</a>
        </div>
        <div class="hidden lg:block">
          <h1 class="t-section text-slate-400">Operations</h1>
        </div>
        <div class="flex items-center gap-4">
          <span class="badge badge-ok hidden sm:inline-flex"><span class="dot pulse"></span>Server online</span>
          <span class="flex h-8 w-8 items-center justify-center rounded-full bg-slate-800 text-xs font-semibold text-slate-300 ring-1 ring-slate-700 transition-colors hover:bg-slate-700" title="Administrator">AD</span>
        </div>
      </header>
      <main id="main" class="flex-1 overflow-y-auto p-6"></main>
    </div>
  </div>

  <div id="toast" class="hidden-toast fixed bottom-6 right-6 z-50 rounded-lg px-4 py-2 text-sm font-semibold shadow-xl"></div>
  <div id="htmx-indicator" class="htmx-indicator fixed bottom-4 left-4 z-40"></div>
</body>
</html>
"""
}
