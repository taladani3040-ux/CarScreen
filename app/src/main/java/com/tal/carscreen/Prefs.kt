package com.tal.carscreen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A driver/viewer profile for the parked-only guard. */
data class Profile(
    val name: String,
    /** Above this speed the screen is hidden. */
    val kmh: Int,
    /** How long the car must stay stopped before the screen comes back. */
    val holdSec: Int,
)

/** User settings: apps on the car home screen, switches, and guard profiles. */
object Prefs {
    private const val FILE = "carscreen"
    private const val KEY_APPS = "apps"
    private const val KEY_LANDSCAPE = "force_landscape"
    private const val KEY_GUARD = "parked_only"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"

    const val MIN_KMH = 5
    const val MAX_KMH = 30
    const val MIN_HOLD = 2
    const val MAX_HOLD = 30

    val DEFAULT_PROFILE = Profile("רגיל", 10, 4)

    /** Shown by default if they are installed. */
    val DEFAULT_APPS = listOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically",      // TikTok
        "com.android.chrome",
        "com.spotify.music",
        "com.google.android.apps.photos",
    )

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Apps ---------------------------------------------------------------

    fun apps(c: Context): List<String> {
        val saved = sp(c).getString(KEY_APPS, null)
        return if (saved == null) DEFAULT_APPS else saved.split(",").filter { it.isNotBlank() }
    }

    fun setApps(c: Context, pkgs: List<String>) =
        sp(c).edit().putString(KEY_APPS, pkgs.joinToString(",")).apply()

    // --- Switches -----------------------------------------------------------

    fun forceLandscape(c: Context) = sp(c).getBoolean(KEY_LANDSCAPE, true)
    fun setForceLandscape(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_LANDSCAPE, v).apply()

    fun parkedOnly(c: Context) = sp(c).getBoolean(KEY_GUARD, true)
    fun setParkedOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_GUARD, v).apply()

    // --- Guard profiles -----------------------------------------------------

    fun profiles(c: Context): List<Profile> {
        val raw = sp(c).getString(KEY_PROFILES, null) ?: return listOf(DEFAULT_PROFILE)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(
                    o.optString("name", "פרופיל ${i + 1}"),
                    o.optInt("kmh", DEFAULT_PROFILE.kmh).coerceIn(MIN_KMH, MAX_KMH),
                    o.optInt("hold", DEFAULT_PROFILE.holdSec).coerceIn(MIN_HOLD, MAX_HOLD),
                )
            }.ifEmpty { listOf(DEFAULT_PROFILE) }
        } catch (_: Exception) {
            listOf(DEFAULT_PROFILE)
        }
    }

    fun setProfiles(c: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("kmh", it.kmh).put("hold", it.holdSec))
        }
        sp(c).edit().putString(KEY_PROFILES, arr.toString()).apply()
        if (activeIndex(c) >= list.size) setActiveIndex(c, 0)
    }

    fun activeIndex(c: Context): Int = sp(c).getInt(KEY_ACTIVE, 0)

    fun setActiveIndex(c: Context, i: Int) = sp(c).edit().putInt(KEY_ACTIVE, i).apply()

    fun activeProfile(c: Context): Profile {
        val list = profiles(c)
        return list.getOrElse(activeIndex(c)) { list.first() }
    }

    fun updateProfile(c: Context, index: Int, p: Profile) {
        val list = profiles(c).toMutableList()
        if (index !in list.indices) return
        list[index] = p.copy(
            kmh = p.kmh.coerceIn(MIN_KMH, MAX_KMH),
            holdSec = p.holdSec.coerceIn(MIN_HOLD, MAX_HOLD),
        )
        setProfiles(c, list)
    }
}
