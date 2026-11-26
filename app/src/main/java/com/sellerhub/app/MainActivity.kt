package com.sellerhub.app

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val base = "https://supplier.meesho.com/panel/v3/new/root/"
    private val defaultRoutes = linkedMapOf(
        "Dashboard" to "DASHBOARD",
        "Pending Orders" to base + "orders",
        "Labels / Ready" to base + "orders",
        "Returns & OTP" to base + "returns",
        "Stock" to base + "inventory",
        "Payments" to base + "payments",
        "Home" to "https://supplier.meesho.com/"
    )
    private val hosts = listOf(
        "https://supplier.meesho.com",
        "https://meesho.com",
        "https://www.meesho.com"
    )

    // Realistic Mobile Chrome User-Agent to prevent Akamai / edgesuite.net WebView blocks
    private val mobileChromeUA =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"

    private lateinit var web: WebView
    private lateinit var dashScroll: ScrollView
    private lateinit var dashContainer: LinearLayout
    private lateinit var shopChipBar: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLine: TextView

    private val prefs by lazy { getSharedPreferences("hub_v3", Context.MODE_PRIVATE) }
    private var shops = mutableListOf<String>()
    private var curShop = ""
    private var isDashboardMode = true
    private var isSyncAllRunning = false
    private var syncQueue = mutableListOf<String>()
    private var syncIndex = 0
    private var currentRouteUrl = base + "orders"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
        }

        // Top Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#4A154B"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "Seller Hub Multi-Shop"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val addShopBtn = makePillBtn("+ Shop", "#FFFFFF", "#4A154B") { askNewShop() }
        val syncAllBtn = makePillBtn("Sync All", "#F59E0B", "#1F2937") { startSyncAllShops() }
        header.addView(titleTv)
        header.addView(syncAllBtn)
        header.addView(space(6))
        header.addView(addShopBtn)

        // Shop Switcher Chips Bar
        shopChipBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val shopScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            isHorizontalScrollBarEnabled = false
            addView(shopChipBar)
        }

        // Navigation / Action Tabs Bar
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        defaultRoutes.forEach { (label, target) ->
            val btn = makePillBtn(label, "#EDE9FE", "#4A154B") {
                if (target == "DASHBOARD") {
                    showUnifiedDashboard()
                } else {
                    if (curShop.isEmpty()) {
                        askNewShop()
                    } else {
                        openShopUrl(curShop, ensureHttps(getCustomRoute(label, target)))
                    }
                }
            }
            navBar.addView(btn)
            navBar.addView(space(6))
        }
        val navScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            isHorizontalScrollBarEnabled = false
            addView(navBar)
        }

        // Browser Mini Toolbar
        val toolBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E5E7EB"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLine = TextView(this).apply {
            text = "Ready"
            textSize = 12f
            setTextColor(Color.parseColor("#374151"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val backBtn = makeMiniBtn("◀") { if (web.canGoBack()) web.goBack() }
        val reloadBtn = makeMiniBtn("↻") {
            if (!isDashboardMode) {
                val u = ensureHttps(web.url ?: currentRouteUrl)
                web.loadUrl(u)
            } else {
                renderDashboard()
            }
        }
        val captureBtn = makeMiniBtn("Save Stats") { captureCurrentPageStats() }
        val urlFixBtn = makeMiniBtn("Fix URL") { askCustomUrlDialog() }
        toolBar.addView(statusLine)
        toolBar.addView(backBtn)
        toolBar.addView(space(4))
        toolBar.addView(reloadBtn)
        toolBar.addView(space(4))
        toolBar.addView(captureBtn)
        toolBar.addView(space(4))
        toolBar.addView(urlFixBtn)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(3))
        }

        // Unified Dashboard Container
        dashContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        dashScroll = ScrollView(this).apply {
            addView(dashContainer)
        }

        // WebView Setup with Anti-Block Headers & HTTPS Enforcement
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        web = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.userAgentString = mobileChromeUA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cm.setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(BlobBridge(), "AndroidBlob")

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    progressBar.progress = newProgress
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val rawUrl = request?.url?.toString() ?: return false
                    if (rawUrl.startsWith("http://")) {
                        val secureUrl = ensureHttps(rawUrl)
                        view?.loadUrl(secureUrl)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val safeUrl = url ?: ""
                    if (curShop.isNotEmpty() && safeUrl.startsWith("https://")) {
                        saveCookies(curShop)
                        saveLocalStorage(curShop)
                        extractPageMetrics(curShop, safeUrl)
                    }
                    if (isSyncAllRunning) {
                        view?.postDelayed({ advanceSyncQueue() }, 2800)
                    }
                }
            }

            setDownloadListener { url, ua, cd, mime, _ ->
                handleDownload(ensureHttps(url), ua.ifEmpty { mobileChromeUA }, cd, mime)
            }
        }

        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(shopScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(navScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(toolBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(progressBar)
        root.addView(dashScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)

        loadSavedShops()
        rebuildShopChips()
        if (shops.isEmpty()) {
            renderDashboard()
            askNewShop()
        } else {
            showUnifiedDashboard()
        }
    }

    private fun ensureHttps(url: String): String {
        return when {
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            url.startsWith("https://") || url.startsWith("blob:") || url.startsWith("about:") -> url
            else -> "https://$url"
        }
    }

    private fun loadSavedShops() {
        val arr = JSONArray(prefs.getString("shops", "[]"))
        shops = MutableList(arr.length()) { arr.getString(it) }
        val saved = prefs.getString("cur", "") ?: ""
        curShop = if (saved in shops) saved else shops.firstOrNull() ?: ""
    }

    private fun saveShopsList() {
        prefs.edit().putString("shops", JSONArray(shops).toString()).putString("cur", curShop).apply()
    }

    private fun rebuildShopChips() {
        shopChipBar.removeAllViews()
        val dashChip = makePillBtn(
            "All Shops (${shops.size})",
            if (isDashboardMode) "#4A154B" else "#E5E7EB",
            if (isDashboardMode) "#FFFFFF" else "#111827"
        ) {
            showUnifiedDashboard()
        }
        shopChipBar.addView(dashChip)
        shopChipBar.addView(space(6))

        shops.forEach { shop ->
            val selected = (!isDashboardMode && shop == curShop)
            val chip = makePillBtn(
                shop,
                if (selected) "#10B981" else "#F3F4F6",
                if (selected) "#FFFFFF" else "#1F2937"
            ) {
                openShopUrl(shop, ensureHttps(getCustomRoute("Pending Orders", base + "orders")))
            }
            chip.setOnLongClickListener {
                showShopOptions(shop)
                true
            }
            shopChipBar.addView(chip)
            shopChipBar.addView(space(6))
        }
    }

    private fun showUnifiedDashboard() {
        isDashboardMode = true
        isSyncAllRunning = false
        dashScroll.visibility = View.VISIBLE
        web.visibility = View.GONE
        statusLine.text = "Unified Dashboard - ${shops.size} Shops"
        rebuildShopChips()
        renderDashboard()
    }

    private fun renderDashboard() {
        dashContainer.removeAllViews()

        val summaryCard = makeCard("#4A154B")
        val sTitle = TextView(this).apply {
            text = "All Meesho Accounts Overview"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        val sSub = TextView(this).apply {
            text = "Har shop me ek baar login karein. Uske baad 'Sync All' dabayein ya neeche har shop ke quick buttons se Orders Accept, Label Download aur Returns/OTP dekhein."
            setTextColor(Color.parseColor("#E9D5FF"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        val syncBtn = makePillBtn("Sync All Accounts Now", "#F59E0B", "#111827") {
            startSyncAllShops()
        }
        summaryCard.addView(sTitle)
        summaryCard.addView(sSub)
        summaryCard.addView(syncBtn)
        dashContainer.addView(summaryCard)
        dashContainer.addView(spaceV(12))

        if (shops.isEmpty()) {
            val emptyCard = makeCard("#FFFFFF")
            val tv = TextView(this).apply {
                text = "Abhi koi Meesho shop add nahi hai. Upar '+ Shop' dabakar apna pehla account add karein."
                setTextColor(Color.parseColor("#374151"))
                textSize = 14f
            }
            emptyCard.addView(tv)
            dashContainer.addView(emptyCard)
            return
        }

        shops.forEach { shop ->
            val card = makeCard("#FFFFFF")

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameTv = TextView(this).apply {
                text = shop
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#111827"))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val manageBtn = makeMiniBtn("Options") { showShopOptions(shop) }
            topRow.addView(nameTv)
            topRow.addView(manageBtn)

            val statsJson = JSONObject(prefs.getString("stats_$shop", "{}") ?: "{}")
            val pendingInfo = statsJson.optString("pending", "Not synced yet")
            val returnInfo = statsJson.optString("returns", "Tap Returns & OTP")
            val lastSync = statsJson.optString("updated", "Never")
            val loginState = if (prefs.getString("ck_$shop", null).isNullOrEmpty()) "Login Required" else "Session Saved"

            val infoTv = TextView(this).apply {
                text = "Status: $loginState  |  Last Sync: $lastSync
Orders Summary: $pendingInfo
Returns / OTP Info: $returnInfo"
                textSize = 12.5f
                setTextColor(Color.parseColor("#4B5563"))
                setPadding(0, dp(6), 0, dp(10))
            }

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnOrders = makePillBtn("Orders / Accept", "#DBEAFE", "#1E40AF") {
                openShopUrl(shop, ensureHttps(getCustomRoute("Pending Orders", base + "orders")))
            }
            val btnLabels = makePillBtn("Download Labels", "#D1FAE5", "#065F46") {
                openShopUrl(shop, ensureHttps(getCustomRoute("Labels / Ready", base + "orders")))
            }
            row1.addView(btnOrders)
            row1.addView(space(8))
            row1.addView(btnLabels)

            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            val btnReturns = makePillBtn("Returns & OTP", "#FEE2E2", "#991B1B") {
                openShopUrl(shop, ensureHttps(getCustomRoute("Returns & OTP", base + "returns")))
            }
            val btnPayments = makePillBtn("Payments", "#FEF3C7", "#92400E") {
                openShopUrl(shop, ensureHttps(getCustomRoute("Payments", base + "payments")))
            }
            val btnLogin = makePillBtn("Login / Home", "#F3F4F6", "#1F2937") {
                openShopUrl(shop, "https://supplier.meesho.com/")
            }
            row2.addView(btnReturns)
            row2.addView(space(6))
            row2.addView(btnPayments)
            row2.addView(space(6))
            row2.addView(btnLogin)

            card.addView(topRow)
            card.addView(infoTv)
            card.addView(row1)
            card.addView(row2)

            dashContainer.addView(card)
            dashContainer.addView(spaceV(10))
        }
    }

    private fun openShopUrl(shop: String, rawUrl: String) {
        val url = ensureHttps(rawUrl)
        if (curShop.isNotEmpty() && curShop in shops && !isDashboardMode) {
            saveCookies(curShop)
            saveLocalStorage(curShop)
        }
        curShop = shop
        currentRouteUrl = url
        saveShopsList()
        isDashboardMode = false
        dashScroll.visibility = View.GONE
        web.visibility = View.VISIBLE
        statusLine.text = "$shop - Loading..."
        rebuildShopChips()

        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            restoreCookies(shop)
            web.loadUrl("about:blank")
            web.postDelayed({
                restoreLocalStorage(shop)
                web.loadUrl(url)
                statusLine.text = "$shop - Active"
            }, 200)
        }
    }

    private fun startSyncAllShops() {
        if (shops.isEmpty()) {
            toast("Pehle kam se kam ek shop add karein")
            return
        }
        syncQueue = shops.toMutableList()
        syncIndex = 0
        isSyncAllRunning = true
        toast("Sabhi ${shops.size} accounts sync ho rahe hain...")
        openShopUrl(syncQueue[0], ensureHttps(getCustomRoute("Pending Orders", base + "orders")))
    }

    private fun advanceSyncQueue() {
        if (!isSyncAllRunning) return
        syncIndex++
        if (syncIndex < syncQueue.size) {
            openShopUrl(syncQueue[syncIndex], ensureHttps(getCustomRoute("Pending Orders", base + "orders")))
        } else {
            isSyncAllRunning = false
            toast("Sabhi accounts sync ho gaye!")
            showUnifiedDashboard()
        }
    }

    private fun extractPageMetrics(shop: String, url: String) {
        val js = "(function() { try { var txt = document.body ? document.body.innerText : ''; if (/Access Denied/i.test(txt)) { return JSON.stringify({ orders: 'Access Denied - Tap Login/Home', returns: 'Session refresh needed' }); } var lines = txt.split('
').map(function(s){return s.trim();}).filter(function(s){return s.length > 0;}); var orderHits = lines.filter(function(l){return /pending|ready to ship|on hold|shipped|accept|label/i.test(l);}).slice(0, 3).join(' | '); var retHits = lines.filter(function(l){return /return|rto|otp|pickup|claim/i.test(l);}).slice(0, 3).join(' | '); return JSON.stringify({ title: document.title || '', orders: orderHits || 'Opened panel', returns: retHits || 'Check Returns tab' }); } catch(e) { return '{}'; } })();"
        web.evaluateJavascript(js) { raw ->
            try {
                val cleaned = raw?.trim('"')?.replace("\"", """)?.replace("\\", "\") ?: "{}"
                val obj = JSONObject(cleaned)
                val now = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
                val existing = JSONObject(prefs.getString("stats_$shop", "{}") ?: "{}")
                if (url.contains("return", true)) {
                    existing.put("returns", obj.optString("returns", "Checked"))
                } else {
                    existing.put("pending", obj.optString("orders", "Checked"))
                    if (obj.optString("returns", "").isNotEmpty()) {
                        existing.put("returns", obj.optString("returns"))
                    }
                }
                existing.put("updated", now)
                prefs.edit().putString("stats_$shop", existing.toString()).apply()
            } catch (_: Exception) {
            }
        }
    }

    private fun captureCurrentPageStats() {
        if (curShop.isEmpty() || isDashboardMode) {
            toast("Pehle kisi shop ka page kholein")
            return
        }
        extractPageMetrics(curShop, web.url ?: "")
        toast("$curShop ka current status Dashboard me save ho gaya")
    }

    private fun saveCookies(shop: String) {
        val o = JSONObject()
        val cm = CookieManager.getInstance()
        hosts.forEach { h -> cm.getCookie(h)?.let { o.put(h, it) } }
        prefs.edit().putString("ck_$shop", o.toString()).apply()
    }

    private fun restoreCookies(shop: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        prefs.getString("ck_$shop", null)?.let {
            val o = JSONObject(it)
            o.keys().forEach { h ->
                o.getString(h).split("; ").forEach { c -> cm.setCookie(h, c) }
            }
        }
        cm.flush()
    }

    private fun saveLocalStorage(shop: String) {
        val js = "(function() { try { var data = {}; for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); data[k] = localStorage.getItem(k); } return JSON.stringify(data); } catch(e) { return '{}'; } })();"
        web.evaluateJavascript(js) { raw ->
            if (raw != null && raw != "null") {
                prefs.edit().putString("ls_$shop", raw).apply()
            }
        }
    }

    private fun restoreLocalStorage(shop: String) {
        val raw = prefs.getString("ls_$shop", null) ?: return
        val js = "(function() { try { localStorage.clear(); var data = JSON.parse($raw); Object.keys(data).forEach(function(k) { localStorage.setItem(k, data[k]); }); } catch(e) {} })();"
        web.evaluateJavascript(js, null)
    }

    private fun resetShopSession(shop: String) {
        prefs.edit().remove("ck_$shop").remove("ls_$shop").remove("stats_$shop").apply()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            toast("$shop ka session clear ho gaya, ab dobara login karein")
            openShopUrl(shop, "https://supplier.meesho.com/")
        }
    }

    private fun askNewShop() {
        val et = EditText(this).apply {
            hint = "Shop naam likhein (jaise: VYRA Store, Shop 2)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Naya Meesho Account Add Karein")
            .setView(et)
            .setPositiveButton("Add & Login") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty() || name in shops) {
                    toast("Naam khaali hai ya pehle se maujood hai")
                } else {
                    shops.add(name)
                    curShop = name
                    saveShopsList()
                    rebuildShopChips()
                    openShopUrl(name, "https://supplier.meesho.com/")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShopOptions(shop: String) {
        val opts = arrayOf(
            "Open Orders / Accept",
            "Open Returns & OTP",
            "Open Login / Home",
            "Reset Session (Fix Access Denied)",
            "Rename Shop",
            "Delete Shop"
        )
        AlertDialog.Builder(this)
            .setTitle(shop)
            .setItems(opts) { _, idx ->
                when (idx) {
                    0 -> openShopUrl(shop, ensureHttps(getCustomRoute("Pending Orders", base + "orders")))
                    1 -> openShopUrl(shop, ensureHttps(getCustomRoute("Returns & OTP", base + "returns")))
                    2 -> openShopUrl(shop, "https://supplier.meesho.com/")
                    3 -> resetShopSession(shop)
                    4 -> renameShopDialog(shop)
                    5 -> {
                        shops.remove(shop)
                        prefs.edit().remove("ck_$shop").remove("ls_$shop").remove("stats_$shop").apply()
                        if (curShop == shop) curShop = shops.firstOrNull() ?: ""
                        saveShopsList()
                        showUnifiedDashboard()
                    }
                }
            }
            .show()
    }

    private fun renameShopDialog(oldName: String) {
        val et = EditText(this).apply { setText(oldName) }
        AlertDialog.Builder(this)
            .setTitle("Shop Rename Karein")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotEmpty() && newName !in shops) {
                    val idx = shops.indexOf(oldName)
                    if (idx >= 0) shops[idx] = newName
                    prefs.edit()
                        .putString("ck_$newName", prefs.getString("ck_$oldName", null))
                        .putString("ls_$newName", prefs.getString("ls_$oldName", null))
                        .putString("stats_$newName", prefs.getString("stats_$oldName", null))
                        .remove("ck_$oldName").remove("ls_$oldName").remove("stats_$oldName")
                        .apply()
                    if (curShop == oldName) curShop = newName
                    saveShopsList()
                    showUnifiedDashboard()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCustomRoute(key: String, fallback: String): String {
        return ensureHttps(prefs.getString("route_$key", fallback) ?: fallback)
    }

    private fun askCustomUrlDialog() {
        val currentUrl = ensureHttps(web.url ?: "")
        if (currentUrl.isEmpty() || currentUrl == "https://") {
            toast("Pehle browser me koi page kholein")
            return
        }
        val targets = arrayOf("Pending Orders", "Labels / Ready", "Returns & OTP", "Stock", "Payments")
        AlertDialog.Builder(this)
            .setTitle("Is Page Ko Kis Button Se Jodein?")
            .setItems(targets) { _, i ->
                prefs.edit().putString("route_${targets[i]}", currentUrl).apply()
                toast("'${targets[i]}' ab is page par khulega")
            }
            .show()
    }

    private fun handleDownload(url: String, ua: String, cd: String, mime: String) {
        if (url.startsWith("blob:")) {
            val js = "(function() { var xhr = new XMLHttpRequest(); xhr.open('GET', '$url', true); xhr.responseType = 'blob'; xhr.onload = function() { if (this.status == 200) { var reader = new FileReader(); reader.readAsDataURL(this.response); reader.onloadend = function() { AndroidBlob.saveBase64(reader.result, '$mime'); }; } }; xhr.send(); })();"
            web.evaluateJavascript(js, null)
            return
        }
        try {
            val secureUrl = ensureHttps(url)
            val fileName = "${curShop.ifEmpty { "Meesho" }}_${URLUtil.guessFileName(secureUrl, cd, mime)}"
            val r = DownloadManager.Request(Uri.parse(secureUrl)).apply {
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(secureUrl) ?: "")
                addRequestHeader("User-Agent", ua)
                setMimeType(mime)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            toast("Download shuru: $fileName (Downloads folder me dekhein)")
        } catch (e: Exception) {
            toast("Download error: ${e.message}")
        }
    }

    inner class BlobBridge {
        @JavascriptInterface
        fun saveBase64(base64Data: String, mime: String) {
            try {
                val pureBase64 = base64Data.substringAfter(",")
                val bytes = Base64.decode(pureBase64, Base64.DEFAULT)
                val ext = if (mime.contains("pdf", true)) "pdf" else "bin"
                val fileName = "${curShop.ifEmpty { "Label" }}_${System.currentTimeMillis()}.$ext"
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(bytes) }
                runOnUiThread {
                    toast("Label save ho gaya: Downloads/$fileName")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("Label save nahi ho paya: ${e.message}")
                }
            }
        }
    }

    private fun makeCard(bgHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(2).toFloat()
        }
    }

    private fun makePillBtn(label: String, bgHex: String, textHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.parseColor(textHex))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minHeight = 0
            minimumHeight = 0
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeMiniBtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11.5f
            setTextColor(Color.parseColor("#111827"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun space(wDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(wDp), 1) }
    private fun spaceV(hDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(hDp)) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onPause() {
        super.onPause()
        if (curShop.isNotEmpty() && !isDashboardMode) {
            saveCookies(curShop)
            saveLocalStorage(curShop)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!isDashboardMode && web.canGoBack()) {
            web.goBack()
        } else if (!isDashboardMode) {
            showUnifiedDashboard()
        } else {
            super.onBackPressed()
        }
    }
}

