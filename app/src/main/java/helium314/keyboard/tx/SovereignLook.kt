// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the cutscene.me look for the AI screens (fact check, Smart take): Geist type and the
// theme's blue. Geist is under the SIL Open Font License (res/font).
package helium314.keyboard.tx

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import helium314.keyboard.latin.R

object SovereignLook {
    /** Light blue for chips and quiet buttons: cutscene's --primary at 13%, as its status badges. */
    const val TINT = 0x213B6FE0

    private var regular: Typeface? = null
    private var semibold: Typeface? = null

    fun regular(context: Context): Typeface =
        regular ?: (runCatching { ResourcesCompat.getFont(context, R.font.geist_regular) }.getOrNull() ?: Typeface.DEFAULT).also { regular = it }

    fun semibold(context: Context): Typeface =
        semibold ?: (runCatching { ResourcesCompat.getFont(context, R.font.geist_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD).also { semibold = it }

    /** Geist on every text in [view] and below; bold text gets the semibold cut. */
    fun dress(view: View) {
        if (view is TextView) {
            val bold = view.typeface?.isBold == true
            val face = if (bold) semibold(view.context) else regular(view.context)
            if (view.typeface != face) view.typeface = face
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) dress(view.getChildAt(i))
    }

    /** Keeps [root] dressed as its contents change. */
    fun keepDressed(root: View) {
        root.viewTreeObserver.addOnGlobalLayoutListener { dress(root) }
        dress(root)
    }
}
