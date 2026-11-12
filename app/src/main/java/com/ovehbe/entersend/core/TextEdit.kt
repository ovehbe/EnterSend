package com.ovehbe.entersend.core

/** Inserts "\n" at the cursor, replacing the selection. Returns the new text and cursor. */
fun insertNewline(text: String, selStart: Int, selEnd: Int): Pair<String, Int> {
    val a = if (selStart in 0..text.length) selStart else text.length
    val b = if (selEnd in 0..text.length) selEnd else a
    val start = minOf(a, b)
    val end = maxOf(a, b)
    return (text.substring(0, start) + "\n" + text.substring(end)) to start + 1
}
