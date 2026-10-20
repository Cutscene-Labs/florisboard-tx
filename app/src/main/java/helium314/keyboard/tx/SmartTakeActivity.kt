// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: "Ask SovereignBoard" - share a screenshot, say what you want done with it.
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
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
 * Share target for images ("Ask SovereignBoard" in the share sheet): take a screenshot - say of an
 * Instagram video to repost - tap Share, pick it. A small window asks "What do you want to do with this?":
 * type or dictate an answer, or tap one of the suggestions. While the box is already open, the server
 * looks at the picture (POST /v1/smart with suggest) and offers a one-line read of what it is plus
 * prompts made for that picture; if that fails, fixed quick picks show instead. The screenshot (downscaled, JPEG, memory only) and the request go to
 * the user's own server (POST /v1/smart), which asks Claude. Options come back as separate lines (tap
 * one to copy it); a single answer gets a Copy button. Ask again goes back to the question.
 * Developer feature (the server enforces it, like the screenshot fact check).
 */
class SmartTakeActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var body: LinearLayout
    private var image: ByteArray? = null
    /** Prompts made for this picture: null until they arrive; [suggestFailed] = use [QUICK_PICKS]. */
    private var suggestions: Pair<String, List<String>>? = null
    private var suggestStarted = false
    private var suggestFailed = false
    private var picksBox: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = DictationSettings(this)
        if (settings.serverUrl.isBlank() || settings.token.isBlank()) return quit("SovereignBoard: add your access key in Settings, SovereignBoard")
        if (!DeveloperMode.isOn(settings)) return quit("This is a developer feature. Unlock developer mode in Settings, SovereignBoard.")
        val uri = sharedImage(intent) ?: return quit("SovereignBoard: share a screenshot or picture")

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            minimumWidth = dp(300)
        }
        setContentView(body)
        setFinishOnTouchOutside(true)
        scope.launch {
            image = withContext(Dispatchers.IO) { SovereignScreenshots.jpegForUpload(applicationContext, uri) }
            if (image == null) quit("SovereignBoard couldn't read that picture.") else question(settings, "")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        image = null
        super.onDestroy()
    }

    private fun question(settings: DictationSettings, last: String) {
        body.removeAllViews()
        body.addView(title("What do you want to do with this?"))
        val input = EditText(this).apply {
            hint = "Type or dictate…"
            setText(last)
            setSelection(last.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_GO
            setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            setOnEditorActionListener { v, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { ask(settings, v.text.toString().trim()); true } else false
            }
        }
        body.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        picksBox = box
        body.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        fillPicks(settings)
        if (!suggestStarted) {
            suggestStarted = true
            scope.launch {
                val got = withContext(Dispatchers.IO) {
                    runCatching {
                        val o = request(settings, image ?: throw Failed("No picture"), "", suggest = true)
                        val arr = o.optJSONArray("prompts")
                        val prompts = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optString(it).trim().takeIf { t -> t.isNotEmpty() } }
                        if (prompts.isEmpty()) throw Failed("No suggestions")
                        o.optString("about").trim() to prompts
                    }.getOrNull()
                }
                if (got == null) suggestFailed = true else suggestions = got
                if (picksBox === box) fillPicks(settings)
            }
        }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
            addView(Button(this@SmartTakeActivity).apply { text = "Close"; isAllCaps = false; setOnClickListener { finish() } })
            addView(Button(this@SmartTakeActivity).apply { text = "Go"; isAllCaps = false
                setOnClickListener { ask(settings, input.text.toString().trim()) } })
        })
        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun fillPicks(settings: DictationSettings) {
        val box = picksBox ?: return
        box.removeAllViews()
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
        val found = suggestions
        when {
            found != null -> {
                if (found.first.isNotEmpty()) box.addView(text(found.first, 14f).apply { alpha = 0.75f; setPadding(0, dp(6), 0, 0) })
                for (pick in found.second) box.addView(chip(pick) { ask(settings, pick) }, lp())
            }
            suggestFailed -> for (pick in QUICK_PICKS) box.addView(chip(pick) { ask(settings, pick) }, lp())
            else -> box.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(4))
                addView(ProgressBar(this@SmartTakeActivity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(text("Looking at your picture for ideas…", 14f).apply { alpha = 0.75f; setPadding(dp(10), 0, 0, 0) })
            })
        }
    }

    private fun ask(settings: DictationSettings, request: String) {
        picksBox = null
        val wanted = request.ifBlank { QUICK_PICKS[0] }
        currentFocus?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        body.removeAllViews()
        body.addView(title("Working on it…"))
        body.addView(text(wanted, 14f).apply { alpha = 0.7f; setPadding(0, dp(4), 0, 0) })
        body.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(8)
        })
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(answerOf(request(settings, image ?: return@withContext Result.failure(Failed("No picture")), wanted))) }
                catch (e: Failed) { Result.failure(e) }
                catch (e: IOException) { Result.failure(Failed("Couldn't reach the server. Check your connection and try again.")) }
                catch (e: Exception) { Result.failure(Failed("That didn't work. Try again.")) }
            }
            result.onSuccess { show(it, settings, wanted) }.onFailure { showError(it.message ?: "That didn't work.", settings, wanted) }
        }
    }

    private fun show(answer: Answer, settings: DictationSettings, request: String) {
        body.removeAllViews()
        val options = answer.lines.size > 1
        body.addView(title(if (options) "Tap one to copy it" else "Here you go"))
        if (answer.text.isBlank()) body.addView(text("Nothing came back. Try asking again.", 15f))
        else if (options) for (line in answer.lines) body.addView(chip(line) { copy(line) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        else body.addView(ScrollView(this).apply {
            addView(text(answer.text, 16f).apply { setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons(settings, request, copyAll = if (!options && answer.text.isNotBlank()) answer.text else null)
    }

    private fun showError(message: String, settings: DictationSettings, request: String) {
        body.removeAllViews()
        body.addView(title("Ask SovereignBoard"))
        body.addView(text(message, 15f).apply { setPadding(0, dp(8), 0, 0) })
        buttons(settings, request, copyAll = null)
    }

    private fun buttons(settings: DictationSettings, request: String, copyAll: String?) {
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
            addView(Button(this@SmartTakeActivity).apply { text = "Ask again"; isAllCaps = false; setOnClickListener { question(settings, request) } })
            if (copyAll != null) addView(Button(this@SmartTakeActivity).apply { text = "Copy"; isAllCaps = false; setOnClickListener { copy(copyAll) } })
            addView(Button(this@SmartTakeActivity).apply { text = "Close"; isAllCaps = false; setOnClickListener { finish() } })
        })
    }

    private fun copy(s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SovereignBoard", s))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun chip(s: String, onTap: () -> Unit) = text(s, 16f).apply {
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#14D81B3C")) }
        setOnClickListener { onTap() }
    }

    private class Answer(val text: String, val lines: List<String>)

    private class Failed(message: String) : Exception(message)

    private fun request(settings: DictationSettings, jpeg: ByteArray, wanted: String, suggest: Boolean = false): JSONObject {
        val bytes = JSONObject().put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("media_type", "image/jpeg")
            .put("request", wanted).put("suggest", suggest).toString().toByteArray()
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
                    403 -> { settings.devToken = ""; "This is a developer feature. Developer mode is now locked; unlock it again in Settings, SovereignBoard." }
                    404 -> "This isn't on the server yet."
                    413 -> "That picture is too big."
                    415 -> "That picture couldn't be read."
                    429 -> reason.ifBlank { "That's a lot - try again in a moment." }
                    503 -> "This isn't switched on on the server."
                    else -> "The server had a problem ($code). Try again."
                })
            }
            SovereignToken.onAccepted(this)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun answerOf(o: JSONObject): Answer {
        val arr = o.optJSONArray("lines")
        val lines = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optString(it).trim().takeIf { t -> t.isNotEmpty() } }
        return Answer(o.optString("answer").trim(), lines)
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

    companion object {
        /** Shown if the picture-made suggestions fail. The first one is also what an empty request means. */
        val QUICK_PICKS = listOf(
            "What smart thing can I say about this?",
            "Write a witty caption to repost it",
            "Write a reply",
            "Explain this to me",
            "Pull out the text",
        )
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
