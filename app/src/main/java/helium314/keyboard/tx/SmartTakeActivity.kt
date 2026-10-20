// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: "Smart take" - share a screenshot, get three witty lines to post or say.
package helium314.keyboard.tx

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Share target for images ("Smart take" in the share sheet): take a screenshot - say of an Instagram
 * video to repost - tap Share, pick Smart take. The screenshot (downscaled, JPEG, memory only) goes to
 * the user's own server (POST /v1/smart), which asks Claude for three short witty lines: a repost
 * caption for a video or post, a reply for a conversation. Tap a line to copy it; Again asks for three
 * more. Developer feature (the server enforces it, like the screenshot fact check).
 */
class SmartTakeActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var body: LinearLayout
    private var image: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = DictationSettings(this)
        if (settings.serverUrl.isBlank() || settings.token.isBlank()) return quit("Smart take: add your access key in Settings, SovereignBoard")
        if (!DeveloperMode.isOn(settings)) return quit("Smart take is a developer feature. Unlock developer mode in Settings, SovereignBoard.")
        val uri = sharedImage(intent) ?: return quit("Smart take: share a screenshot or picture")

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            minimumWidth = dp(300)
        }
        setContentView(body)
        setFinishOnTouchOutside(true)
        scope.launch {
            image = withContext(Dispatchers.IO) { SovereignScreenshots.jpegForUpload(applicationContext, uri) }
            if (image == null) quit("Smart take couldn't read that picture.") else ask(settings)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        image = null
        super.onDestroy()
    }

    private fun ask(settings: DictationSettings) {
        body.removeAllViews()
        body.addView(title("Thinking of something smart…"))
        body.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(8)
        })
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(request(settings, image ?: return@withContext Result.failure(Failed("No picture")))) }
                catch (e: Failed) { Result.failure(e) }
                catch (e: IOException) { Result.failure(Failed("Couldn't reach the server. Check your connection and try again.")) }
                catch (e: Exception) { Result.failure(Failed("Smart take failed. Try again.")) }
            }
            result.onSuccess { show(it, settings) }.onFailure { showError(it.message ?: "Smart take failed.", settings) }
        }
    }

    private fun show(lines: List<String>, settings: DictationSettings) {
        body.removeAllViews()
        body.addView(title("Tap one to copy it"))
        if (lines.isEmpty()) body.addView(text("Nothing came back. Try Again.", 15f))
        for (line in lines) body.addView(text(line, 16f).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#14D81B3C")) }
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Smart take", line))
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this@SmartTakeActivity, "Copied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        buttons(settings)
    }

    private fun showError(message: String, settings: DictationSettings) {
        body.removeAllViews()
        body.addView(title("Smart take"))
        body.addView(text(message, 15f).apply { setPadding(0, dp(8), 0, 0) })
        buttons(settings)
    }

    private fun buttons(settings: DictationSettings) {
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
            addView(Button(this@SmartTakeActivity).apply { text = "Again"; isAllCaps = false; setOnClickListener { ask(settings) } })
            addView(Button(this@SmartTakeActivity).apply { text = "Close"; isAllCaps = false; setOnClickListener { finish() } })
        })
    }

    private class Failed(message: String) : Exception(message)

    private fun request(settings: DictationSettings, jpeg: ByteArray): List<String> {
        val bytes = JSONObject().put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("media_type", "image/jpeg")
            .toString().toByteArray()
        val conn = (URL(settings.serverUrl.trimEnd('/') + "/v1/smart").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 55_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${settings.token}")
            setRequestProperty("X-Dev-Token", settings.devToken)
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val reason = runCatching {
                    JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}").optString("detail")
                }.getOrDefault("")
                throw Failed(when (code) {
                    401 -> { SovereignToken.onRejected(this); "The server didn't accept your access key." }
                    403 -> { settings.devToken = ""; "Smart take is a developer feature. Developer mode is now locked; unlock it again in Settings, SovereignBoard." }
                    404 -> "Smart take isn't on the server yet."
                    413 -> "That picture is too big."
                    415 -> "That picture couldn't be read."
                    429 -> reason.ifBlank { "That's a lot - try again in a moment." }
                    503 -> "Smart take isn't switched on on the server."
                    else -> "The server had a problem ($code). Try again."
                })
            }
            SovereignToken.onAccepted(this)
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("lines")
            return (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

    private fun quit(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun title(s: String) = text(s, 17f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }

    private fun text(s: String, sp: Float) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextIsSelectable(false)
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
