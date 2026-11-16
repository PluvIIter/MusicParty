package com.thornex.musicparty

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * 持有当前 WebView，供「用户关闭 App」时真正停止播放。
 *
 * <p>音频在 WebView 的 `<audio>` 里，结束 Activity 并不会停止解码；只有卸载页面或销毁
 * WebView 才会停下来。划掉最近任务时由 {@link MediaPlaybackService#onTaskRemoved} 调用，
 * 返回键退出时由 {@link MainActivity#onDestroy} 在 {@code isFinishing} 分支调用。</p>
 */
object MusicBridge {
    @Volatile
    private var webView: WebView? = null

    fun attach(view: WebView) {
        webView = view
    }

    /** 停掉页面里的媒体并销毁 WebView；重复调用安全。 */
    fun release() {
        val view = webView ?: return
        webView = null
        Handler(Looper.getMainLooper()).post {
            // 先把页面卸载掉：媒体解碼随之结束，比单纯 destroy 更稳。
            runCatching { view.loadUrl("about:blank") }
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { view.destroy() }
            }, 300)
        }
    }
}
