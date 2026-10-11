package app.droidnative.display

import android.view.KeyEvent

/** Android key events -> Linux evdev codes and xkb modifier masks (us keymap). */
object KeyMap {
    // xkb modifier indices of the standard evdev/us keymap
    private const val MOD_SHIFT = 1 shl 0
    private const val MOD_LOCK = 1 shl 1
    private const val MOD_CTRL = 1 shl 2
    private const val MOD_ALT = 1 shl 3
    private const val MOD_NUM = 1 shl 4
    private const val MOD_LOGO = 1 shl 6
    private const val MOD_LEVEL3 = 1 shl 7   // Mod5

    /** Depressed modifiers for wl_keyboard.modifiers. */
    /**
     * xkb modifier masks for [meta]. Right Alt is AltGr (level 3, Mod5)
     * when the keymap from a hardware keyboard has a third level.
     */
    fun modsDepressed(meta: Int): Int {
        var m = modsBase(meta)
        if (HardwareKeyboard.altGrIsLevel3 && meta and KeyEvent.META_ALT_RIGHT_ON != 0) {
            m = m or MOD_LEVEL3
            if (meta and KeyEvent.META_ALT_LEFT_ON == 0) m = m and MOD_ALT.inv()
        }
        return m
    }

    private fun modsBase(meta: Int): Int {
        var m = 0
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or MOD_SHIFT
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or MOD_CTRL
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or MOD_ALT
        if (meta and KeyEvent.META_META_ON != 0) m = m or MOD_LOGO
        return m
    }

    /** Locked modifiers (Caps Lock, Num Lock). */
    fun modsLocked(meta: Int): Int {
        var m = 0
        if (meta and KeyEvent.META_CAPS_LOCK_ON != 0) m = m or MOD_LOCK
        if (meta and KeyEvent.META_NUM_LOCK_ON != 0) m = m or MOD_NUM
        return m
    }

    /**
     * Hardware keyboards report the evdev code as scan code; virtual and
     * synthetic events do not, so fall back to the keycode table.
     */
    fun toEvdev(event: KeyEvent): Int {
        val sc = event.scanCode
        if (sc in 1..0x2ff && event.device?.isVirtual == false) return sc
        return TABLE[event.keyCode] ?: 0
    }

    /** Android keycode at the US position of evdev key [code], or 0. */
    fun locationOf(code: Int): Int = REVERSE[code] ?: 0

    private val REVERSE: Map<Int, Int> by lazy { TABLE.entries.associate { (k, v) -> v to k } }

    private val TABLE: Map<Int, Int> = buildMap {
        val letters = intArrayOf(30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
            49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44)
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, letters[i])
        put(KeyEvent.KEYCODE_0, 11)
        for (i in 1..9) put(KeyEvent.KEYCODE_0 + i, 1 + i)
        for (i in 0 until 10) put(KeyEvent.KEYCODE_F1 + i, 59 + i)
        put(KeyEvent.KEYCODE_F11, 87)
        put(KeyEvent.KEYCODE_F12, 88)
        put(KeyEvent.KEYCODE_ESCAPE, 1)
        put(KeyEvent.KEYCODE_MINUS, 12)
        put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, 14)
        put(KeyEvent.KEYCODE_TAB, 15)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_ENTER, 28)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 29)
        put(KeyEvent.KEYCODE_SEMICOLON, 39)
        put(KeyEvent.KEYCODE_APOSTROPHE, 40)
        put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        put(KeyEvent.KEYCODE_BACKSLASH, 43)
        put(KeyEvent.KEYCODE_COMMA, 51)
        put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56)
        put(KeyEvent.KEYCODE_SPACE, 57)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 58)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(KeyEvent.KEYCODE_MOVE_HOME, 102)
        put(KeyEvent.KEYCODE_DPAD_UP, 103)
        put(KeyEvent.KEYCODE_PAGE_UP, 104)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 106)
        put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(KeyEvent.KEYCODE_INSERT, 110)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111)
        put(KeyEvent.KEYCODE_META_LEFT, 125)
        put(KeyEvent.KEYCODE_META_RIGHT, 126)
    }
}
