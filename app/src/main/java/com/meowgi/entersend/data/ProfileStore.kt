package com.meowgi.entersend.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.service.RuleStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Named full-config snapshots on disk. Room holds only the **active** working set; switching a
 * profile replaces Room + crop files, then updates [RuleStore] so the accessibility service
 * picks up the new tap spots immediately.
 */
class ProfileStore(private val context: Context) {
    data class Meta(val id: String, val name: String)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }
    private val crops = CropFiles(context.filesDir)

    fun list(): List<Meta> {
        ensureDefaultMeta()
        val ids = prefs.getStringSet(KEY_IDS, emptySet())!!.toList().sorted()
        return ids.map { Meta(it, prefs.getString(nameKey(it), it) ?: it) }
    }

    fun activeId(): String {
        ensureDefaultMeta()
        return prefs.getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID
    }

    fun activeName(): String = list().firstOrNull { it.id == activeId() }?.name ?: "Default"

    /** Snapshots current Room rules into a new named profile and makes it active. */
    fun saveCurrentAs(name: String, dao: RuleDao): Meta {
        val id = "p-" + UUID.randomUUID().toString().take(8)
        writeProfileFile(id, name, snapshotRules(dao))
        addId(id, name)
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        return Meta(id, name)
    }

    /** Overwrites the active profile file with the current Room working set. */
    fun saveActive(dao: RuleDao) {
        val id = activeId()
        val name = prefs.getString(nameKey(id), "Default") ?: "Default"
        writeProfileFile(id, name, snapshotRules(dao))
    }

    /**
     * Saves the current working set into the active profile file, then loads [id] into Room
     * and refreshes [store].
     */
    fun switchTo(id: String, dao: RuleDao, store: RuleStore?) {
        if (!prefs.getStringSet(KEY_IDS, emptySet())!!.contains(id)) {
            throw IllegalArgumentException("Unknown profile $id")
        }
        saveActive(dao)
        val profile = readProfileFile(id)
        applyProfile(profile, dao, store)
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun rename(id: String, name: String) {
        if (!prefs.getStringSet(KEY_IDS, emptySet())!!.contains(id)) return
        prefs.edit().putString(nameKey(id), name).apply()
        val file = File(dir, "$id.json")
        if (file.exists()) {
            val snap = readProfileFile(id)
            writeProfileFile(id, name, snap.rules)
        }
    }

    /**
     * Deletes a profile file. If it was active, activates another profile and loads it into Room
     * without writing the doomed working set back onto the survivor.
     */
    fun delete(id: String, dao: RuleDao? = null, store: RuleStore? = null) {
        val ids = prefs.getStringSet(KEY_IDS, emptySet())!!.toMutableSet()
        if (ids.size <= 1) throw IllegalStateException("Cannot delete the last profile")
        if (!ids.remove(id)) return
        val wasActive = activeId() == id
        File(dir, "$id.json").delete()
        val ed = prefs.edit().putStringSet(KEY_IDS, ids).remove(nameKey(id))
        if (wasActive) {
            val next = ids.sorted().first()
            ed.putString(KEY_ACTIVE, next)
            ed.apply()
            if (dao != null) applyProfile(readProfileFile(next), dao, store)
        } else {
            ed.apply()
        }
    }

    fun exportBundle(dao: RuleDao, allProfiles: Boolean): ConfigJson.Bundle {
        saveActive(dao)
        val metas = if (allProfiles) list() else list().filter { it.id == activeId() }
        val profiles = metas.map { readProfileFile(it.id) }
        return ConfigJson.Bundle(ConfigJson.SCHEMA_VERSION, activeId(), profiles)
    }

    /**
     * Imports a validated bundle. Replaces matching profile ids; creates new ones.
     * Always activates [ConfigJson.Bundle.activeProfileId] and loads it into Room.
     */
    fun importBundle(bundle: ConfigJson.Bundle, dao: RuleDao, store: RuleStore?) {
        for (p in bundle.profiles) {
            writeProfileFile(p.id, p.name, p.rules)
            addId(p.id, p.name)
        }
        prefs.edit().putString(KEY_ACTIVE, bundle.activeProfileId).apply()
        applyProfile(readProfileFile(bundle.activeProfileId), dao, store)
    }

    /** Ensures a Default profile exists (empty rules until first save). */
    fun ensureDefault(dao: RuleDao) {
        ensureDefaultMeta()
        val file = File(dir, "$DEFAULT_ID.json")
        if (!file.exists()) {
            writeProfileFile(DEFAULT_ID, "Default", snapshotRules(dao))
        }
    }

    private fun ensureDefaultMeta() {
        val ids = prefs.getStringSet(KEY_IDS, null)
        if (ids.isNullOrEmpty()) {
            prefs.edit()
                .putStringSet(KEY_IDS, setOf(DEFAULT_ID))
                .putString(nameKey(DEFAULT_ID), "Default")
                .putString(KEY_ACTIVE, DEFAULT_ID)
                .apply()
        }
    }

    private fun addId(id: String, name: String) {
        val ids = prefs.getStringSet(KEY_IDS, emptySet())!!.toMutableSet()
        ids.add(id)
        prefs.edit().putStringSet(KEY_IDS, ids).putString(nameKey(id), name).apply()
    }

    private fun snapshotRules(dao: RuleDao): List<ConfigJson.RuleSnapshot> =
        dao.all().map { rule ->
            val png = rule.cropPath?.let { path ->
                // Prefer decoding via CropFiles so a slightly-odd PNG still round-trips;
                // fall back to raw bytes if the file is present but not decodable.
                crops.load(path)?.let { pixelsToPng(it) }
                    ?: File(path).takeIf { it.exists() }?.readBytes()
            }
            ConfigJson.RuleSnapshot(rule.copy(cropPath = null), png)
        }

    private fun writeProfileFile(id: String, name: String, rules: List<ConfigJson.RuleSnapshot>) {
        val snap = ConfigJson.ProfileSnapshot(id, name, rules)
        File(dir, "$id.json").writeText(ConfigJson.profileToJson(snap).toString(2))
    }

    private fun readProfileFile(id: String): ConfigJson.ProfileSnapshot {
        val file = File(dir, "$id.json")
        if (!file.exists()) return ConfigJson.ProfileSnapshot(id, prefs.getString(nameKey(id), id) ?: id, emptyList())
        return ConfigJson.profileFromJson(JSONObject(file.readText()))
    }

    private fun applyProfile(profile: ConfigJson.ProfileSnapshot, dao: RuleDao, store: RuleStore?) {
        // Wipe working crops and rules, then restore from the snapshot.
        CropFiles.dir(context.filesDir).listFiles()?.forEach { it.delete() }
        dao.deleteAll()
        val restored = ArrayList<AppRule>(profile.rules.size)
        for (snap in profile.rules) {
            var rule = snap.rule
            if (snap.cropPng != null) {
                val pixels = pngToPixels(snap.cropPng) ?: continue
                val path = crops.save(rule.packageName, pixels)
                rule = rule.copy(cropPath = path)
            }
            dao.upsert(rule)
            restored.add(rule)
        }
        store?.replaceAll(restored)
    }

    companion object {
        const val PREFS = "entersend_profiles"
        const val KEY_IDS = "ids"
        const val KEY_ACTIVE = "active"
        const val DEFAULT_ID = "default"
        fun nameKey(id: String) = "name_$id"

        fun pngToPixels(png: ByteArray): Pixels? {
            val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return Pixels(bitmap.width, bitmap.height, argb)
        }

        fun pixelsToPng(pixels: Pixels): ByteArray {
            val bitmap = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }
    }
}
