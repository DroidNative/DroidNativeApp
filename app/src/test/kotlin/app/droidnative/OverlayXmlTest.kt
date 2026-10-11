package app.droidnative

import app.droidnative.overlay.ButtonAction
import app.droidnative.overlay.ButtonGroup
import app.droidnative.overlay.MouseButton
import app.droidnative.overlay.OverlayButton
import app.droidnative.overlay.OverlayLayout
import app.droidnative.overlay.OverlayView
import app.droidnative.overlay.OverlayXml
import app.droidnative.overlay.StickOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OverlayXmlTest {
    @Test fun roundTrip() {
        val g = ButtonGroup("g1", "Movement", visible = false)
        val layout = OverlayLayout("l1", "Game & <test>", 0.55f, listOf(g), listOf(
            OverlayButton("b1", "Jump", 0.85f, 0.8f, 72, 72, ButtonAction.Keys(listOf("leftctrl", "s")), "g1"),
            OverlayButton("b2", "RMB", 0.1f, 0.2f, 48, 40, ButtonAction.Mouse(MouseButton.RIGHT)),
            OverlayButton("b3", "Up", 0.2f, 0.2f, 48, 48, ButtonAction.Wheel(true)),
            OverlayButton("b4", "Kbd", 0.3f, 0.2f, 48, 48, ButtonAction.Keyboard),
            OverlayButton("b5", "Mode", 0.4f, 0.2f, 48, 48, ButtonAction.ToggleGroup("g1")),
            OverlayButton("j1", "", 0.15f, 0.75f, 140, 140, ButtonAction.Joystick.WASD, "g1"),
            OverlayButton("j2", "Scroll", 0.9f, 0.5f, 96, 96,
                ButtonAction.Joystick(listOf(StickOutput.Wheel(true), null, StickOutput.Key("pagedown")), false, 45)),
        ))
        val out = ByteArrayOutputStream()
        OverlayXml.write(layout, out)
        val back = OverlayXml.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(layout, back)
    }

    @Test fun rejectsOtherFiles() {
        assertThrows(OverlayXml.FormatException::class.java) {
            OverlayXml.read(ByteArrayInputStream("<html/>".toByteArray()))
        }
        assertThrows(OverlayXml.FormatException::class.java) {
            OverlayXml.read(ByteArrayInputStream("not xml".toByteArray()))
        }
    }

    @Test fun unknownGroupIsDropped() {
        val xml = """<overlay name="x"><button label="a"><keys><key name="a"/></keys></button>
            <button label="b" group="missing"><keyboard/></button></overlay>"""
        val l = OverlayXml.read(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(2, l.buttons.size)
        assertEquals(null, l.buttons[1].group)
    }

    @Test fun olderReadersSkipJoysticks() {
        // Joysticks are their own element, unknown elements are ignored
        val xml = """<overlay name="x"><button label="a"><keys><key name="a"/></keys></button>
            <slider label="future"/></overlay>"""
        assertEquals(1, OverlayXml.read(ByteArrayInputStream(xml.toByteArray())).buttons.size)
        val bad = """<overlay name="x"><joystick label="j"><direction key="w"/></joystick></overlay>"""
        assertThrows(OverlayXml.FormatException::class.java) {
            OverlayXml.read(ByteArrayInputStream(bad.toByteArray()))
        }
    }

    @Test fun stickDirections() {
        val wasd = ButtonAction.Joystick.WASD // up, right, down, left
        assertEquals(setOf(0), OverlayView.stickDirections(wasd, 10f))
        assertEquals(setOf(0, 3), OverlayView.stickDirections(wasd, -45f))
        assertEquals(setOf(0, 3), OverlayView.stickDirections(wasd, 315f))
        assertEquals(setOf(1, 2), OverlayView.stickDirections(wasd, 130f))
        assertEquals(setOf(2), OverlayView.stickDirections(wasd, 180f))
        val single = wasd.copy(multi = false)
        assertEquals(setOf(3), OverlayView.stickDirections(single, -50f))
        assertEquals(setOf(0), OverlayView.stickDirections(single, -40f))
        val scroll = ButtonAction.Joystick.SCROLL
        assertEquals(setOf(1), OverlayView.stickDirections(scroll, 170f))
        assertEquals(setOf(0), OverlayView.stickDirections(scroll, 80f))
        // Two directions, multi: sideways holds neither
        assertEquals(emptySet<Int>(), OverlayView.stickDirections(scroll.copy(multi = true), 90f))
        // Rotated: first direction points right
        val turned = wasd.copy(rotation = 90)
        assertEquals(setOf(0), OverlayView.stickDirections(turned, 90f))
    }
}
