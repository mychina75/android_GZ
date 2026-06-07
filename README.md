# Android Runtime Guide

This document consolidates Android-related integration and deployment content from the project root documentation.

## Additional Demo Overview

- Chinese demo architecture document: [DEMO_SYSTEM_OVERVIEW_ZH.md](DEMO_SYSTEM_OVERVIEW_ZH.md)

## Scope

This guide covers:
- Android Java runtime architecture and package responsibilities
- Full Java package listing and class descriptions
- Phase 1b composition feature pipeline
- Motion capture advisory on Android
- App-side integration with `CameraIntelligenceManager`
- ToolRecommender convenience APIs
- MasterMatch dataset export/import for Android assets
- On-device probe/debug workflow
- Mobile conversion notes (ONNX/TFLite)

For model training, annotation, and research workflows, continue using the root [README.md](../README.md) and [IMPLEMENTATION_GUIDE.md](../IMPLEMENTATION_GUIDE.md).

## Composition Guidance UI (May 2026 update)

This release keeps the Phase A/B/C composition-guidance pipeline in place, simplifies the demo Settings surface, switches the default demo backbone to the Round-3 FastViT ONNX split runtime, and enables FastViT-specific MasterMatch/Mimic assets.

### Default model switch

| Setting | Old | New |
|---------|-----|-----|
| `ModelAssetSelector.DEFAULT_BACKBONE` | `clip_vit_b32` | `fastvit_sa36_round3` |
| Persisted-pref key | `preferred_backbone_v10` | `preferred_backbone_v11` |
| Bundled runtime assets | TFLite-only demo defaults | `assets/models/fastvit_sa36_encoder.onnx` + `assets/models/multi_task_heads.onnx` |
| Mimic bundle | shared 768-d default assets only | `assets/master_match_fastvit_sa36_round3/*` with 1024-d `feature_embedding` |

The migration logic in `ModelAssetSelector.getPreferredBackbone()` clears older persisted preference keys on first launch so existing installs automatically pick up the FastViT default. The simplified Settings dialog now exposes only two selectable backbones: `fastvit_sa36_round3` and `clip_vit_b32`.

### Trigger + Mimic snapshot

Android and Python now share the same canonical 11 trigger nudges:
`nd_filter`, `only_me`, `miniature`, `flash`, `tele_portrait`, `light_box`, `uw_selfie`, `lens_blocked`, `out_of_focus`, `business_card`, `wifi_credential`.

The Android `.so` owns the algorithmic core for:

- trigger L0 scoring, arbitration, and hysteresis
- native CV helpers for bokeh and lens-corner blockage cues
- Mimic LUT generation/composition/apply, tone extraction, tone curves, saturation matrix, MasterMatch top-K, and exposure mapping

Recent trigger retunes tighten `lens_blocked` around strong `finger_cover` evidence and relax `only_me` suppression when the foreground selfie subject is still effectively single-person.

### New canvas overlay widgets (`GuidanceOverlayView`)

All widgets are still gated by the internal `show_guidance_overlay` preference plus `Settings.CompositionGuidanceSettings`, but the simplified demo no longer exposes the composition-guidance switches in Settings. End-users now see only the reduced demo-safe controls, while the composition flags remain available for internal experiments.

| Widget | Phase | Trigger |
|--------|-------|---------|
| **Grid** — rule-of-thirds / golden-ratio (φ-grid) / golden-triangles | A.1 | `GridOverlay.gridType` from `CompositionGuide.selectGrid()` (model heads `has_symmetry`, `has_diagonal_lines` + scene_type) |
| **Subject guide** — dashed line from current subject center → nearest power point with traffic-light coloring | A.2 | `SubjectGuide` overlay; gated by `subject_confidence > 0.5` (or `composition_score` proxy) |
| **Direction arrow** at screen edge with magnitude-based alpha (50–140 px) | A.3 | `DirectionArrow` overlay; only emitted when `composition_score < 0.55` |
| **Composition score badge** — colored circle (≥75 green / 50–74 yellow / <50 orange) near the sensor level indicator | A.4 | `FrameAnalysis.compositionScore` |
| **Fill-ratio gauge** — vertical bar with sweet-spot ticks at 25 % / 70 % | B.1 | `FrameAnalysis.subjectFillRatio` |
| **Dominant-color chips strip** + **B&W badge** | B.2 | `FrameAnalysis.dominantColors`, `FrameAnalysis.suggestBw` |
| **Symmetry mirror lines** — bold center axis + faint dashed mirrors | B.3 | `SymmetryGuideOverlay` |
| **Anchored composition tip card** — single-line, anchored to top-of-bbox | B.5 | guide-text message piped via `setCompositionTip()` |

The legacy bottom-screen rotating text-card layout (`drawTextOverlayCardsLegacy`) remains in the source for reference but is no longer drawn — guidance text is now shown in the existing top status-bar `guide_text` TextView plus the new anchored tip card.

### Settings & threshold configuration

* New `Settings.CompositionGuidanceSettings` inner class (in `android/src/main/java/com/samsung/camera/intelligence/config/Settings.java`) holds per-feature flags + numeric thresholds.
* New asset file [`android/app/src/main/assets/composition_thresholds.json`](app/src/main/assets/composition_thresholds.json) seeds the on-device thresholds at install time. The metadata now tracks the FastViT Round-3 demo default while the thresholds remain conservative heuristics until recalibrated.
* Run [`scripts/calibrate_composition_thresholds.py`](../scripts/calibrate_composition_thresholds.py) with a JSON of per-head sigmoid probabilities + ground-truth labels to derive F1-optimal thresholds via `sklearn.metrics.precision_recall_curve` and overwrite the asset file.

### Color analysis throttling (Phase 1c, already shipped)

`OverlayGenerator` skips full-frame color stats when neither scene/subject changes (configurable via `setBwAnalysisIntervalFrames` / `setColorAnalysisIntervalFrames`). Defaults: B&W every 3 frames, full color every 8 frames, contrast-delta gate 0.15.

### Composition rule-engine fixes (`CompositionGuide`)

* `selectGrid()` now actually emits `golden_triangles` (with diagonal endpoints) and tags the default grid as `rule_of_thirds` so the renderer can branch correctly. Previously the renderer fell back to ROT regardless and the inverted `if`-condition skipped golden-triangle diagonals entirely.
* `checkSubjectPosition()` is re-enabled with a confidence gate (`subjectGuideMinConfidence`, default 0.5) and the DirectionArrow it emits only fires when `compositionScore < directionArrowScoreCeiling` (default 0.55) so well-framed shots stay arrow-free.

### Build

```bash
cd android
./gradlew assembleDebug
# → android/app-debug.apk
```

## Runtime Architecture (Android)

```
Camera Preview Bitmap
  → CameraIntelligenceManager.processFrame()
  → TFLiteInferenceEngine.run()
  → FrameAnalyzer.decode()          ← Phase 1b features extracted here
  → AnalysisBridge                   ← Phase 1b + siglip features mapped
  → OverlayGenerator.generate()      ← 15+ composition rules applied
  → ToolRecommender.recommend()      ← motion advisory + video auto-switch
  → GuidanceFrame + ToolRecommendationResult
```

### Java package mapping (52 files)

Android runtime code is under:

`android/src/main/java/com/samsung/camera/intelligence/`

#### Root — Facade & Bootstrap
| Class | Description |
|-------|-------------|
| `CameraIntelligenceManager` | Top-level facade: TFLite init → frame processing → guidance + recommendations |
| `MasterMatchBootstrap` | Safe MasterMatch asset loading with probe/debug helpers |

#### `models/` — Enums & Data Models (16 files)
| Class | Description |
|-------|-------------|
| `SceneType` | 29 scene categories (portrait, landscape, night, food, sports, …) |
| `LightingCondition` | 12 lighting conditions (very_low_light, backlit, golden_hour, …) |
| `MotionType` | 7 motion types (static, slow, normal, fast, very_fast, repeating, chaotic) |
| `MainSubject` | 23 subject categories (human, animal, food, landscape, …) |
| `CompositionIssue` | 14 composition issue types (off_center, tilted, cluttered, subject_too_small, subject_cut_off, …) |
| `CameraMode` | Camera modes (photo, portrait, night, pro, pro_video, slow_motion, hyperlapse, …) |
| `CameraApp` | App context (camera, expert_raw, gallery, photo_editor) |
| `CameraResolution` | Resolution tiers (MP_12, MP_50, MP_108, MP_200) |
| `ExpertRawMode` | Expert Raw modes (astro, astro_portrait, multi_exposure, nd_filter, virtual_aperture) |
| `FocusMode` | Focus modes (auto, center, multi_point, manual) |
| `MeteringMode` | Metering modes (matrix, center_weighted, spot) |
| `WhiteBalanceMode` | White balance presets (auto, daylight, cloudy, tungsten, …) |
| `ProModeParameters` | Bundled Pro Mode settings (ISO, shutter, EV, WB, focus, metering) |
| `SceneAnalysisResult` | Full scene analysis DTO (scene/lighting/motion/subject, quality metrics, Phase 1b features, motion advisory, raw features) |
| `ToolRecommendation` | Single tool recommendation (name, server, priority, params, confidence, prerequisites) |
| `ToolRecommendationResult` | Complete recommendation result (tools, mode, pro params, conflicts) |

#### `inference/` — TFLite Runtime (4 files)
| Class | Description |
|-------|-------------|
| `TFLiteInferenceEngine` | TFLite model loading + inference (CPU/GPU delegate) |
| `FrameAnalyzer` | Decodes TFLite outputs → `FrameAnalysis` (includes Phase 1b feature extraction) |
| `ImagePreprocessor` | Bitmap → float tensor with backbone-specific normalization |
| `SegmentationRunner` | Lazy TFLite segmentation inference + mask-to-bounding-box conversion |
| `UnifiedSegmentationDecoder` | Decodes combined CLIP-B/16 + SegNeXt `seg_logits` into 5-class masks and bounding boxes |

#### `guidance/` — Real-Time Coaching (22 files)
| Class | Description |
|-------|-------------|
| `CompositionGuide` | Layer 1: 15+ aesthetic composition rules (Phase 1a + 1b) |
| `AngleGuide` | Layer 2: Angle & perspective guidance |
| `TechnicalGuide` | Layer 3: Technical parameter alerts + motion capture limit + shutter/video suggestions |
| `MasterMatchGuide` | Layer 4: Aesthetic transfer (FAISS match → Pro Mode mapping) |
| `OverlayGenerator` | Merges, deduplicates & caps overlays from all layers |
| `TemporalSmoother` | EMA + hysteresis flicker prevention |
| `AnalysisBridge` | `SceneAnalysisResult` ↔ `FrameAnalysis` converter (Phase 1b + siglip feature mapping) |
| `MasterMatchAssetLoader` | Loads master match embeddings + records from Android assets |
| `FrameAnalysis` | Per-frame analysis data (scene, lighting, motion, quality, color, Phase 1b composition) |
| `GuidanceFrame` | Complete per-frame overlay output with `to_json()` |
| `GuidanceOverlay` | Base overlay class |
| `GuidanceCategory` | Overlay category enum (COMPOSITION, TECHNICAL, ANGLE, OBSTRUCTION) |
| `GuidanceUrgency` | Urgency level enum (INFO, SUGGESTION, WARNING, CRITICAL) |
| `GridOverlay` | Composition grid (rule_of_thirds / golden_ratio / golden_triangles + diagonal_points) |
| `HorizonLine` | Tilt correction indicator |
| `DirectionArrow` | Camera shift arrow overlay |
| `SubjectGuide` | Ideal subject placement crosshair |
| `AngleSuggestion` | Pitch recommendation overlay |
| `AlertBadge` | Text/icon alert badge |
| `SymmetryGuideOverlay` | Symmetry centre-line overlay (axis + position) |
| `CompositionTipOverlay` | Technique tip card (tip_text + technique_name) |
| `MasterMatchOverlay` | Matched professional photo card |

#### `recommendation/` — Tool Recommendation (4 files)
| Class | Description |
|-------|-------------|
| `ToolRecommender` | Core recommendation engine: scene → camera/album tools with motion advisory |
| `ParameterOptimizer` | Auto parameter configuration for Pro Mode |
| `ExposureMapper` | DSLR EXIF → Phone Pro Mode EV equivalence (S24U/S25U/S26U profiles) |
| `DefectLocalizer` | Spatial defect localization for post-processing tools, with learned segmentation-first fallback |

#### `config/` — Configuration (5 files)
| Class | Description |
|-------|-------------|
| `Settings` | Global configuration with resolution, guidance, master-match settings |
| `DeviceProfile` | Device-specific capabilities (camera HAL, supported resolutions) |
| `ToolMappings` | Tool definitions, parameter schemas, validation with conflict detection |
| `ToolDefinition` | Single tool definition (name, server, params, requires_mode, incompatible_with) |
| `ToolParameter` | Parameter definition (name, type, range, default) |

### Python → Java Parity

The Java port is fully synchronized with the Python implementation. Key alignment points:

#### Enums & Models
- All enum values match Python 1:1 (29 SceneTypes, 12 LightingConditions, 7 MotionTypes, 14 CompositionIssues, etc.)
- `SceneAnalysisResult` carries all Python fields including motion advisory (`preferVideo`, `recommendedFps`, `motionSpeedTier`, `captureWarning`, `recommendedShutter`) and Phase 1b composition features

#### Inference & Decoding
- `FrameAnalyzer.decode()` extracts 13 composition issue labels (including `subject_too_small`, `subject_cut_off`) + all Phase 1b features (`has_symmetry`, `has_diagonal_lines`, `has_leading_lines`, `subject_fill_ratio`, `subject_count`, `visual_complexity`, `scene_depth_layers`)
- `FrameAnalysis` carries 11 guidance fields: 3 color analysis + 8 Phase 1b composition

### Packaged Model Assets

As of May 2026 the APK exposes only two selectable backbones for demo and Mimic: FastViT-SA36 Round-3 ONNX split (default) and CLIP ViT-B/32 TFLite.

| asset | role | default |
|---|---|:---:|
| `assets/models/fastvit_sa36_encoder.onnx` + `assets/models/multi_task_heads.onnx` | FastViT Round-3 split runtime with 1024-d `feature_embedding` for Mimic / MasterMatch | ✓ |
| `assets/models/multi_task_heads_int8.onnx` | optional INT8 heads variant for NNAPI/NPU experiments on the FastViT split runtime | |
| `assets/models/clip_vit_b32_220Kshadow_v5_dynamic_range.tflite` | bundled CLIP-B/32 TFLite backbone | |

The bundled MasterMatch metadata is now shared v3 records with `camera_make`, `camera_model`, `camera_brand`, and `camera_cluster_id`. FastViT uses its own embeddings bundle under `assets/master_match_fastvit_sa36_round3/`, while CLIP ViT-B/32 continues to use the shared `assets/master_match/` payload.

First-launch and invalid legacy preferences now resolve to FastViT via `preferred_backbone_v11`. Explicit selections stay strict, and recovery from a failed manual switch also returns to FastViT instead of exposing a separate Auto mode.

Debug APK build:

```bash
cd android
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

### Round-3 FastViT ONNX Split

The Android demo can now load the split Round-3 FastViT pair through ONNX Runtime:

| file | role |
|---|---|
| `fastvit_sa36_encoder.onnx` | frozen FastViT-SA36 encoder |
| `multi_task_heads.onnx` | Round-3 scene + trigger heads |

Selection details:

- Backbone key: `fastvit_sa36_round3` (default demo backbone)
- Runtime: `OnnxSplitInferenceEngine` via `com.microsoft.onnxruntime:onnxruntime-android`
- Search locations: bundled `assets/models/` first, then external model directories returned by `ModelAssetSelector.getExternalModelDirectories()`
- MasterMatch assets: bundled `assets/master_match_fastvit_sa36_round3/` with 1000 x 1024 embeddings plus shared v3 records carrying `camera_cluster_id`
- External-file deployment: place both `.onnx` files into the same phone-side model directory, then pick `FastViT-SA36 Round-3 ONNX split` in the app's model selector

Current limitation: the preview runtime keeps the legacy learned L1 trigger head disabled for `fastvit_sa36_round3` until a dedicated Android trigger asset is exported for the Round-3 signal schema. The rule-based L0 trigger path remains enabled. This does not block Mimic / MasterMatch, which uses `multi_task_heads.onnx` `feature_embedding` and the bundled FastViT MasterMatch assets.

#### Guidance Rules
- `CompositionGuide`: 15+ rules matching Python — includes scene-aware grid selection (golden ratio / golden triangles / rule of thirds), Phase 1b rules (symmetry, fill ratio, rule of odds, leading lines, diagonals, depth layers, simplicity), and Phase 1a aesthetic tips (isolate subject, human interest, color/B&W)
- `TechnicalGuide`: 9 rules including 3 motion-specific (capture limit, shutter recommendation, video switch)
- `AnalysisBridge`: Prefers `siglipFeatures` for feature embedding (falls back to `featureEmbedding`), maps all Phase 1b fields

#### Overlay Types
- 10 overlay types: `GridOverlay` (with `diagonalPoints`), `HorizonLine`, `DirectionArrow`, `SubjectGuide`, `AngleSuggestion`, `AlertBadge`, `SymmetryGuideOverlay`, `CompositionTipOverlay`, `MasterMatchOverlay`, `GuidanceOverlay`

#### Recommendation Logic
- `ToolRecommender.recommend()`: prefer_video auto-switch, NIGHT_PORTRAIT flash, motion photo human-subject guard, face-count group aspect ratio, FPS-based video mode overrides, 180° shutter rule, capture warning annotation
- Motion shutter: continuous flow magnitude → tier-based shutter denominator (same thresholds as Python `MotionCaptureAdvisor`)
- Tool validation: passes parameters (not just names) to `ToolMappings.validateToolSequenceWithParams()` for mode-gated checks
- Convenience APIs: `getMacroRecommendations()`, `getLowLightRecommendations()`, `validateModeCompatibility()`, `getAllAvailableModes()`, `getExpertRawCapabilityCheck()`, `getObjectRemovalRecommendation()`, `getSceneDetectionTool()`

## App Integration Quick Start

```java
CameraIntelligenceManager manager = new CameraIntelligenceManager(context);
manager.initialize(context, "models/intelligent_camera.tflite", false);

CameraIntelligenceManager.FrameResult result = manager.processFrame(previewBitmap);
if (result != null) {
    GuidanceFrame guidance = result.guidanceFrame;
    // Render overlays from guidance payload
}
```

### Manager API lifecycle

- `initialize(context, modelPath, useGpu)`: set up TFLite runtime
- `processFrame(bitmap)`: run analysis + guidance + recommendation output
- `processAnalysis(frameAnalysis)`: run guidance + recommendation from pre-built FrameAnalysis
- `reset()`: clear temporal state on camera/lens/mode change
- `release()`: release runtime resources
- `enableMasterMatchFromAssets(context, embPath, recPath)`: load MasterMatch from assets
- `getToolRecommender()` / `getOverlayGenerator()`: access internal engines

## Phase 1b Composition Features (Android)

The TFLite model outputs Phase 1b compositional features. The Android pipeline decodes and uses them end-to-end:

Note on Phase 1b compatibility:

- Android does not expose a CLI-style switch equivalent to Python `--disable-phase1b-composition-features`, because Android is inference-only and does not consume training targets.
- If a deployed TFLite model omits some Phase 1b heads, `FrameAnalyzer.decode()` falls back to safe defaults (`false`, `0.0`, `1`, etc.) for missing outputs, so runtime guidance remains functional.
- The recent Android `OverlayGenerator` sync now also applies low-frequency cached color analysis, matching the Python guidance-path behavior more closely without requiring a separate Phase 1b runtime toggle.
- Android overlay post-processing now matches Python capping behavior for high-frequency hints, including separate caps for `SymmetryGuideOverlay` and `CompositionTipOverlay`, which keeps the guidance payload stable on repeated frames.

Practical interpretation:

- Python `--disable-phase1b-composition-features` is a train/evaluate target-availability switch.
- Android remains inference-safe without that switch because missing optional outputs are decoded to conservative defaults.
- Cross-platform guidance behavior is now aligned in the two main runtime-sensitive areas introduced recently: cached color analysis cadence and overlay capping.

### Data Flow

```
TFLiteInferenceEngine.run()
  → FrameAnalyzer.decode()
      ├── 13 composition issue labels (incl. subject_too_small, subject_cut_off)
      └── Phase 1b: has_symmetry, has_diagonal_lines, has_leading_lines,
                     subject_fill_ratio, subject_count, visual_complexity,
                     scene_depth_layers
  → FrameAnalysis (11 Phase 1b + color fields)
  → AnalysisBridge → SceneAnalysisResult (14 extended fields)
  → CompositionGuide.evaluate() → Phase 1b overlays
```

### Phase 1b Composition Rules

| Rule | Trigger | Result |
|------|---------|--------|
| Symmetry | `hasSymmetry=true` | `SymmetryGuideOverlay` (vertical axis at 0.5) |
| Fill Ratio | `subjectFillRatio < 0.15` or `> 0.85` | `AlertBadge` (move closer/farther) |
| Rule of Odds | Even `subjectCount` (2/4/6) | `CompositionTipOverlay` |
| Leading Lines | `hasLeadingLines=true` | `CompositionTipOverlay` |
| Diagonals | `hasDiagonalLines=true` | `GridOverlay` (golden_triangles + diagonalPoints) |
| Depth Layers | `sceneDepthLayers=1` in suitable scene | `CompositionTipOverlay` |
| Simplicity | `visualComplexity > 0.8` | `AlertBadge` (simplify composition) |

### Scene-Aware Grid Selection

```java
// CompositionGuide.selectGrid() logic:
// Architecture, has_symmetry → golden_ratio (φ points)
// Landscape + has_diagonal_lines → golden_triangles (diagonal points)
// All other → rule_of_thirds (default)
```

## Motion Capture Advisory (Android)

The `ToolRecommender` integrates continuous flow-based motion tier classification matching Python's `MotionCaptureAdvisor`:

### Tier → Shutter Mapping

| Flow (px/frame) | Tier | Shutter Denom | ISO Compensation |
|-----------------|------|--------------|------------------|
| 0–2 | STATIC | 60 | — |
| 2–8 | GENTLE | 125 | — |
| 8–20 | MODERATE | 250 | — |
| 20–50 | FAST | 500 | min 400 |
| 50–100 | VERY_FAST | 1000 | min 800 |
| 100–200 | EXTREME | 2000 | min 800 |
| >200 | UNCAPTURABLE | 4000 | min 800 |

### Video Auto-Switch

When `SceneAnalysisResult.isPreferVideo()` returns `true` (set by motion advisory when flow > threshold):
1. `recommend()` auto-switches to video mode
2. FPS-based mode selection: ≥240 → SLOW_MOTION, ≥120 → SLOW_MOTION, ≥60 → PRO_VIDEO, else VIDEO
3. 180° shutter rule: `video_shutter = 1/(targetFps × 2)`
4. `Camera_VideoFPS` tool emitted when recommended FPS > 30

## ToolRecommender Convenience APIs (Java)

```java
ToolRecommender recommender = manager.getToolRecommender();
```

| Method | Return | Description |
|--------|--------|-------------|
| `recommend(scene)` | `ToolRecommendationResult` | Core recommendation with auto mode, resolution, Pro params, post-processing |
| `getMacroRecommendations(scene)` | `List<ToolRecommendation>` | Pro mode + manual focus + spot metering for close-up |
| `getLowLightRecommendations(scene, useFlash)` | `List<ToolRecommendation>` | Night/Pro mode with optimized ISO/shutter |
| `validateModeCompatibility(mode, toolNames)` | `List<String>` | Check tool compatibility (Pro-only, Night restrictions, Food WB) |
| `getAllAvailableModes()` | `Map<String, List<String>>` | Photo/video/special/expert_raw mode categories |
| `getExpertRawCapabilityCheck()` | `List<ToolRecommendation>` | Check Expert Raw labs support |
| `getObjectRemovalRecommendation(hasObjects, desc)` | `ToolRecommendation` | Gallery object remover (null if no objects) |
| `getSceneDetectionTool(app)` | `ToolRecommendation` | Scene detection for Camera or Expert Raw |

### Scene → Mode Mapping (Demo Build)

The recommendation engine maps detected scenes and subjects to camera modes via `buildSceneModeMap()` and `buildSubjectModeMap()` in `ToolRecommender`.

**All portrait/person-related scenes are mapped to Pro mode** so that the demo always surfaces Pro controls for human subjects.

| Scene | Mode | Notes |
|-------|------|-------|
| `PORTRAIT` | **Pro** | Single person portrait |
| `GROUP_PORTRAIT` | **Pro** | Multiple people |
| `SELFIE` | **Pro** | Front/rear camera selfie |
| `BACKLIT_PORTRAIT` | **Pro** | Backlit person |
| `NIGHT_PORTRAIT` | **Pro** | Night-time portrait |
| `NIGHT` | Night → Photo | Remapped to Photo in demo |
| `NIGHT_CITYSCAPE` | Night → Photo | Remapped to Photo in demo |
| `NIGHT_SKY` | Night → Photo | Remapped to Photo in demo |
| `FOOD` | Food | |
| `PANORAMIC` | Panorama | |
| `FAST_MOVING` | **Pro** | High-speed subject |
| `SPORTS` | **Pro** | Sports action |
| `WATERFALL` | **Pro** | Long exposure candidate |
| `REPEATING_MOTION` | **Pro** | Cyclic motion |
| `VEHICLE` | **Pro** | Moving vehicle |
| `WILDLIFE` | **Pro** | Distant animal/bird |
| `PET` | Photo | Domestic animal |
| Others | Photo | Landscape, architecture, macro, document, product, general |

**Subject → Mode fallback** (when scene is not in the table):

| Subject | Mode |
|---------|------|
| `HUMAN_SINGLE` | **Pro** |
| `HUMAN_GROUP` | **Pro** |
| `HUMAN_FACE` | **Pro** |
| `HUMAN_FULL_BODY` | **Pro** |
| `ANIMAL_WILDLIFE` | **Pro** |
| `ANIMAL_BIRD` | **Pro** |
| `FOOD_DISH` / `FOOD_INGREDIENT` / `FOOD_DRINK` | Food |
| Others | Photo |

**Additional overrides:**
- Night lighting + human subject/face → **Pro** (active even when scene is not explicitly NIGHT_PORTRAIT)

## Segmentation Model Integration (Android)

Phase 3 adds an Android-side segmentation path for post-processing localization.
This path complements the existing heuristic defect detectors and is used for:

- `shadow`
- `reflection`
- `flare`
- `background_people`

### Runtime Components

| Class | Responsibility |
|-------|----------------|
| `SegmentationRunner` | Loads a `.tflite` segmentation model lazily, runs per-pixel inference, and converts masks into normalized bounding boxes |
| `DefectLocalizer` | Registers segmentation runners per defect type and prefers them before heuristic localization |

### Execution Flow

```text
Main model defect flag = true
    -> DefectLocalizer.localize(bitmap, scene)
    -> trySegmentationModel(defectType)
    -> SegmentationRunner.runSegmentation(bitmap)
    -> maskToBoundingBoxes(...)
    -> target areas attached to post-processing recommendations
```

If no segmentation model is registered, Android keeps the old behavior and falls
back to heuristic localization. This preserves backward compatibility.

### Supported Model Mapping

| Defect Type | Typical Model | Default Input |
|-------------|---------------|---------------|
| `SHADOW` | U2-Net-lite style shadow segmenter | `256x256` |
| `REFLECTION` | UNet-lite reflection segmenter | `256x256` |
| `FLARE` | UNet-lite flare segmenter | `256x256` |
| `BACKGROUND_PEOPLE` | MobileSeg-style person segmenter | `320x320` |

### Model Registration Example

Register TFLite segmentation models during app startup after creating the
recommendation stack:

```java
DefectLocalizer localizer = new DefectLocalizer();

localizer.registerSegmentationModel(
        DefectLocalizer.DefectType.SHADOW,
        new SegmentationRunner(context, "models/segmentation/shadow_seg.tflite", 256, 256)
);

localizer.registerSegmentationModel(
        DefectLocalizer.DefectType.REFLECTION,
        new SegmentationRunner(context, "models/segmentation/reflection_seg.tflite", 256, 256)
);

localizer.registerSegmentationModel(
        DefectLocalizer.DefectType.FLARE,
        new SegmentationRunner(context, "models/segmentation/flare_seg.tflite", 256, 256)
);

localizer.registerSegmentationModel(
        DefectLocalizer.DefectType.BACKGROUND_PEOPLE,
        new SegmentationRunner(context, "models/segmentation/people_seg.tflite", 320, 320)
);
```

### Asset Placement

Recommended asset layout:

```text
android/src/main/assets/
    models/
        segmentation/
            shadow_seg.tflite
            reflection_seg.tflite
            flare_seg.tflite
            people_seg.tflite
```

`SegmentationRunner` supports both:

- asset-relative file names such as `models/segmentation/shadow_seg.tflite`
- absolute filesystem paths when models are provisioned outside the APK

### Output Format

`SegmentationRunner` provides two output forms:

1. raw probability mask: `float[H][W]`
2. backward-compatible defect regions: `List<DefectLocalizer.DefectRegion>`

`DefectLocalizer.LocalizationResult` also stores raw masks in:

- `masks.get("shadow")`
- `masks.get("reflection")`
- `masks.get("flare")`
- `masks.get("background_people")`

This allows the Android app to support both:

- legacy box-based editing tools
- future mask-aware editing workflows

### Tool Delivery Implications

When a segmentation mask is available, Android still converts it to boxes for the
existing recommendation contract. This means current tools keep working without
schema changes, while mask data remains available for later use by object-removal
or inpainting flows.

For parity with Python Phase 3 behavior:

- learned segmentation is attempted first
- heuristic detection remains the fallback
- mask-derived boxes are attached to tool parameters as localized target areas

## MasterMatch on Android

### 1) Build and export assets offline (Python)

Build master index first:

```bash
python scripts/build_master_index.py \
    --photos_dir data/master_photos/images/ \
    --metadata data/master_photos/metadata.json \
    --output_dir data/master_photos/ \
    --backbone siglip \
    --device cuda
```

Export Android-ready assets:

```bash
python scripts/export_mastermatch_android.py \
    --index data/master_photos/master_index.faiss \
    --metadata data/master_photos/master_index.json \
    --output_dir android/src/main/assets/master_match
```

Generated files:
- `master_match_embeddings.json`
- `master_match_records.json`

No on-device image re-embedding is required when these assets are present.

### 1b) Generate card thumbnails (optional, recommended)

Each Mimic card can show a 160×110 WebP thumbnail of the matched professional
photo. Generate and pack thumbnails from local source images:

```bash
python scripts/generate_thumbnails.py \
    --quality 75
```

The script:
1. Reads all 1,000 `photo_id` entries from `android/src/main/assets/master_index.jsonl`
2. Loads the corresponding source JPEG from the local dataset directory
   (`/home/nvme03/li.zuo/data/master_match/selected_images/{photo_id}.jpg`)
3. Center-crops and resizes to 160×110 px, saves as WebP quality 75
4. Writes to `android/src/main/assets/master_match/thumbnails/{photo_id}.webp`
5. Updates `thumbnail_url` in `master_match_records.json` to the asset-relative path
   `master_match/thumbnails/{photo_id}.webp`

Options:
- `--dry-run` — scan and report without writing any files
- `--force` — re-generate thumbnails that already exist
- `--quality N` — WebP quality 1–100 (default: 75)

Generated files:
- `android/src/main/assets/master_match/thumbnails/*.webp` (1,000 files, ~3.1 MB total)

At runtime, `MimicModePanelController` loads thumbnails asynchronously from assets.
If a thumbnail is missing for a card, the `ImageView` stays hidden — no crash.

Asset layout after generation:
```text
android/src/main/assets/master_match/
    master_match_embeddings.bin       ← main embedding index (binary)
    master_match_records.json         ← 1,000 records + updated thumbnail_url
    thumbnails/
        000301443.webp
        000303608.webp
        …  (1,000 files, ~3.1 MB total)
```

### 2) Enable in app

```java
CameraIntelligenceManager manager = new CameraIntelligenceManager(context);
manager.initialize(context, "model.tflite");

boolean ok = manager.enableMasterMatchFromAssets(
    context,
    "master_match/master_match_embeddings.json",
    "master_match/master_match_records.json"
);
```

### 3) Recommended bootstrap with fallback

```java
CameraIntelligenceManager manager = new CameraIntelligenceManager(context);
manager.initialize(context, "model.tflite");

boolean masterMatchEnabled = MasterMatchBootstrap.configureDefault(context, manager);
// If false: warning logged and MasterMatch disabled, app flow continues
```

`MasterMatchBootstrap.configureDefault(...)` will:
1. Verify both assets exist in `android/src/main/assets/master_match/`
2. Load vectors + records into `MasterMatchGuide`
3. If loading fails, call `manager.disableMasterMatch()` and keep runtime safe

### 4) Device-model profile selection (S24U/S25U/S26U)

Exposure mapping now supports model-based profile selection on both Python and Android runtimes.

**Android (Java):**
```java
// Auto-select profile by model string (best-effort):
// S24U: default profile
// S25U: predicted profile (currently same aperture/range assumptions as S24U)
// S26U: predicted profile (f/1.4 assumption)
ExposureMapper mapper = new ExposureMapper("SM-S938B");  // S25 Ultra
```

**Python:**
```python
from recommendation.exposure_mapper import ExposureMapper

mapper = ExposureMapper.from_device_model("SM-S948U")  # S26 Ultra
```

Supported Python profile constants:
- `SAMSUNG_GALAXY_S24U_MAIN`
- `SAMSUNG_GALAXY_S25U_MAIN` (predicted)
- `SAMSUNG_GALAXY_S26U_MAIN` (predicted)

Current calibrated assumptions (to be updated after real HAL validation):
- S24U: `f/1.7`, ISO `50~3200`, shutter `1/12000~30s`
- S25U (predicted): `f/1.7`, ISO `50~3200`, shutter `1/12000~30s`
- S26U (predicted): `f/1.4`, ISO `50~3200`, shutter `1/12000~30s`

These values are predictive placeholders for S25U/S26U and may change with final firmware/HAL constraints.

### 5) HAL field capture snippet (copy-paste)

Use the following minimal Android snippet to print calibration fields from `CameraCharacteristics`:

```java
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;
import android.util.Range;

import java.util.Arrays;

public final class CameraHalProbe {
    private static final String TAG = "CameraHalProbe";

    private CameraHalProbe() {}

    public static void dumpMainCameraHal(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.w(TAG, "CameraManager is null");
            return;
        }
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics cc = manager.getCameraCharacteristics(cameraId);

                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }

                float[] apertures = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                Range<Integer> isoRange = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                Range<Long> expRangeNs = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                int[] aeModes = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
                int[] awbModes = cc.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);

                Log.i(TAG, "cameraId=" + cameraId);
                Log.i(TAG, "apertures=" + Arrays.toString(apertures));
                Log.i(TAG, "isoRange=" + (isoRange == null ? "null" : isoRange));
                Log.i(TAG, "exposureRangeNs=" + (expRangeNs == null ? "null" : expRangeNs));
                Log.i(TAG, "aeModes=" + Arrays.toString(aeModes));
                Log.i(TAG, "awbModes=" + Arrays.toString(awbModes));

                if (expRangeNs != null) {
                    double minSec = expRangeNs.getLower() / 1_000_000_000.0;
                    double maxSec = expRangeNs.getUpper() / 1_000_000_000.0;
                    Log.i(TAG, "exposureRangeSec=[" + minSec + ", " + maxSec + "]");
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to read camera HAL fields", e);
        }
    }
}
```

How to backfill profiles from the output:
- `LENS_INFO_AVAILABLE_APERTURES` → `fixed_aperture`
- `SENSOR_INFO_SENSITIVITY_RANGE` → `base_iso` + `max_usable_iso` (after noise-based practical cap)
- `SENSOR_INFO_EXPOSURE_TIME_RANGE` (ns) → `min_shutter` / `max_shutter`
- Pro UI or HAL observed stepping → `valid_isos` / `valid_shutters`

## Style Straw — Gallery Tone Extraction (风格吸管)

The **Style Straw** feature lets users pick a photo from the device gallery and
extract its tone/color signature, which is then applied to the live camera
preview via the existing Mimic mode LUT pipeline.  No network access or model
inference is required — extraction runs entirely on-device in ~10 ms.

### How it works

1. User taps the **"+"** button below the three MasterMatch cards.
2. System gallery picker opens (`ActivityResultContracts.GetContent`, MIME `image/*`).
3. Selected photo is decoded at ≤512 px (via `inSampleSize`) for analysis.
4. `StyleStrawExtractor.extractToneParams(Bitmap)` computes six parameters in a
   single-pass pixel scan:
   - **contrast** — luminance standard-deviation relative to neutral 0.18
   - **highlights** — mean luminance of bright pixels (L > 0.65) vs neutral 0.72
   - **shadows** — mean luminance of dark pixels (L < 0.35) vs neutral 0.28
   - **saturation** — mean HSL saturation vs neutral 0.35
   - **highlight_warmth** — circular mean hue of bright warm pixels vs 30°
   - **shadow_tint** — circular mean hue of dark cool pixels vs 220°
5. A `MasterMatchOverlay` is built from the extracted params and loaded into
   gallery card slot 3 (the 4th card).
6. Params flow through the existing `applyMimicOverlayParams → CameraProSettings
   → LutToneMapper → GPU shader` pipeline unchanged.

### Key files

| File | Purpose |
|------|---------|
| `StyleStrawExtractor.java` | Single-pass BT.709 tone/color extraction |
| `MimicModePanelController.java` | Gallery slot 3, add-straw/close/replace button wiring |
| `MainActivity.java` | Gallery picker, persistence (`SharedPreferences` + thumbnail file) |
| `activity_main.xml` | `mimic_add_straw_btn`, `mimic_card_3_wrapper` with overlay buttons |
| `ic_add_straw.xml` | Vector drawable for the standalone "+" button |
| `ic_straw_close.xml` | Semi-transparent circle-× overlay (top-right of card) |
| `ic_straw_replace.xml` | Semi-transparent circle-+ overlay (top-left of card) |

### Persistence

Gallery card data is persisted to `SharedPreferences` (`intelligent_camera_prefs`)
and the thumbnail is saved to internal storage (`straw_thumb.png`).  On app
restart, the card is automatically restored when the user enters Mimic mode.

Persisted keys: `straw_uri`, `straw_title`, `straw_contrast`, `straw_highlights`,
`straw_shadows`, `straw_saturation`, `straw_warmth`, `straw_tint`.

### Card overlay buttons

- **Top-left ⊕** (`ic_straw_replace.xml`): opens gallery to pick a new photo,
  replacing the current gallery card.
- **Top-right ⊗** (`ic_straw_close.xml`): removes the gallery card and clears
  persisted data, restoring the standalone "+" button.

### Limitations

- Maximum one gallery card at a time (re-picking replaces the previous).
- Hue-based parameters (warmth/tint) require ≥50 qualifying pixels; otherwise
  they default to 0 (neutral).

## Auto Scene Optimization — 15-Mode Enhancement Engine

The **Auto Scene Optimization** feature detects the current scene type, lighting
condition and subject, then automatically applies scene-specific tone grading,
shader enhancements, and sharpening to the live preview.  It operates entirely
on the GPU via the existing LUT + shader pipeline and introduces no ISP-level
changes, preserving the analysis stream from feedback loops.

### Modes

| # | Mode | Emoji | Trigger (Scene / Lighting / Subject) | Tier |
|---|------|-------|--------------------------------------|------|
| 1+22 | Backlit Face Lift + HDR | 🌤️ | backlit + person | T2 |
| 4 | Golden Glow | 🌅 | golden_hour | T1 |
| 6 | Blue Hour Cool | 🌆 | blue_hour | T1 |
| 7 | Digital GND | 🏔️ | landscape + high brightness | T2 |
| 8 | Architecture Sharpen | 🏛️ | architecture | T3 |
| 9 | Food Boost | 🍽️ | food | T1 |
| 12 | Macro Detail | 🔍 | macro / flower | T3 |
| 13 | Starry Sky | 🌌 | night + low brightness | T2 |
| 15 | Sunset Warmth | 🌇 | sunset | T1 |
| 16 | Night Vision | 🌙 | night | T1 |
| 19 | Pet Fur | 🐾 | pet | T3 |
| 21 | Film Simulation | 🎞️ | studio | T1 |
| 23 | B&W Conversion | ⬛ | suggestBw = true | T1 |
| 24 | Consistency Lock | 🔒 | same scene ≥5 s | T1 |

### Three-tier GPU Pipeline

- **T1 (LUT only)**: Scene-tuned 6-parameter tone grading (contrast, highlights,
  shadows, saturation, highlight warmth, shadow tint) applied via the existing
  33³ 3D LUT shader.
- **T2 (Shader extensions)**: Additional uniform-gated GLSL blocks in the main
  fragment shader for radial face-lift + HDR, graduated ND filter, and star sky
  enhancement.
- **T3 (FBO + USM)**: Multi-pass Unsharp Mask using 3 FBOs: camera→FBO,
  5-tap Gaussian blur (horizontal + vertical), then composite
  `sharp = original + strength × (original − blur)` before LUT grading.

### Anti-feedback Lock

Scene enhancement uses the same temporal stabilization pattern as Pro Auto-Tone:
- Minimum 5-second lock after each parameter change.
- Re-computation only when scene type, lighting condition, or main subject
  changes, or when brightness drifts by >20%.

### Settings Toggle

A new **"Auto Scene Optimization"** checkbox appears in the Settings dialog.
The feature is disabled by default and persisted to `SharedPreferences`.
It operates in all modes except Mimic, and coexists with Pro Auto-Tone
(scene optimization takes priority when a mode matches).

### Key Files

| File | Purpose |
|------|---------|
| `SceneEnhancementOptimizer.java` | Scene matching engine + 15 mode baselines + anti-feedback lock |
| `CameraGLRenderer.java` | T2 uniforms + T3 FBO/USM multi-pass rendering |
| `CameraGLPreview.java` | `updateEnhancement()`, `updateUsm()`, `clearEnhancements()` pass-throughs |
| `MainActivity.java` | Settings toggle, `handleFrame` integration, apply/clear helpers |

## First-device Debug Checklist (3 lines)

## Android App (New) - End-to-End Integration

An executable Android app module now exists under:

- `android/app/`

The app wires the existing Java intelligence runtime to a camera UI with:

- CameraX preview and frame analysis
- `CameraIntelligenceManager.processFrame()` real-time inference
- Overlay rendering (`GuidanceOverlayView`) for composition/technical guidance
- Real previous-frame motion input (not zeros) for accurate motion detection
- Recommendation panel with filtered tool list (PhotoEditor_/Gallery_ tools hidden during live preview)
- AI mode recommendation dialog with one-tap Pro auto-fill
- Photo preview overlay after capture with EXIF rotation correction
- Post-capture photo analysis with editing tool suggestion dialog
- Collapsible bottom toolbar (▼/▲ toggle) to reduce preview occlusion
- Direction arrows with magnitude-based length and triangle arrowheads
- Recommendation execution pipeline (`RecommendationExecutor`)
- MasterMatch bootstrap (`MasterMatchBootstrap.configureDefault(...)`)

### Direct Pro Parameter Control (ISO / Shutter / EV / WB / Focus)

The app supports **direct** manual parameter setting in Pro mode:

1. UI input fields (`ISO`, `Shutter`, `EV`, `WB`, `Focus`) in `activity_main.xml`
2. `MainActivity.applyManualProSettings()` forces logical mode to `Pro`
3. `CameraController.applyDirectProSettings(...)` writes values into active state
4. `CameraController.applyCurrentProSettings()` applies Camera2 capture request options:

    - `SENSOR_SENSITIVITY` (ISO)
    - `SENSOR_EXPOSURE_TIME` (shutter in ns)
    - `CONTROL_AE_MODE = OFF` (manual exposure path in Pro mode)
    - `CONTROL_AWB_MODE` (WB mapping)
    - `CONTROL_AF_MODE` (focus mode mapping)
    - `setExposureCompensationIndex(...)` (EV compensation)

This ensures manual Pro settings are not just displayed but actually sent to camera controls.

### Recommended Parameters -> Direct Apply

`RecommendationExecutor` maps recommender outputs to direct hardware/app actions:

- `Camera_ChangeIso` -> `CameraController.applyIso(...)`
- `Camera_ChangeShutterSpeed` -> `CameraController.applyShutter(...)`
- `Camera_ChangeEV` -> `CameraController.applyEv(...)`
- `Camera_ChangeWhiteBalance` -> `CameraController.applyWhiteBalance(...)`
- `Camera_ChangeFocusMode` -> `CameraController.applyFocus(...)`
- `Camera_ChangeMeteringMode` -> `CameraController.applyMetering(...)`

The "Apply AI" button executes the ordered recommendation set in one step.

### AI Mode Recommendation Dialog

When the model detects the current camera mode is suboptimal, `checkAndSuggestModeSwitch()` shows a dialog with:

- **Parameter changes** explaining what settings would change (e.g. "ISO → 800, Shutter → 1/60") instead of a generic "Optimal mode" message
- **Switch** button: auto-switches mode; if switching to Pro, calls `fillProParamsFromRecommendation()` to auto-fill ISO/shutter/EV/WB/focus from the latest inference result, and expands the Pro controls panel
- **Stay** button: dismisses without changes
- Guard conditions: dialog is suppressed while the photo preview is visible, while another dialog is already showing, if auto mode suggestion is disabled, or if the same mode was already recommended
- Recommended modes are mapped through demo mode mapping (video modes → Photo)

### Pro Auto-Fill on Mode Switch

When the user manually selects Pro mode (not just via the dialog), `switchMode("PRO")` automatically:
1. Expands the bottom extras container and shows the Pro controls panel
2. Calls `fillProParamsFromRecommendation()` to populate input fields from the latest `ToolRecommendationResult`
3. Maps: `Camera_ChangeIso` → ISO field, `Camera_ChangeShutterSpeed` → Shutter field, `Camera_ChangeEV` → EV field, `Camera_ChangeWhiteBalance` → WB field, `Camera_ChangeFocusMode` → Focus field

### Motion Frame Input (Real Previous Frame)

`FrameAnalyzer` stores the previous camera frame in a `previousFrame` field. On each `analyze()` call:
- The previous frame is preprocessed with `NormMode.RAW` (simple [0,1] scaling) and fed as TFLite input 2 (motion_frame)
- The old previous frame bitmap is recycled to prevent memory leaks
- First frame uses zeros (no previous frame available)

This provides real temporal motion data instead of always-zero motion input.

### Filtered Recommendation List

During live camera preview, the recommendation panel filters out `PhotoEditor_*` and `Gallery_*` tools (these are post-processing tools not actionable during shooting). These tools are shown only in the post-capture analysis dialog.

### Collapsible Bottom Toolbar

The bottom panel uses a ▼/▲ toggle button (`btn_toggle_extras`):
- Default state: extras hidden (recommendation list + pro controls collapsed)
- Tap ▼: expands `bottom_extras_container` with animated visibility
- Tap ▲: collapses back
- Auto-expands when switching to Pro mode

This reduces occlusion of the camera preview during normal shooting.

## End-to-End Capture Workflow

The app uses a simplified demo-oriented capture workflow:

### Camera Modes (Demo)

Available modes in the mode picker:
- **Photo** — Default photo mode (also the mapping target for Portrait, Night, Single Take, Hyperlapse, Slow Motion, Dual Recording)
- **Pro** — Manual exposure controls (ISO, shutter, EV, WB, focus)
- **Food** — Optimized for food photography
- **Panorama** — Panoramic capture
- **Mimic** — MasterMatch mode: captures a photo, matches the scene against the Master Photo Index, shows match info, and on confirmation applies matched parameters to the camera

Video-related modes (VIDEO, PRO_VIDEO, PORTRAIT_VIDEO) are disabled in the demo.

### UI Controls

- `Capture` button for still photo capture (aligned horizontally with mode selector)
- Mode selector button opens simplified mode picker
- Recommendation panel + one-click apply (via ▼/▲ toggle)
- **Photo preview overlay** after capture (full-screen with close button)
- **Post-capture editing tool recommendations** displayed directly as an overlay on the photo preview area (no popup dialog)
- **Settings** button opens a settings dialog with configurable options

### Settings Dialog

The Settings button opens a dialog with the following options:
1. **Demo UI** — Toggle between demo mode (clean preview, debug info hidden) and debugging mode (full diagnostic overlays)
2. **Show composition guidance** — Toggle visibility of composition guidance tips on the preview
3. **Show guidance overlay** — Toggle visibility of the guidance overlay (grid lines, arrows, etc.)
4. **Auto mode suggestion** — Toggle automatic mode switch recommendations
5. **Model Selection** — Button to open the model backbone selection dialog

### Mimic (MasterMatch) Mode

When the user selects Mimic mode and taps the capture button:
1. A photo is captured and shown in preview
2. The photo is analyzed against the Master Photo Index via `CameraIntelligenceManager.processFrame()`
3. A dialog shows the match result including scene type, lighting, and recommended parameters
4. If the user taps "Apply Parameters", the app switches to Pro mode and fills the parameter inputs with the matched values

### AI Mode Recommendation Dialog

When the model detects the current camera mode is suboptimal, `checkAndSuggestModeSwitch()` shows a dialog with:

- **Parameter changes** explaining what settings would change (e.g. "ISO → 800, Shutter → 1/60, WB → tungsten") instead of a generic "Optimal mode" message
- Recommended modes are mapped through `toDemoMode()` (video modes map to Photo)
- **Switch** button: auto-switches mode; if switching to Pro, calls `fillProParamsFromRecommendation()` to auto-fill parameters
- **Stay** button: dismisses without changes
- Can be disabled via the "Auto mode suggestion" setting

### Pro Parameter Sync

When switching camera modes, the Pro input fields (ISO, shutter, EV, WB, focus) are automatically synchronized with the current camera controller parameters via `syncProInputsFromCamera()`. This ensures the input fields always reflect the actual camera state.

### Direction Arrows & Guidance Overlays

`GuidanceOverlayView` renders real-time guidance overlays on the camera preview:

- **Direction arrows**: `drawArrow()` renders arrows with magnitude-based length (60–180px mapped from guidance magnitude) and filled triangle arrowheads via `Path`
- **Guidance tips**: `drawTip()` renders at y=200f (moved down from y=50f to avoid overlap with the status bar area)

## Pro Capability Guard (Option 1)

To ensure manual controls are safe and device-compatible, `CameraController` now probes
Camera2 capabilities and clamps parameters before applying:

- `SENSOR_INFO_SENSITIVITY_RANGE` -> clamp ISO
- `SENSOR_INFO_EXPOSURE_TIME_RANGE` -> clamp shutter time (ns)
- `CONTROL_AE_AVAILABLE_MODES` -> verify manual exposure support (`AE_MODE_OFF`)

If manual exposure is unavailable, it automatically falls back to AE ON.

This prevents invalid ISO/shutter combinations from crashing or silently failing on devices
with narrower HAL ranges.

## Post-Processing Executor (Real Execution + Fallback)

`PostProcessingDispatcher` now executes tools through a hybrid strategy:

- Local execution available now:
    - `Gallery_AutoFit` (light enhancement)
    - `Gallery_Crop` / `PhotoEditor_SmartCrop` (center crop by aspect ratio)
    - `Gallery_AutoTilt` (safe no-op fallback)
- Remote execution (optional):
    - Unsupported tools are delegated to `RemotePostProcessingService` when configured.
- No service configured:
    - Unsupported tools are queued as pending and do not break camera/capture flow.

This means the app remains stable even without a post-processing backend.

1. Call `MasterMatchBootstrap.configureDefaultWithProbe(context, manager)` and verify one probe log: `MasterMatch enabled=true/false`.
2. To re-check without app restart, call `MasterMatchBootstrap.resetProbeForDebug()` (debug build only).
3. Call `configureDefaultWithProbe(...)` again and verify probe log appears once again.

### Probe helpers

```java
boolean enabled = MasterMatchBootstrap.configureDefaultWithProbe(context, manager);
// Logs once per process: [Probe] MasterMatch enabled=true/false

boolean reset = MasterMatchBootstrap.resetProbeForDebug();
// Debug build: true + "[Probe] reset complete"
// Release build: false + warning, no reset
```

## Mobile conversion notes (ONNX/TFLite)

### ONNX export (static batch recommended for TFLite)

```bash
python deployment/convert_cli.py --backbone clip_vit_b32 --input_size 224 --no_tflite --static_batch
python -m deployment.convert_cli --backbone mobileclip_s2 --input_size 256 --no_tflite --static_batch
```

### ONNX→TF/TFLite conversion

```bash
python scripts/run_onnx2tf.py
```

Windows alternate environment:

```bash
C:\v\onnx2tf\Scripts\python.exe scripts\run_onnx2tf.py
```

Notes:
- Outputs are written under `deployment_outputs/<backbone_name>/`

## One-Click Device Regression

Use the Gradle task below to run end-to-end device validation for TFLite models under `deployment_outputs/tinynn/`.

Prerequisites:
- Android phone connected via USB
- USB debugging enabled and authorized
- `sdk.dir` configured in `android/local.properties` or `ANDROID_HOME` set

Run:

```bash
cd android
./gradlew.bat :app:runDeploymentOutputsDeviceSmokeTest --no-daemon -q
```

What it does:
- Builds `debug` app and `androidTest` APKs
- Installs both APKs to the connected device
- Runs `TFLiteDeploymentOutputsDeviceTest`
- Fails with non-zero exit code if model loading or inference fails

## Build APK Locally

Generate installable APKs on your machine:

```bash
cd android
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
```

Output files:
- `android/app/build/outputs/apk/debug/app-debug.apk`
- `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Install to phone:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

## TFLite Model Copy Paths On Device

The app checks external model files before packaged assets.
For each external model directory, it always tries `intelligent_camera.tflite` first.
If you explicitly select a backbone in app settings, it then also tries `<backbone>.tflite` in the same external directories.
Only after those external candidates miss does it fall back to packaged assets.

Recommended model directories on phone:
- `/sdcard/Android/data/com.samsung.camera.intelligence.app/files/models/`
- `/sdcard/Download/intelligent_camera/models/`

Supported filenames:
- `intelligent_camera.tflite` for Auto mode override
- `<backbone>.tflite` only when that backbone is explicitly selected in app settings

Copy example:

```bash
adb shell mkdir -p /sdcard/Download/intelligent_camera/models
adb push deployment_outputs/tinynn/intelligent_camera.tflite /sdcard/Download/intelligent_camera/models/intelligent_camera.tflite
```

## Related files

### Core Android
- Android facade: [CameraIntelligenceManager.java](src/main/java/com/samsung/camera/intelligence/CameraIntelligenceManager.java)
- MasterMatch bootstrap: [MasterMatchBootstrap.java](src/main/java/com/samsung/camera/intelligence/MasterMatchBootstrap.java)
- Asset loader: [MasterMatchAssetLoader.java](src/main/java/com/samsung/camera/intelligence/guidance/MasterMatchAssetLoader.java)

### Guidance (Phase 1b)
- Composition guide: [CompositionGuide.java](src/main/java/com/samsung/camera/intelligence/guidance/CompositionGuide.java)
- Technical guide: [TechnicalGuide.java](src/main/java/com/samsung/camera/intelligence/guidance/TechnicalGuide.java)
- Bridge: [AnalysisBridge.java](src/main/java/com/samsung/camera/intelligence/guidance/AnalysisBridge.java)
- Symmetry overlay: [SymmetryGuideOverlay.java](src/main/java/com/samsung/camera/intelligence/guidance/SymmetryGuideOverlay.java)
- Composition tip: [CompositionTipOverlay.java](src/main/java/com/samsung/camera/intelligence/guidance/CompositionTipOverlay.java)
- Grid overlay: [GridOverlay.java](src/main/java/com/samsung/camera/intelligence/guidance/GridOverlay.java)

### Inference & Models
- Frame analyzer: [FrameAnalyzer.java](src/main/java/com/samsung/camera/intelligence/inference/FrameAnalyzer.java)
- Frame analysis: [FrameAnalysis.java](src/main/java/com/samsung/camera/intelligence/guidance/FrameAnalysis.java)
- Scene analysis result: [SceneAnalysisResult.java](src/main/java/com/samsung/camera/intelligence/models/SceneAnalysisResult.java)
- Composition issues: [CompositionIssue.java](src/main/java/com/samsung/camera/intelligence/models/CompositionIssue.java)

### Recommendation
- Tool recommender: [ToolRecommender.java](src/main/java/com/samsung/camera/intelligence/recommendation/ToolRecommender.java)
- Exposure mapper: [ExposureMapper.java](src/main/java/com/samsung/camera/intelligence/recommendation/ExposureMapper.java)
- Tool mappings: [ToolMappings.java](src/main/java/com/samsung/camera/intelligence/config/ToolMappings.java)

### Python Scripts
- Export script: [scripts/export_mastermatch_android.py](../scripts/export_mastermatch_android.py)
- Build master index: [scripts/build_master_index.py](../scripts/build_master_index.py)

---

## Mimic Mode — Camera-Style Baked LUT Pipeline (format_version 3)

When a master record carries `camera_cluster_id` (`format_version >= 3`), the
Mimic flow composes a **baked 3D-LUT** with the per-photo tone overlay and
pushes the result directly to the GL preview, instead of building the LUT
from tone parameters alone.

The baked `camera_luts` assets are **not** tied to any specific visual
encoder. They are derived from `camera_clusters.json` cluster centroids
(`tone_saturation`, `tone_highlight_warmth`, `tone_shadow_tint`) and can be
shared across FastViT and CLIP as long as those backbones point to
records carrying the same `camera_cluster_id` contract.

### Asset layout

```
app/src/main/assets/
  camera_luts/
    cluster_<id>_<label>.png       # 1089×33 ARGB atlas (33³ LUT)
    manifest.json                   # {k:8, files:[{cluster_id,label,file},…]}
  master_match/
    master_match_records.json      # format_version 3 (camera_make / camera_model / camera_brand / camera_cluster_id at top level)
    camera_clusters.json           # cluster summaries (member_count, top_camera_brands, mean_tone_params)
```

### Java classes

| Class | Responsibility |
|-------|----------------|
| `app.camera.CameraStylePresetManager` | Parses `camera_luts/manifest.json`, decodes cluster PNGs into an LRU-cached `Bitmap` (size 8). `loadClusterLut(int)`/`labelOf(int)`/`isAvailable()`. **Returned bitmap is owned by the cache — callers must `.copy()` it before handing it to the GL renderer.** |
| `app.camera.LutComposer` | `compose(Bitmap baseLut, c, h, s, sat, warmth, tint) → Bitmap`. Builds the 1-D tone curve via the same algorithm as `LutToneMapper`, applies it per pixel of the baked LUT, then applies BT.709-luminance saturation and smoothstep-weighted warmth/tint. Writes the result to a fresh 1089×33 ARGB atlas. |
| `app.camera.CameraController` | New `lutOverrideActive` flag + `applyDirectLutBitmap(Bitmap)` / `clearLutOverride()`. While the override is active `applyToneCurve` skips the GPU LUT update; ISO/shutter/EV/WB/AF still flow through `applyDirectProSettings`. |
| `guidance.MasterMatchAssetLoader` | Reads `format_version` (warns if `> 3`), parses `camera_make`/`camera_model`/`camera_brand`/`camera_cluster_id` (nullable) and forwards them through the new 11-arg `MasterRecord` constructor (old 7-arg constructor preserved for back-compat). |
| `guidance.MasterMatchGuide` | Injects `camera_cluster_id`/`camera_brand`/`camera_make`/`camera_model` into the `proParams` Map returned by `evaluate()` so they survive into `MasterMatchOverlay`. |
| `app.MainActivity.applyMimicOverlayParams` | New cluster-LUT branch: when `camera_cluster_id` is present and `stylePresetManager.isAvailable()`, composes and pushes the baked LUT before forwarding the remaining Camera2 settings (ISO/shutter/EV/WB/AF) with `null` tone fields. Falls back to the legacy tone-only path otherwise. |
| `app.ui.MimicModePanelController` | Card info text shows photographer + compact tone summary only (camera brand/model hidden). |

### Capture-follows-preview

`CameraController.capturePhoto()` already snapshots `glPreview.getCurrentLutBitmap()` into the JPEG path — capture inherits the composed cluster LUT for free, no separate capture-side work was required.

### Backward compatibility

- `format_version <= 2` records have no `camera_cluster_id`; the cluster branch is skipped and the existing tone-only path runs.
- `format_version > 3` records load with a warning and any unknown extra fields are ignored (forward-compatible).
- The 8-bitmap LRU cache + lazy decode keep the cold-start cost well under the 1.6 MB asset budget (8 PNGs ≈ 81 KB total after the 10-D tone+hue+sensor re-cluster).
