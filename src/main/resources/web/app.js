/* Bingwa Relay — Admin Portal v3 SPA Frontend
   Self-contained vanilla JS with hash routing and live polling.
*/
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

/* ---------- Toast Notifications ---------- */
let toastTimer = null;
function toast(msg, kind) {
  const t = $('#toast');
  if (!t) return;
  t.textContent = msg;
  t.style.borderColor = kind === 'err' ? 'var(--status-failed-fg)' : 'var(--status-ok-fg)';
  t.classList.remove('hidden-toast');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden-toast'), 2600);
}

/* ---------- Login Gate ---------- */
function showLogin() {
  $('#login').classList.remove('hidden');
  $('#app').classList.add('hidden');
  const inp = $('#login-key');
  if (inp) {
    inp.value = '';
    inp.focus();
  }
}
function initLogin() {
  const form = $('#login-form');
  if (form) {
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const k = $('#login-key').value.trim();
      if (!k) return;
      const url = new URL(location.href);
      url.searchParams.set('key', k);
      location.href = url.toString();
    });
  }
}

/* ---------- Router ---------- */
let pollInterval = null;
const routes = {
  overview: renderOverview,
  users: renderUsers,
  payments: renderPayments,
  stores: renderStores
};

function currentRoute() {
  const h = (location.hash || '#/overview').replace(/^#\/?/, '');
  return routes[h] ? h : 'overview';
}

function navigate() {
  if (pollInterval) { clearInterval(pollInterval); pollInterval = null; }
  const name = currentRoute();
  $$('.nav-item[data-route]').forEach(a => a.classList.toggle('active', a.dataset.route === name));
  $$('.nav-item-sm[data-route]').forEach(a => a.classList.toggle('active', a.dataset.route === name));
  document.title = 'Bingwa Relay · ' + name.charAt(0).toUpperCase() + name.slice(1);
  const main = $('#main');
  main.innerHTML = '<div class="view-container"></div>';
  routes[name](main.querySelector('.view-container'));
}

/* Masking helper */
function maskPhone(phone) {
  if (!phone) return '—';
  const clean = String(phone).trim();
  if (clean.length <= 6) return '***';
  return clean.slice(0, 3) + '***' + clean.slice(-3);
}

/* ================= 1. OVERVIEW ================= */
async function renderOverview(el) {
  async function loadData() {
    try {
      const data = await api('/overview');
      el.innerHTML =
        '<div class="grid-4">' +
          kpiCard('Total Users', data.totalUsers.toLocaleString()) +
          kpiCard('Active Plans', data.activePlans.toLocaleString()) +
          kpiCard('Transactions Today', data.transactionsToday.toLocaleString()) +
          kpiCard('Revenue This Week', 'Ksh ' + Number(data.revenueThisWeekKsh).toLocaleString(undefined, { minimumFractionDigits: 2 })) +
        '</div>' +

        '<div class="grid-2">' +
          '<div class="card">' +
            '<div style="display:flex; justify-content:space-between; align-items:center;">' +
              '<h3 class="t-section">7-Day Revenue & Activity Sparkline</h3>' +
              '<span class="badge badge-ok"><span class="dot pulse"></span>10s Live Poll</span>' +
            '</div>' +
            '<div class="sparkline-box">' + renderSparkline(data.sparkline7d || []) + '</div>' +
          '</div>' +

          '<div class="card">' +
            '<h3 class="t-section" style="margin-bottom:12px;">Live Activity Feed</h3>' +
            '<div class="activity-feed">' +
              (data.activityFeed && data.activityFeed.length ? data.activityFeed.map(item =>
                '<div class="activity-item">' +
                  '<div>' +
                    '<p class="t-body" style="font-weight:600;">' + esc(item.title) + '</p>' +
                    '<p class="t-label text-subtle">' + esc(item.subtitle) + '</p>' +
                  '</div>' +
                  '<div style="text-align:right;">' +
                    (item.amount != null ? '<p class="num" style="font-weight:700; color:var(--status-ok-fg);">+Ksh ' + Number(item.amount).toFixed(2) + '</p>' : '') +
                    '<p class="t-micro">' + esc(item.timestamp.slice(0, 10)) + '</p>' +
                  '</div>' +
                '</div>'
              ).join('') : '<p class="t-body text-subtle">No recent activity recorded.</p>') +
            '</div>' +
          '</div>' +
        '</div>';
    } catch (e) {
      el.innerHTML = '<div class="card"><p class="t-body" style="color:var(--status-failed-fg)">Failed to load overview: ' + esc(e.message) + '</p></div>';
    }
  }

  await loadData();
  pollInterval = setInterval(loadData, 10000);
}

function kpiCard(title, val) {
  return '<div class="card stat-card">' +
    '<p class="t-micro">' + esc(title) + '</p>' +
    '<p class="val num">' + esc(val) + '</p>' +
  '</div>';
}

function renderSparkline(points) {
  if (!points || !points.length) return '<div class="text-subtle">No sparkline data</div>';
  const min = Math.min(...points);
  const max = Math.max(...points) || 1;
  const w = 400;
  const h = 140;
  const step = w / (points.length - 1 || 1);

  const coords = points.map((p, i) => {
    const x = i * step;
    const y = h - ((p - min) / (max - min || 1)) * (h - 20) - 10;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  const pathD = 'M ' + coords.join(' L ');
  const areaD = pathD + ` L ${w},${h} L 0,${h} Z`;

  return `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" style="width:100%; height:100%; overflow:visible;">
    <defs>
      <linearGradient id="sg" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="var(--brand-blue)" stop-opacity="0.35"/>
        <stop offset="100%" stop-color="var(--brand-blue)" stop-opacity="0"/>
      </linearGradient>
    </defs>
    <path d="${areaD}" fill="url(#sg)" />
    <path d="${pathD}" fill="none" stroke="var(--brand-blue)" stroke-width="3" stroke-linecap="round"/>
  </svg>`;
}

/* ================= 2. USERS ================= */
async function renderUsers(el) {
  let users = [];
  try {
    users = await api('/users');
  } catch (e) {
    el.innerHTML = '<div class="card"><p class="t-body" style="color:var(--status-failed-fg)">Failed to load users: ' + esc(e.message) + '</p></div>';
    return;
  }

  el.innerHTML =
    '<div class="card" style="margin-bottom:16px; padding:16px 24px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px;">' +
      '<div>' +
        '<h2 class="t-title">User Accounts Intelligence</h2>' +
        '<p class="t-label text-subtle">' + users.length + ' accounts registered · Search & click row for full identity history drawer</p>' +
      '</div>' +
      '<div style="display:flex; gap:12px;">' +
        '<input id="user-search" type="text" placeholder="Search name, phone, business, email..." class="input-field" style="margin:0; width:260px;">' +
        '<a class="btn btn-secondary" href="/admin/api/export/users.csv?key=' + encodeURIComponent(KEY) + '">Export CSV</a>' +
      '</div>' +
    '</div>' +

    '<div class="card" style="padding:0; overflow:hidden;">' +
      '<div class="table-wrapper">' +
        '<table class="tbl">' +
          '<thead>' +
            '<tr>' +
              '<th>Name / Identity</th>' +
              '<th>Business</th>' +
              '<th>Masked Phone</th>' +
              '<th>Email</th>' +
              '<th>Plan</th>' +
              '<th>Credits</th>' +
              '<th>Joined</th>' +
              '<th>Status</th>' +
            '</tr>' +
          '</thead>' +
          '<tbody id="users-tbody"></tbody>' +
        '</table>' +
      '</div>' +
    '</div>' +

    '<div id="user-drawer-backdrop" class="drawer-backdrop hidden">' +
      '<div class="drawer-content">' +
        '<div style="display:flex; justify-content:space-between; align-items:center;">' +
          '<h3 class="t-title">User Intelligence Detail</h3>' +
          '<button id="close-drawer" class="btn btn-secondary" style="height:28px; padding:0 8px;">✕</button>' +
        '</div>' +
        '<div id="drawer-body" style="display:flex; flex-direction:column; gap:16px;"></div>' +
      '</div>' +
    '</div>';

  const tbody = $('#users-tbody');

  function renderTableRows(filterText = '') {
    tbody.innerHTML = '';
    const q = filterText.toLowerCase().trim();
    const filtered = users.filter(u =>
      (u.name && u.name.toLowerCase().includes(q)) ||
      (u.business && u.business.toLowerCase().includes(q)) ||
      (u.email && u.email.toLowerCase().includes(q)) ||
      u.id.toLowerCase().includes(q) ||
      u.phone.toLowerCase().includes(q)
    );

    if (!filtered.length) {
      tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding:32px; color:var(--text-subtle);">No matching user records found.</td></tr>';
      return;
    }

    filtered.forEach(u => {
      const tr = document.createElement('tr');
      tr.style.cursor = 'pointer';
      const active = u.status === 'active';
      const isPending = u.status === 'pending_verification';
      const statusBadgeClass = active ? 'badge-ok' : (isPending ? 'badge-pending' : 'badge-failed');
      tr.innerHTML =
        '<td style="font-weight:600; color:var(--text-primary);">' + esc(u.name || u.id) + '</td>' +
        '<td>' + esc(u.business || '—') + '</td>' +
        '<td class="num">' + esc(maskPhone(u.phone)) + '</td>' +
        '<td>' + esc(u.email || '—') + '</td>' +
        '<td><span class="badge badge-ok">' + esc(u.plan || 'Free') + '</span></td>' +
        '<td class="num">' + Number(u.credits || 0) + '</td>' +
        '<td class="text-subtle">' + esc((u.createdAt || '').slice(0, 10) || 'Today') + '</td>' +
        '<td><span class="badge ' + statusBadgeClass + '"><span class="dot"></span>' + esc(u.status) + '</span></td>';

      tr.addEventListener('click', () => openUserDrawer(u.id));
      tbody.appendChild(tr);
    });
  }

  renderTableRows();

  $('#user-search').addEventListener('input', (e) => renderTableRows(e.target.value));
  $('#close-drawer').addEventListener('click', () => $('#user-drawer-backdrop').classList.add('hidden'));
}

async function openUserDrawer(userId) {
  const backdrop = $('#user-drawer-backdrop');
  const body = $('#drawer-body');
  backdrop.classList.remove('hidden');
  body.innerHTML = '<p class="t-body text-subtle">Loading identity profile...</p>';

  try {
    const detail = await api('/users/' + encodeURIComponent(userId));
    const u = detail.user;
    const passes = detail.passes || [];
    const txs = detail.transactions || [];
    const isActive = u.status === 'active';

    const isPending = u.status === 'pending_verification';
    const statusBadgeClass = isActive ? 'badge-ok' : (isPending ? 'badge-pending' : 'badge-failed');

    body.innerHTML =
      '<div class="card" style="padding:16px; display:flex; flex-direction:column; gap:8px;">' +
        '<p class="t-micro">Identity Profile</p>' +
        '<p class="t-body"><strong>ID:</strong> <code class="num">' + esc(u.id) + '</code></p>' +
        '<p class="t-body"><strong>Name:</strong> ' + esc(u.name || 'N/A') + '</p>' +
        '<p class="t-body"><strong>Business:</strong> ' + esc(u.business || 'N/A') + '</p>' +
        '<p class="t-body"><strong>Phone (Full):</strong> <code class="num">' + esc(u.phone) + '</code></p>' +
        '<p class="t-body"><strong>Email:</strong> ' + esc(u.email || 'N/A') + '</p>' +
        '<p class="t-body"><strong>Plan:</strong> <span class="badge badge-ok">' + esc(detail.activePlan || u.plan) + '</span></p>' +
        '<p class="t-body"><strong>Status:</strong> <span class="badge ' + statusBadgeClass + '">' + esc(u.status) + '</span></p>' +
        (u.verifiedAt ? '<p class="t-body"><strong>Verified At:</strong> ' + esc(u.verifiedAt) + '</p>' : '') +
        (detail.otp ? '<p class="t-body" style="background:var(--bg-canvas); padding:8px; border-radius:var(--radius-sm);"><strong>Active OTP (Sandbox):</strong> <code class="num" style="font-size:16px; font-weight:bold; color:var(--brand-blue);">' + esc(detail.otp) + '</code></p>' : '') +
        '<div style="margin-top:8px;">' +
          '<button id="toggle-user-btn" class="btn ' + (isActive ? 'btn-danger' : 'btn-success') + ' w-full">' +
            (isActive ? 'Disable User Account' : 'Reactivate User Account') +
          '</button>' +
        '</div>' +
      '</div>' +

      '<div class="card" style="padding:16px;">' +
        '<h4 class="t-section" style="margin-bottom:8px;">Granted Passes & Credits (' + passes.length + ')</h4>' +
        (passes.length ? passes.map(p =>
          '<div style="padding:8px 12px; margin-bottom:6px; background-color:var(--bg-canvas); border-radius:var(--radius-sm); display:flex; justify-content:space-between; align-items:center;">' +
            '<span class="t-body">Feature: <code class="num">' + esc(p.featureId) + '</code></span>' +
            '<span class="badge badge-ok">Active</span>' +
          '</div>'
        ).join('') : '<p class="t-label text-subtle">No passes granted yet.</p>') +
      '</div>' +

      '<div class="card" style="padding:16px;">' +
        '<h4 class="t-section" style="margin-bottom:8px;">Full Payment History (' + txs.length + ')</h4>' +
        (txs.length ? txs.map(t =>
          '<div style="padding:8px 12px; margin-bottom:6px; background-color:var(--bg-canvas); border-radius:var(--radius-sm);">' +
            '<div style="display:flex; justify-content:space-between;" class="t-body font-mono">' +
              '<span>' + esc(t.id) + '</span>' +
              '<span style="font-weight:700;">' + esc(t.currency) + ' ' + Number(t.amount).toFixed(2) + '</span>' +
            '</div>' +
            '<div style="display:flex; justify-content:space-between; margin-top:4px;" class="t-label text-subtle">' +
              '<span>Offer: ' + esc(t.offerId) + '</span>' +
              '<span class="badge ' + (t.status === 'paid' ? 'badge-ok' : (t.status === 'failed' ? 'badge-failed' : 'badge-pending')) + '">' + esc(t.status) + '</span>' +
            '</div>' +
          '</div>'
        ).join('') : '<p class="t-label text-subtle">No payment history.</p>') +
      '</div>';

    const toggleBtn = $('#toggle-user-btn');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', async () => {
        toggleBtn.disabled = true;
        try {
          await api('/users/' + encodeURIComponent(u.id) + '/status', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ status: isActive ? 'disabled' : 'active' })
          });
          toast(isActive ? 'User disabled' : 'User reactivated');
          openUserDrawer(u.id);
          renderUsers(document.querySelector('.view-container'));
        } catch (err) {
          toast('Action failed: ' + err.message, 'err');
          toggleBtn.disabled = false;
        }
      });
    }

  } catch (e) {
    body.innerHTML = '<p class="t-body" style="color:var(--status-failed-fg)">Failed to load details: ' + esc(e.message) + '</p>';
  }
}

/* ================= 3. PAYMENTS ================= */
async function renderPayments(el) {
  let txs = [];
  try {
    txs = await api('/transactions');
  } catch (e) {
    el.innerHTML = '<div class="card"><p class="t-body" style="color:var(--status-failed-fg)">Failed to load payments: ' + esc(e.message) + '</p></div>';
    return;
  }

  el.innerHTML =
    '<div class="card" style="margin-bottom:16px; padding:16px 24px;">' +
      '<h2 class="t-title">Payments & Settlement Reconciliation</h2>' +
      '<p class="t-label text-subtle">' + txs.length + ' total transaction records · Wire actions directly to M-Pesa endpoints</p>' +
    '</div>' +

    '<div class="card" style="padding:0; overflow:hidden;">' +
      '<div class="table-wrapper">' +
        '<table class="tbl">' +
          '<thead>' +
            '<tr>' +
              '<th>Tx ID / Checkout ID</th>' +
              '<th>User ID</th>' +
              '<th>Agent / Offer</th>' +
              '<th>Amount</th>' +
              '<th>Status</th>' +
              '<th style="text-align:right;">Actions</th>' +
            '</tr>' +
          '</thead>' +
          '<tbody id="txs-tbody"></tbody>' +
        '</table>' +
      '</div>' +
    '</div>';

  const tbody = $('#txs-tbody');
  if (!txs.length) {
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; padding:32px; color:var(--text-subtle);">No payments recorded yet.</td></tr>';
    return;
  }

  txs.forEach(t => {
    const tr = document.createElement('tr');
    const isPaid = t.status === 'paid';
    const isFailed = t.status === 'failed';
    const badgeClass = isPaid ? 'badge-ok' : (isFailed ? 'badge-failed' : 'badge-pending');

    tr.innerHTML =
      '<td><code class="num" style="font-size:12px;">' + esc(t.id) + '</code></td>' +
      '<td>' + esc(t.userId || 'guest') + '</td>' +
      '<td>' + esc((t.agentSlug ? t.agentSlug + ' / ' : '') + t.offerId) + '</td>' +
      '<td class="num" style="font-weight:600; color:var(--text-primary);">' + esc(t.currency || 'KES') + ' ' + Number(t.amount).toFixed(2) + '</td>' +
      '<td><span class="badge ' + badgeClass + '"><span class="dot"></span>' + esc(t.status) + '</span></td>' +
      '<td>' +
        '<div style="display:flex; justify-content:flex-end; gap:8px;">' +
          (!isPaid ? '<button class="btn btn-success confirm-tx-btn" data-id="' + esc(t.id) + '">Confirm</button>' : '') +
          (!isFailed ? '<button class="btn btn-danger fail-tx-btn" data-id="' + esc(t.id) + '">Fail</button>' : '') +
        '</div>' +
      '</td>';

    const confirmBtn = tr.querySelector('.confirm-tx-btn');
    if (confirmBtn) {
      confirmBtn.addEventListener('click', async (e) => {
        const btn = e.currentTarget;
        btn.disabled = true;
        try {
          await api('/transactions/' + encodeURIComponent(btn.dataset.id) + '/confirm', { method: 'POST' });
          toast('Transaction confirmed & pass granted');
          renderPayments(el);
        } catch (err) {
          toast('Confirm failed: ' + err.message, 'err');
          btn.disabled = false;
        }
      });
    }

    const failBtn = tr.querySelector('.fail-tx-btn');
    if (failBtn) {
      failBtn.addEventListener('click', async (e) => {
        const btn = e.currentTarget;
        btn.disabled = true;
        try {
          await api('/transactions/' + encodeURIComponent(btn.dataset.id) + '/fail', { method: 'POST' });
          toast('Transaction marked failed');
          renderPayments(el);
        } catch (err) {
          toast('Fail action failed: ' + err.message, 'err');
          btn.disabled = false;
        }
      });
    }

    tbody.appendChild(tr);
  });
}

/* ================= 4. STORES ================= */
async function renderStores(el) {
  let agents = [];
  try {
    agents = await api('/agents');
  } catch (e) {
    el.innerHTML = '<div class="card"><p class="t-body" style="color:var(--status-failed-fg)">Failed to load agent stores: ' + esc(e.message) + '</p></div>';
    return;
  }

  el.innerHTML =
    '<div class="card" style="margin-bottom:16px; padding:16px 24px;">' +
      '<h2 class="t-title">Agent Storefront Directories</h2>' +
      '<p class="t-label text-subtle">' + agents.length + ' registered agent storefronts · Direct live links</p>' +
    '</div>' +

    '<div class="card" style="padding:0; overflow:hidden;">' +
      '<div class="table-wrapper">' +
        '<table class="tbl">' +
          '<thead>' +
            '<tr>' +
              '<th>Agent Name</th>' +
              '<th>Store Slug</th>' +
              '<th>Owner Phone</th>' +
              '<th>Status</th>' +
              '<th style="text-align:right;">Live Store Link</th>' +
            '</tr>' +
          '</thead>' +
          '<tbody id="stores-tbody"></tbody>' +
        '</table>' +
      '</div>' +
    '</div>';

  const tbody = $('#stores-tbody');
  if (!agents.length) {
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding:32px; color:var(--text-subtle);">No agent stores registered yet.</td></tr>';
    return;
  }

  agents.forEach(a => {
    const tr = document.createElement('tr');
    tr.innerHTML =
      '<td style="font-weight:600; color:var(--text-primary);">' + esc(a.name) + '</td>' +
      '<td><code class="num">' + esc(a.slug) + '</code></td>' +
      '<td class="num">' + esc(a.ownerPhone || '—') + '</td>' +
      '<td><span class="badge badge-ok"><span class="dot"></span>Active</span></td>' +
      '<td>' +
        '<div style="display:flex; justify-content:flex-end;">' +
          '<a class="btn btn-secondary" href="/store/' + encodeURIComponent(a.slug) + '" target="_blank" rel="noopener">' +
            'Open Store ↗' +
          '</a>' +
        '</div>' +
      '</td>';
    tbody.appendChild(tr);
  });
}

/* ================= Boot ================= */
function boot() {
  initLogin();
  if (!KEY) { showLogin(); return; }
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');

  window.addEventListener('hashchange', () => {
    navigate();
  });

  navigate();
}

document.addEventListener('DOMContentLoaded', boot);
