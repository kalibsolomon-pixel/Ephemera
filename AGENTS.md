# Ephemera Agent Guidelines

## Default Performance Benchmark Presentation

Whenever presenting performance benchmarks, multi-config comparisons, or before/after FPS gains in this repository, use the standardized interactive HTML benchmark report format as the default presentation.

### 1. Standard Benchmark Protocol
- Run uninstrumented (`--no-jfr`) on a quiet CPU/GPU using the 10-workload suite:
  - **RD 20 (`--suite all --render-distance 20 --duration 4 --no-jfr`)**: `terrain_vista_rd20`, `dense_world_crowd`, `chunk_rebuild_burst`, `gui_pvp_hud`, `mouse_whip_flick`, `block_fluid_storm`, `chunk_flight_load`, `worldgen_frontier`.
  - **RD 32 (`--suite default --render-distance 32 --duration 3 --no-jfr`)**: `terrain_vista_rd32` and `chunk_rebuild_burst_rd32` (mapped from `chunk_rebuild_burst` at RD 32).
- Summarize each configuration with **10-scene geometric means** for `Avg FPS`, `1% Low FPS`, `p99 (ms)`, and `Alloc (MB/s)`.
- Report deltas with correct directionality: **higher is better** for `Avg FPS` and `1% Low FPS`; **lower is better** for `p99 (ms)` and `Alloc (MB/s)`.

### 2. Shareable Standalone HTML Report Format
Generate a self-contained single `.html` file (zero external CDN or network dependencies; save outside tracked git files unless asked to commit) with this exact structure:
- **Themes & Actions**:
  - Default dark theme (`data-theme="dark"`) with a `Light Mode` / `Dark Mode` toggle button and an `Export CSV` button.
  - Dark palette: `--bg: #0c0e14`, `--surface: #131722`, `--surface-2: #1a2030`, `--border: #252d40`, `--fg: #eef2f9`, `--muted: #8e99b3`, `--success: #34d399`, `--warning: #fbbf24`, series colors `--c1: #3b82f6`, `--c2: #2dd4bf`, `--c3: #f59e0b`, `--c4: #a78bfa`, `--c5: #f472b6`.
  - Light palette: `--bg: #f4f6fb`, `--surface: #ffffff`, `--surface-2: #e9eef7`, `--border: #d5dce9`, `--fg: #111827`, `--muted: #5b667a`, `--success: #059669`, `--warning: #d97706`, series colors `--c1: #2563eb`, `--c2: #0d9488`, `--c3: #d97706`, `--c4: #7c3aed`, `--c5: #db2777`.
- **Layout (`max-width: 1020px`)**:
  1. **Header**: Title + concise metadata subtitle (`MC version(s) · GPU · CPU · resolution · mod versions · No JFR`) on the left; `Export CSV` and `Light Mode` buttons right-aligned on the top row.
  2. **Controls bar**: Interactive tab groups for **MC Version** (when multiple versions are tested, e.g. `MC 26.3 | MC 26.2`), **Backend** (`Vulkan | OpenGL`), and **Metric** (`Avg FPS | 1% Low FPS | p99 (ms) | Alloc (MB/s)`).
  3. **KPI Cards**: One card per configuration (`border-top: 3px solid var(--c*)`) showing the 10-scene geomean, `% vs Baseline` (typically Sodium), and optional `vs previous report` delta when comparing against an earlier release.
  4. **Key FPS Gains Strip**: 3 compact highlight cards (e.g. `Ephemera vs Baseline`, `Ephemera + Aurora vs Baseline + Aurora`, and either `Upgrade Gains vs Previous Report` or `Aurora Retention %`).
  5. **Grouped Horizontal Bar Chart**: Color legend + one row per workload (`grid-template-columns: 185px minmax(0, 1fr) 70px`), showing workload title + short description, stacked color-coded bars with compact monospace values (bold on primary Ephemera series), and a right-aligned `% vs Baseline` delta column.
  6. **Full Data Table**: All workloads + highlighted `Geomean (10 workloads)` footer row, using concise column headers (`padding: 8px 8px`) so all columns (including `Eph vs Sod` and `Eph+A vs Sod+A`) fit within `1020px` without horizontal clipping.
  7. **CSV Export**: Downloads all included versions, backends, workloads, and configs with columns `mc_version,backend,workload,config,avg_fps,low_1pct_fps,p99_ms,median_ms,alloc_mb_s`.

### 3. Inline Chat Rendering (T3 Code)
When presenting benchmarks in chat where inline HTML rendering (`html_preview` / `html_render`) is available:
- Render a companion **theme-native inline version** of the interactive chart (version/backend/metric switchers + KPI cards + 10-workload grouped horizontal bars) using T3 CSS variables (`--card`, `--secondary`, `--foreground`, `--muted-foreground`, `--border`, `--success`, `--warning`, `--chart-1`..`--chart-5`, `--radius`, `--font-sans`, `--font-mono`), with no page background or outer card, and visually verify via `html_preview` before calling `html_render`.
- Also provide a clickable link to the full standalone shareable `.html` file (which includes the dark/light toggle, full data table, and CSV export).
