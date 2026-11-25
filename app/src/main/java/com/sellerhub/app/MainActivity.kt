package com.sellerhub.app

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val base = "https://supplier.meesho.com/panel/v3/new/root/"
    private val pages = linkedMapOf(
        "Home" to "https://supplier.meesho.com",
        "Orders" to base + "orders",
        "Stock" to base + "inventory",
        "Returns" to base + "returns",
        "Payments" to base + "payments"
    )
    private val hosts = listOf("https://supplier.meesho.com", "https://meesho.com", "https://www.meesho.com")
    private lateinit var web: WebView
    private lateinit var shopBtn: Button
    private val prefs by lazy { getSharedPreferences("hub", Context.MODE_PRIVATE) }
    private var shops = mutableListOf<String>()
    private var cur = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shopBtn = Button(this).apply { isAllCaps = false; setOnClickListener { showShops() } }
        val bar = LinearLayout(this)
        pages.forEach { (n, u) ->
            bar.addView(Button(this).apply { text = n; isAllCaps = false; setOnClickListener { web.loadUrl(u) } })
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        root.addView(shopBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(HorizontalScrollView(this).apply { addView(bar) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        val a = JSONArray(prefs.getString("shops", "[]"))
        shops = MutableList(a.length()) { a.getString(it) }
        val saved = prefs.getString("cur", "") ?: ""
        if (shops.isEmpty()) askNewShop() else {
            cur = if (saved in shops) saved else shops[0]
            shopBtn.text = "Shop: $cur  ▾"
            web.loadUrl(pages["Orders"]!!)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun persist() = prefs.edit().putString("shops", JSONArray(shops).toString()).apply()

    private fun saveCookies(shop: String) {
        val o = JSONObject()
        hosts.forEach { h -> CookieManager.getInstance().getCookie(h)?.let { o.put(h, it) } }
        prefs.edit().putString("ck_$shop", o.toString()).apply()
    }

    private fun restoreCookies(shop: String) {
        val cm = CookieManager.getInstance()
        prefs.getString("ck_$shop", null)?.let {
            val o = JSONObject(it)
            o.keys().forEach { h -> o.getString(h).split("; ").forEach { c -> cm.setCookie(h, c) } }
        }
        cm.flush()
    }

    private fun switchTo(name: String) {
        if (cur.isNotEmpty() && cur in shops) saveCookies(cur)
        cur = name
        prefs.edit().putString("cur", name).apply()
        shopBtn.text = "Shop: $name  ▾"
        CookieManager.getInstance().removeAllCookies {
            restoreCookies(name)
            web.loadUrl(pages["Orders"]!!)
        }
    }

    private fun askNewShop() {
        val et = EditText(this).apply { hint = "Shop ka naam (jaise Shop 1)" }
        AlertDialog.Builder(this).setTitle("Naya Meesho shop").setView(et).setCancelable(shops.isNotEmpty())
            .setPositiveButton("Add") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isEmpty() || n in shops) {
                    toast("Naam khaali ya pehle se hai")
                    if (shops.isEmpty()) askNewShop()
                } else { shops.add(n); persist(); switchTo(n) }
            }
            .setNegativeButton("Cancel") { _, _ -> if (shops.isEmpty()) askNewShop() }
            .show()
    }

    private fun showShops() {
        val items = (shops + listOf("+ Naya shop add karo", "Ye shop hatao")).toTypedArray()
        AlertDialog.Builder(this).setTitle("Apna shop chuno").setItems(items) { _, i ->
            when {
                i < shops.size -> if (shops[i] != cur) switchTo(shops[i])
                i == shops.size -> askNewShop()
                else -> {
                    val gone = cur
                    shops.remove(gone); prefs.edit().remove("ck_$gone").apply(); persist()
                    if (shops.isEmpty()) { cur = ""; CookieManager.getInstance().removeAllCookies(null); askNewShop() } else switchTo(shops[0])
                }
            }
        }.show()
    }

    private fun download(url: String, ua: String, cd: String, mime: String) {
        if (url.startsWith("blob:")) { toast("Ye file direct download nahi ho sakti, panel ka download button dobara try karo"); return }
        val r = DownloadManager.Request(Uri.parse(url)).apply {
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            addRequestHeader("User-Agent", ua)
            setMimeType(mime)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime))
        }
        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
        toast("Download shuru. Downloads folder me dekho.")
    }

    override fun onPause() { super.onPause(); if (cur.isNotEmpty()) saveCookies(cur) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
