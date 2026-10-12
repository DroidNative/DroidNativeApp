# On-screen buttons

Transparent buttons and joysticks over a Linux window that send keys,
mouse buttons and wheel notches, for games and programs without touch
support. Layouts are
managed on the **Buttons** tab (`ui/ButtonsScreen.kt`), edited in
`overlay/OverlayEditorActivity.kt`, and chosen per application in its
per-app settings ("On-screen buttons").

## Behaviour in a window

`overlay/OverlayView.kt` draws the buttons and gets every touch event of
the window first (`WindowActivity.handleTouch`).

- A finger counts as a circle of 10 dp radius, not a point: every visible
  button it overlaps is pressed. A finger between two adjacent buttons
  holds both (run and jump at once); sliding over buttons presses and
  releases them like on a gamepad.
- A finger that starts on a button stays with the buttons until it lifts.
  All other fingers go to the window as a gesture of their own (the event
  is split, `OverlayView.subset`), so the game can be used while a button
  is held.
- A button stays pressed while any finger covers it. Keys held by several
  buttons are pressed once and released with the last one; modifier keys
  set the modifier state sent with every key.
- Mode buttons show or hide a group. Hidden buttons are neither drawn nor
  pressable; buttons hidden while held are released.
- The keyboard button shows or hides the on-screen keyboard (useful with
  "Open keyboard automatically" turned off).

Joysticks (round, `OverlayView.moveStick`):

- A joystick has 2 to 8 directions, evenly spaced clockwise from its
  first direction. Each sends a key (held), a wheel direction (repeated
  every 400 ms just past the centre, down to every 50 ms at the rim) or
  nothing. The inner 30 % of the radius does nothing.
- One at a time: only the nearest direction is held. Several at once:
  every direction closer than three quarters of the spacing (at most
  67.5 degrees), so a 4-way stick held between up and left holds both, like
  an 8-way stick (`OverlayView.stickDirections`).
- A finger that starts on a joystick controls only that stick until it
  lifts, also outside the circle; it never presses buttons it slides over.
- Presets: W A S D, arrow keys (both 4-way, several at once) and scroll
  wheel (up and down, one at a time).

Keys are physical key positions (evdev codes, `overlay/Keys.kt`): with a
German keymap the key named "z" types a y, as on a real keyboard.

## Editor

The canvas is the screen without system bars and cutout, the area a
window gets, so positions match. "+ Add" places a button or a joystick
preset. Tap selects an element, drag moves it, pinch resizes it, double
tap opens its properties (label, size, action or directions, group);
swipe the panel down to close it. Key fields are searchable: type into
them to filter (by label, key name or words such as "arrow" or
"escape"). "Groups" adds, renames and deletes groups and sets whether
each is visible at start; buttons of groups hidden at start are drawn
dimmed. "Layout" sets the name and the button opacity. The layout is
saved when the editor is left.

## File format

Layouts are stored in the app's `files/overlays/<id>.xml`; export and
import use the same format (`overlay/Overlay.kt`, `OverlayXml`):

```xml
<overlay version="1" id="1a2b3c4d" name="Game" opacity="0.4000">
  <group id="g1" name="Movement" visible="true"/>
  <button id="b1" label="Jump" x="0.8500" y="0.8000" width="72" height="72" group="g1">
    <keys><key name="space"/></keys>
  </button>
  <button id="b2" label="Save" x="0.1" y="0.1" width="64" height="48">
    <keys><key name="leftctrl"/><key name="s"/></keys>
  </button>
  <button id="b3" label="RMB" x="0.1" y="0.3" width="64" height="64"><mouse button="right"/></button>
  <button id="b4" label="Up" x="0.9" y="0.3" width="48" height="48"><wheel direction="up"/></button>
  <button id="b5" label="Kbd" x="0.5" y="0.05" width="64" height="40"><keyboard/></button>
  <button id="b6" label="Mode" x="0.95" y="0.05" width="48" height="40"><toggle group="g1"/></button>
  <joystick id="j1" label="" x="0.15" y="0.75" width="140" height="140" multi="true" rotation="0">
    <direction key="w"/><direction key="d"/><direction key="s"/><direction key="a"/>
  </joystick>
  <joystick id="j2" label="" x="0.9" y="0.6" width="96" height="96" multi="false" rotation="0">
    <direction wheel="up"/><direction wheel="down"/>
  </joystick>
</overlay>
```

- `x`, `y`: centre of the button as a fraction of the window (0 to 1), so
  a layout fits any window size and orientation.
- `width`, `height`: size in dp (16 to 400).
- `opacity`: 0.1 to 1.
- Key names: `a` to `z`, `0` to `9`, `f1` to `f12`, `esc`, `tab`, `enter`,
  `space`, `backspace`, `delete`, `insert`, `up`, `down`, `left`, `right`,
  `home`, `end`, `pageup`, `pagedown`, `leftshift`, `leftctrl`, `leftalt`,
  `leftmeta`, `rightalt`, `minus`, `equal`, `leftbrace`, `rightbrace`,
  `semicolon`, `apostrophe`, `grave`, `backslash`, `comma`, `dot`,
  `slash`, `capslock`, `print`, `pause`.
- Mouse buttons: `left`, `right`, `middle`.
- `joystick`: `width` equals `height` (the diameter); `rotation` is the
  first direction in degrees (0 up, 90 right); 2 to 8 `direction`
  children in clockwise order, each with `key`, `wheel` (`up`, `down`) or
  nothing. Joysticks are separate elements and unknown elements are
  ignored, so older versions load such a layout without its joysticks.

An imported layout whose id already exists gets a new id.
