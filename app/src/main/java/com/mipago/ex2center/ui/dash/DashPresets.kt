package com.mipago.ex2center.ui.dash

import android.content.Context
import com.mipago.ex2center.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * The widgets the Dashboard can show. Each size variant is its own layout; variants of the same
 * widget share a [family] (only one of them can be on the grid) and can be switched in edit mode.
 */
enum class WidgetType(
    val layout: Int,
    val colSpan: Int,
    val rowSpan: Int,
    val family: String,
    val label: String
) {
    DRIVE(R.layout.widget_drive, 2, 2, "drive", "Lái xe"),
    CLIMATE(R.layout.widget_climate, 2, 2, "climate", "Điều hòa"),
    CLIMATE_COMPACT(R.layout.widget_climate_compact, 2, 1, "climate", "Điều hòa"),
    CAR(R.layout.widget_car, 2, 2, "car", "Xe · Cốp · Kính"),
    MEDIA(R.layout.widget_media, 2, 1, "media", "Nhạc"),
    MEDIA_LARGE(R.layout.widget_media_large, 2, 2, "media", "Nhạc"),
    DRIVE_MODE(R.layout.widget_drive_mode, 2, 1, "drive_mode", "Chế độ lái"),
    SHORTCUTS(R.layout.widget_shortcuts, 2, 1, "shortcuts", "Phím tắt"),
    BATTERY(R.layout.widget_battery, 2, 1, "battery", "Pin & quãng đường"),
    SPLIT(R.layout.widget_split, 2, 1, "split", "Chạy cùng Vietmap"),
    TILE(R.layout.widget_tile, 1, 1, "tile", "Ô 1×1");

    val sizeLabel get() = "$colSpan×$rowSpan"

    /** The other sizes of the same widget, in the order "⇲" cycles through them. */
    val variants: List<WidgetType> get() = if (this == TILE) listOf(TILE) else values().filter { it.family == family }

    /** The size "⇲" switches to (itself when the widget has only one size). */
    val nextVariant: WidgetType get() = variants.let { it[(it.indexOf(this) + 1) % it.size] }
}

/** What a 1×1 tile does. */
enum class TileKind(val title: String, val icon: Int) {
    BLACKOUT("Tắt màn hình", R.drawable.ic_night),
    AMBIENT("Đèn nội thất", R.drawable.ic_ambient_light),
    DEFROST("Sấy kính trước", R.drawable.ic_defrost_front),
    AUTO_HOLD("Auto Hold", R.drawable.ic_auto_hold),
    VALET("Chế độ Valet", R.drawable.ic_wrench),
    AVAS("Tắt âm AVAS", R.drawable.ic_avas),
    WIRELESS("Sạc không dây", R.drawable.ic_wireless_charge),
    FRONT_TRUNK("Mở cốp trước", R.drawable.ic_trunk_front)
}

/** One widget on the grid: its top-left cell; the size comes from [type]. */
data class Placement(
    val type: WidgetType,
    val col: Int,
    val row: Int,
    val tile: TileKind? = null
) {
    val colSpan get() = type.colSpan
    val rowSpan get() = type.rowSpan

    /** Identity on the grid: a widget family (or one tile kind) appears at most once. */
    val key get() = if (type == WidgetType.TILE) "tile:${tile?.name}" else type.family

    val label get() = tile?.title ?: type.label

    fun covers(c: Int, r: Int) = c in col until col + colSpan && r in row until row + rowSpan

    fun overlaps(o: Placement) =
        col < o.col + o.colSpan && o.col < col + colSpan && row < o.row + o.rowSpan && o.row < row + rowSpan
}

/** Ready-made layouts. Each one covers all 18 cells. */
enum class DashPreset(val id: String, val label: String, val placements: List<Placement>) {
    DAILY(
        "daily", "Lái xe hàng ngày", listOf(
            Placement(WidgetType.DRIVE, 0, 0),
            Placement(WidgetType.CLIMATE, 2, 0),
            Placement(WidgetType.CAR, 4, 0),
            Placement(WidgetType.MEDIA, 0, 2),
            Placement(WidgetType.DRIVE_MODE, 2, 2),
            Placement(WidgetType.SHORTCUTS, 4, 2)
        )
    ),
    NIGHT(
        "night", "Đi đêm tối giản", listOf(
            Placement(WidgetType.DRIVE, 0, 0),
            Placement(WidgetType.CLIMATE, 2, 0),
            Placement(WidgetType.MEDIA_LARGE, 4, 0),
            Placement(WidgetType.TILE, 0, 2, TileKind.BLACKOUT),
            Placement(WidgetType.TILE, 1, 2, TileKind.AMBIENT),
            Placement(WidgetType.TILE, 2, 2, TileKind.DEFROST),
            Placement(WidgetType.TILE, 3, 2, TileKind.AUTO_HOLD),
            Placement(WidgetType.DRIVE_MODE, 4, 2)
        )
    ),
    PARK(
        "park", "Đỗ xe / Cắm trại", listOf(
            Placement(WidgetType.CLIMATE, 0, 0),
            Placement(WidgetType.CAR, 2, 0),
            // Parked: watching / listening matters, navigation does not (no split screen here).
            Placement(WidgetType.MEDIA_LARGE, 4, 0),
            Placement(WidgetType.BATTERY, 0, 2),
            Placement(WidgetType.TILE, 2, 2, TileKind.VALET),
            Placement(WidgetType.TILE, 3, 2, TileKind.AVAS),
            Placement(WidgetType.TILE, 4, 2, TileKind.WIRELESS),
            Placement(WidgetType.TILE, 5, 2, TileKind.AMBIENT)
        )
    );

    companion object {
        fun of(id: String?) = values().firstOrNull { it.id == id }
    }
}

/**
 * The Dashboard layout the driver uses: a preset, or a preset they changed in edit mode.
 * Stored as versioned JSON; anything invalid (unknown widget, off-grid, overlap, duplicate)
 * falls back to the "Lái xe hàng ngày" preset, so a bad save can never break the Dashboard.
 */
object DashLayout {
    const val COLUMNS = 6
    const val ROWS = 3
    private const val PREFS = "dashboard"
    private const val KEY_LAYOUT = "layout_v1"
    private const val KEY_PRESET = "preset"
    private const val KEY_REV = "rev"
    private const val CUSTOM = "custom"

    /** Every widget that can be added from the tray (default size of each family + every tile). */
    val catalog: List<Placement> = listOf(
        WidgetType.DRIVE, WidgetType.CLIMATE, WidgetType.CAR, WidgetType.MEDIA, WidgetType.DRIVE_MODE,
        WidgetType.SHORTCUTS, WidgetType.BATTERY, WidgetType.SPLIT
    ).map { Placement(it, 0, 0) } + TileKind.values().map { Placement(WidgetType.TILE, 0, 0, it) }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<Placement> {
        val json = prefs(context).getString(KEY_LAYOUT, null)
        if (json != null) parse(json)?.let { return it }
        return (DashPreset.of(prefs(context).getString(KEY_PRESET, null)) ?: DashPreset.DAILY).placements
    }

    /** Preset the layout still equals, or null once the driver changed it. */
    fun currentPreset(context: Context): DashPreset? = DashPreset.of(prefs(context).getString(KEY_PRESET, null))
        ?: if (prefs(context).getString(KEY_LAYOUT, null) == null) DashPreset.DAILY else null

    /** Changes every time the layout is saved (the Dashboard rebuilds when it differs). */
    fun revision(context: Context) = prefs(context).getInt(KEY_REV, 0)

    fun applyPreset(context: Context, preset: DashPreset) = write(context, preset.placements, preset.id)

    fun save(context: Context, items: List<Placement>) {
        val preset = DashPreset.values().firstOrNull { it.placements.toSet() == items.toSet() }
        write(context, items, preset?.id ?: CUSTOM)
    }

    private fun write(context: Context, items: List<Placement>, presetId: String) {
        val p = prefs(context)
        p.edit()
            .putString(KEY_LAYOUT, toJson(items))
            .putString(KEY_PRESET, presetId)
            .putInt(KEY_REV, p.getInt(KEY_REV, 0) + 1)
            .apply()
    }

    fun toJson(items: List<Placement>): String = JSONObject().apply {
        put("v", 1)
        put("items", JSONArray().apply {
            items.forEach { p ->
                put(JSONObject().apply {
                    put("t", p.type.name)
                    put("c", p.col)
                    put("r", p.row)
                    p.tile?.let { put("k", it.name) }
                })
            }
        })
    }.toString()

    fun parse(json: String): List<Placement>? = try {
        val root = JSONObject(json)
        if (root.optInt("v") != 1) null else {
            val arr = root.getJSONArray("items")
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val type = WidgetType.valueOf(o.getString("t"))
                val tile = if (o.has("k")) TileKind.valueOf(o.getString("k")) else null
                Placement(type, o.getInt("c"), o.getInt("r"), tile)
            }
            items.takeIf { isValid(it) }
        }
    } catch (_: Throwable) {
        null
    }

    fun isValid(items: List<Placement>): Boolean {
        if (items.map { it.key }.toSet().size != items.size) return false
        for ((i, p) in items.withIndex()) {
            if (p.type == WidgetType.TILE && p.tile == null) return false
            if (!fits(p)) return false
            for (j in i + 1 until items.size) if (p.overlaps(items[j])) return false
        }
        return true
    }

    fun fits(p: Placement) = p.col >= 0 && p.row >= 0 && p.col + p.colSpan <= COLUMNS && p.row + p.rowSpan <= ROWS

    /**
     * Drop of [p] at (col, row). Free spot → move; the widgets under it fit into the spot [p]
     * leaves → swap; otherwise they are pushed to the first free spots. Null = not possible
     * (the widget bounces back).
     */
    fun move(items: List<Placement>, p: Placement, col: Int, row: Int): List<Placement>? {
        val moved = p.copy(col = col, row = row)
        if (!fits(moved)) return null
        val others = items - p
        val hit = others.filter { it.overlaps(moved) }
        if (hit.isEmpty()) return others + moved

        // Swap: shift the covered widgets by the opposite offset, into the spot p left.
        val dc = p.col - col
        val dr = p.row - row
        val swapped = (others - hit) + moved + hit.map { it.copy(col = it.col + dc, row = it.row + dr) }
        if (isValid(swapped)) return swapped

        // Push aside: biggest covered widget first, each into the first free spot left.
        val result = ArrayList((others - hit) + moved)
        for (h in hit.sortedByDescending { it.colSpan * it.rowSpan }) {
            result += firstFree(result, h) ?: return null
        }
        return result
    }

    /** "⇲": next size of [p], in place if it fits, else in the first free spot. Null = no room. */
    fun resize(items: List<Placement>, p: Placement): List<Placement>? {
        if (p.type.variants.size < 2) return null
        val resized = p.copy(type = p.type.nextVariant)
        val others = items - p
        if (fits(resized) && others.none { it.overlaps(resized) }) return others + resized
        return firstFree(others, resized)?.let { others + it }
    }

    /** First free spot (row by row) where [p]'s size fits among [items], or null when full. */
    fun firstFree(items: List<Placement>, p: Placement): Placement? {
        for (r in 0 until ROWS) for (c in 0 until COLUMNS) {
            val cand = p.copy(col = c, row = r)
            if (fits(cand) && items.none { it.overlaps(cand) }) return cand
        }
        return null
    }
}
