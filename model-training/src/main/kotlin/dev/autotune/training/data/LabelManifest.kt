package dev.autotune.training.data

import dev.autotune.core.ml.AudioClass
import java.io.File
import java.util.Locale

/**
 * One labelled span of one audio file.
 *
 * [start] and [end] are seconds; an end of [Float.MAX_VALUE] means "to the end
 * of the file".
 */
data class LabelEntry(
    val file: File,
    val start: Float,
    val end: Float,
    val label: AudioClass,
    val source: String,
    val confidence: Float = Float.NaN,
) {
    val durationSeconds: Float get() = if (end == Float.MAX_VALUE) Float.MAX_VALUE else end - start
}

/**
 * The label file format: a comment-friendly CSV that a person can actually edit.
 *
 * ```
 * # file, start, end, label, source, confidence
 * ary/humsafar-ep1.wav, 12.0, 27.5, speech, ary, 0.98
 * ary/humsafar-ep1.wav, 27.5, 33.0, music,  ary, 0.71
 * ```
 *
 * Paths are resolved relative to the manifest. Blank `start`/`end` means the
 * whole file, which is the natural form when clips have been cut by hand.
 */
object LabelManifest {

    const val HEADER = "# file, start, end, label, source, confidence"

    fun read(manifest: File): List<LabelEntry> {
        require(manifest.isFile) { "manifest not found: $manifest" }
        val root = manifest.absoluteFile.parentFile
        val entries = mutableListOf<LabelEntry>()

        manifest.readLines().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val fields = line.split(',').map { it.trim() }
            // Tolerate a header row written without the leading '#'.
            if (fields[0].equals("file", ignoreCase = true)) return@forEachIndexed

            require(fields.size >= 4) {
                "${manifest.name}:${index + 1}: expected at least 'file, start, end, label', got '$raw'"
            }
            val label = parseLabel(fields[3])
                ?: error("${manifest.name}:${index + 1}: unknown label '${fields[3]}' (expected speech, music or effects)")

            entries += LabelEntry(
                file = File(fields[0]).let { if (it.isAbsolute) it else File(root, fields[0]) },
                start = fields[1].toFloatOrNull() ?: 0f,
                end = fields[2].toFloatOrNull() ?: Float.MAX_VALUE,
                label = label,
                source = fields.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: "unknown",
                confidence = fields.getOrNull(5)?.toFloatOrNull() ?: Float.NaN,
            )
        }
        return entries
    }

    fun write(manifest: File, entries: List<LabelEntry>) {
        val root = manifest.absoluteFile.parentFile
        manifest.parentFile?.mkdirs()
        manifest.bufferedWriter().use { writer ->
            writer.appendLine(HEADER)
            for (entry in entries) {
                val path = entry.file.absoluteFile.relativeToOrNull(root)?.path ?: entry.file.path
                val end = if (entry.end == Float.MAX_VALUE) "" else format(entry.end)
                val confidence = if (entry.confidence.isNaN()) "" else "%.3f".format(Locale.ROOT, entry.confidence)
                writer.appendLine(
                    "$path, ${format(entry.start)}, $end, ${entry.label.name.lowercase()}, ${entry.source}, $confidence",
                )
            }
        }
    }

    /**
     * Reads the directory-per-label layout, which is what you get by simply
     * dropping hand-cut clips into folders:
     *
     * ```
     * data/speech/     data/music/     data/effects/
     * ```
     *
     * The folder above the label becomes the source tag, so a file in
     * `data/ary/speech/` is tagged `ary`.
     */
    fun fromDirectoryLayout(root: File): List<LabelEntry> {
        require(root.isDirectory) { "not a directory: $root" }
        val entries = mutableListOf<LabelEntry>()
        root.walkTopDown().filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }.forEach { file ->
            val label = parseLabel(file.parentFile?.name ?: return@forEach) ?: return@forEach
            val source = file.parentFile?.parentFile
                ?.takeIf { it.absolutePath != root.absolutePath }
                ?.name ?: root.name
            entries += LabelEntry(file, 0f, Float.MAX_VALUE, label, source)
        }
        return entries
    }

    private fun parseLabel(text: String): AudioClass? = when (text.trim().lowercase()) {
        "speech", "dialogue", "voice", "talk" -> AudioClass.SPEECH
        "music", "score", "song", "ost" -> AudioClass.MUSIC
        "effects", "effect", "sfx", "ambience", "ambient", "noise" -> AudioClass.EFFECTS
        else -> null
    }

    private fun format(value: Float): String = "%.2f".format(Locale.ROOT, value)
}
