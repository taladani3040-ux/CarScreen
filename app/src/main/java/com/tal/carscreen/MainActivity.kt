package com.tal.carscreen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Setup screen on the phone: permissions, app picker, start/stop mirroring. Built in code, Hebrew RTL. */
class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 10
        private const val REQ_PERMS = 11
        private val ACCENT = Color.parseColor("#2EC4B6")
        private val OK = Color.parseColor("#4CAF50")
        private val BAD = Color.parseColor("#FF7043")
    }

    private lateinit var content: LinearLayout
    private val stateListener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(24), dp(18), dp(32))
            setBackgroundColor(Color.parseColor("#0B1218"))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1218"))
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        Mirror.addListener(stateListener)
        render()
    }

    override fun onPause() {
        Mirror.removeListener(stateListener)
        super.onPause()
    }

    // --- UI ----------------------------------------------------------------

    private fun render() {
        content.removeAllViews()

        content.addView(text("CarScreen", 28f, ACCENT, bold = true))
        content.addView(text("שיקוף הטלפון למסך הרכב דרך Android Auto", 15f, Color.LTGRAY))
        content.addView(space(16))

        // Status
        val status = when {
            Mirror.projecting && Mirror.carConnected -> "● משקף עכשיו למסך הרכב"
            Mirror.projecting -> "● השיקוף מוכן – פתח את CarScreen במסך הרכב"
            Mirror.carConnected -> "○ הרכב מחובר – לחץ \"התחל שיקוף\""
            else -> "○ לא משקף"
        }
        content.addView(text(status, 17f, if (Mirror.projecting) OK else Color.WHITE, bold = true))
        if (Mirror.moving) content.addView(text("הרכב בתנועה – המסך מוסתר", 15f, BAD))
        content.addView(space(12))

        // Main button
        if (Mirror.projecting) {
            content.addView(bigButton("עצור שיקוף", BAD) { MirrorService.stop(this) })
        } else {
            content.addView(bigButton("התחל שיקוף", ACCENT) { startCapture() })
        }
        content.addView(space(8))
        content.addView(button("הצג את מסך הבית לרכב") {
            startActivity(Intent(this, CarLauncherActivity::class.java))
        })

        // Checklist
        content.addView(space(24))
        content.addView(header("הכנה חד-פעמית"))

        val touchOn = TouchService.instance != null
        content.addView(step(
            "1. מגע ממסך הרכב (נגישות)", touchOn,
            if (touchOn) "פעיל" else "הגדרות › נגישות › אפליקציות מותקנות › CarScreen – מגע. " +
                "אם האפשרות אפורה: פרטי אפליקציה › ⋮ › \"אפשר הגדרות מוגבלות\".",
            if (touchOn) null else "פתח הגדרות נגישות"
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })

        val locOn = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        content.addView(step(
            "2. מיקום (לזיהוי נסיעה)", locOn,
            if (locOn) "מאושר" else "נדרש כדי להסתיר את המסך כשהרכב נוסע.",
            if (locOn) null else "אשר מיקום"
        ) { askPermissions() })

        if (Build.VERSION.SDK_INT >= 33) {
            val notifOn = granted(Manifest.permission.POST_NOTIFICATIONS)
            content.addView(step(
                "3. התראות", notifOn,
                if (notifOn) "מאושר" else "להצגת כפתור \"עצור שיקוף\" בהתראה.",
                if (notifOn) null else "אשר התראות"
            ) { askPermissions() })
        }

        val overlayOn = Settings.canDrawOverlays(this)
        content.addView(step(
            "4. הצגה מעל אפליקציות (סיבוב לרוחב)", overlayOn,
            if (overlayOn) "מאושר" else "עוזר לכפות מסך לרוחב בכל האפליקציות.",
            if (overlayOn) null else "פתח הגדרה"
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        // Options
        content.addView(space(24))
        content.addView(header("אפשרויות"))
        content.addView(switch("כפה מסך לרוחב בזמן שיקוף", Prefs.forceLandscape(this)) {
            Prefs.setForceLandscape(this, it)
            TouchService.instance?.onMirroringChanged(Mirror.projecting)
        })
        content.addView(space(8))
        if (Prefs.parkedOnly(this)) {
            content.addView(button("הסר הגבלת מהירות לשיקוף הנוכחי") { confirmRemoveLimit() })
            addProfilesSection()
        } else {
            content.addView(text("⚠ הגבלת המהירות כבויה – הוידאו ימשיך גם בנסיעה.", 15f, BAD, bold = true))
            content.addView(text("ההגבלה תחזור אוטומטית בשיקוף הבא.", 13f, Color.GRAY))
            content.addView(bigButton("החזר הגבלת מהירות", ACCENT) {
                Prefs.setParkedOnly(this, true)
                Mirror.service?.refreshGuard()
                render()
            })
        }

        // App picker
        content.addView(space(24))
        content.addView(header("אפליקציות במסך הבית לרכב"))
        content.addView(text("נטפליקס, דיסני+ ודומיהן מוגנות ויופיעו שחורות בשיקוף.", 13f, Color.GRAY))
        addAppPicker()
    }

    // --- Guard profiles ----------------------------------------------------

    private fun addProfilesSection() {
        content.addView(space(16))
        content.addView(header("חסימה בנסיעה – פרופילים"))
        content.addView(text("הקש על פרופיל כדי להפעיל אותו. אפשר גם להחליף פרופיל מכפתור במסך הרכב.", 13f, Color.GRAY))
        content.addView(space(6))

        val list = Prefs.profiles(this)
        val active = Prefs.activeIndex(this).coerceIn(list.indices)

        list.forEachIndexed { i, p ->
            val isActive = i == active
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#16222C"))
                    if (isActive) setStroke(dp(2), ACCENT)
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            }
            card.addView(text(
                (if (isActive) "● " else "○ ") + p.name + "   ·   מעל ${p.kmh} קמ\"ש · חזרה אחרי ${p.holdSec} שנ'",
                16f, if (isActive) ACCENT else Color.WHITE, bold = isActive
            ))

            if (!isActive) {
                card.setOnClickListener {
                    Prefs.setActiveIndex(this, i)
                    Mirror.notifyChanged()
                    render()
                }
            } else {
                // Speed limit slider
                val speedLabel = text("מסתיר את המסך מעל ${p.kmh} קמ\"ש", 15f, Color.LTGRAY)
                card.addView(speedLabel)
                card.addView(slider(Prefs.MIN_KMH, Prefs.MAX_KMH, p.kmh,
                    onMove = { v -> speedLabel.text = "מסתיר את המסך מעל $v קמ\"ש" },
                    onDone = { v -> Prefs.updateProfile(this, i, Prefs.profiles(this)[i].copy(kmh = v)); render() }
                ))
                // Hold time slider
                val holdLabel = text("המסך חוזר אחרי ${p.holdSec} שניות עצירה", 15f, Color.LTGRAY)
                card.addView(holdLabel)
                card.addView(slider(Prefs.MIN_HOLD, Prefs.MAX_HOLD, p.holdSec,
                    onMove = { v -> holdLabel.text = "המסך חוזר אחרי $v שניות עצירה" },
                    onDone = { v -> Prefs.updateProfile(this, i, Prefs.profiles(this)[i].copy(holdSec = v)); render() }
                ))
                if (p.kmh > 15) {
                    card.addView(text("שים לב: מעל 15 קמ\"ש הוידאו ימשיך לפעול בנסיעה איטית בפקקים.", 13f, BAD))
                }

                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(button("שנה שם") { askName("שם הפרופיל", p.name) { name ->
                    Prefs.updateProfile(this, i, Prefs.profiles(this)[i].copy(name = name)); render()
                } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
                if (list.size > 1) {
                    row.addView(button("מחק") {
                        val rest = Prefs.profiles(this).toMutableList().apply { removeAt(i) }
                        Prefs.setActiveIndex(this, 0)
                        Prefs.setProfiles(this, rest)
                        Mirror.notifyChanged()
                        render()
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                card.addView(space(6))
                card.addView(row)
            }
            content.addView(card)
        }

        content.addView(button("+ הוסף פרופיל") {
            askName("שם הפרופיל החדש", "") { name ->
                val cur = Prefs.activeProfile(this)
                val newList = Prefs.profiles(this) + Profile(name, cur.kmh, cur.holdSec)
                Prefs.setProfiles(this, newList)
                Prefs.setActiveIndex(this, newList.lastIndex)
                Mirror.notifyChanged()
                render()
            }
        })
    }

    private fun slider(min: Int, max: Int, value: Int, onMove: (Int) -> Unit, onDone: (Int) -> Unit) =
        SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = onMove(p + min)
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) = onDone(sb.progress + min)
            })
        }

    /** Deliberate, two-step removal of the speed limit. Only while stopped; lasts one mirroring session. */
    private fun confirmRemoveLimit() {
        if (Mirror.moving) {
            AlertDialog.Builder(this)
                .setMessage("אפשר להסיר את ההגבלה רק כשהרכב עומד.")
                .setPositiveButton("הבנתי", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("להסיר את הגבלת המהירות?")
            .setMessage(
                "הוידאו ימשיך לפעול במסך הרכב גם בזמן נסיעה.\n\n" +
                    "צפייה בוידאו על ידי הנהג בזמן נהיגה מסוכנת ואסורה בחוק.\n\n" +
                    "ההגבלה תחזור אוטומטית כשהשיקוף ייגמר."
            )
            .setPositiveButton("כן, הסר לשיקוף הנוכחי") { _, _ ->
                Prefs.setParkedOnly(this, false)
                Mirror.service?.refreshGuard()
                render()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun askName(title: String, current: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            hint = "למשל: אני / אשתי / ילדים"
            textDirection = View.TEXT_DIRECTION_RTL
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("שמור") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun addAppPicker() {
        val pm = packageManager
        val selected = Prefs.apps(this).toMutableList()
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { Triple(it.packageName, pm.getApplicationLabel(it).toString(), it) }
            .sortedWith(compareBy({ it.first !in selected }, { it.second.lowercase() }))

        for ((pkg, label, info) in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(ImageView(this).apply { setImageDrawable(pm.getApplicationIcon(info)) },
                LinearLayout.LayoutParams(dp(36), dp(36)))
            val cb = CheckBox(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                isChecked = pkg in selected
                setPadding(dp(8), 0, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    val list = Prefs.apps(this@MainActivity).toMutableList()
                    if (checked && pkg !in list) list += pkg
                    if (!checked) list.remove(pkg)
                    Prefs.setApps(this@MainActivity, list)
                }
            }
            row.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row)
        }
    }

    // --- Actions -----------------------------------------------------------

    private fun startCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Skips the "single app" option: we need the whole screen.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            MirrorService.start(this, resultCode, data)
            // Land on the car home screen so it's the first thing the car shows.
            startActivity(Intent(this, CarLauncherActivity::class.java))
        }
    }

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // --- Small view helpers -----------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_RTL
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun header(s: String) = text(s, 19f, ACCENT, bold = true).apply { setPadding(0, 0, 0, dp(8)) }

    private fun roundBg(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        setTextColor(Color.parseColor("#0B1218"))
        background = roundBg(color)
        isAllCaps = false
        minHeight = dp(64)
        setOnClickListener { onClick() }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        background = roundBg(Color.parseColor("#1E2D38"))
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun step(title: String, done: Boolean, detail: String, action: String?, onAction: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Color.parseColor("#16222C"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
            addView(text((if (done) "✓ " else "✗ ") + title, 16f, if (done) OK else BAD, bold = true))
            addView(text(detail, 14f, Color.LTGRAY))
            if (action != null) addView(button(action, onAction))
        }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        isChecked = value
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
