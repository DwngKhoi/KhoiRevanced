package dev.khoirevanced.runtime.agent

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The class names a dex file defines, read straight from its own tables.
 *
 * ## Why this is needed
 *
 * The payload and the host both ship `com.google.protobuf`, and a patch that
 * parses a message with the payload's generated class while the host's
 * `ExtensionRegistryLite` is what gets loaded produces this, once per response:
 *
 *     java.lang.NoSuchMethodError: No static method getEmptyRegistry() in class
 *       Lcom/google/protobuf/ExtensionRegistryLite; (declaration of
 *       'com.google.protobuf.ExtensionRegistryLite' appears in
 *       /data/app/.../com.google.android.youtube-.../base.apk!classes3.dex)
 *
 * Every other symptom of that clash looks like a different bug -- a feed that
 * reports "An error occurred", a thumbnail that never loads -- and each was
 * chased separately before the class resolution was understood. Reading the
 * tables gives the whole overlap instead, so the fix is chosen from evidence
 * rather than from whichever symptom happened to surface.
 *
 * Only the three tables that name types are read: the header, `string_ids`,
 * `type_ids` and `class_defs`. The format is fixed and versioned by the magic
 * alone, so there is nothing to fall back on and nothing to get wrong
 * silently -- an unexpected magic throws.
 */
internal object DexClassNames {

    private const val HEADER_STRING_IDS_SIZE = 0x38
    private const val HEADER_STRING_IDS_OFF = 0x3C
    private const val HEADER_TYPE_IDS_SIZE = 0x40
    private const val HEADER_TYPE_IDS_OFF = 0x44
    private const val HEADER_CLASS_DEFS_SIZE = 0x60
    private const val HEADER_CLASS_DEFS_OFF = 0x64
    private const val CLASS_DEF_SIZE = 32

    /** `Lcom/google/protobuf/ExtensionRegistryLite;` becomes `com.google.protobuf`. */
    fun packagePrefix(descriptor: String): String {
        val end = descriptor.indexOfLast { it == ';' || it == '[' }
        if (end < 0) return ""
        val withoutArrayMarkers = descriptor.dropLast(descriptor.length - end)
        val afterL = withoutArrayMarkers.removePrefix("L")
        val lastDot = afterL.lastIndexOf('/')
        if (lastDot < 0) return ""
        return afterL.substring(0, lastDot).replace('/', '.')
    }

    /**
     * Every class the given dex defines, as binary names.
     *
     * A dex that cannot be read is skipped rather than failing the run: the
     * caller uses this for a diagnostic, and a diagnostic must not be able to
     * stop a process from being patched.
     */
    fun of(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        return runCatching { read(file) }.getOrElse { error ->
            RuntimeLog.warn(
                "classnames",
                "could not read ${file.name}: ${error.javaClass.simpleName}: ${error.message}",
            )
            emptySet()
        }
    }

    private fun read(file: File): Set<String> {
        val raw = file.readBytes()
        val bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        // "dex\n" + a three digit version + NUL. Checked by hand rather than
        // parsed, because the only thing needed from it is to refuse a file that
        // is not a dex, and every table offset below is fixed.
        val magic = String(raw, 0, 4, Charsets.ISO_8859_1)
        require(magic == "dex\n") { "$file is not a dex file (magic=$magic)" }
        val version = raw[4].toInt() - '0'.code
        val minor = raw[5].toInt() - '0'.code
        val patch = raw[6].toInt() - '0'.code
        require(raw[7].toInt() == 0 && version in 0..9 && minor in 0..9 && patch in 0..9) {
            "$file has an unrecognised dex version ${raw[4]}.${raw[5]}.${raw[6]}"
        }

        fun section(sizeOffset: Int, offOffset: Int): Pair<Int, Int> {
            bytes.position(sizeOffset)
            val size = bytes.int
            val offset = bytes.getInt(offOffset)
            return size to offset
        }

        val (stringCount, stringOffset) = section(HEADER_STRING_IDS_SIZE, HEADER_STRING_IDS_OFF)
        val (typeCount, typeOffset) = section(HEADER_TYPE_IDS_SIZE, HEADER_TYPE_IDS_OFF)
        val (classCount, classOffset) = section(HEADER_CLASS_DEFS_SIZE, HEADER_CLASS_DEFS_OFF)
        if (stringCount == 0 || typeCount == 0 || classCount == 0) return emptySet()

        fun readString(index: Int): String {
            bytes.position(stringOffset + index * 4)
            val dataOffset = bytes.int
            // uleb128 utf16_size, then MUTF-8 bytes terminated by NUL.
            var cursor = dataOffset
            while ((raw[cursor].toInt() and 0x80) != 0) cursor++
            cursor++
            val start = cursor
            while (raw[cursor].toInt() != 0) cursor++
            val text = String(raw, start, cursor - start, Charsets.ISO_8859_1)
            return if (text.startsWith("L") && text.endsWith(";")) {
                text.substring(1, text.length - 1).replace('/', '.')
            } else {
                text
            }
        }

        // A class_def's first word is an index into type_ids, not into string_ids,
        // and type_ids in turn holds an index into string_ids. Reading the first
        // word as a string index yields plausible-looking but wrong names, and the
        // symptom is a package that is quietly reported as absent -- which is how
        // this read 0 protobuf classes out of a dex that defines 534 of them.
        fun typeDescriptor(typeIndex: Int): String {
            bytes.position(typeOffset + typeIndex * 4)
            return readString(bytes.int)
        }

        val names = HashSet<String>(classCount * 2)
        for (i in 0 until classCount) {
            bytes.position(classOffset + i * CLASS_DEF_SIZE)
            names += typeDescriptor(bytes.int)
        }
        return names
    }
}
