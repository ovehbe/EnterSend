package com.meowgi.entersend.core

object AppLists {
    /** Messengers whose compose field counts as chat-like even without other signals. */
    val CHAT = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.viber.voip", "im.vector.app", "jp.naver.line.android", "com.snapchat.android",
        "com.kakao.talk", "ch.threema.app", "chat.simplex.app", "com.discord", "com.Slack",
        "com.microsoft.teams", "com.skype.raider", "org.briarproject.briar.android",
    )

    /** Apps where physical Enter already sends; EnterSend passes Enter through unless overridden. */
    val NATIVE_ENTER = setOf("com.discord", "com.Slack", "com.microsoft.teams")

    /** Browsers, notes and email apps. EnterSend never acts in them, even with an override. */
    val NEVER = setOf(
        // browsers
        "com.android.chrome", "org.chromium.chrome", "org.mozilla.firefox", "org.mozilla.fenix",
        "org.mozilla.focus", "com.brave.browser", "com.opera.browser", "com.opera.mini.native",
        "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "org.mozilla.fennec_fdroid",
        "com.android.browser",
        // notes
        "com.google.android.keep", "com.samsung.android.app.notes", "com.microsoft.office.onenote",
        "com.evernote", "md.obsidian", "net.cozic.joplin", "com.automattic.simplenote",
        "notion.id", "org.fossify.notes", "com.simplemobiletools.notes.pro", "com.orgzly",
        "com.standardnotes",
        // email
        "com.google.android.gm", "com.microsoft.office.outlook", "com.fsck.k9",
        "net.thunderbird.android", "com.samsung.android.email.provider", "ch.protonmail.android",
        "com.yahoo.mobile.client.android.mail", "eu.faircode.email", "com.android.email",
        "me.proton.android.mail", "com.readdle.spark",
    )
}
