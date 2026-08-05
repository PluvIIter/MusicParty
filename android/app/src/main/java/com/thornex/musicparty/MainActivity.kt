package com.thornex.musicparty

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MusicParty 安卓壳。
 *  - 起始页：输入服务器地址（域名或 IP），连接后进入主页。
 *  - 主页：WebView 加载站点。返回键从主页回到起始页（可换服务器）。
 *  - WebView 配置：无手势自动播放；退后台/锁屏不暂停（音频持续）。
 *  - 前台服务 MediaPlaybackService 保活进程。
 */
@SuppressLint("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var startPage: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var webView: WebView
    private val serviceIntent by lazy { Intent(this, MediaPlaybackService::class.java) }

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("musicparty", Context.MODE_PRIVATE)

        val root = FrameLayout(this)

        // 起始页（默认可见）
        startPage = buildStartPage()
        root.addView(startPage, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // WebView（先隐藏，连接后显示）
        webView = WebView(this)
        webView.visibility = View.GONE
        root.addView(webView, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(root)

        // Android 13+ 通知权限（前台服务通知要显示）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // WebView 配置
        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.userAgentString = ws.userAgentString + " MusicPartyAndroid/1.0"
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val site = getServerUrl()
                if (url != null && site != null && !url.startsWith(site)) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    return true
                }
                return false
            }
        }

        // 通知栏媒体控制：JS 桥（Web → 原生 推送状态） + 控制回调（原生 → Web）
        MediaSessionManager.init(this)
        MediaSessionManager.onControl = { action ->
            runOnUiThread { controlWeb(action) }
        }
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun updateMedia(json: String) { MediaSessionManager.update(json) }
        }, "AndroidBridge")

        // 前台服务保活（进程不被节流/杀）
        ContextCompat.startForegroundService(this, serviceIntent)

        // 已有保存地址 → 直接进主页；否则停在起始页让用户输入
        getServerUrl()?.takeIf { it.isNotBlank() }?.let { connect(it) }

        // 返回键：主页 → 起始页；起始页 → 退出
        onBackPressedDispatcher.addCallback(this) {
            if (webView.visibility == View.VISIBLE) {
                urlInput.setText(getServerUrl().orEmpty())
                webView.visibility = View.GONE
                startPage.visibility = View.VISIBLE
            } else {
                finish()
            }
        }
    }

    private fun buildStartPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111827"))
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }

        page.addView(TextView(this).apply {
            text = "MUSIC PARTY"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        page.addView(TextView(this).apply {
            text = "服务器地址"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 14f
            gravity = Gravity.CENTER
        }, lpWrap(top = dp(8), bottom = dp(16)))

        urlInput = EditText(this).apply {
            hint = "https://你的域名 或 http://192.168.x.x:8848"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            setSingleLine(true)
        }
        page.addView(urlInput, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))

        page.addView(Button(this).apply {
            text = "连接"
            setOnClickListener { connect(urlInput.text.toString().trim()) }
        }, lpWrap(top = dp(20)))

        return page
    }

    private fun lpWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            this.topMargin = top
            this.bottomMargin = bottom
        }

    private fun connect(raw: String) {
        if (raw.isBlank()) return
        val url = if (raw.startsWith("http")) raw else "http://$raw"
        prefs.edit().putString("server_url", url).apply()
        startPage.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    private fun getServerUrl(): String? = prefs.getString("server_url", null)

    /** 通知栏按钮 → 触发 Web 端播放器动作（经 Vue 应用注册的 window.MusicPartyControl） */
    private fun controlWeb(action: String) {
        val js = when (action) {
            "play", "pause" -> "window.MusicPartyControl && window.MusicPartyControl.togglePause()"
            "next" -> "window.MusicPartyControl && window.MusicPartyControl.next()"
            else -> ""
        }
        if (js.isNotEmpty()) webView.evaluateJavascript(js, null)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // 关键：退到后台/锁屏时不暂停 WebView（音频/网络持续）
    override fun onPause() { super.onPause() /* 故意不调 webView.onPause() */ }
    override fun onStop() { super.onStop() /* 故意不调 webView.onPause() */ }

    override fun onDestroy() {
        MediaSessionManager.onControl = null
        MediaSessionManager.destroy()
        super.onDestroy()
    }
}
