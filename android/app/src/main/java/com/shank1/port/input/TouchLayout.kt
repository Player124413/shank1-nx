package com.shank1.port.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One touch control: a stick, a button, a trigger or a d-pad.
 *
 * Positions are fractions of the *safe drawing area* (the window minus the display cutout and the system bars), and
 * sizes are fractions of its height, so one layout fits every resolution and aspect ratio: [x] runs 0..1 left to right,
 * [y] 0..1 top to bottom, [r] is the radius in heights. A circle therefore stays a circle on a tablet, and a layout
 * made on a phone stays usable on a foldable.
 *
 * The editor (TouchControlsView's edit mode) writes x, y and r; everything else is what the player changes in the
 * controls screen.
 */
data class Element(
    var id: String,
    var kind: Kind,
    /** Which game input this drives: "ls", "rs", "dpad", "a", "b", "x", "y", "lb", "rb", "lt", "rt", "start", "back",
     *  "l3", "r3". See [Outputs]. */
    var output: String,
    var x: Float,
    var y: Float,
    var r: Float,
    /** Half-height of a trigger's bar and half-width of its thickness, in heights; 0 for the round kinds. */
    var h: Float = 0f,
    /** Caption drawn under the glyph: "Jump", "Light"... empty for none. */
    var caption: String = "",
    var alpha: Float = 0.55f,
    /** Fraction of the stick's radius where movement starts. */
    var deadzone: Float = 0.14f,
    /** Analog response: 1 is linear, above that is finer near the centre. */
    var curve: Float = 1.35f,
    /** A stick whose centre follows the thumb, or a fixed one that recentres on press. */
    var follow: Boolean = true,
    /** A button that repeats while held (Shank's dash/grapple can want it). */
    var turbo: Boolean = false,
    var visible: Boolean = true,
) {
    enum class Kind { STICK, BUTTON, TRIGGER, DPAD }

    /** The label drawn in the circle. */
    val glyph: String
        get() = Outputs.glyph(output)

    val isRound: Boolean get() = kind == Kind.STICK || kind == Kind.BUTTON || kind == Kind.DPAD

    /** A copy under a new unique id: the same control added a second time. */
    fun duplicate(idSuffix: String = "") = copy(id = if (idSuffix.isEmpty()) id else id + idSuffix)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name.lowercase())
        put("output", output)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("r", r.toDouble())
        if (h != 0f) put("h", h.toDouble())
        if (caption.isNotEmpty()) put("caption", caption)
        put("alpha", alpha.toDouble())
        if (kind == Kind.STICK) {
            put("deadzone", deadzone.toDouble())
            put("curve", curve.toDouble())
            put("follow", follow)
        }
        if (turbo) put("turbo", true)
        if (!visible) put("visible", false)
    }

    companion object {
        fun fromJson(json: JSONObject): Element {
            val kind = when (json.optString("kind", "button").lowercase()) {
                "stick" -> Kind.STICK
                "trigger" -> Kind.TRIGGER
                "dpad" -> Kind.DPAD
                else -> Kind.BUTTON
            }
            return Element(
                id = json.optString("id", "el" + json.optString("output", "a")),
                kind = kind,
                output = json.optString("output", "a"),
                x = json.optDouble("x", 0.5).toFloat(),
                y = json.optDouble("y", 0.5).toFloat(),
                r = json.optDouble("r", 0.07).toFloat(),
                h = json.optDouble("h", 0.0).toFloat(),
                caption = json.optString("caption", ""),
                alpha = json.optDouble("alpha", 0.55).toFloat(),
                deadzone = json.optDouble("deadzone", 0.14).toFloat(),
                curve = json.optDouble("curve", 1.35).toFloat(),
                follow = json.optBoolean("follow", true),
                turbo = json.optBoolean("turbo", false),
                visible = json.optBoolean("visible", true),
            )
        }
    }
}

/** A whole layout: the elements, their name, and where they came from. */
data class TouchLayout(
    var name: String,
    var elements: MutableList<Element>,
    /** Where the file lives once saved; null for a bundled default. */
    var file: File? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", VERSION)
        put("name", name)
        put("elements", JSONArray().also { array -> elements.forEach { array.put(it.toJson()) } })
    }.toString(2)

    fun renamed(newName: String) = TouchLayout(newName, elements.map { it.copy() }.toMutableList())

    fun find(id: String): Element? = elements.firstOrNull { it.id == id }

    /** A new element of [kind] with a free id, in the middle of the screen. */
    fun add(kind: Element.Kind, output: String): Element {
        var index = 1
        while (elements.any { it.id == "new$index" }) index++
        val element = Element(
            id = "new$index",
            kind = kind,
            output = output,
            x = 0.5f,
            y = 0.5f,
            r = if (kind == Element.Kind.TRIGGER) 0.05f else 0.07f,
            h = if (kind == Element.Kind.TRIGGER) 0.14f else 0f,
        )
        elements.add(element)
        return element
    }

    fun remove(element: Element) {
        elements.removeAll { it === element || it.id == element.id }
    }

    fun undoSnapshot(): String = toJson()

    companion object {
        const val VERSION = 1

        fun fromJson(text: String, file: File? = null): TouchLayout {
            val json = JSONObject(text)
            val array = json.optJSONArray("elements") ?: JSONArray()
            val elements = ArrayList<Element>(array.length())
            for (i in 0 until array.length()) elements.add(Element.fromJson(array.getJSONObject(i)))
            return TouchLayout(json.optString("name", "Layout"), elements, file)
        }

        /** The built-in landscape layout: the Xbox 360 pad by position, as the console port maps it
         *  (assets/touch/default_landscape.json is the same list; that file wins when it is in the APK). */
        fun defaultLandscape() = TouchLayout(
            name = "Default",
            elements = mutableListOf(
                Element("stick_left", Element.Kind.STICK, "ls", 0.135f, 0.750f, 0.115f, alpha = 0.50f),
                Element("dpad", Element.Kind.DPAD, "dpad", 0.305f, 0.640f, 0.072f, alpha = 0.40f),
                Element("trig_lt", Element.Kind.TRIGGER, "lt", 0.038f, 0.330f, 0.048f, h = 0.105f),
                Element("trig_rt", Element.Kind.TRIGGER, "rt", 0.962f, 0.330f, 0.048f, h = 0.105f),
                Element("btn_lb", Element.Kind.BUTTON, "lb", 0.095f, 0.180f, 0.050f, alpha = 0.45f),
                Element("btn_rb", Element.Kind.BUTTON, "rb", 0.905f, 0.180f, 0.050f, alpha = 0.45f),
                Element("btn_x", Element.Kind.BUTTON, "x", 0.775f, 0.680f, 0.060f),
                Element("btn_y", Element.Kind.BUTTON, "y", 0.860f, 0.560f, 0.060f),
                Element("btn_a", Element.Kind.BUTTON, "a", 0.860f, 0.790f, 0.070f),
                Element("btn_b", Element.Kind.BUTTON, "b", 0.945f, 0.675f, 0.060f),
                Element("btn_back", Element.Kind.BUTTON, "back", 0.430f, 0.075f, 0.030f, alpha = 0.40f),
                Element("btn_start", Element.Kind.BUTTON, "start", 0.505f, 0.075f, 0.034f, alpha = 0.40f),
            ),
        )

        /** Portrait: the same controls with room for two thumbs under the picture (a tablet held upright). */
        fun defaultPortrait() = TouchLayout(
            name = "Default",
            elements = mutableListOf(
                Element("stick_left", Element.Kind.STICK, "ls", 0.200f, 0.810f, 0.100f, alpha = 0.50f),
                Element("dpad", Element.Kind.DPAD, "dpad", 0.075f, 0.680f, 0.058f, alpha = 0.40f),
                Element("trig_lt", Element.Kind.TRIGGER, "lt", 0.030f, 0.520f, 0.040f, h = 0.080f),
                Element("trig_rt", Element.Kind.TRIGGER, "rt", 0.970f, 0.520f, 0.040f, h = 0.080f),
                Element("btn_lb", Element.Kind.BUTTON, "lb", 0.060f, 0.440f, 0.042f, alpha = 0.45f),
                Element("btn_rb", Element.Kind.BUTTON, "rb", 0.940f, 0.440f, 0.042f, alpha = 0.45f),
                Element("btn_x", Element.Kind.BUTTON, "x", 0.700f, 0.760f, 0.050f),
                Element("btn_y", Element.Kind.BUTTON, "y", 0.800f, 0.680f, 0.050f),
                Element("btn_a", Element.Kind.BUTTON, "a", 0.800f, 0.840f, 0.058f),
                Element("btn_b", Element.Kind.BUTTON, "b", 0.900f, 0.760f, 0.050f),
                Element("btn_start", Element.Kind.BUTTON, "start", 0.505f, 0.940f, 0.028f, alpha = 0.40f),
            ),
        )
    }
}

/** What each element can drive, and the glyph drawn for it. */
object Outputs {
    val BUTTONS = listOf("a", "b", "x", "y", "lb", "rb", "lt", "rt", "start", "back", "l3", "r3", "dpad")
    val STICKS = listOf("ls", "rs")

    fun glyph(output: String): String = when (output) {
        "a", "b", "x", "y" -> output.uppercase()
        "lb" -> "LB"
        "rb" -> "RB"
        "lt" -> "LT"
        "rt" -> "RT"
        "start" -> "≡"
        "back" -> "⊂"
        "l3" -> "L3"
        "r3" -> "R3"
        "dpad" -> "✛"
        "ls" -> ""
        "rs" -> ""
        else -> output.uppercase()
    }

    fun displayName(output: String): String = when (output) {
        "ls" -> "Left stick"
        "rs" -> "Right stick"
        "dpad" -> "D-pad"
        "a" -> "A"
        "b" -> "B"
        "x" -> "X"
        "y" -> "Y"
        "lb" -> "Left bumper"
        "rb" -> "Right bumper"
        "lt" -> "Left trigger"
        "rt" -> "Right trigger"
        "start" -> "Start"
        "back" -> "Back"
        "l3" -> "Left stick click"
        "r3" -> "Right stick click"
        else -> output
    }
}

/** Reads and writes layouts: bundled defaults from the APK's assets, the player's own in filesDir/touch. */
class LayoutStore(private val context: Context) {

    private val folder: File get() = File(context.filesDir, "touch").apply { mkdirs() }

    fun bundled(landscape: Boolean): TouchLayout = try {
        val name = if (landscape) "touch/default_landscape.json" else "touch/default_portrait.json"
        val text = context.assets.open(name).bufferedReader().use { it.readText() }
        TouchLayout.fromJson(text)
    } catch (e: Exception) {
        // The assets are the truth; a missing one is a build problem, not something to hide, but the game must still
        // start with usable controls.
        if (landscape) TouchLayout.defaultLandscape() else TouchLayout.defaultPortrait()
    }

    /** The saved layout for this orientation, or the bundled default. */
    fun load(landscape: Boolean, profile: String? = null): TouchLayout {
        val name = profile ?: (if (landscape) "landscape" else "portrait")
        val file = File(folder, "$name.json")
        if (!file.isFile) return bundled(landscape)
        return try {
            TouchLayout.fromJson(file.readText(), file)
        } catch (e: Exception) {
            bundled(landscape)
        }
    }

    fun save(layout: TouchLayout, name: String = if (layout.name == "Default") "" else layout.name): File {
        val fileName = if (name.isEmpty()) "custom" else name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        val file = File(folder, "$fileName.json")
        file.writeText(layout.toJson())
        layout.file = file
        return file
    }

    fun list(): List<File> = folder.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }
        ?: emptyList()

    /** Removes every saved layout, both orientations and any named one: the defaults come back. */
    fun resetAll() {
        folder.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { it.delete() }
    }

    fun reset(landscape: Boolean) {
        File(folder, (if (landscape) "landscape" else "portrait") + ".json").delete()
        File(folder, "custom.json").delete()
    }
}
