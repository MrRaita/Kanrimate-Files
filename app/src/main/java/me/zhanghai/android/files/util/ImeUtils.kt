package me.zhanghai.android.files.util

import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Whether the soft keyboard is currently showing. The window insets are asked first; on devices
 * or window setups where they do not report the keyboard, the visible frame of the window is
 * compared with its full height as a fallback.
 */
fun View.isImeVisibleCompat(): Boolean {
    val insets = ViewCompat.getRootWindowInsets(this)
    if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
        return true
    }
    val root = rootView ?: return false
    val frame = Rect()
    root.getWindowVisibleDisplayFrame(frame)
    val hidden = root.height - frame.height()
    val threshold = (150 * resources.displayMetrics.density).toInt()
    return hidden > threshold
}
