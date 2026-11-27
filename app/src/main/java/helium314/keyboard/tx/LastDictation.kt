// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the life raft key (his ask, 27 Nov 2026): a lost dictation can always be typed in again.
package helium314.keyboard.tx

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.widget.Toast

/**
 * The text of the most recent dictation (cleaned up when cleanup ran, else as heard), kept in the
 * keyboard's own private SharedPreferences file so it survives the keyboard restarting. Each new
 * dictation overwrites it. It is saved before the text is typed in, so a dictation that never made
 * it into the field (no field, field changed, app dropped it) can still be recovered with the life
 * raft key next to the clipboard key ([recover]).
 */
object LastDictation {
    private const val FILE = "sovereign_last_dictation"
    private const val KEY_TEXT = "text"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Keeps [text] as the last dictation. Written synchronously, so it is on disk before the text is typed. */
    fun save(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // runCatching: before the first unlock after a reboot the app's normal storage is not readable
        runCatching { prefs(context).edit().putString(KEY_TEXT, trimmed).commit() }
    }

    /** The last dictation, or null if there is none. */
    fun get(context: Context): String? =
        runCatching { prefs(context).getString(KEY_TEXT, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * The life raft key: types the last dictation in at the cursor, fitted to what is before it
     * (a space after the previous word, lower-case start mid-sentence). Does nothing but say so if
     * there is no dictation to recover.
     */
    fun recover(ime: InputMethodService) {
        val text = get(ime)
        val ic = ime.currentInputConnection
        if (text == null || ic == null) {
            Toast.makeText(ime.applicationContext, "No dictation to recover", Toast.LENGTH_SHORT).show()
            return
        }
        val before = runCatching {
            ic.getTextBeforeCursor(DictationCasing.CONTEXT_CHARS, 0)?.toString()
        }.getOrNull()
        val words = DictationSettings(ime).let { s ->
            s.wordList + s.serverWords.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        val fitted = runCatching { DictationCasing.fit(text, before, words) }.getOrDefault(text)
        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.commitText(fitted, 1)
        ic.endBatchEdit()
    }
}
