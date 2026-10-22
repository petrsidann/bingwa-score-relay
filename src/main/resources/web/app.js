/* Bingwa Relay — admin portal v2 (core). Plain script, no build step. */
'use strict';

const KEY = new URLSearchParams(location.search).get('key') || '';

const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function api(path, opts) {
  const res = await fetch('/admin/api' + path, Object.assign({
    headers: Object.assign({ 'X-Admin-Key': KEY }, (opts && opts.headers) || {})
  }, opts || {}));
  if (res.status === 401) { showLogin(); throw new Error('unauthorized'); }
  if (!res.ok) {
    let msg = 'HTTP ' + res.status;
    try { const j = await res.json(); msg = j.error || msg; } catch (e) { /* keep status text */ }
    throw new Error(msg);
  }
  const ct = res.headers.get('content-type') || '';
  return ct.indexOf('json') >= 0 ? res.json() : res.text();
}

/* ---------- toast ---------- */
let toastTimer = null;
function toast(msg, kind) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'fixed bottom-6 right-6 z-50 rounded-lg px-4 py-2 text-sm font-semibold shadow-xl ' +
    (kind === 'err' ? 'bg-rose-600 text-white' : 'bg-emerald-600 text-white');
  t.classList.remove('hidden-toast');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden-toast'), 2600);
}

/* ---------- login gate ---------- */
function showLogin() {
  $('#login').classList.remove('hidden');
  $('#app').classList.add('hidden');
  const inp = $('#login-key');
  inp.value = '';
  inp.focus();
}
function initLogin() {
  $('#login-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const k = $('#login-key').value.trim();
    if (!k) return;
    const url = new URL(location.href);
    url.searchParams.set('key', k);
    location.href = url.toString();
  });
}

/* ---------- hash router ---------- */
const routes = { overview: renderOverview, users: renderUsers, offers: renderOffers, payments: renderPayments };
function currentRoute() {
  const h = (location.hash || '#/overview').replace(/^#\/?/, '');
  return routes[h] ? h : 'overview';
}
function navigate() {
  const name = currentRoute();
  $$('.nav-item[data-route]').forEach(a => a.classList.toggle('active', a.dataset.route === name));
  document.title = 'Bingwa Relay \u00b7 ' + name.charAt(0).toUpperCase() + name.slice(1);
  const main = $('#main');
  main.innerHTML = '<div class="page-in space-y-6">' + skeletonsFor(name) + '</div>';
  routes[name](main.querySelector('.page-in'));
}

/* ---------- skeletons & empty states ---------- */
function skRow(cols) {
  return '<tr>' + cols.map(w => '<td><div class="sk" style="height:.875rem;width:' + w + '"></div></td>').join('') + '</tr>';
}
function skeletonsFor(name) {
  if (name === 'overview') {
    const kpi = '<div class="card kpi p-6"><div class="sk mb-3" style="height:.75rem;width:40%"></div><div class="sk" style="height:1.75rem;width:60%"></div></div>';
    const chartBox = '<div class="card p-6"><div class="sk mb-4" style="height:1rem;width:30%"></div><div class="sk" style="height:14rem"></div></div>';
    return '<div class="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-6">' + kpi.repeat(4) + '</div>' +
      '<div class="grid grid-cols-1 lg:grid-cols-2 gap-6">' + chartBox + chartBox + '</div>' +
      '<div class="card p-6"><div class="sk mb-4" style="height:1rem;width:25%"></div><div class="sk" style="height:10rem"></div></div>';
  }
  const cols = name === 'users' ? ['30%', '25%', '20%', '25%'] : ['20%', '35%', '15%', '15%', '15%'];
  return '<div class="card overflow-hidden">' +
    '<div class="px-6 py-4 border-b border-slate-700/60"><div class="sk" style="height:1rem;width:30%"></div></div>' +
    '<table class="tbl w-full"><tbody>' + skRow(cols).repeat(5) + '</tbody></table></div>';
}
function emptyState(icon, title, sub) {
  return '<div class="flex flex-col items-center justify-center py-16 text-center">' +
    '<div class="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-slate-800 text-slate-500">' + icon + '</div>' +
    '<p class="t-section text-slate-300">' + esc(title) + '</p>' +
    '<p class="t-body mt-1 text-slate-500 max-w-sm">' + esc(sub) + '</p></div>';
}
const ICONS = {
  inbox: '<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-6 w-6"><path stroke-linecap="round" stroke-linejoin="round" d="M2.25 13.38V10.5a2.25 2.25 0 0 1 2.25-2.25h15a2.25 2.25 0 0 1 2.25 2.25v2.88m-19.5 0A2.25 2.25 0 0 0 2.25 15.75v2.25a2.25 2.25 0 0 0 2.25 2.25h15a2.25 2.25 0 0 0 2.25-2.25v-2.25a2.25 2.25 0 0 0-2.25-2.37m-15 0a2.25 2.25 0 1 1-4.31.97m19.31-.97a2.25 2.25 0 1 1 4.31.97"/></svg>',
  users: '<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-6 w-6"><path stroke-linecap="round" stroke-linejoin="round" d="M15 19.13A5.95 5.95 0 0 0 18 20.25c2.9 0 5.25-1.75 5.25-3.94 0-1.4-.74-2.64-1.9-3.44M15 19.13V18a3 3 0 0 0-3-3H6.5a3 3 0 0 0-3 3v1.13m11.5-14.88a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0Z"/></svg>',
  tag: '<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-6 w-6"><path stroke-linecap="round" stroke-linejoin="round" d="M9.568 3H5.25A2.25 2.25 0 0 0 3 5.25v4.318c0 .597.237 1.17.659 1.591l9.585 9.585c.421.422.994.659 1.591.659H18a2.25 2.25 0 0 0 2.25-2.25v-4.318c0-.597-.237-1.17-.659-1.591L9.99 3.66A2.252 2.252 0 0 0 8.4 3h1.168ZM6 6h.008v.008H6V6Z"/></svg>'
};

/* ---------- formatting ---------- */
function fmtUptime(sec) {
  const d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60);
  return (d ? d + 'd ' : '') + (d || h ? h + 'h ' : '') + m + 'm';
}
function fmtTime(ms) {
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/* ================= OVERVIEW ================= */
let sseSource = null;
const charts = {};
function destroyCharts() { Object.values(charts).forEach(c => c && c.destroy()); }

async function renderOverview(el) {
  el.innerHTML =
    '<div class="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-6">' +
      kpiCard('kpi-ws', 'Live WS connections') +
      kpiCard('kpi-rps', 'Requests / sec') +
      kpiCard('kpi-msg', 'Messages / min') +
      kpiCard('kpi-up', 'Uptime') +
    '</div>' +
    '<div class="grid grid-cols-1 lg:grid-cols-2 gap-6">' +
      chartCard('chart-ws', 'WebSocket connections') +
      chartCard('chart-rps', 'Request rate') +
    '</div>' +
    '<div class="card p-6">' +
      '<div class="mb-4 flex items-center justify-between">' +
        '<h3 class="t-section text-slate-200">Traffic by route</h3>' +
        '<span class="t-label text-slate-500">since boot</span></div>' +
      '<div id="route-bars" class="space-y-3"><div class="sk" style="height:6rem"></div></div>' +
    '</div>';

  // seed history, then live-update over SSE
  let hist = [];
  try { hist = await api('/metrics/history'); } catch (e) { /* stream still attempts */ }
  const labels = [], wsData = [], rpsData = [];
  hist.forEach(s => { labels.push(fmtTime(s.t)); wsData.push(s.wsConnections); rpsData.push(+s.rps.toFixed(2)); });
  charts.ws = lineChart($('#chart-ws'), labels, wsData, '#60a5fa');
  charts.rps = lineChart($('#chart-rps'), labels, rpsData, '#2563eb');

  if (sseSource) sseSource.close();
  sseSource = new EventSource('/admin/sse/metrics?key=' + encodeURIComponent(KEY));
  sseSource.addEventListener('metric', (ev) => {
    const s = JSON.parse(ev.data);
    setKpi('kpi-ws', s.wsConnections);
    setKpi('kpi-rps', s.rps.toFixed(1));
    setKpi('kpi-msg', s.messagesPerMin);
    pushPoint(charts.ws, fmtTime(s.t || Date.now()), s.wsConnections);
    pushPoint(charts.rps, fmtTime(s.t || Date.now()), +s.rps.toFixed(2));
    const d1 = $('#' + 'chart-ws-live-dot'), d2 = $('#' + 'chart-rps-live-dot');
    if (d1) d1.classList.remove('pulse-off');
    if (d2) d2.classList.remove('pulse-off');
  });
  sseSource.onerror = () => {
    $$('.live-dot').forEach(d => d.classList.add('pulse-off'));
  };

  // uptime + route bars (slow poll while on this page)
  async function slowPoll() {
    if (currentRoute() !== 'overview') return;
    try {
      const m = await api('/metrics');
      setKpi('kpi-up', fmtUptime(m.uptimeSeconds));
      const entries = Object.entries(m.routes || {}).sort((a, b) => b[1] - a[1]).slice(0, 8);
      const box = $('#route-bars');
      if (!box) return;
      if (!entries.length) {
        box.innerHTML = emptyState(ICONS.inbox, 'No traffic yet', 'Route counters appear as soon as clients hit the relay API.');
        return;
      }
      const max = entries[0][1];
      box.innerHTML = entries.map(function (pair) {
        const r = pair[0], n = pair[1];
        return '<div class="group">' +
          '<div class="mb-1 flex items-baseline justify-between gap-4">' +
            '<code class="truncate text-xs text-slate-400 group-hover:text-slate-200 transition-colors">' + esc(r) + '</code>' +
            '<span class="num text-xs font-semibold text-slate-300">' + n.toLocaleString() + '</span></div>' +
          '<div class="h-1.5 rounded-full bg-slate-800"><div class="h-1.5 rounded-full bg-blue-600 transition-all duration-500" style="width:' +
            Math.max(2, Math.round(n / max * 100)) + '%"></div></div></div>';
      }).join('');
    } catch (e) { /* ignore transient */ }
    setTimeout(slowPoll, 5000);
  }
  slowPoll();
}

function kpiCard(id, label) {
  return '<div class="card kpi p-6"><p class="t-micro mb-2 text-slate-500">' + esc(label) + '</p>' +
    '<p id="' + id + '" class="num t-display text-slate-100">&mdash;</p></div>';
}
function chartCard(id, title) {
  return '<div class="card p-6">' +
    '<div class="mb-4 flex items-center justify-between">' +
      '<h3 class="t-section text-slate-200">' + esc(title) + '</h3>' +
      '<span class="badge badge-info"><span id="' + id + '-live-dot" class="dot live-dot pulse pulse-off"></span>live</span></div>' +
    '<div class="relative h-56"><canvas id="' + id + '"></canvas></div></div>';
}
function setKpi(id, v) { const node = document.getElementById(id); if (node) node.textContent = v; }

function lineChart(canvas, labels, data, color) {
  if (!canvas || !window.Chart) return null;
  const ctx = canvas.getContext('2d');
  const grad = ctx.createLinearGradient(0, 0, 0, 220);
  grad.addColorStop(0, 'rgba(37,99,235,0.35)');
  grad.addColorStop(1, 'rgba(37,99,235,0)');
  return new Chart(ctx, {
    type: 'line',
    data: { labels: labels, datasets: [{ data: data, borderColor: color, backgroundColor: grad, borderWidth: 2,
      fill: true, tension: 0.35, pointRadius: 0, pointHitRadius: 8 }] },
    options: {
      responsive: true, maintainAspectRatio: false, animation: { duration: 300 },
      interaction: { intersect: false, mode: 'index' },
      plugins: { legend: { display: false }, tooltip: {
        backgroundColor: '#0f172a', borderColor: '#334155', borderWidth: 1, titleColor: '#94a3b8',
        bodyColor: '#e2e8f0', padding: 10, displayColors: false, cornerRadius: 8 } },
      scales: {
        x: { ticks: { color: '#475569', maxTicksLimit: 6, font: { size: 10 } }, grid: { display: false } },
        y: { beginAtZero: true, ticks: { color: '#475569', precision: 0, font: { size: 10 } },
             grid: { color: 'rgba(51,65,85,0.4)' } } }
    }
  });
}
function pushPoint(chart, label, value) {
  if (!chart) return;
  chart.data.labels.push(label);
  chart.data.datasets[0].data.push(value);
  while (chart.data.labels.length > 300) { chart.data.labels.shift(); chart.data.datasets[0].data.shift(); }
  chart.update('none');
}

/* ================= USERS ================= */
async function renderUsers(el) {
  let users;
  try { users = await api('/users'); } catch (e) { el.innerHTML = cardError(e); return; }
  el.innerHTML =
    '<div class="flex flex-wrap items-center justify-between gap-4">' +
      '<div><h2 class="t-title text-slate-100">Users</h2>' +
        '<p class="t-body text-slate-500">' + users.length + ' registered ' + (users.length === 1 ? 'account' : 'accounts') +
        ' \u00b7 kill switch disables an account instantly</p></div>' +
      '<a class="btn btn-primary" href="/admin/api/export/users.csv?key=' + encodeURIComponent(KEY) + '">' +
        '<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-4 w-4"><path stroke-linecap="round" stroke-linejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5M16.5 12 12 16.5m0 0L7.5 12m4.5 4.5V3"/></svg>' +
        'Export CSV</a></div>' +
    '<div class="card overflow-hidden"><div class="overflow-x-auto">' +
      '<table class="tbl w-full"><thead><tr><th>User</th><th>Phone</th><th>Created</th><th>Status</th>' +
      '<th class="text-right">Kill switch</th></tr></thead><tbody id="users-body"></tbody></table></div>' +
      (users.length ? '' : emptyState(ICONS.users, 'No registered users yet',
        'Accounts appear here after the first successful sign-up via POST /api/auth/sign-up.')) +
    '</div>';
  const tbody = $('#users-body');
  users.forEach(u => {
    const tr = document.createElement('tr');
    const active = u.status === 'active';
    tr.innerHTML =
      '<td class="font-medium text-slate-200">' + esc(u.id) + '</td>' +
      '<td class="num">' + esc(u.phone) + '</td>' +
      '<td class="text-slate-500">' + esc((u.createdAt || '').slice(0, 10)) + '</td>' +
      '<td><span class="badge ' + (active ? 'badge-ok' : 'badge-bad') + '"><span class="dot"></span>' + esc(u.status) + '</span></td>' +
      '<td><div class="flex justify-end">' +
        '<button class="btn ' + (active ? 'btn-danger' : 'btn-success') + '" data-id="' + esc(u.id) + '" data-active="' + active + '">' +
        (active ? 'Disable' : 'Reactivate') + '</button></div></td>';
    tr.querySelector('button').addEventListener('click', async (ev) => {
      const b = ev.currentTarget;
      b.disabled = true;
      try {
        await api('/users/' + encodeURIComponent(b.dataset.id) + '/status', {
          method: 'POST', contentType: 'application/json',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ status: b.dataset.active === 'true' ? 'disabled' : 'active' })
        });
        toast(b.dataset.active === 'true' ? 'User disabled' : 'User reactivated');
        navigate();
      } catch (e) { toast('Failed: ' + e.message, 'err'); b.disabled = false; }
    });
    tbody.appendChild(tr);
  });
}

/* ================= PAYMENTS ================= */
async function renderPayments(el) {
  let txs;
  try { txs = await api('/transactions'); } catch (e) { el.innerHTML = cardError(e); return; }
  el.innerHTML =
    '<div class="flex flex-wrap items-center justify-between gap-4">' +
      '<div><h2 class="t-title text-slate-100">Payments & Reconciliation</h2>' +
        '<p class="t-body text-slate-500">' + txs.length + ' total transactions \u00b7 M-Pesa STK push & manual fallback</p></div></div>' +
    '<div class="card overflow-hidden"><div class="overflow-x-auto">' +
      '<table class="tbl w-full"><thead><tr><th>Tx ID</th><th>Offer</th><th>Amount</th><th>Status</th>' +
      '<th class="text-right">Actions</th></tr></thead><tbody id="txs-body"></tbody></table></div>' +
      (txs.length ? '' : emptyState(ICONS.inbox, 'No transactions yet',
        'Transactions created via M-Pesa STK push or storefront buy buttons will appear here.')) +
    '</div>';
  const tbody = $('#txs-body');
  txs.forEach(t => {
    const tr = document.createElement('tr');
    const isPaid = t.status === 'paid';
    const isFailed = t.status === 'failed';
    const badgeClass = isPaid ? 'badge-ok' : (isFailed ? 'badge-bad' : 'badge-dim');
    tr.innerHTML =
      '<td class="font-medium text-slate-200"><code class="text-xs">' + esc(t.id) + '</code></td>' +
      '<td>' + esc(t.offerId) + '</td>' +
      '<td class="num">' + esc(t.currency || 'KES') + ' ' + Number(t.amount).toFixed(2) + '</td>' +
      '<td><span class="badge ' + badgeClass + '"><span class="dot"></span>' + esc(t.status) + '</span></td>' +
      '<td><div class="flex justify-end gap-2">' +
        (!isPaid ? '<button class="btn btn-success confirm-btn" data-id="' + esc(t.id) + '">Confirm</button>' : '') +
        (!isFailed ? '<button class="btn btn-danger fail-btn" data-id="' + esc(t.id) + '">Fail</button>' : '') +
      '</div></td>';

    const confirmBtn = tr.querySelector('.confirm-btn');
    if (confirmBtn) {
      confirmBtn.addEventListener('click', async (ev) => {
        const b = ev.currentTarget; b.disabled = true;
        try {
          await api('/transactions/' + encodeURIComponent(b.dataset.id) + '/confirm', { method: 'POST' });
          toast('Transaction confirmed & pass granted');
          navigate();
        } catch (e) { toast('Failed: ' + e.message, 'err'); b.disabled = false; }
      });
    }
    const failBtn = tr.querySelector('.fail-btn');
    if (failBtn) {
      failBtn.addEventListener('click', async (ev) => {
        const b = ev.currentTarget; b.disabled = true;
        try {
          await api('/transactions/' + encodeURIComponent(b.dataset.id) + '/fail', { method: 'POST' });
          toast('Transaction marked failed');
          navigate();
        } catch (e) { toast('Failed: ' + e.message, 'err'); b.disabled = false; }
      });
    }
    tbody.appendChild(tr);
  });
}

/* ================= OFFERS ================= */
async function renderOffers(el) {
  let offers;
  try { offers = await api('/offers'); } catch (e) { el.innerHTML = cardError(e); return; }
  el.innerHTML =
    '<div class="flex flex-wrap items-center justify-between gap-4">' +
      '<div><h2 class="t-title text-slate-100">Offers</h2>' +
        '<p class="t-body text-slate-500">' + offers.length + ' total \u00b7 ' +
        offers.filter(o => o.verified).length + ' verified and visible to clients</p></div></div>' +
    '<div class="card overflow-hidden"><div class="overflow-x-auto">' +
      '<table class="tbl w-full"><thead><tr><th>ID</th><th>Title</th><th>Price</th><th>Verified</th>' +
      '<th class="text-right">Visibility</th></tr></thead><tbody id="offers-body"></tbody></table></div>' +
      (offers.length ? '' : emptyState(ICONS.tag, 'No offers yet',
        'Create your first offer via POST /admin/api/offers \u2014 it will show up here for verification.')) +
    '</div>';
  const tbody = $('#offers-body');
  offers.forEach(o => {
    const tr = document.createElement('tr');
    tr.innerHTML =
      '<td class="font-medium text-slate-200">' + esc(o.id) + '</td>' +
      '<td>' + esc(o.title) + '</td>' +
      '<td class="num">\u20a6 ' + Number(o.price).toLocaleString(undefined, { minimumFractionDigits: 2 }) + '</td>' +
      '<td><span class="badge ' + (o.verified ? 'badge-ok' : 'badge-dim') + '"><span class="dot"></span>' +
        (o.verified ? 'Verified' : 'Hidden') + '</span></td>' +
      '<td><div class="flex items-center justify-end">' +
        '<label class="switch" title="Toggle client visibility">' +
          '<input type="checkbox" ' + (o.verified ? 'checked' : '') + ' aria-label="Verify offer ' + esc(o.id) + '">' +
          '<span class="track"></span><span class="thumb"></span></label></div></td>';
    tr.querySelector('input').addEventListener('change', async (ev) => {
      const inp = ev.currentTarget;
      inp.disabled = true;
      try {
        await api('/offers/' + encodeURIComponent(o.id) + '/verify?value=' + inp.checked, { method: 'POST' });
        toast(inp.checked ? 'Offer verified' : 'Offer hidden');
      } catch (e) { toast('Failed: ' + e.message, 'err'); inp.checked = !inp.checked; }
      inp.disabled = false;
    });
    tbody.appendChild(tr);
  });
}

function cardError(e) {
  return '<div class="card p-6">' + emptyState(ICONS.inbox, 'Could not load data',
    e.message === 'unauthorized' ? 'Session key invalid \u2014 sign in again.' : e.message) + '</div>';
}

/* ================= boot ================= */
function boot() {
  initLogin();
  if (!KEY) { showLogin(); return; }
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  window.addEventListener('hashchange', () => {
    destroyCharts();
    if (sseSource) { sseSource.close(); sseSource = null; }
    navigate();
  });
  destroyCharts();
  navigate();
}
document.addEventListener('DOMContentLoaded', boot);
