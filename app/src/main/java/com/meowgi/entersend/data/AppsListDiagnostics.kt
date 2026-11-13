package com.meowgi.entersend.data

/**
 * Visible Apps-page diagnostic line + Diagnostics log payload.
 * Format is fixed so field reports stay greppable:
 * `scan=<n> launcher=<n> system=<n> cached=<n> shown=<n> err=<…>`
 */
data class AppsListDiagnostics(
    val scan: Int = 0,
    val launcher: Int = 0,
    val system: Int = 0,
    val cached: Int = 0,
    val shown: Int = 0,
    val err: String = "none",
    val source: String = "",
) {
    fun line(): String =
        "scan=$scan launcher=$launcher system=$system cached=$cached shown=$shown err=$err"

    companion object {
        const val LOG_PKG = "apps_list"
        const val LOG_SOURCE = "apps_diag"

        fun errOf(t: Throwable?): String {
            if (t == null) return "none"
            val msg = (t.message ?: "").replace('\n', ' ').take(120)
            return t.javaClass.simpleName + if (msg.isEmpty()) "" else ":$msg"
        }
    }
}
