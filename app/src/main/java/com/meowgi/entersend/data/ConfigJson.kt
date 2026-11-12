package com.meowgi.entersend.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Versioned JSON export/import of EnterSend config (rules + crop PNGs + profiles).
 * schemaVersion must be bumped when the wire format changes.
 */
object ConfigJson {
    const val SCHEMA_VERSION = 1

    data class ProfileSnapshot(
        val id: String,
        val name: String,
        val rules: List<RuleSnapshot>,
    )

    data class RuleSnapshot(
        val rule: AppRule,
        /** PNG bytes for the tap-spot crop, if any. */
        val cropPng: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RuleSnapshot) return false
            return rule == other.rule && cropPng.contentEquals(other.cropPng)
        }

        override fun hashCode(): Int = 31 * rule.hashCode() + (cropPng?.contentHashCode() ?: 0)
    }

    data class Bundle(
        val schemaVersion: Int,
        val activeProfileId: String,
        val profiles: List<ProfileSnapshot>,
    )

    class FormatException(message: String) : Exception(message)

    fun write(bundle: Bundle, out: OutputStream) {
        val root = JSONObject()
        root.put("schemaVersion", bundle.schemaVersion)
        root.put("activeProfileId", bundle.activeProfileId)
        val profiles = JSONArray()
        for (p in bundle.profiles) {
            val po = JSONObject()
            po.put("id", p.id)
            po.put("name", p.name)
            val rules = JSONArray()
            for (r in p.rules) {
                rules.put(ruleToJson(r))
            }
            po.put("rules", rules)
            profiles.put(po)
        }
        root.put("profiles", profiles)
        out.write(root.toString(2).toByteArray(Charsets.UTF_8))
    }

    fun read(input: InputStream): Bundle {
        val text = input.bufferedReader(Charsets.UTF_8).readText()
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw FormatException("Not valid JSON: ${e.message}")
        }
        val version = root.optInt("schemaVersion", -1)
        if (version != SCHEMA_VERSION) {
            throw FormatException("Unsupported schemaVersion $version (expected $SCHEMA_VERSION)")
        }
        val active = root.optString("activeProfileId", "")
        if (active.isEmpty()) throw FormatException("Missing activeProfileId")
        val arr = root.optJSONArray("profiles") ?: throw FormatException("Missing profiles")
        if (arr.length() == 0) throw FormatException("No profiles in file")
        val profiles = ArrayList<ProfileSnapshot>(arr.length())
        for (i in 0 until arr.length()) {
            val po = arr.getJSONObject(i)
            val id = po.optString("id")
            val name = po.optString("name")
            if (id.isEmpty() || name.isEmpty()) throw FormatException("Profile missing id/name")
            val rulesArr = po.optJSONArray("rules") ?: JSONArray()
            val rules = ArrayList<RuleSnapshot>(rulesArr.length())
            for (j in 0 until rulesArr.length()) {
                rules.add(ruleFromJson(rulesArr.getJSONObject(j)))
            }
            profiles.add(ProfileSnapshot(id, name, rules))
        }
        if (profiles.none { it.id == active }) {
            throw FormatException("activeProfileId '$active' not in profiles")
        }
        return Bundle(version, active, profiles)
    }

    fun profileToJson(profile: ProfileSnapshot): JSONObject {
        val po = JSONObject()
        po.put("id", profile.id)
        po.put("name", profile.name)
        val rules = JSONArray()
        for (r in profile.rules) rules.put(ruleToJson(r))
        po.put("rules", rules)
        return po
    }

    fun profileFromJson(po: JSONObject): ProfileSnapshot {
        val id = po.optString("id")
        val name = po.optString("name")
        if (id.isEmpty() || name.isEmpty()) throw FormatException("Profile missing id/name")
        val rulesArr = po.optJSONArray("rules") ?: JSONArray()
        val rules = ArrayList<RuleSnapshot>(rulesArr.length())
        for (j in 0 until rulesArr.length()) {
            rules.add(ruleFromJson(rulesArr.getJSONObject(j)))
        }
        return ProfileSnapshot(id, name, rules)
    }

    private fun ruleToJson(snap: RuleSnapshot): JSONObject {
        val r = snap.rule
        val o = JSONObject()
        o.put("packageName", r.packageName)
        o.putOpt("mode", r.mode)
        o.put("teachDeclined", r.teachDeclined)
        o.putOpt("lastAskedDay", r.lastAskedDay)
        o.put("successCount", r.successCount)
        o.put("failureCount", r.failureCount)
        o.put("consecutiveFailures", r.consecutiveFailures)
        o.put("needsRecheck", r.needsRecheck)
        o.putOpt("taughtVersionCode", r.taughtVersionCode)
        o.putOpt("fieldViewId", r.fieldViewId)
        o.putOpt("sendViewId", r.sendViewId)
        o.putOpt("sendText", r.sendText)
        o.putOpt("sendDesc", r.sendDesc)
        o.putOpt("sendClassPath", r.sendClassPath)
        o.putOpt("tapX", r.tapX)
        o.putOpt("tapY", r.tapY)
        o.putOpt("tapDxDp", r.tapDxDp?.toDouble())
        o.putOpt("tapDyDp", r.tapDyDp?.toDouble())
        o.putOpt("tapFx", r.tapFx?.toDouble())
        o.putOpt("tapFy", r.tapFy?.toDouble())
        o.putOpt("cropLeft", r.cropLeft)
        o.putOpt("cropTop", r.cropTop)
        o.putOpt("cropRight", r.cropRight)
        o.putOpt("cropBottom", r.cropBottom)
        o.putOpt("orientation", r.orientation)
        o.putOpt("windowWidth", r.windowWidth)
        o.putOpt("windowHeight", r.windowHeight)
        if (r.keyboardShown != null) o.put("keyboardShown", r.keyboardShown)
        if (snap.cropPng != null) {
            o.put("cropPngBase64", Base64.encodeToString(snap.cropPng, Base64.NO_WRAP))
        }
        return o
    }

    private fun ruleFromJson(o: JSONObject): RuleSnapshot {
        val pkg = o.optString("packageName")
        if (pkg.isEmpty()) throw FormatException("Rule missing packageName")
        val rule = AppRule(
            packageName = pkg,
            mode = o.optNullableString("mode"),
            teachDeclined = o.optBoolean("teachDeclined", false),
            lastAskedDay = o.optNullableLong("lastAskedDay"),
            successCount = o.optInt("successCount", 0),
            failureCount = o.optInt("failureCount", 0),
            consecutiveFailures = o.optInt("consecutiveFailures", 0),
            needsRecheck = o.optBoolean("needsRecheck", false),
            taughtVersionCode = o.optNullableLong("taughtVersionCode"),
            fieldViewId = o.optNullableString("fieldViewId"),
            sendViewId = o.optNullableString("sendViewId"),
            sendText = o.optNullableString("sendText"),
            sendDesc = o.optNullableString("sendDesc"),
            sendClassPath = o.optNullableString("sendClassPath"),
            tapX = o.optNullableInt("tapX"),
            tapY = o.optNullableInt("tapY"),
            tapDxDp = o.optNullableDouble("tapDxDp")?.toFloat(),
            tapDyDp = o.optNullableDouble("tapDyDp")?.toFloat(),
            tapFx = o.optNullableDouble("tapFx")?.toFloat(),
            tapFy = o.optNullableDouble("tapFy")?.toFloat(),
            cropPath = null, // restored by importer after writing PNG
            cropLeft = o.optNullableInt("cropLeft"),
            cropTop = o.optNullableInt("cropTop"),
            cropRight = o.optNullableInt("cropRight"),
            cropBottom = o.optNullableInt("cropBottom"),
            orientation = o.optNullableInt("orientation"),
            windowWidth = o.optNullableInt("windowWidth"),
            windowHeight = o.optNullableInt("windowHeight"),
            keyboardShown = if (o.has("keyboardShown") && !o.isNull("keyboardShown")) o.getBoolean("keyboardShown") else null,
        )
        val b64 = o.optNullableString("cropPngBase64")
        val png = try {
            b64?.let { Base64.decode(it, Base64.DEFAULT) }
        } catch (e: IllegalArgumentException) {
            throw FormatException("Bad cropPngBase64 for $pkg: ${e.message}")
        }
        return RuleSnapshot(rule, png)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null
}
