package com.cleanlauncher.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class AppItem(
        val label: String,
        val key: String,          // package name (or "builtin/...")
        val intent: Intent,
        val info: ResolveInfo?
    )

    private class Holder(val icon: ImageView, val label: TextView) {
        var key: String? = null
    }

    private val apps = ArrayList<AppItem>()
    private val iconCache = LruCache<String, Drawable>(200)
    private val loader = Executors.newSingleThreadExecutor()
    private val iconLoader = Executors.newFixedThreadPool(2)

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var grid: GridView
    private lateinit var adapter: AppAdapter
    private lateinit var topHeader: TextView
    private lateinit var topScroll: HorizontalScrollView
    private lateinit var topRow: LinearLayout
    private lateinit var favHeader: TextView
    private lateinit var favScroll: HorizontalScrollView
    private lateinit var favRow: LinearLayout

    private var cols = 6
    private var firstLoad = true

    private val themeNames = arrayOf("Midnight", "Ocean", "Forest", "Sunset", "AMOLED black")
    private val themeColors = arrayOf(
        intArrayOf(Color.parseColor("#0B0F14"), Color.parseColor("#16202B")),
        intArrayOf(Color.parseColor("#021B2B"), Color.parseColor("#0E4D6B")),
        intArrayOf(Color.parseColor("#07140D"), Color.parseColor("#1C4A2E")),
        intArrayOf(Color.parseColor("#1A0B1F"), Color.parseColor("#5A2A3F")),
        intArrayOf(Color.parseColor("#000000"), Color.parseColor("#000000"))
    )

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        cols = prefs.getInt("cols", 6)

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false
        root.setPadding(dp(48), dp(28), dp(48), dp(12))

        val clock = TextClock(this)
        clock.format12Hour = "hh:mm a  |  EEE, d MMM"
        clock.format24Hour = "HH:mm  |  EEE, d MMM"
        clock.setTextColor(Color.WHITE)
        clock.textSize = 26f
        root.addView(clock, wrapParams())

        topHeader = newHeader("Featured")
        topRow = LinearLayout(this)
        topScroll = newScroll(topRow)
        root.addView(topHeader, wrapParams())
        root.addView(topScroll, wrapParams())

        favHeader = newHeader("Favourites")
        favRow = LinearLayout(this)
        favScroll = newScroll(favRow)
        root.addView(favHeader, wrapParams())
        root.addView(favScroll, wrapParams())

        root.addView(newHeader("All apps   (hold OK on any app for options)"), wrapParams())

        grid = GridView(this)
        grid.numColumns = cols
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.verticalSpacing = dp(10)
        grid.horizontalSpacing = dp(10)
        grid.setSelector(android.R.color.transparent)
        grid.clipToPadding = false
        grid.setPadding(0, dp(8), 0, dp(16))
        grid.isVerticalScrollBarEnabled = false
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        grid.isFocusable = true
        adapter = AppAdapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            if (position in 0 until apps.size) launch(apps[position])
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            if (position in 0 until apps.size) showOptions(apps[position])
            true
        }
        root.addView(
            grid,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        applyTheme()

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        filter.addDataScheme("package")
        registerReceiver(pkgReceiver, filter)

        loadApps()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while the launcher is already open: go back to the start.
        if (topRow.childCount > 0) topRow.getChildAt(0).requestFocus() else grid.requestFocus()
        grid.setSelection(0)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // A launcher should never close on Back.
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pkgReceiver)
        } catch (_: Exception) {
        }
        loader.shutdown()
        iconLoader.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- small view helpers

    private fun wrapParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun newHeader(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.parseColor("#99FFFFFF"))
        t.textSize = 14f
        t.setPadding(dp(4), dp(10), 0, dp(2))
        return t
    }

    private fun newScroll(row: LinearLayout): HorizontalScrollView {
        val s = HorizontalScrollView(this)
        s.isHorizontalScrollBarEnabled = false
        s.clipChildren = false
        s.clipToPadding = false
        s.setPadding(dp(10), dp(8), dp(10), dp(8))
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        s.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return s
    }

    private fun itemBackground(): StateListDrawable {
        val highlighted = GradientDrawable()
        highlighted.setColor(Color.parseColor("#33FFFFFF"))
        highlighted.cornerRadius = dp(14).toFloat()
        highlighted.setStroke(dp(2), Color.WHITE)

        val normal = GradientDrawable()
        normal.setColor(Color.TRANSPARENT)
        normal.cornerRadius = dp(14).toFloat()

        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), highlighted)
        states.addState(intArrayOf(android.R.attr.state_selected), highlighted)
        states.addState(intArrayOf(), normal)
        return states
    }

    private fun applyTheme() {
        val i = prefs.getInt("theme", 0).coerceIn(0, themeColors.size - 1)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, themeColors[i])
    }

    // ---------------------------------------------------------------- saved lists

    private fun getList(name: String): MutableList<String> {
        val s = prefs.getString(name, "") ?: ""
        return if (s.isEmpty()) ArrayList() else ArrayList(s.split("\n"))
    }

    private fun saveList(name: String, list: List<String>) {
        prefs.edit().putString(name, list.joinToString("\n")).apply()
    }

    private fun toggle(list: MutableList<String>, key: String) {
        if (!list.remove(key)) list.add(key)
    }

    private fun findByKey(key: String): AppItem? = apps.firstOrNull { it.key == key }

    // ---------------------------------------------------------------- loading apps

    private fun loadApps() {
        loader.execute {
            try {
                val pm = packageManager
                val seen = HashSet<String>()
                val list = ArrayList<AppItem>()

                val categories = arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
                for (cat in categories) {
                    val query = Intent(Intent.ACTION_MAIN)
                    query.addCategory(cat)
                    for (ri in pm.queryIntentActivities(query, 0)) {
                        val ai = ri.activityInfo ?: continue
                        if (ai.packageName == packageName) continue
                        if (!seen.add(ai.packageName)) continue
                        val launchIntent = Intent(Intent.ACTION_MAIN)
                        launchIntent.addCategory(cat)
                        launchIntent.setClassName(ai.packageName, ai.name)
                        launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        )
                        list.add(AppItem(ri.loadLabel(pm).toString(), ai.packageName, launchIntent, ri))
                    }
                }

                list.sortBy { it.label.lowercase() }
                list.add(AppItem("Launcher settings", "builtin/menu", Intent(), null))

                runOnUiThread {
                    apps.clear()
                    apps.addAll(list)
                    ensureDefaultFeatured()
                    adapter.notifyDataSetChanged()
                    refreshRows()
                    if (firstLoad) {
                        firstLoad = false
                        if (topRow.childCount > 0) topRow.getChildAt(0).requestFocus()
                        else grid.requestFocus()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't load apps", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** First run: put YouTube and JioHotstar in the featured row, found by app name. */
    private fun ensureDefaultFeatured() {
        if (prefs.contains("top")) return
        val defaults = ArrayList<String>()
        val yt = apps.firstOrNull {
            val l = it.label.lowercase()
            l.contains("youtube") && !l.contains("music") && !l.contains("kids")
        }
        if (yt != null) defaults.add(yt.key)
        val hs = apps.firstOrNull { it.label.lowercase().contains("hotstar") }
        if (hs != null) defaults.add(hs.key)
        if (defaults.isNotEmpty()) saveList("top", defaults)
    }

    // ---------------------------------------------------------------- rows

    private fun refreshRows() {
        val top = getList("top").mapNotNull { findByKey(it) }
        val fav = getList("fav").mapNotNull { findByKey(it) }

        topRow.removeAllViews()
        for (item in top) {
            val lp = LinearLayout.LayoutParams(dp(240), dp(135))
            lp.rightMargin = dp(14)
            topRow.addView(makeTile(item, true), lp)
        }
        favRow.removeAllViews()
        for (item in fav) {
            val lp = LinearLayout.LayoutParams(dp(100), dp(108))
            lp.rightMargin = dp(10)
            favRow.addView(makeTile(item, false), lp)
        }

        val topVis = if (top.isEmpty()) View.GONE else View.VISIBLE
        topHeader.visibility = topVis
        topScroll.visibility = topVis
        val favVis = if (fav.isEmpty()) View.GONE else View.VISIBLE
        favHeader.visibility = favVis
        favScroll.visibility = favVis
    }

    private fun makeTile(item: AppItem, big: Boolean): View {
        val tile = LinearLayout(this)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER
        tile.background = itemBackground()
        tile.isFocusable = true
        tile.isClickable = true
        tile.setPadding(dp(if (big) 4 else 6), dp(if (big) 4 else 8), dp(if (big) 4 else 6), dp(if (big) 4 else 6))

        val icon = ImageView(this)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val iconSize = if (big) 56 else 52
        tile.addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))

        val label = TextView(this)
        label.setTextColor(Color.WHITE)
        label.textSize = if (big) 15f else 12f
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.text = item.label
        label.setPadding(0, dp(6), 0, 0)
        tile.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        tile.setOnClickListener { launch(item) }
        tile.setOnLongClickListener {
            showOptions(item)
            true
        }
        tile.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) 1.07f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }

        iconLoader.execute {
            try {
                var banner: Drawable? = null
                if (big && item.info != null) {
                    banner = item.info.activityInfo.loadBanner(packageManager)
                }
                val normalIcon = iconFor(item)
                runOnUiThread {
                    if (banner != null) {
                        icon.scaleType = ImageView.ScaleType.CENTER_CROP
                        icon.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        icon.setImageDrawable(banner)
                        label.visibility = View.GONE
                    } else if (normalIcon != null) {
                        icon.setImageDrawable(fresh(normalIcon))
                    }
                }
            } catch (_: Exception) {
            }
        }
        return tile
    }

    // ---------------------------------------------------------------- icons

    private fun iconFor(item: AppItem): Drawable? {
        val cached = iconCache.get(item.key)
        if (cached != null) return cached
        val d: Drawable? = try {
            if (item.info != null) {
                item.info.loadIcon(packageManager)
            } else {
                getDrawable(android.R.drawable.ic_menu_preferences)
            }
        } catch (e: Exception) {
            null
        }
        if (d != null) iconCache.put(item.key, d)
        return d
    }

    /** Each ImageView needs its own Drawable instance, otherwise sizes can clash. */
    private fun fresh(d: Drawable): Drawable {
        val st = d.constantState
        return if (st != null) st.newDrawable(resources) else d
    }

    // ---------------------------------------------------------------- actions & menus

    private fun launch(item: AppItem) {
        if (item.key == "builtin/menu") {
            showMenu()
            return
        }
        try {
            startActivity(item.intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open " + item.label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOptions(item: AppItem) {
        if (item.key.startsWith("builtin/")) return
        val fav = getList("fav")
        val top = getList("top")
        val options = arrayOf(
            if (fav.contains(item.key)) "Remove from Favourites" else "Add to Favourites",
            if (top.contains(item.key)) "Remove from Featured row" else "Add to Featured row"
        )
        AlertDialog.Builder(this)
            .setTitle(item.label)
            .setItems(options) { _, which ->
                if (which == 0) {
                    toggle(fav, item.key)
                    saveList("fav", fav)
                } else {
                    toggle(top, item.key)
                    saveList("top", top)
                }
                refreshRows()
            }
            .show()
    }

    private fun showMenu() {
        val options = arrayOf(
            "Change theme",
            "Grid columns (now $cols)",
            "Reset Favourites and Featured row",
            "Open Android system settings"
        )
        AlertDialog.Builder(this)
            .setTitle("Launcher settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickTheme()
                    1 -> pickColumns()
                    2 -> {
                        prefs.edit().remove("top").remove("fav").apply()
                        ensureDefaultFeatured()
                        refreshRows()
                    }
                    3 -> {
                        try {
                            val i = Intent(Settings.ACTION_SETTINGS)
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun pickTheme() {
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setItems(themeNames) { _, which ->
                prefs.edit().putInt("theme", which).apply()
                applyTheme()
            }
            .show()
    }

    private fun pickColumns() {
        val options = arrayOf("5 columns", "6 columns", "7 columns")
        AlertDialog.Builder(this)
            .setTitle("Grid columns")
            .setItems(options) { _, which ->
                cols = 5 + which
                prefs.edit().putInt("cols", cols).apply()
                grid.numColumns = cols
                adapter.notifyDataSetChanged()
            }
            .show()
    }

    // ---------------------------------------------------------------- grid adapter

    private fun newGridCell(): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(dp(8), dp(14), dp(8), dp(10))
        cell.background = itemBackground()

        val icon = ImageView(this)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        cell.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))

        val label = TextView(this)
        label.setTextColor(Color.WHITE)
        label.textSize = 13f
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.setPadding(0, dp(6), 0, 0)
        cell.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        cell.tag = Holder(icon, label)
        return cell
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: newGridCell()
            val holder = view.tag as Holder
            val item = apps[position]

            holder.label.text = item.label
            holder.key = item.key

            val cached = iconCache.get(item.key)
            if (cached != null) {
                holder.icon.setImageDrawable(fresh(cached))
            } else {
                holder.icon.setImageDrawable(null)
                iconLoader.execute {
                    try {
                        val d = iconFor(item)
                        if (d != null) {
                            runOnUiThread {
                                if (holder.key == item.key) holder.icon.setImageDrawable(fresh(d))
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            return view
        }
    }
}
