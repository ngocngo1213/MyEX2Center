# UI/UX audit — 8-point grid & touch targets (in-car tablet)

**Device:** Geely EX2 head unit IHU629G — 1920×1080 landscape, 160 dpi (1 dp = 1 px), viewed from ~75 cm, operated with one outstretched hand (left-hand-drive: the left column is closest to the driver).

**Method:** layouts and code-built screens were measured on the emulator with a `uiautomator` dump of every clickable view (only views that actually receive taps), plus a static scan of every `dp` value in layouts, drawables and code.

## Criteria

| Rule | Value | Source |
|---|---|---|
| Minimum touch target | 48 dp | Material / UI UX Pro Max `touch-target-size` |
| Controls used while driving | ≥ 56 dp, primary 64 dp | In-car glance & reach |
| Gap between touch targets | ≥ 8 dp (12 dp between driving controls) | `touch-spacing` |
| Spacing / sizes | multiples of 8 dp (4 and 12 as half-steps) | `spacing-scale` |
| Text | ≥ 14 sp; key values 24–72 sp | Glanceability |

Tokens live in `app/src/main/res/values/dimens.xml` (`space_4…space_32`, `touch_min/drive/primary`, `touch_gap`, `icon_sm/md`, `radius_*`, `slider_touch_height`).

## Results

| Screen | Before | After |
|---|---|---|
| Dashboard | 35 targets · **2 < 48 dp** (Ghi nhớ switches 40 dp) · **24 pairs < 8 dp apart** (6 dp gaps) · **sliders 24 dp tall** | 35 targets · 0 < 48 dp · 0 gaps < 8 dp · sliders 56 dp |
| Settings | switches 64×32 dp tappable inside 78 dp rows · rows 1 dp apart | whole 84 dp row is the only target · rows 9 dp apart |
| App store | header buttons 48–52 dp | 56 dp |
| Split-screen bar | 48 dp buttons inside a 48 dp bar (clipped) | 56 dp buttons, 12 dp gaps, 72 dp bar |
| Grid | 40 distinct dp values, ~150 off the 4 dp grid (6, 10, 14, 22, 34, 46…) | layouts fully on grid (except 1 dp hairlines); code on grid |

## Changes

- **Driving controls:** drive mode / regen 64 dp, climate rows and ± buttons 64 dp, windows 64 dp, media 56 dp; 12 dp between adjacent buttons.
- **Sliders:** 56 dp touch height, 8 dp track, 28 dp thumb with emerald ring (temperature, fan, volume).
- **HUD glanceability:** speed 72 sp, range and battery 24 sp.
- **Header:** 64 dp bar, 56 dp buttons and pills; speed-limit sign 64 dp.
- **Settings:** switches are visual only (not separately tappable); 80 dp+ rows with 4 dp air around dividers; segmented theme buttons 64 dp.
- **Floating bubble:** 64 dp, snaps to the nearest side edge after a drag and stays out of the top 112 dp header band and the bottom 96 dp gesture area (also re-applied to a saved position).
- **Corners:** cards 16 dp, buttons 12 dp, small 4–8 dp.

## Remaining / not measurable here

- The floating overlay panel is a system overlay window: not captured by `uiautomator`; checked in code (buttons 56 dp, ± 56 dp).
- Final check on the real head unit (glare, viewing angle, actual reach) is still to be done.
