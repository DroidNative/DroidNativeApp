package app.droidnative.overlay

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream

/** Button layouts, one XML file each in the app's files/overlays. */
class OverlayStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "overlays")

    fun list(): List<OverlayLayout> =
        (dir.listFiles { f -> f.name.endsWith(".xml") } ?: emptyArray())
            .mapNotNull { f ->
                try {
                    f.inputStream().use { OverlayXml.read(it) }
                } catch (e: Exception) {
                    Log.w("dn-overlay", "unreadable layout ${f.name}: ${e.message}")
                    null
                }
            }
            .sortedBy { it.name.lowercase() }

    fun get(id: String): OverlayLayout? = file(id).takeIf { it.isFile }?.let { f ->
        runCatching { f.inputStream().use { OverlayXml.read(it) } }.getOrNull()
    }

    fun save(layout: OverlayLayout) {
        dir.mkdirs()
        val tmp = File(dir, "${layout.id}.xml.tmp")
        tmp.outputStream().use { OverlayXml.write(layout, it) }
        tmp.renameTo(file(layout.id))
    }

    fun delete(id: String) {
        file(id).delete()
    }

    /** Stores a layout from a file; a layout with the same id gets a new one. */
    @Throws(OverlayXml.FormatException::class)
    fun import(input: InputStream): OverlayLayout {
        var layout = OverlayXml.read(input)
        if (file(layout.id).exists()) layout = layout.copy(id = newId())
        save(layout)
        return layout
    }

    private fun file(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' }}.xml")
}
