package relay

fun renderStorefront(slug: String, offers: List<Offer>, boughtOfferId: String? = null, txId: String? = null): String {
    val titleEsc = htmlEsc(slug)
    val offersHtml = if (offers.isEmpty()) {
        """<div class="card p-6 text-center text-slate-400">No offers available at this time.</div>"""
    } else {
        offers.joinToString("\n") { offer ->
            val offerIdEsc = htmlEsc(offer.id)
            val offerTitleEsc = htmlEsc(offer.title)
            val isBought = boughtOfferId == offer.id
            val confirmationHtml = if (isBought && !txId.isNullOrBlank()) {
                """<div class="mt-3 p-3 bg-emerald-950/80 border border-emerald-800 text-emerald-300 text-sm font-semibold rounded-lg flex items-center justify-between">
                    <span>✓ Transaction pending confirmation</span>
                    <code class="text-xs text-emerald-400">${htmlEsc(txId)}</code>
                </div>"""
            } else {
                ""
            }

            """
            <div class="card p-6 border border-slate-800 bg-slate-900 rounded-xl shadow-lg flex flex-col justify-between">
                <div>
                    <h3 class="text-lg font-bold text-slate-100">$offerTitleEsc</h3>
                    <div class="mt-2 text-2xl font-extrabold text-[#2563EB]">₦${offer.price}</div>
                </div>
                <div class="mt-6">
                    <form method="POST" action="/store/$titleEsc/buy">
                        <input type="hidden" name="offerId" value="$offerIdEsc">
                        <button type="submit" class="w-full bg-[#2563EB] hover:bg-blue-600 active:bg-blue-700 text-white font-semibold py-2.5 px-4 rounded-lg shadow-md transition-colors">
                            ${if (isBought) "Buy Again" else "Buy"}
                        </button>
                    </form>
                    $confirmationHtml
                </div>
            </div>
            """.trimIndent()
        }
    }

    return """
    <!doctype html>
    <html lang="en" class="h-full bg-slate-950 text-slate-100">
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$titleEsc · Agent Storefront</title>
        <script src="https://cdn.tailwindcss.com"></script>
        <script>
            tailwind.config = { theme: { extend: { colors: { brand: { DEFAULT: '#2563EB', hover: '#1D4ED8' } } } } };
        </script>
        <style>
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
            .card { background-color: #0f172a; border: 1px solid #1e293b; }
        </style>
    </head>
    <body class="min-h-full flex flex-col bg-slate-950 antialiased py-8 px-4 sm:px-6">
        <div class="max-w-md w-full mx-auto space-y-6">
            <header class="text-center space-y-2">
                <div class="inline-flex items-center justify-center h-12 w-12 rounded-xl bg-[#2563EB] text-white font-black text-xl shadow-lg shadow-blue-600/30">
                    B
                </div>
                <h1 class="text-2xl font-extrabold text-white tracking-tight">$titleEsc</h1>
                <p class="text-xs font-semibold text-slate-400 uppercase tracking-wider">Official Partner Storefront</p>
            </header>

            <main class="space-y-4">
                $offersHtml
            </main>

            <footer class="pt-6 border-t border-slate-900 text-center text-xs text-slate-500">
                Powered by Bingwa Score Relay
            </footer>
        </div>
    </body>
    </html>
    """.trimIndent()
}

private fun htmlEsc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
