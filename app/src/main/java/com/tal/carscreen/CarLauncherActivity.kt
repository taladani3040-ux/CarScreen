package com.tal.carscreen

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "car home screen": big tiles of the apps you picked. Runs on the phone in landscape,
 * and is what you see on the car screen when you press "בית".
 */
class CarLauncherActivity : Activity() {

    private data class Tile(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var grid: GridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1218"))
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "CarScreen"
            textSize = 20f
            setTextColor(Color.parseColor("#2EC4B6"))
            setPadding(0, 0, 0, dp(12))
        })
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(150)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(16)
            verticalSpacing = dp(16)
            gravity = Gravity.CENTER
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        val tiles = loadTiles()
        grid.adapter = TileAdapter(tiles)
        grid.setOnItemClickListener { _, _, pos, _ -> open(tiles[pos].pkg) }
    }

    private fun loadTiles(): List<Tile> {
        val pm = packageManager
        return Prefs.apps(this).mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (pm.getLaunchIntentForPackage(pkg) == null) null
                else Tile(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    private fun open(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class TileAdapter(val tiles: List<Tile>) : BaseAdapter() {
        override fun getCount() = tiles.size
        override fun getItem(position: Int) = tiles[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = tiles[position]
            return LinearLayout(this@CarLauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(18), dp(8), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor("#16222C"))
                }
                addView(ImageView(context).apply { setImageDrawable(t.icon) }, LinearLayout.LayoutParams(dp(84), dp(84)))
                addView(TextView(context).apply {
                    text = t.label
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(10), 0, 0)
                })
            }
        }
    }
}
