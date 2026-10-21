package relay

/** Server-rendered admin dashboard (no frontend framework). Escapes all dynamic values. */
object AdminHtml {
    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    suspend fun render(store: Store): String {
        val users = store.users()
        val offers = store.offers()
        val messages = AdminState.messageLog.toList()
        val audits = AdminState.auditLog.toList()
        val metrics = AdminState.routeCounts.entries.sortedByDescending { it.value.get() }
            .joinToString("") { "<tr><td>${esc(it.key)}</td><td>${it.value.get()}</td></tr>" }
        return buildString {
            append(
"""<!doctype html><html><head><meta charset="utf-8"><title>Bingwa Relay Admin</title>
<style>body{font-family:sans-serif;margin:2rem;background:#f6f7f9;color:#222}
section{background:#fff;border:1px solid #ddd;border-radius:8px;padding:1rem 1.5rem;margin-bottom:1.5rem}
table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:4px 8px;font-size:14px;text-align:left}
h1{font-size:22px}h2{font-size:16px;margin-top:0}textarea{width:100%;height:120px;font-family:monospace}
.badge{padding:2px 6px;border-radius:4px;font-size:12px}.on{background:#d8f5d8}.off{background:#f5d8d8}
button{padding:4px 10px;cursor:pointer}</style></head><body>
<h1>Bingwa Relay — Admin Console</h1>""")
            append("<section><h2>Status</h2><p>Uptime: <b>${AdminState.uptimeSeconds()}s</b> · Live WS connections: <b>${WsRegistry.count()}</b></p></section>")

            append("<section><h2>Users</h2><table><tr><th>Phone</th><th>Created</th><th>Status</th><th>Kill switch</th></tr>")
            users.forEach { u ->
                val cls = if (u.status == "active") "badge on" else "badge off"
                append("<tr><td>${esc(u.phone)}</td><td>${esc(u.createdAt)}</td><td><span class=\"$cls\">${esc(u.status)}</span></td>" +
                    "<td><button onclick=\"killUser('${esc(u.id)}')\">Disable</button> " +
                    "<button onclick=\"reactivateUser('${esc(u.id)}')\">Reactivate</button></td></tr>")
            }
            if (users.isEmpty()) append("<tr><td colspan=\"4\">No registered users yet.</td></tr>")
            append("</table><p><a href=\"/admin/api/export/users.csv\">Export users CSV</a></p></section>")

            append("<section><h2>Offers (verified)</h2><table><tr><th>ID</th><th>Title</th><th>Price</th><th>Verified</th><th>Action</th></tr>")
            offers.forEach { o ->
                append("<tr><td>${esc(o.id)}</td><td>${esc(o.title)}</td><td>${o.price}</td><td>${o.verified}</td>" +
                    "<td><button onclick=\"disableOffer('${esc(o.id)}')\">Disable</button></td></tr>")
            }
            append("</table><p>Create/disable offers via JSON API: <code>POST /admin/api/offers</code>, <code>POST /admin/api/offers/{id}/disable</code>.</p></section>")

            append("<section><h2>Feature flags (JSON)</h2><p>Current:</p><pre>${esc(AdminState.flagsJson)}</pre>" +
                "<p>Edit via <code>POST /admin/api/flags</code> with a JSON object body and header <code>X-Admin-Key</code>.</p></section>")

            append("<section><h2>Message log (last ${messages.size} of 200 via POST /api/messages/send)</h2>" +
                "<table><tr><th>ID</th><th>From</th><th>To</th><th>Body</th></tr>")
            messages.forEach { m ->
                append("<tr><td>${esc(m.id)}</td><td>${esc(m.from)}</td><td>${esc(m.to)}</td><td>${esc(m.body)}</td></tr>")
            }
            if (messages.isEmpty()) append("<tr><td colspan=\"4\">No messages yet.</td></tr>")
            append("</table></section>")

            append("<section><h2>Metrics (per-route counts)</h2><table><tr><th>Route</th><th>Hits</th></tr>")
            if (metrics.isEmpty()) append("<tr><td colspan=\"2\">No traffic yet.</td></tr>") else append(metrics)
            append("</table></section>")

            append("<section><h2>Audit log (admin actions)</h2><table><tr><th>When</th><th>Action</th><th>Detail</th></tr>")
            audits.forEach { a ->
                append("<tr><td>${esc(a.at)}</td><td>${esc(a.action)}</td><td>${esc(a.detail)}</td></tr>")
            }
            if (audits.isEmpty()) append("<tr><td colspan=\"3\">No admin actions yet.</td></tr>")
            append("</table></section>")

            append("""<script>
// Dashboard actions call the gated JSON API; ADMIN_KEY comes from the page URL (?key=...).
const KEY=new URLSearchParams(location.search).get('key')||'';
async function post(url,body){
  const r=await fetch(url,{method:'POST',headers:{'X-Admin-Key':KEY,'Content-Type':'application/json'},body:body?JSON.stringify(body):null});
  alert(r.ok?'Done':'Failed: '+r.status); if(r.ok) location.reload();
}
function killUser(id){post('/admin/api/users/'+encodeURIComponent(id)+'/status',{status:'disabled'})}
function reactivateUser(id){post('/admin/api/users/'+encodeURIComponent(id)+'/status',{status:'active'})}
function disableOffer(id){post('/admin/api/offers/'+encodeURIComponent(id)+'/disable')}
</script>""")
            append("</body></html>")
        }
    }
}
