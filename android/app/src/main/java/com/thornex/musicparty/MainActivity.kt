package com.thornex.musicparty

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * MusicParty 安卓壳：WebView 加载用户填写的服务器地址。
 * 关键点：
 *  - mediaPlaybackRequiresUserGesture=false：无手势自动播放
 *  - onPause/onStop 故意不暂停 WebView：退后台/锁屏持续播放
 *  - 启动前台服务 MediaPlaybackService：进程不被节流/杀
 */
@SuppressLint("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private val serviceIntent by lazy { Intent(this, MediaPlaybackService::class.java) }

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("musicparty", Context.MODE_PRIVATE)

        // 容器：WebView 全屏 + 右下角悬浮设置按钮（v1 简单做法，之后可换成菜单）
        val container = FrameLayout(this)
        webView = WebView(this)
        container.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val settingsBtn = Button(this).apply {
            text = "⚙"
            alpha = 0.7f
        }
        container.addView(settingsBtn, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = dp(16)
            bottomMargin = dp(16)
        })
        setContentView(container)
        settingsBtn.setOnClickListener { showUrlDialog() }

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
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }
                    return true
                }
                return false
            }
        }

        // 启动前台服务（进程保持活跃）
        ContextCompat.startForegroundService(this, serviceIntent)

        // 首次启动：让用户填域名/IP；之后直接加载已存地址
        val saved = getServerUrl()
        if (saved.isNullOrEmpty()) showUrlDialog() else webView.loadUrl(saved)
    }

    private fun getServerUrl(): String? = prefs.getString("server_url", null)

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://你的域名 或 http://192.168.x.x:8848"
            setText(getServerUrl().orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("服务器地址")
            .setView(input)
            .setPositiveButton("连接") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val url = if (raw.startsWith("http")) raw else "http://$raw"
                    prefs.edit().putString("server_url", url).apply()
                    webView.loadUrl(url)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // 关键：退到后台/锁屏时不暂停 WebView（音频/网络持续）
    override fun onPause() { super.onPause() /* 故意不调 webView.onPause() */ }
    override fun onStop() { super.onStop() /* 故意不调 webView.onPause() */ }

    override fun onDestroy() { super.onDestroy() }
}
