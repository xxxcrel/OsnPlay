# OsnPlay UI design V1

## Direction

- CarPlay green is the action/status accent. Warm off-white surfaces for daytime;
  muted charcoal-green surfaces for nighttime. Borders and flat surfaces replace
  heavy decoration. The existing opaque green rectangular icon remains the brand.
- Landscape 1920×1080 presentation, with generous touch targets and clear labels.
  A persistent left rail holds Home, Connection and Settings; Car home remains a
  secondary action. Content adapts to smaller windows.
- **Home:** one primary connection action, visible connection state, selected phone,
  USB alternative and shortcuts. The design includes ready, connecting, connected
  and failed states. Failure has a specific next step rather than a generic error.
- **Connection:** wireless/USB selection, three setup steps, concise hotspot form and
  separate device/permission summary. Real implementation keeps manual selection of
  actual network interfaces. The displayed devices, interfaces and permissions are
  explicitly examples.
- **Settings:** categories for appearance, display/performance, audio/microphone,
  connection/startup and diagnostics. Theme applies immediately; connection-related
  changes keep the existing reconnect behavior. Removed eCarX features are excluded.

## System theme behavior

Default to **Follow system**, with manual Light and Dark overrides. This design's
offline HTML follows `prefers-color-scheme` and responds live to OS changes.

The 1.2.0 Android implementation derives colors from
`Configuration.UI_MODE_NIGHT_MASK`, using semantic color resources and `values-night`
variants, and refresh the activity's palette on `onConfigurationChanged`/resume.
The activity now refreshes a semantic palette rather than fixed companion-object
colors. It reads system night mode from application resources to support locale
overrides, with a status-loop fallback for missed callbacks. System bars, dialogs,
inputs, switches and disabled states use the same palette. Page, category, scroll,
hotspot editor drafts and the active CarPlay controller are retained.

## Preview

`osnplay-ui-v1.html` is a design template, with no car/network operations. The export
script embeds the current app icon so the exported file works offline on its own.
The HTML remains the standalone design concept; the native implementation is in
`OsnPlayActivity.kt` and `OsnAppearance.kt`. Native settings save as changes are
applied rather than adding a redundant Save button. Actual readiness replaces
the concept's example status.

```sh
python docs/design/render-osnplay-preview.py --output-dir /path/to/Downloads
```

Requires Pillow, Playwright's Python package and a local Chrome installation. The
script exports a clickable HTML, six page/theme PNGs and one comparison board. It
checks live system theme changes, persisted overrides, page/category navigation,
USB and simulated connection states, and smaller-window horizontal layout.
