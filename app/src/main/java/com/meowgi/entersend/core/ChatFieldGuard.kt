package com.meowgi.entersend.core

import android.text.InputType
import com.meowgi.entersend.data.AppRule
import java.util.Locale

/** Decides whether a focused field is a chat compose box. Unsure means no. */
object ChatFieldGuard {
    private val BLOCKED_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_FILTER,
        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
    )

    private val NEGATIVE_PARTS = listOf(
        "search", "arama", "omnibox", "url", "address", "query", "find", "filter",
        "subject", "password", "passwd", "şifre", "sifre", "parola",
    )
    private val NEGATIVE_WORDS = setOf("ara", "bul", "konu")

    private val POSITIVE_PARTS = listOf(
        "message", "mesaj", "chat", "sohbet", "compose", "reply", "yanıt", "yanit", "entry",
    )

    fun isChatLike(field: Field, rule: AppRule): Boolean {
        if (field.packageName in AppLists.NEVER) return false
        if (field.isPassword) return false

        val type = field.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (type != 0 && cls != InputType.TYPE_CLASS_TEXT) return false
        if (cls == InputType.TYPE_CLASS_TEXT && variation in BLOCKED_VARIATIONS) return false

        val words = listOfNotNull(field.viewId?.substringAfter(":id/"), field.hint)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        if (NEGATIVE_PARTS.any { it in words }) return false
        if (words.split(Regex("[^\\p{L}\\p{N}]+")).any { it in NEGATIVE_WORDS }) return false

        if (rule.isTaught && (rule.fieldViewId == null || rule.fieldViewId == field.viewId)) return true
        if (field.packageName in AppLists.CHAT) return true
        if (cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE) return true
        return POSITIVE_PARTS.any { it in words }
    }
}
