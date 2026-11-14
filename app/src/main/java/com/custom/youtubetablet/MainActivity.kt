package com.custom.youtubetablet

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

object GeckoRuntimeHolder {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(context.applicationContext).also { runtime = it }
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var settingsBtn: TextView
    private lateinit var runtime: GeckoRuntime
    private var canGoBack = false

    private var ublockId: String? = null
    private var unhookId: String? = null
    private var unhookPopupPath: String = "popup.html"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.parseColor("#0F0F0F"))

        val root = FrameLayout(this)

        geckoView = GeckoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(geckoView)

        // Discreet corner button to configure Unhook / uBlock settings (hides in fullscreen)
        settingsBtn = TextView(this).apply {
            text = "⚙"
            textSize = 18f
            setTextColor(Color.WHITE)
            alpha = 0.35f
            setBackgroundColor(Color.argb(140, 20, 20, 20))
            setPadding(28, 16, 28, 16)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                setMargins(24, 24, 24, 24)
            }
            setOnClickListener { showExtensionMenu() }
        }
        root.addView(settingsBtn)
        setContentView(root)

        runtime = GeckoRuntimeHolder.get(this)
        ublockId = loadBuiltInExtension("ublock")
        unhookId = loadBuiltInExtension("unhook")?.also {
            unhookPopupPath = readPopupPath("unhook") ?: "popup.html"
        }

        val sessionSettings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .build()

        geckoSession = GeckoSession(sessionSettings)

        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
            }
        }

        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                settingsBtn.visibility = if (fullScreen) View.GONE else View.VISIBLE
                setImmersiveFullscreen(fullScreen)
            }
        }

        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        geckoSession.loadUri("https://www.youtube.com")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) {
                    geckoSession.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun readManifestJson(folder: String): JSONObject? {
        return try {
            val text = assets.open("extensions/$folder/manifest.json")
                .bufferedReader()
                .use { it.readText() }
            JSONObject(text)
        } catch (e: Exception) {
            Log.e("YTApp", "Failed reading manifest for $folder", e)
            null
        }
    }

    private fun readPopupPath(folder: String): String? {
        val manifest = readManifestJson(folder) ?: return null
        return manifest.optJSONObject("action")?.optString("default_popup")?.takeIf { it.isNotBlank() }
            ?: manifest.optJSONObject("browser_action")?.optString("default_popup")?.takeIf { it.isNotBlank() }
    }

    private fun loadBuiltInExtension(folder: String): String? {
        val manifest = readManifestJson(folder) ?: return null
        val id = manifest.optJSONObject("browser_specific_settings")
            ?.optJSONObject("gecko")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: manifest.optJSONObject("applications")
                ?.optJSONObject("gecko")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: return null

        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/extensions/$folder/",
            id
        ).accept(
            { ext -> Log.i("YTApp", "Loaded $folder: ${ext?.id}") },
            { err -> Log.e("YTApp", "Failed $folder", err) }
        )
        return id
    }

    private fun showExtensionMenu() {
        val items = arrayOf("Unhook Settings", "uBlock Origin Settings", "Reload YouTube")
        AlertDialog.Builder(this)
            .setTitle("Extensions")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openExtensionPopup(unhookId, unhookPopupPath, "Unhook Settings")
                    1 -> openExtensionPopup(ublockId, "dashboard.html", "uBlock Origin")
                    2 -> geckoSession.reload()
                }
            }
            .show()
    }

    private fun openExtensionPopup(extId: String?, fallbackPage: String, title: String) {
        if (extId == null) return
        runtime.webExtensionController.listInstalled().accept(
            { list ->
                val ext = list?.firstOrNull { it.id == extId } ?: return@accept
                val base = ext.metaData?.baseUrl
                val url = if (!base.isNullOrBlank()) {
                    base + fallbackPage.trimStart('/')
                } else {
                    ext.metaData?.optionsPageUrl
                } ?: return@accept

                runOnUiThread {
                    val dialogView = GeckoView(this)
                    val dialogSession = GeckoSession()
                    dialogSession.open(runtime)
                    dialogView.setSession(dialogSession)
                    dialogSession.loadUri(url)

                    val heightPx = (resources.displayMetrics.heightPixels * 0.72).toInt()
                    dialogView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        heightPx
                    )

                    AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(dialogView)
                        .setPositiveButton("Done") { d, _ -> d.dismiss() }
                        .setOnDismissListener {
                            dialogView.releaseSession()
                            dialogSession.close()
                            geckoSession.reload()
                        }
                        .show()
                }
            },
            { err -> Log.e("YTApp", "Could not open extension page", err) }
        )
    }

    private fun setImmersiveFullscreen(enable: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
