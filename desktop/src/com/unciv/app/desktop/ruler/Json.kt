package com.unciv.app.desktop.ruler

/** Minimal JSON writer for maps, lists and primitives - all the API responses need */
object Json {
    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { write(it.key.toString()) + ":" + write(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]") { write(it) }
        is Sequence<*> -> write(v.toList())
        else -> write(v.toString())
    }
}
