package com.samsung.camera.intelligence.recommendation;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.samsung.camera.intelligence.models.SceneAnalysisResult;

import java.util.*;

/**
 * Conditional post-processing pipeline for spatial defect localization.
 * Ported from Python DefectLocalizer in recommendation/defect_localizer.py.
 *
 * When binary flags (hasShadow, hasReflection, etc.) are True, lightweight
 * heuristic detectors run and return bounding-box regions for downstream tools.
 *
 * NOTE: Unlike the Python version which uses OpenCV + NumPy, this Java version
 * uses lighter-weight Android Bitmap operations. For production use, consider
 * integrating OpenCV Android SDK for the full feature set.
 */
public class DefectLocalizer {

    // ---------------------------------------------------------------
    // Data classes
    // ---------------------------------------------------------------

    public enum DefectType {
        SHADOW("shadow"),
        REFLECTION("reflection"),
        BACKGROUND_PEOPLE("background_people"),
        FLARE("flare"),
        MOIRE("moire");

        public final String value;
        DefectType(String value) { this.value = value; }
    }

    public static class BoundingBox {
        public final float x, y, w, h;
        public final float confidence;

        public BoundingBox(float x, float y, float w, float h, float confidence) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.confidence = confidence;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", x); map.put("y", y);
            map.put("w", w); map.put("h", h);
            map.put("confidence", confidence);
            return map;
        }
    }

    public static class DefectRegion {
        public final DefectType defectType;
        public final BoundingBox bbox;
        public final float severity;
        public final String description;

        public DefectRegion(DefectType type, BoundingBox bbox, float severity, String desc) {
            this.defectType = type;
            this.bbox = bbox;
            this.severity = severity;
            this.description = desc;
        }
    }

    public static class LocalizationResult {
        public final List<DefectRegion> regions = new ArrayList<>();
        public final List<String> detectorsRun = new ArrayList<>();
        public final List<String> detectorsSkipped = new ArrayList<>();
        /** Raw masks keyed by defect type value (e.g. "shadow"). Available when
         *  segmentation models are registered via {@link #registerSegmentationModel}. */
        public final Map<String, float[][]> masks = new LinkedHashMap<>();

        public boolean hasDefects() { return !regions.isEmpty(); }

        public List<DefectRegion> getRegionsByType(DefectType type) {
            List<DefectRegion> result = new ArrayList<>();
            for (DefectRegion r : regions) {
                if (r.defectType == type) result.add(r);
            }
            return result;
        }

        /** Check whether a pixel-level mask is available for a defect type. */
        public boolean hasMask(DefectType type) {
            return masks.containsKey(type.value);
        }

        /** Get the raw probability mask for a defect type, or null. */
        public float[][] getMask(DefectType type) {
            return masks.get(type.value);
        }

        /** Maps tool name → list of target bounding boxes for tool recommendations. */
        public Map<String, List<Map<String, Object>>> getToolTargetAreas() {
            Map<DefectType, String> toolMap = new HashMap<>();
            toolMap.put(DefectType.SHADOW, "PhotoEditor_RemoveShadow");
            toolMap.put(DefectType.REFLECTION, "PhotoEditor_removeReflection");
            toolMap.put(DefectType.BACKGROUND_PEOPLE, "PhotoEditor_removeBackgroundPeople");
            toolMap.put(DefectType.FLARE, "PhotoEditor_RemoveFlare");
            toolMap.put(DefectType.MOIRE, "PhotoEditor_RemoveMoire");

            Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
            for (DefectRegion region : regions) {
                String toolName = toolMap.get(region.defectType);
                if (toolName != null) {
                    result.computeIfAbsent(toolName, k -> new ArrayList<>())
                            .add(region.bbox.toMap());
                }
            }
            return result;
        }
    }

    // ---------------------------------------------------------------
    // Shadow detector (heuristic)
    // ---------------------------------------------------------------

    private final float darkThreshRatio;
    private final float satThresh;
    private final float minRegionRatio;
    private final float maxRegionRatio;

    public DefectLocalizer() {
        this(0.45f, 60f, 0.005f, 0.40f);
    }

    public DefectLocalizer(float darkThreshRatio, float satThresh,
                           float minRegionRatio, float maxRegionRatio) {
        this.darkThreshRatio = darkThreshRatio;
        this.satThresh = satThresh;
        this.minRegionRatio = minRegionRatio;
        this.maxRegionRatio = maxRegionRatio;
    }

    // ---------------------------------------------------------------
    // Segmentation model registry (Step 3.3)
    // ---------------------------------------------------------------

    /** Registered TFLite segmentation runners keyed by DefectType. */
    private final Map<DefectType, com.samsung.camera.intelligence.inference.SegmentationRunner>
            segmentationRunners = new HashMap<>();

    /**
     * Optional zero-shot prompt-based segmenter (MobileSAM). When set, defect
     * regions produced by heuristic detectors are refined into pixel masks by
     * SAM using the heuristic bbox as a box prompt. Skipped for moire (no
     * spatial boundary) and for any region whose heuristic confidence is below
     * {@link #samMinPromptConfidence}.
     */
    private com.samsung.camera.intelligence.inference.MobileSamRunner mobileSam;
    private float samMinPromptConfidence = 0.55f;

    /** Defect types eligible for SAM refinement. Moire is intentionally excluded. */
    private final Set<DefectType> samEligible = new HashSet<>(Arrays.asList(
            DefectType.SHADOW,
            DefectType.REFLECTION,
            DefectType.FLARE,
            DefectType.BACKGROUND_PEOPLE));

    /**
     * Register a TFLite segmentation model for a defect type.
     * When registered, {@link #localize} will prefer it over the heuristic detector
     * and also populate {@link LocalizationResult#masks}.
     *
     * @param defectType   Which defect this model handles
     * @param runner       A configured {@link com.samsung.camera.intelligence.inference.SegmentationRunner}
     */
    public void registerSegmentationModel(
            DefectType defectType,
            com.samsung.camera.intelligence.inference.SegmentationRunner runner) {
        segmentationRunners.put(defectType, runner);
    }

    /** Attach a MobileSAM runner used for zero-shot mask refinement. */
    public void setMobileSam(com.samsung.camera.intelligence.inference.MobileSamRunner runner) {
        this.mobileSam = runner;
    }

    /** Override the minimum heuristic confidence required to trigger SAM. */
    public void setSamMinPromptConfidence(float c) {
        this.samMinPromptConfidence = c;
    }

    public boolean isMobileSamAvailable() {
        return mobileSam != null && mobileSam.isAvailable();
    }

    public float[][] segmentObjectAtPoint(Bitmap bitmap, float xNorm, float yNorm) {
        if (mobileSam == null || !mobileSam.isAvailable() || bitmap == null) {
            return null;
        }
        float pointX = clamp01(xNorm);
        float pointY = clamp01(yNorm);
        float boxHalf = 0.14f;
        android.graphics.RectF promptBox = new android.graphics.RectF(
            clamp01(pointX - boxHalf), clamp01(pointY - boxHalf),
            clamp01(pointX + boxHalf), clamp01(pointY + boxHalf));
        float[][] mask = mobileSam.runWithBoxAndPoint(bitmap, promptBox, pointX, pointY);
        return mask != null ? mask : mobileSam.runWithPoint(bitmap, pointX, pointY);
    }

    /**
     * Run conditional defect localization based on scene analysis flags.
     *
     * @param bitmap Full-resolution image bitmap
     * @param scene  Scene analysis result with binary defect flags
     * @return LocalizationResult with detected defect regions
     */
    public LocalizationResult localize(Bitmap bitmap, SceneAnalysisResult scene) {
        LocalizationResult result = new LocalizationResult();

        // Shadow detection
        if (scene.isHasShadow()) {
            try {
                if (trySegmentationModel(bitmap, DefectType.SHADOW, result)) {
                    result.detectorsRun.add("ShadowSegModel");
                } else {
                    List<DefectRegion> shadows = detectShadows(bitmap);
                    result.regions.addAll(shadows);
                    result.detectorsRun.add("ShadowDetector");
                    refineWithSam(bitmap, shadows, DefectType.SHADOW, result);
                }
            } catch (Exception e) {
                result.detectorsSkipped.add("ShadowDetector");
            }
        } else {
            result.detectorsSkipped.add("ShadowDetector");
        }

        // Reflection detection
        if (scene.isHasReflection()) {
            try {
                if (trySegmentationModel(bitmap, DefectType.REFLECTION, result)) {
                    result.detectorsRun.add("ReflectionSegModel");
                } else {
                    List<DefectRegion> reflections = detectReflections(bitmap);
                    result.regions.addAll(reflections);
                    result.detectorsRun.add("ReflectionDetector");
                    refineWithSam(bitmap, reflections, DefectType.REFLECTION, result);
                }
            } catch (Exception e) {
                result.detectorsSkipped.add("ReflectionDetector");
            }
        } else {
            result.detectorsSkipped.add("ReflectionDetector");
        }

        // Flare detection
        if (scene.isHasFlare()) {
            try {
                if (trySegmentationModel(bitmap, DefectType.FLARE, result)) {
                    result.detectorsRun.add("FlareSegModel");
                } else {
                    List<DefectRegion> flares = detectFlare(bitmap);
                    result.regions.addAll(flares);
                    result.detectorsRun.add("FlareDetector");
                    refineWithSam(bitmap, flares, DefectType.FLARE, result);
                }
            } catch (Exception e) {
                result.detectorsSkipped.add("FlareDetector");
            }
        } else {
            result.detectorsSkipped.add("FlareDetector");
        }

        // Background people
        if (scene.isHasBackgroundPeople()) {
            if (trySegmentationModel(bitmap, DefectType.BACKGROUND_PEOPLE, result)) {
                result.detectorsRun.add("PeopleSegModel");
            } else if (mobileSam != null && mobileSam.isAvailable()) {
                // Without an upstream people detector, prompt SAM with a coarse
                // "lower thirds" box that empirically captures background figures.
                List<DefectRegion> seeds = new ArrayList<>();
                seeds.add(new DefectRegion(
                        DefectType.BACKGROUND_PEOPLE,
                        new BoundingBox(0.05f, 0.45f, 0.9f, 0.5f, 0.6f),
                        0.6f,
                        "Background-people candidate region (lower-half heuristic)"));
                refineWithSam(bitmap, seeds, DefectType.BACKGROUND_PEOPLE, result);
                if (result.hasMask(DefectType.BACKGROUND_PEOPLE)) {
                    result.detectorsRun.add("BackgroundPeople+MobileSAM");
                } else {
                    result.detectorsSkipped.add("BackgroundPeopleDetector (SAM produced no mask)");
                }
            } else {
                result.detectorsSkipped.add("BackgroundPeopleDetector (requires ML Kit or segmentation model)");
            }
        } else {
            result.detectorsSkipped.add("BackgroundPeopleDetector");
        }

        // Moiré detection (FFT-based heuristic)
        if (scene.isHasMoire()) {
            try {
                List<DefectRegion> moireRegions = detectMoire(bitmap);
                result.regions.addAll(moireRegions);
                result.detectorsRun.add("MoireDetector");
            } catch (Exception e) {
                result.detectorsSkipped.add("MoireDetector");
            }
        } else {
            result.detectorsSkipped.add("MoireDetector");
        }

        return result;
    }

    // ---------------------------------------------------------------
    // Segmentation model dispatch
    // ---------------------------------------------------------------

    /**
     * Try to run a registered segmentation model for a defect type.
     * On success, adds regions and raw mask to the result.
     *
     * @return true if a segmentation model was available and ran successfully
     */
    private boolean trySegmentationModel(Bitmap bitmap, DefectType type, LocalizationResult result) {
        com.samsung.camera.intelligence.inference.SegmentationRunner runner =
                segmentationRunners.get(type);
        if (runner == null) return false;

        float[][] mask = runner.runSegmentation(bitmap);
        if (mask == null) return false;

        result.masks.put(type.value, mask);
        List<DefectRegion> regions = runner.maskToBoundingBoxes(
                mask, type, 0.5f, minRegionRatio, maxRegionRatio);
        result.regions.addAll(regions);
        return true;
    }

    /**
     * Refine heuristic bounding boxes into pixel masks using MobileSAM.
     * Stores the union mask under {@link LocalizationResult#masks}. The original
     * heuristic regions are kept (their bbox is also used as the SAM prompt).
     */
    private void refineWithSam(Bitmap bitmap,
                               List<DefectRegion> regions,
                               DefectType type,
                               LocalizationResult result) {
        if (mobileSam == null || regions == null || regions.isEmpty()) return;
        if (!samEligible.contains(type)) return;
        if (!mobileSam.isAvailable()) return;

        int imageWidth = bitmap.getWidth();
        int imageHeight = bitmap.getHeight();
        float[][] union = result.masks.get(type.value);

        for (DefectRegion region : regions) {
            if (region.bbox.confidence < samMinPromptConfidence) continue;
            float left = region.bbox.x;
            float top = region.bbox.y;
            float right = Math.min(1f, left + region.bbox.w);
            float bottom = Math.min(1f, top + region.bbox.h);
            android.graphics.RectF box = new android.graphics.RectF(left, top, right, bottom);
            float[][] mask = mobileSam.runWithBox(bitmap, box);
            if (mask == null) continue;
            if (union == null) {
                union = new float[imageHeight][imageWidth];
            }
            for (int yCoord = 0; yCoord < imageHeight; yCoord++) {
                for (int xCoord = 0; xCoord < imageWidth; xCoord++) {
                    if (mask[yCoord][xCoord] > union[yCoord][xCoord]) {
                        union[yCoord][xCoord] = mask[yCoord][xCoord];
                    }
                }
            }
        }

        if (union != null) {
            result.masks.put(type.value, union);
            if (!result.detectorsRun.contains("MobileSAM")) {
                result.detectorsRun.add("MobileSAM");
            }
        }
    }

    // ---------------------------------------------------------------
    // Heuristic detectors
    // ---------------------------------------------------------------

    /**
     * Detect shadow regions by finding dark low-saturation areas.
     * Simplified version — uses grid-based block analysis instead of pixel-level connected components.
     */
    private List<DefectRegion> detectShadows(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int gridSize = 16;
        int blockW = w / gridSize;
        int blockH = h / gridSize;

        // Compute overall median brightness (approximate with center sampling)
        float medianBrightness = getRegionBrightness(bitmap, w / 4, h / 4, w * 3 / 4, h * 3 / 4);
        float darkThresh = medianBrightness * darkThreshRatio;

        List<DefectRegion> regions = new ArrayList<>();

        for (int gy = 0; gy < gridSize; gy++) {
            for (int gx = 0; gx < gridSize; gx++) {
                int x1 = gx * blockW;
                int y1 = gy * blockH;
                int x2 = Math.min(x1 + blockW, w);
                int y2 = Math.min(y1 + blockH, h);

                float[] stats = getRegionBrightnessAndSaturation(bitmap, x1, y1, x2, y2);
                float brightness = stats[0];
                float saturation = stats[1];

                if (brightness < darkThresh && saturation < satThresh) {
                    float area = (float) (x2 - x1) * (y2 - y1) / (w * h);
                    if (area >= minRegionRatio && area <= maxRegionRatio) {
                        float severity = Math.max(0, Math.min(1,
                                1.0f - brightness / Math.max(medianBrightness, 1)));
                        regions.add(new DefectRegion(
                                DefectType.SHADOW,
                                new BoundingBox(
                                        (float) x1 / w, (float) y1 / h,
                                        (float) (x2 - x1) / w, (float) (y2 - y1) / h,
                                        Math.min(0.5f + severity * 0.5f, 1.0f)),
                                severity,
                                String.format("Shadow region covering %.1f%% of image", area * 100)
                        ));
                    }
                }
            }
        }

        return regions;
    }

    /**
     * Detect reflection regions by finding bright high-contrast areas.
     */
    private List<DefectRegion> detectReflections(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int gridSize = 16;
        int blockW = w / gridSize;
        int blockH = h / gridSize;

        float brightThresh = 255 * 0.90f;
        List<DefectRegion> regions = new ArrayList<>();

        for (int gy = 0; gy < gridSize; gy++) {
            for (int gx = 0; gx < gridSize; gx++) {
                int x1 = gx * blockW;
                int y1 = gy * blockH;
                int x2 = Math.min(x1 + blockW, w);
                int y2 = Math.min(y1 + blockH, h);

                float brightness = getRegionBrightness(bitmap, x1, y1, x2, y2);

                if (brightness > brightThresh) {
                    float area = (float) (x2 - x1) * (y2 - y1) / (w * h);
                    if (area >= 0.003f && area <= 0.35f) {
                        float severity = brightness / 255.0f;
                        regions.add(new DefectRegion(
                                DefectType.REFLECTION,
                                new BoundingBox(
                                        (float) x1 / w, (float) y1 / h,
                                        (float) (x2 - x1) / w, (float) (y2 - y1) / h,
                                        Math.min(0.5f + severity * 0.5f, 1.0f)),
                                severity,
                                String.format("Reflection covering %.1f%% of image", area * 100)
                        ));
                    }
                }
            }
        }

        return regions;
    }

    /**
     * Detect lens flare by finding saturated white blobs.
     */
    private List<DefectRegion> detectFlare(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int gridSize = 16;
        int blockW = w / gridSize;
        int blockH = h / gridSize;

        int whiteThresh = 240;
        List<DefectRegion> regions = new ArrayList<>();

        for (int gy = 0; gy < gridSize; gy++) {
            for (int gx = 0; gx < gridSize; gx++) {
                int x1 = gx * blockW;
                int y1 = gy * blockH;
                int x2 = Math.min(x1 + blockW, w);
                int y2 = Math.min(y1 + blockH, h);

                float whiteFraction = getWhitePixelFraction(bitmap, x1, y1, x2, y2, whiteThresh);

                if (whiteFraction > 0.3f) {
                    float area = (float) (x2 - x1) * (y2 - y1) / (w * h);
                    if (area >= 0.002f && area <= 0.50f) {
                        regions.add(new DefectRegion(
                                DefectType.FLARE,
                                new BoundingBox(
                                        (float) x1 / w, (float) y1 / h,
                                        (float) (x2 - x1) / w, (float) (y2 - y1) / h,
                                        Math.min(0.5f + whiteFraction * 0.5f, 1.0f)),
                                whiteFraction,
                                String.format("Flare covering %.1f%% of image", area * 100)
                        ));
                    }
                }
            }
        }

        return regions;
    }

    // ---------------------------------------------------------------
    // Image statistics helpers
    // ---------------------------------------------------------------

    /** Detect moire-like fine repetitive texture on low-saturation surfaces. */
    private List<DefectRegion> detectMoire(Bitmap bitmap) {
        int imageWidth = bitmap.getWidth();
        int imageHeight = bitmap.getHeight();
        int gridCols = 24;
        int gridRows = 24;
        int blockWidth = Math.max(1, imageWidth / gridCols);
        int blockHeight = Math.max(1, imageHeight / gridRows);

        float[][] textureScore = new float[gridRows][gridCols];
        boolean[][] candidate = new boolean[gridRows][gridCols];
        float scoreSum = 0f;
        float scoreSqSum = 0f;
        int scoreCount = 0;

        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < gridCols; col++) {
                int left = col * blockWidth;
                int top = row * blockHeight;
                int right = col == gridCols - 1 ? imageWidth : Math.min(left + blockWidth, imageWidth);
                int bottom = row == gridRows - 1 ? imageHeight : Math.min(top + blockHeight, imageHeight);
                float[] stats = getMoireTextureStats(bitmap, left, top, right, bottom);
                float brightness = stats[0];
                float saturation = stats[1];
                float fineTexture = stats[2];
                boolean neutralSurface = saturation < 70f && brightness > 45f && brightness < 225f;
                textureScore[row][col] = fineTexture;
                if (neutralSurface && fineTexture > 8f) {
                    candidate[row][col] = true;
                    scoreSum += fineTexture;
                    scoreSqSum += fineTexture * fineTexture;
                    scoreCount++;
                }
            }
        }

        if (scoreCount < 3) {
            return new ArrayList<>();
        }

        float meanScore = scoreSum / scoreCount;
        float variance = Math.max(0f, scoreSqSum / scoreCount - meanScore * meanScore);
        float threshold = Math.max(12f, meanScore + (float) Math.sqrt(variance) * 0.9f);

        boolean[][] active = new boolean[gridRows][gridCols];
        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < gridCols; col++) {
                active[row][col] = candidate[row][col] && textureScore[row][col] >= threshold;
            }
        }

        List<DefectRegion> regions = new ArrayList<>();
        boolean[][] visited = new boolean[gridRows][gridCols];
        int[] queueRows = new int[gridRows * gridCols];
        int[] queueCols = new int[gridRows * gridCols];
        int[] rowDirs = new int[]{1, -1, 0, 0};
        int[] colDirs = new int[]{0, 0, 1, -1};

        for (int startRow = 0; startRow < gridRows; startRow++) {
            for (int startCol = 0; startCol < gridCols; startCol++) {
                if (!active[startRow][startCol] || visited[startRow][startCol]) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                queueRows[tail] = startRow;
                queueCols[tail] = startCol;
                tail++;
                visited[startRow][startCol] = true;
                int minRow = startRow, maxRow = startRow, minCol = startCol, maxCol = startCol;
                float componentScore = 0f;
                int componentCells = 0;

                while (head < tail) {
                    int row = queueRows[head];
                    int col = queueCols[head];
                    head++;
                    componentCells++;
                    componentScore += textureScore[row][col];
                    minRow = Math.min(minRow, row);
                    maxRow = Math.max(maxRow, row);
                    minCol = Math.min(minCol, col);
                    maxCol = Math.max(maxCol, col);
                    for (int dir = 0; dir < 4; dir++) {
                        int nextRow = row + rowDirs[dir];
                        int nextCol = col + colDirs[dir];
                        if (nextRow < 0 || nextRow >= gridRows || nextCol < 0 || nextCol >= gridCols) {
                            continue;
                        }
                        if (active[nextRow][nextCol] && !visited[nextRow][nextCol]) {
                            visited[nextRow][nextCol] = true;
                            queueRows[tail] = nextRow;
                            queueCols[tail] = nextCol;
                            tail++;
                        }
                    }
                }

                if (componentCells < 3) {
                    continue;
                }
                int left = minCol * blockWidth;
                int top = minRow * blockHeight;
                int right = maxCol == gridCols - 1 ? imageWidth : Math.min((maxCol + 1) * blockWidth, imageWidth);
                int bottom = maxRow == gridRows - 1 ? imageHeight : Math.min((maxRow + 1) * blockHeight, imageHeight);
                float area = (float) (right - left) * (bottom - top) / (imageWidth * imageHeight);
                if (area < 0.01f || area > 0.65f) {
                    continue;
                }
                float severity = Math.min(1f, componentScore / componentCells / (threshold + 1e-6f));
                regions.add(new DefectRegion(
                        DefectType.MOIRE,
                        new BoundingBox(
                                (float) left / imageWidth, (float) top / imageHeight,
                                (float) (right - left) / imageWidth, (float) (bottom - top) / imageHeight,
                                Math.min(0.45f + severity * 0.45f, 0.95f)),
                        severity,
                        String.format("Moiré texture covering %.1f%% of image", area * 100)
                ));
            }
        }
        return regions;
    }

    private float[] getMoireTextureStats(Bitmap bitmap, int left, int top, int right, int bottom) {
        left = Math.max(2, left);
        top = Math.max(2, top);
        right = Math.min(bitmap.getWidth() - 2, right);
        bottom = Math.min(bitmap.getHeight() - 2, bottom);
        if (right <= left || bottom <= top) return new float[]{0f, 255f, 0f};

        float brightnessSum = 0f;
        float saturationSum = 0f;
        float laplacianSum = 0f;
        int count = 0;
        int step = 2;
        for (int yCoord = top; yCoord < bottom; yCoord += step) {
            for (int xCoord = left; xCoord < right; xCoord += step) {
                int pixel = bitmap.getPixel(xCoord, yCoord);
                float center = luminance(pixel);
                float leftGray = luminance(bitmap.getPixel(xCoord - 1, yCoord));
                float rightGray = luminance(bitmap.getPixel(xCoord + 1, yCoord));
                float topGray = luminance(bitmap.getPixel(xCoord, yCoord - 1));
                float bottomGray = luminance(bitmap.getPixel(xCoord, yCoord + 1));
                float laplacian = Math.abs(center * 4f - leftGray - rightGray - topGray - bottomGray);
                brightnessSum += center;
                saturationSum += saturation(pixel);
                laplacianSum += Math.min(255f, laplacian);
                count++;
            }
        }
        if (count == 0) return new float[]{0f, 255f, 0f};
        return new float[]{brightnessSum / count, saturationSum / count, laplacianSum / count};
    }

    private static float luminance(int pixel) {
        return Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f;
    }

    private static float saturation(int pixel) {
        int maxChannel = Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel)));
        int minChannel = Math.min(Color.red(pixel), Math.min(Color.green(pixel), Color.blue(pixel)));
        return maxChannel > 0 ? (float) (maxChannel - minChannel) / maxChannel * 255f : 0f;
    }

    /**
     * Compute pixel intensity variance in a region (for moiré detection).
     */
    private float getRegionVariance(Bitmap bitmap, int x1, int y1, int x2, int y2) {
        x1 = Math.max(0, x1); y1 = Math.max(0, y1);
        x2 = Math.min(bitmap.getWidth(), x2); y2 = Math.min(bitmap.getHeight(), y2);
        if (x2 <= x1 || y2 <= y1) return 0;

        float sum = 0, sumSq = 0;
        int count = 0;
        int step = 2; // finer sampling for variance
        for (int y = y1; y < y2; y += step) {
            for (int x = x1; x < x2; x += step) {
                int pixel = bitmap.getPixel(x, y);
                float gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3.0f;
                sum += gray;
                sumSq += gray * gray;
                count++;
            }
        }
        if (count < 2) return 0;
        float mean = sum / count;
        return sumSq / count - mean * mean;
    }

    private float getRegionBrightness(Bitmap bitmap, int x1, int y1, int x2, int y2) {
        x1 = Math.max(0, x1); y1 = Math.max(0, y1);
        x2 = Math.min(bitmap.getWidth(), x2); y2 = Math.min(bitmap.getHeight(), y2);
        if (x2 <= x1 || y2 <= y1) return 0;

        float sum = 0;
        int count = 0;
        int step = 4;
        for (int y = y1; y < y2; y += step) {
            for (int x = x1; x < x2; x += step) {
                int pixel = bitmap.getPixel(x, y);
                sum += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3.0f;
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    private float[] getRegionBrightnessAndSaturation(Bitmap bitmap, int x1, int y1, int x2, int y2) {
        x1 = Math.max(0, x1); y1 = Math.max(0, y1);
        x2 = Math.min(bitmap.getWidth(), x2); y2 = Math.min(bitmap.getHeight(), y2);
        if (x2 <= x1 || y2 <= y1) return new float[]{0, 0};

        float sumBrightness = 0;
        float sumSaturation = 0;
        int count = 0;
        int step = 4;
        for (int y = y1; y < y2; y += step) {
            for (int x = x1; x < x2; x += step) {
                int pixel = bitmap.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                float brightness = (r + g + b) / 3.0f;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                float saturation = max > 0 ? (float) (max - min) / max * 255 : 0;
                sumBrightness += brightness;
                sumSaturation += saturation;
                count++;
            }
        }
        return new float[]{
                count > 0 ? sumBrightness / count : 0,
                count > 0 ? sumSaturation / count : 0
        };
    }

    private float getWhitePixelFraction(Bitmap bitmap, int x1, int y1, int x2, int y2, int thresh) {
        x1 = Math.max(0, x1); y1 = Math.max(0, y1);
        x2 = Math.min(bitmap.getWidth(), x2); y2 = Math.min(bitmap.getHeight(), y2);
        if (x2 <= x1 || y2 <= y1) return 0;

        int white = 0, count = 0;
        int step = 4;
        for (int y = y1; y < y2; y += step) {
            for (int x = x1; x < x2; x += step) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.red(pixel) > thresh && Color.green(pixel) > thresh && Color.blue(pixel) > thresh) {
                    white++;
                }
                count++;
            }
        }
        return count > 0 ? (float) white / count : 0;
    }

    private static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
