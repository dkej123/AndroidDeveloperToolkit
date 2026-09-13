# Task 042 — reusable primitive mapping

This note records the source of each reusable visual primitive implemented by task 042 and the
intentional IntelliJ-native substitutions. It does not define new visual rules.

## Custom primitives

| Production primitive | Direct design source | Mapping |
|---|---|---|
| `PresetChipRow` | `design/designs/ADB Toolbox Design System.dc.html`, section 06 “Buttons, inputs, presets”; `design/README.md`, Display view | A wrapping, single-selection row of 22px chips. Default selection is neutral/strong, an override selection is amber, and the supplied `Custom…` variant has a dashed border. Left/Right traverse within the row. |
| `LevelChip` | `design/README.md`, Logcat view “Filter row”; `design/tokens/tokens.json`, `logSeverity`; `design/IMPLEMENTATION.md` §3 “preset chips” | A 20px square toggle for the supplied `V D I W E` minimum-level choices. It uses the severity token for its glyph and the accent selection treatment. Filter state and policy remain in the Logcat feature. |

All pixel dimensions and gaps use `JBUI.scale` directly or the already-scaled values exposed by
`AdbToolboxTheme`. Fonts and colors come from the IntelliJ-backed theme mapping; no webfont or raw
production hex value is embedded in either primitive.

## Production asset integration

`AdbToolboxIcons` loads the supplied tool-window and action SVGs from `/icons/…` with IntelliJ's
`IconLoader`; matching `_dark.svg` resources are selected by the platform. The ToolWindow extension
references `/icons/adbToolbox.svg` directly. The supplied 40px Marketplace logo is additionally
packaged as `META-INF/pluginIcon.svg` and `META-INF/pluginIcon_dark.svg`, which is IntelliJ's native
plugin-logo discovery convention. The 16px logo specimens remain available under `/icons/` for
explicit compact usages.

## Token ownership and platform deviations

`AdbToolboxTheme` maps every visual group from `design/tokens/tokens.json`: both color palettes,
Logcat severity palettes, spacing, sizes, radii, type, motion, breakpoints, disabled opacity, and
popup-shadow geometry. The token file's `semantics` object is documentation rather than a numeric
style value; its accent/brand/amber/red/green meanings remain binding at each call site. The
`presets` values are feature-domain input rather than visual styling and stay in
`FontScalePresets` and `DensityPresets`, where the feature tasks validate and test them.

IntelliJ has no portable 600/700 font-weight distinction in `java.awt.Font`, so supplied semibold
and bold roles both use the platform bold font. Body and caption intentionally use the current IDE
label/small fonts, and mono roles derive from the active editor scheme, as required by
`design/README.md` “Fidelity” and `design/IMPLEMENTATION.md` §3. Only values whose token unit is
`px` pass through `JBUI.scale`; milliseconds, counts, letter-spacing ratio, and opacity retain their
semantic units.

## Native IntelliJ substitutions

The following supplied controls deliberately do not get wrapper APIs. This follows
`design/IMPLEMENTATION.md` §3 and keeps task 042 from becoming a generic UI utility layer:

- primary/secondary buttons: `JButton` with native default-button behavior;
- icon/toggle actions: IntelliJ `ActionButton` / `ToggleAction`;
- text and search fields: `JBTextField`, `SearchTextField`, and `ComponentValidator`;
- switches: `JBCheckBox` unless a feature later demonstrates that the supplied compact track cannot
  be represented accessibly by the platform control;
- tooltips: `HelpTooltip`;
- destructive confirmation: `MessageDialogBuilder` with Cancel as the default action.

Toast presentation already belongs to the task 013 feedback overlay. Feature layouts, custom-value
disclosures, validation, disabled-by-device policy, and Logcat filtering remain outside this lane.
