package com.mipago.ex2center.ui.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout rules the Dashboard edit mode relies on (run with `./gradlew :app:testDebugUnitTest`). */
class DashLayoutTest {

    private val daily = DashPreset.DAILY.placements

    private fun cells(items: List<Placement>) = items.sumOf { it.colSpan * it.rowSpan }

    @Test
    fun everyPresetIsValidAndFillsTheGrid() {
        for (preset in DashPreset.values()) {
            assertTrue("${preset.id} invalid", DashLayout.isValid(preset.placements))
            assertEquals("${preset.id} leaves holes", DashLayout.COLUMNS * DashLayout.ROWS, cells(preset.placements))
        }
    }

    @Test
    fun jsonRoundTripKeepsTheLayout() {
        for (preset in DashPreset.values()) {
            val back = DashLayout.parse(DashLayout.toJson(preset.placements))
            assertEquals(preset.placements.toSet(), back?.toSet())
        }
    }

    @Test
    fun brokenOrUnknownJsonIsRejected() {
        assertNull(DashLayout.parse("not json"))
        assertNull(DashLayout.parse("""{"v":2,"items":[]}"""))
        assertNull(DashLayout.parse("""{"v":1,"items":[{"t":"NOPE","c":0,"r":0}]}"""))
        // Off the grid
        assertNull(DashLayout.parse("""{"v":1,"items":[{"t":"DRIVE","c":5,"r":0}]}"""))
        // Overlap
        assertNull(DashLayout.parse("""{"v":1,"items":[{"t":"DRIVE","c":0,"r":0},{"t":"CAR","c":1,"r":1}]}"""))
        // Same family twice (two climate widgets)
        assertNull(DashLayout.parse("""{"v":1,"items":[{"t":"CLIMATE","c":0,"r":0},{"t":"CLIMATE_COMPACT","c":2,"r":0}]}"""))
        // Tile without a kind
        assertNull(DashLayout.parse("""{"v":1,"items":[{"t":"TILE","c":0,"r":0}]}"""))
    }

    @Test
    fun moveIntoEmptySpot() {
        val media = daily.first { it.type == WidgetType.MEDIA }
        val withoutShortcuts = daily.filter { it.type != WidgetType.SHORTCUTS }
        val result = DashLayout.move(withoutShortcuts, media, 4, 2)
        assertNotNull(result)
        assertTrue(DashLayout.isValid(result!!))
        assertEquals(Placement(WidgetType.MEDIA, 4, 2), result.first { it.type == WidgetType.MEDIA })
    }

    @Test
    fun dropOnSameSizeWidgetSwaps() {
        val drive = daily.first { it.type == WidgetType.DRIVE }   // (0,0) 2×2
        val result = DashLayout.move(daily, drive, 4, 0)            // onto CAR (4,0) 2×2
        assertNotNull(result)
        assertEquals(Placement(WidgetType.DRIVE, 4, 0), result!!.first { it.type == WidgetType.DRIVE })
        assertEquals(Placement(WidgetType.CAR, 0, 0), result.first { it.type == WidgetType.CAR })
        assertTrue(DashLayout.isValid(result))
        assertEquals(daily.size, result.size)
    }

    @Test
    fun dropOnTwoSmallerWidgetsSwapsBoth() {
        // Night preset row 2: four 1×1 tiles at cols 0..3. Drag DRIVE_MODE (4,2) 2×1 onto tiles 0–1.
        val night = DashPreset.NIGHT.placements
        val mode = night.first { it.type == WidgetType.DRIVE_MODE }
        val result = DashLayout.move(night, mode, 0, 2)
        assertNotNull(result)
        assertTrue(DashLayout.isValid(result!!))
        assertEquals(Placement(WidgetType.DRIVE_MODE, 0, 2), result.first { it.type == WidgetType.DRIVE_MODE })
        assertEquals(night.size, result.size)
    }

    @Test
    fun moveOffTheGridIsRefused() {
        val drive = daily.first { it.type == WidgetType.DRIVE }
        assertNull(DashLayout.move(daily, drive, 5, 0))
        assertNull(DashLayout.move(daily, drive, 0, 2))
    }

    @Test
    fun resizeNeedsRoom() {
        // Full grid: Media 2×1 → 2×2 has no room.
        val media = daily.first { it.type == WidgetType.MEDIA }
        assertNull(DashLayout.resize(daily, media))
        // Climate 2×2 → 2×1 always fits in place.
        val climate = daily.first { it.type == WidgetType.CLIMATE }
        val smaller = DashLayout.resize(daily, climate)
        assertNotNull(smaller)
        assertEquals(Placement(WidgetType.CLIMATE_COMPACT, 2, 0), smaller!!.first { it.key == "climate" })
        // And back: 2×1 → 2×2 in place once the freed cell is still empty.
        val bigger = DashLayout.resize(smaller, smaller.first { it.key == "climate" })
        assertEquals(daily.toSet(), bigger?.toSet())
    }

    @Test
    fun widgetsWithOneSizeCannotResize() {
        assertNull(DashLayout.resize(daily, daily.first { it.type == WidgetType.DRIVE }))
        assertFalse(WidgetType.TILE.variants.size > 1)
    }

    @Test
    fun firstFreeFindsTheHoleOrNull() {
        assertNull(DashLayout.firstFree(daily, Placement(WidgetType.TILE, 0, 0, TileKind.VALET)))
        val withHole = daily.filter { it.type != WidgetType.SHORTCUTS }
        assertEquals(Placement(WidgetType.TILE, 4, 2, TileKind.VALET),
            DashLayout.firstFree(withHole, Placement(WidgetType.TILE, 0, 0, TileKind.VALET)))
    }

    @Test
    fun catalogHasEveryFamilyAndTileOnce() {
        val keys = DashLayout.catalog.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(TileKind.values().all { "tile:${it.name}" in keys })
    }
}
