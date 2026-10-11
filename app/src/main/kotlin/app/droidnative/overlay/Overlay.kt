package app.droidnative.overlay

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/** What an overlay button does while it is held. */
sealed class ButtonAction {
    /** Keys pressed together (a key plus modifiers), released on lift. */
    data class Keys(val keys: List<String>) : ButtonAction()
    /** A mouse button at the current pointer position. */
    data class Mouse(val button: MouseButton) : ButtonAction()
    /** One wheel notch per press. */
    data class Wheel(val up: Boolean) : ButtonAction()
    /** Shows or hides the on-screen keyboard. */
    object Keyboard : ButtonAction()
    /** A mode button: shows or hides the buttons of a group. */
    data class ToggleGroup(val group: String) : ButtonAction()
    /**
     * A round stick instead of a button. [outputs] are its 2 to 8
     * directions, evenly spaced clockwise starting at [rotation] degrees
     * (0 = up); null sends nothing. With [multi] the stick between two
     * directions holds both (up and left together), otherwise only the
     * nearest.
     */
    data class Joystick(val outputs: List<StickOutput?>, val multi: Boolean = true, val rotation: Int = 0) : ButtonAction() {
        companion object {
            val WASD = Joystick(listOf("w", "d", "s", "a").map { StickOutput.Key(it) })
            val ARROWS = Joystick(listOf("up", "right", "down", "left").map { StickOutput.Key(it) })
            val SCROLL = Joystick(listOf(StickOutput.Wheel(true), StickOutput.Wheel(false)), multi = false)
            const val MIN_DIRECTIONS = 2
            const val MAX_DIRECTIONS = 8
        }
    }
}

/** What a joystick direction sends while the stick points that way. */
sealed class StickOutput {
    /** Held while the direction is active. */
    data class Key(val name: String) : StickOutput()
    /** Wheel notches, repeated faster the further the stick is pushed. */
    data class Wheel(val up: Boolean) : StickOutput()
}

enum class MouseButton(val xml: String, val code: Int) {
    LEFT("left", 0x110), RIGHT("right", 0x111), MIDDLE("middle", 0x112)
}

/**
 * One button or joystick. [x] and [y] are its centre as a fraction of the
 * window, so a layout fits any window size; [width] and [height] are in dp
 * (equal for joysticks).
 */
data class OverlayButton(
    val id: String = newId(),
    val label: String = "",
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val width: Int = 64,
    val height: Int = 64,
    val action: ButtonAction = ButtonAction.Keys(listOf("space")),
    val group: String? = null,
) {
    val isJoystick get() = action is ButtonAction.Joystick
}

/** A set of buttons shown or hidden together by mode buttons. */
data class ButtonGroup(val id: String = newId(), val name: String, val visible: Boolean = true)

data class OverlayLayout(
    val id: String = newId(),
    val name: String,
    /** Opacity of the buttons, 0.1 to 1. */
    val opacity: Float = 0.4f,
    val groups: List<ButtonGroup> = emptyList(),
    val buttons: List<OverlayButton> = emptyList(),
)

internal fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/**
 * Layout files (export, import, and the stored copies):
 *
 *   <overlay version="1" name="Game" opacity="0.4">
 *     <group id="g1" name="Movement" visible="true"/>
 *     <button id="b1" label="Jump" x="0.85" y="0.80" width="72" height="72" group="g1">
 *       <keys><key name="space"/></keys>
 *     </button>
 *     <button ...><mouse button="right"/></button>
 *     <button ...><wheel direction="up"/></button>
 *     <button ...><keyboard/></button>
 *     <button ...><toggle group="g1"/></button>
 *     <joystick id="j1" label="" x="0.15" y="0.75" width="140" height="140" multi="true" rotation="0">
 *       <direction key="w"/><direction key="d"/><direction wheel="down"/><direction/>
 *     </joystick>
 *   </overlay>
 *
 * Joysticks are elements of their own so that older versions skip them
 * instead of rejecting the layout. Unknown elements are ignored too.
 */
object OverlayXml {
    class FormatException(message: String) : Exception(message)

    fun write(layout: OverlayLayout, out: OutputStream) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = doc.createElement("overlay")
        root.setAttribute("version", "1")
        root.setAttribute("id", layout.id)
        root.setAttribute("name", layout.name)
        root.setAttribute("opacity", fmt(layout.opacity))
        doc.appendChild(root)
        for (g in layout.groups) {
            val e = doc.createElement("group")
            e.setAttribute("id", g.id)
            e.setAttribute("name", g.name)
            e.setAttribute("visible", g.visible.toString())
            root.appendChild(e)
        }
        for (b in layout.buttons) {
            val stick = b.action as? ButtonAction.Joystick
            val e = doc.createElement(if (stick != null) "joystick" else "button")
            e.setAttribute("id", b.id)
            e.setAttribute("label", b.label)
            e.setAttribute("x", fmt(b.x))
            e.setAttribute("y", fmt(b.y))
            e.setAttribute("width", b.width.toString())
            e.setAttribute("height", b.height.toString())
            b.group?.let { e.setAttribute("group", it) }
            if (stick != null) {
                e.setAttribute("multi", stick.multi.toString())
                e.setAttribute("rotation", stick.rotation.toString())
                for (o in stick.outputs) {
                    val d = doc.createElement("direction")
                    when (o) {
                        is StickOutput.Key -> d.setAttribute("key", o.name)
                        is StickOutput.Wheel -> d.setAttribute("wheel", if (o.up) "up" else "down")
                        null -> {}
                    }
                    e.appendChild(d)
                }
                root.appendChild(e)
                continue
            }
            val a = when (val action = b.action) {
                is ButtonAction.Keys -> doc.createElement("keys").also { keys ->
                    for (k in action.keys)
                        keys.appendChild(doc.createElement("key").also { it.setAttribute("name", k) })
                }
                is ButtonAction.Mouse -> doc.createElement("mouse").also { it.setAttribute("button", action.button.xml) }
                is ButtonAction.Wheel -> doc.createElement("wheel").also { it.setAttribute("direction", if (action.up) "up" else "down") }
                is ButtonAction.Keyboard -> doc.createElement("keyboard")
                is ButtonAction.ToggleGroup -> doc.createElement("toggle").also { it.setAttribute("group", action.group) }
                is ButtonAction.Joystick -> error("written above")
            }
            e.appendChild(a)
            root.appendChild(e)
        }
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.INDENT, "yes")
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.transform(DOMSource(doc), StreamResult(out))
    }

    @Throws(FormatException::class)
    fun read(input: InputStream): OverlayLayout {
        val doc = try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        } catch (e: Exception) {
            throw FormatException("not an XML file: ${e.message}")
        }
        val root = doc.documentElement
        if (root.tagName != "overlay") throw FormatException("not a DroidNative button layout")
        val groups = children(root, "group").map {
            ButtonGroup(it.getAttribute("id").ifEmpty { newId() }, it.getAttribute("name"),
                it.getAttribute("visible") != "false")
        }
        val buttons = children(root, "button", "joystick").map { e ->
            OverlayButton(
                id = e.getAttribute("id").ifEmpty { newId() },
                label = e.getAttribute("label"),
                x = num(e, "x", 0.5f).coerceIn(0f, 1f),
                y = num(e, "y", 0.5f).coerceIn(0f, 1f),
                width = num(e, "width", 64f).toInt().coerceIn(16, 400),
                height = num(e, "height", 64f).toInt().coerceIn(16, 400),
                action = if (e.tagName == "joystick") joystick(e) else action(e),
                group = e.getAttribute("group").ifEmpty { null }?.takeIf { g -> groups.any { it.id == g } },
            ).let { if (it.isJoystick) it.copy(height = it.width) else it }
        }
        return OverlayLayout(
            id = root.getAttribute("id").ifEmpty { newId() },
            name = root.getAttribute("name").ifEmpty { "Imported layout" },
            opacity = num(root, "opacity", 0.4f).coerceIn(0.1f, 1f),
            groups = groups,
            buttons = buttons,
        )
    }

    private fun action(e: Element): ButtonAction {
        children(e, "keys").firstOrNull()?.let { keys ->
            val names = children(keys, "key").map { it.getAttribute("name") }.filter { Keys.byName(it) != null }
            if (names.isEmpty()) throw FormatException("button '${e.getAttribute("label")}' has no known key")
            return ButtonAction.Keys(names)
        }
        children(e, "mouse").firstOrNull()?.let { m ->
            val b = MouseButton.entries.firstOrNull { it.xml == m.getAttribute("button") }
                ?: throw FormatException("unknown mouse button '${m.getAttribute("button")}'")
            return ButtonAction.Mouse(b)
        }
        children(e, "wheel").firstOrNull()?.let { return ButtonAction.Wheel(it.getAttribute("direction") == "up") }
        if (children(e, "keyboard").isNotEmpty()) return ButtonAction.Keyboard
        children(e, "toggle").firstOrNull()?.let { return ButtonAction.ToggleGroup(it.getAttribute("group")) }
        throw FormatException("button '${e.getAttribute("label")}' has no action")
    }

    private fun joystick(e: Element): ButtonAction.Joystick {
        val outputs = children(e, "direction").map { d ->
            val key = d.getAttribute("key")
            val wheel = d.getAttribute("wheel")
            when {
                key.isNotEmpty() -> StickOutput.Key(key).takeIf { Keys.byName(key) != null }
                wheel.isNotEmpty() -> StickOutput.Wheel(wheel == "up")
                else -> null
            }
        }
        if (outputs.size !in ButtonAction.Joystick.MIN_DIRECTIONS..ButtonAction.Joystick.MAX_DIRECTIONS)
            throw FormatException("joystick '${e.getAttribute("label")}' needs 2 to 8 directions")
        return ButtonAction.Joystick(outputs, e.getAttribute("multi") != "false",
            Math.floorMod(num(e, "rotation", 0f).toInt(), 360))
    }

    private fun children(e: Element, vararg tags: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName in tags) out += n
        }
        return out
    }

    private fun num(e: Element, attr: String, default: Float) =
        e.getAttribute(attr).toFloatOrNull() ?: default

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.4f", v)
}
