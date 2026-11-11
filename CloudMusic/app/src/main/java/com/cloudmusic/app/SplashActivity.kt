package com.cloudmusic.app

import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 打开 App 时的开屏页。
 *
 * 系统启动页（Android 12+ 强制创建，关不掉）已设成无图标、无动画的纯色底，
 * 冷启动一闪而过就直接露出这一屏：声波柱动画 + 应用名 + 标语。
 * 状态栏/导航栏透明，渐变背景铺到系统栏底下，颜色跟页面完全一致。
 * 停留约 900ms 就进主界面，点一下可以立刻跳过。
 *
 * 用 ComponentActivity 而不是 AppCompatActivity：项目主题是平台
 * Theme.Material.*，AppCompat 会要求 Theme.AppCompat 后代而直接崩。
 */
class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false
    private val goMain = Runnable { goMain() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()   // 必须在 super.onCreate 之前
        super.onCreate(savedInstanceState)

        // 状态栏/导航栏透明，布局一直铺到系统栏底下：
        // 系统栏区域显示的是同一张渐变背景，不会跟页面分出两截颜色。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false      // 深底要白色图标
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_splash)

        val root = findViewById<View>(R.id.splash_root)
        // 背景铺满整屏，但文字/图标避开系统栏（背景本身会画到 padding 区域）
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        (findViewById<ImageView>(R.id.logo).drawable as? Animatable)?.start()
        // 不想等就点一下直接进
        root.setOnClickListener { goMain() }
        handler.postDelayed(goMain, DURATION_MS)
    }

    private fun goMain() {
        if (navigated) return
        navigated = true
        handler.removeCallbacks(goMain)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goMain)
        super.onDestroy()
    }

    private companion object {
        const val DURATION_MS = 900L
    }
}
