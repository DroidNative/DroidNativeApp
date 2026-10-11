package app.droidnative.overlay

/**
 * Linux keys (evdev codes) that overlay buttons can send, with the names
 * used in layout files. They are physical key positions: with a German
 * keymap the key named "z" types a y, as on a real keyboard.
 */
object Keys {
    data class Key(val name: String, val label: String, val code: Int)

    val ALL: List<Key> = buildList {
        val letters = "qwertyuiop" to 16
        letters.first.forEachIndexed { i, c -> add(Key("$c", c.uppercase(), letters.second + i)) }
        "asdfghjkl".forEachIndexed { i, c -> add(Key("$c", c.uppercase(), 30 + i)) }
        "zxcvbnm".forEachIndexed { i, c -> add(Key("$c", c.uppercase(), 44 + i)) }
        for (d in 1..9) add(Key("$d", "$d", 1 + d))
        add(Key("0", "0", 11))
        add(Key("esc", "Esc", 1))
        add(Key("tab", "Tab", 15))
        add(Key("enter", "Enter", 28))
        add(Key("space", "Space", 57))
        add(Key("backspace", "Backspace", 14))
        add(Key("delete", "Delete", 111))
        add(Key("insert", "Insert", 110))
        add(Key("up", "Up", 103))
        add(Key("down", "Down", 108))
        add(Key("left", "Left", 105))
        add(Key("right", "Right", 106))
        add(Key("home", "Home", 102))
        add(Key("end", "End", 107))
        add(Key("pageup", "Page up", 104))
        add(Key("pagedown", "Page down", 109))
        for (f in 1..10) add(Key("f$f", "F$f", 58 + f))
        add(Key("f11", "F11", 87))
        add(Key("f12", "F12", 88))
        add(Key("leftshift", "Shift", 42))
        add(Key("leftctrl", "Ctrl", 29))
        add(Key("leftalt", "Alt", 56))
        add(Key("leftmeta", "Super", 125))
        add(Key("rightalt", "AltGr", 100))
        add(Key("minus", "-", 12))
        add(Key("equal", "=", 13))
        add(Key("leftbrace", "[", 26))
        add(Key("rightbrace", "]", 27))
        add(Key("semicolon", ";", 39))
        add(Key("apostrophe", "'", 40))
        add(Key("grave", "`", 41))
        add(Key("backslash", "\\", 43))
        add(Key("comma", ",", 51))
        add(Key("dot", ".", 52))
        add(Key("slash", "/", 53))
        add(Key("capslock", "Caps lock", 58))
        add(Key("print", "Print", 99))
        add(Key("pause", "Pause", 119))
    }

    private val byName = ALL.associateBy { it.name }

    fun byName(name: String): Key? = byName[name]

    // Other words people search for
    private val aliases = mapOf(
        "up" to "arrow", "down" to "arrow", "left" to "arrow", "right" to "arrow",
        "esc" to "escape", "enter" to "return", "leftctrl" to "control", "leftmeta" to "windows",
        "space" to "spacebar", "dot" to "period full stop", "print" to "screenshot",
        "minus" to "dash hyphen", "equal" to "equals", "leftbrace" to "bracket", "rightbrace" to "bracket",
    )

    /** Words a key is found by in the editor's search. */
    fun searchText(key: Key): String = "${key.label} ${key.name} ${aliases[key.name] ?: ""}"

    /** The modifiers a button can add to its key, as xkb masks of the keymap. */
    enum class Modifier(val key: String, val label: String, val mask: Int) {
        SHIFT("leftshift", "Shift", 1 shl 0),
        CTRL("leftctrl", "Ctrl", 1 shl 2),
        ALT("leftalt", "Alt", 1 shl 3),
        SUPER("leftmeta", "Super", 1 shl 6),
    }

    /** xkb modifier mask of a held key, 0 for other keys. */
    fun modifierMask(code: Int): Int = when (code) {
        42, 54 -> Modifier.SHIFT.mask
        29, 97 -> Modifier.CTRL.mask
        56 -> Modifier.ALT.mask
        125, 126 -> Modifier.SUPER.mask
        else -> 0
    }
}
