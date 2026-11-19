package app.tvremote.ime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * The TV keyboard. It draws nothing; it only owns the InputConnection of the focused text field.
 * Android keeps a selected keyboard bound across reboots - that is what makes this survive them (no Accessibility, no ADB).
 * LIMIT: without a focused text field there is no InputConnection, so no key can be delivered at all.
 */
class ImeService : InputMethodService() {
    override fun onCreate() { super.onCreate(); ImeHost.service = this; ImeHost.ensure(this) }
    override fun onDestroy() { if (ImeHost.service === this) ImeHost.service = null; ImeHost.server()?.inputChanged(); super.onDestroy() }

    override fun onEvaluateInputViewShown(): Boolean = false
    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onCreateInputView(): View = View(this)

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) { super.onStartInput(attribute, restarting); ImeHost.server()?.inputChanged() }
    override fun onFinishInput() { super.onFinishInput(); ImeHost.server()?.inputChanged() }

    fun hasInput(): Boolean = currentInputConnection != null

    fun sendKey(code: Int, action: Char): Boolean {
        val ic = currentInputConnection ?: return false
        if (code == KeyEvent.KEYCODE_ENTER && action == 'P') {            // search boxes want the editor action (Search / Go), not a raw Enter
            val o = currentInputEditorInfo?.imeOptions ?: 0
            val a = o and EditorInfo.IME_MASK_ACTION
            if (a != EditorInfo.IME_ACTION_NONE && a != EditorInfo.IME_ACTION_UNSPECIFIED && (o and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) return ic.performEditorAction(a)
        }
        fun ev(a: Int): Boolean {
            val t = SystemClock.uptimeMillis()
            return ic.sendKeyEvent(KeyEvent(t, t, a, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
        }
        return when (action) { 'D' -> ev(KeyEvent.ACTION_DOWN); 'U' -> ev(KeyEvent.ACTION_UP); else -> ev(KeyEvent.ACTION_DOWN) and ev(KeyEvent.ACTION_UP) }
    }

    fun putText(s: String, replace: Boolean): Boolean {
        val ic = currentInputConnection ?: return false
        ic.beginBatchEdit()
        if (replace) {
            val before = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(100000, 0)?.length ?: 0
            ic.deleteSurroundingText(before, after)
        }
        val ok = ic.commitText(s, 1)
        ic.endBatchEdit()
        return ok
    }
}
