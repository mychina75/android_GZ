package com.samsung.camera.intelligence.recommendation;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.samsung.camera.intelligence.config.ToolMappings;
import com.samsung.camera.intelligence.models.*;

import java.util.*;

/**
 * Intelligent tool recommender that maps scene analysis to camera/album tools.
 * Ported from Python recommendation/tool_recommender.py.
 */
public class ToolRecommender {

    private static final String TAG = "ToolRecommender";

    // TEMP(2026-03-30): Demo-only mode strategy override.
    // Because available modes are limited in current Android demo, force more
    // scenes to recommend Pro mode so users see clearer mode-switch guidance.
    // To rollback later: set this to false (or remove shouldTemporarilyPreferProMode calls).
    // FIXED(2026-03-31): Disabled — this override was too aggressive and
    // prevented mode recommendations from transitioning away from Pro when
    // the scene changed (e.g. portrait → product).  The underlying
    // sceneToMode + DEMO_MODE_MAP pipeline now handles demo-available modes
    // correctly.
    private static final boolean TEMP_DEMO_FORCE_PRO_FOR_MORE_SCENES = false;

    private static final List<CameraResolution> RESOLUTION_ORDER = Arrays.asList(
            CameraResolution.MP_12, CameraResolution.MP_50,
            CameraResolution.MP_108, CameraResolution.MP_200
    );

    private final Map<SceneType, CameraMode> sceneToMode;
    private final Map<SceneType, CameraResolution> sceneToResolution;
    private final Map<SceneType, ExpertRawMode> sceneToExpertRaw;
    private final Map<MainSubject, CameraMode> subjectToMode;
    private final Map<LightingCondition, Map<String, Object>> lightingToSettings;
    private final Map<SceneType, CameraMode> sceneToVideoMode;
    private final Map<String, Integer> ndFilterStrengthMap;
    private final Map<SceneType, Map<String, Object>> sceneToProParams;

    private final DefectLocalizer defectLocalizer;
    private final List<CameraResolution> supportedResolutions;

    public ToolRecommender() {
        this(Arrays.asList(CameraResolution.MP_12, CameraResolution.MP_50,
                CameraResolution.MP_108, CameraResolution.MP_200));
    }

    public ToolRecommender(List<CameraResolution> supportedResolutions) {
        this.supportedResolutions = supportedResolutions == null || supportedResolutions.isEmpty()
                ? new ArrayList<>(RESOLUTION_ORDER)
                : new ArrayList<>(supportedResolutions);
        this.supportedResolutions.sort(Comparator.comparingInt(RESOLUTION_ORDER::indexOf));

        this.sceneToMode = buildSceneModeMap();
        this.sceneToResolution = buildSceneResolutionMap();
        this.sceneToExpertRaw = buildExpertRawMap();
        this.subjectToMode = buildSubjectModeMap();
        this.lightingToSettings = buildLightingSettingsMap();
        this.sceneToVideoMode = buildVideoModeMap();
        this.ndFilterStrengthMap = buildNdFilterMap();
        this.sceneToProParams = buildProModeParamsMap();

        this.defectLocalizer = new DefectLocalizer();
    }

    /** Expose the internal {@link DefectLocalizer} so callers can attach
     *  segmentation models (e.g. MobileSAM) without subclassing. */
    public DefectLocalizer getDefectLocalizer() {
        return defectLocalizer;
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    public ToolRecommendationResult recommend(SceneAnalysisResult scene) {
        return recommend(scene, false, false, true, null);
    }

    public ToolRecommendationResult recommend(
            SceneAnalysisResult scene,
            boolean preferExpertRaw,
            boolean captureVideo,
            boolean includeProModeParams,
            Bitmap originalImage) {

        SceneAnalysisResult workingScene = enrichSceneForPostProcessing(scene, originalImage);

        List<ToolRecommendation> tools = new ArrayList<>();
        List<ToolRecommendation> alternativeTools = new ArrayList<>();

        CameraApp app = CameraApp.CAMERA;

        // AUTO: switch to video mode when motion advisor recommends it
        if (!captureVideo && workingScene.isPreferVideo()) {
            captureVideo = true;
        }

        if (captureVideo) {
            tools.addAll(recommendVideoMode(workingScene));
        } else {
            tools.addAll(recommendCameraApp(workingScene, includeProModeParams));
        }

        SceneAnalysisResult expertRawScene = workingScene;
        if (workingScene.getSceneType() == SceneType.NIGHT
                && (isHumanSubject(workingScene.getMainSubject()) || workingScene.isHasFace())
                && !sceneToExpertRaw.containsKey(SceneType.NIGHT)) {
            expertRawScene = shallowCopyScene(workingScene);
            expertRawScene.setSceneType(SceneType.NIGHT_PORTRAIT);
        }

        if (sceneToExpertRaw.containsKey(expertRawScene.getSceneType())) {
            alternativeTools.addAll(recommendExpertRaw(expertRawScene));
        }

        if (preferExpertRaw && !alternativeTools.isEmpty()) {
            app = CameraApp.EXPERT_RAW;
            List<ToolRecommendation> tmp = tools;
            tools = alternativeTools;
            alternativeTools = tmp;
        }

        tools = reorderForMode(tools);
        ValidationBundle mainValidation = validateAndDropConflicts(tools);
        tools = mainValidation.kept;

        ValidationBundle altValidation = new ValidationBundle();
        if (!alternativeTools.isEmpty()) {
            alternativeTools = reorderForMode(alternativeTools);
            altValidation = validateAndDropConflicts(alternativeTools);
            alternativeTools = altValidation.kept;
        }

        ToolRecommendation captureTool = getCaptureTool(app);
        List<ToolRecommendation> postTools = recommendPostProcessing(workingScene);

        if (originalImage != null) {
            DefectLocalizer.LocalizationResult localization = defectLocalizer.localize(originalImage, workingScene);
            if (localization.hasDefects()) {
                Map<String, List<Map<String, Object>>> targetAreas = localization.getToolTargetAreas();
                for (ToolRecommendation tool : postTools) {
                    if (targetAreas.containsKey(tool.getToolName())) {
                        tool.getParameters().put("target_areas", targetAreas.get(tool.getToolName()));
                    }
                }
            }
            List<ToolRecommendation> refinedPostTools = refinePostProcessing(postTools, workingScene, localization);
            Log.i(TAG, "Shared post tools raw=" + summarizeToolNames(postTools)
                    + " refined=" + summarizeToolNames(refinedPostTools));
            postTools = refinedPostTools;
        }

        ToolRecommendationResult result = new ToolRecommendationResult(
                workingScene, app, tools, captureTool, postTools
        );
        result.setAlternativeTools(alternativeTools);
        result.setAlternativeApp(app == CameraApp.CAMERA ? CameraApp.EXPERT_RAW : CameraApp.CAMERA);

        result.setValidationConflicts(mainValidation.remainingConflicts);
        result.setRemovedToolsDueToConflicts(mainValidation.removed);
        result.setAlternativeValidationConflicts(altValidation.remainingConflicts);
        result.setAlternativeRemovedToolsDueToConflicts(altValidation.removed);

        return result;
    }

    public RecommendationWithPro recommendWithProMode(SceneAnalysisResult scene, boolean captureVideo) {
        ToolRecommendationResult rec = recommend(scene, false, captureVideo, true, null);
        ProModeParameters params = getProModeParameters(scene);
        return new RecommendationWithPro(rec, params);
    }

    public ToolRecommendationResult recommendForIntent(SceneAnalysisResult scene, String intent) {
        if ("quick_capture".equals(intent)) {
            ToolRecommendationResult result = new ToolRecommendationResult();
            result.setSceneAnalysis(scene);
            result.setRecommendedApp(CameraApp.CAMERA);
            result.setTools(Collections.singletonList(getOneStepCapture(scene, false)));
            result.setPostProcessingTools(new ArrayList<>());
            return result;
        }
        if ("best_quality".equals(intent) || "creative".equals(intent) || "print".equals(intent)) {
            return recommend(scene, true, false, true, null);
        }
        if ("video".equals(intent)) {
            return recommend(scene, false, true, true, null);
        }
        if ("selfie".equals(intent)) {
            scene.setUseFrontCamera(true);
            return recommend(scene, false, false, false, null);
        }
        if ("share".equals(intent)) {
            ToolRecommendationResult result = recommend(scene, false, false, false, null);
            result.getTools().add(tool(
                    "Camera_AspectRatio", "Camera", 50,
                    kv("ratio", "9:16"),
                    "9:16 ratio optimized for social media", 0.8f
            ));
            return result;
        }
        return recommend(scene);
    }

    public ProModeParameters getProModeParameters(SceneAnalysisResult scene) {
        // Front camera + portrait-family → use selfie params (center focus, lower ISO)
        SceneType lookupScene = scene.getSceneType();
        if (scene.isUseFrontCamera()
                && (lookupScene == SceneType.PORTRAIT || lookupScene == SceneType.GROUP_PORTRAIT
                    || lookupScene == SceneType.BACKLIT_PORTRAIT)) {
            lookupScene = SceneType.SELFIE;
        }
        Map<String, Object> base = new HashMap<>(sceneToProParams.getOrDefault(
                lookupScene, sceneToProParams.get(SceneType.GENERAL)
        ));
        // Snapshot scene-expert base exposure before lighting/motion adjustments
        // so we can enforce a floor for night scenes whose lighting classifier
        // is confused by bright city lights ("Very Bright" on a night cityscape).
        int  baseIso     = ((Number) base.getOrDefault("iso", 200)).intValue();
        String baseShutter = (String)  base.getOrDefault("shutter_speed", "1/125");
        float baseProduct = baseIso * parseShutter(baseShutter);

        adjustParamsForLighting(base, scene.getLightingCondition(), scene.getEstimatedLux());
        adjustParamsForMotion(base, scene.getMotionType(), scene.getMotionSpeed());
        ensureAdequateExposure(base, scene.getLightingCondition(), scene.getEstimatedLux());
        applyPreviewSafePhoneDefaults(base, scene);
        enforceNightExposureFloor(base, scene.getSceneType(), baseProduct);

        return new ProModeParameters(
                (int) base.getOrDefault("iso", 200),
                (String) base.getOrDefault("shutter_speed", "1/125"),
                ((Number) base.getOrDefault("ev", 0.0f)).floatValue(),
                (WhiteBalanceMode) base.getOrDefault("white_balance", WhiteBalanceMode.AUTO),
                (Integer) base.get("white_balance_kelvin"),
                (FocusMode) base.getOrDefault("focus_mode", FocusMode.MULTI_POINT),
                (Float) base.get("manual_focus"),
                (MeteringMode) base.getOrDefault("metering_mode", MeteringMode.MATRIX)
        );
    }

    public ToolRecommendation getOneStepCapture(SceneAnalysisResult scene, boolean useFrontCamera) {
        CameraMode mode = sceneToMode.getOrDefault(scene.getSceneType(), getModeForSubject(scene.getMainSubject()));
        if (shouldUseNightMode(scene.getLightingCondition())) {
            mode = CameraMode.NIGHT;
        }
        // TEMP(2026-03-30): keep quick-capture recommendation consistent with
        // camera app mode-switch strategy during demo period.
        mode = applyTemporaryDemoModeOverride(mode, scene, "quick_capture");
        return tool(
                "CaptureWithMode", "Camera", 1,
                kv("ModeName", mode.getValue(), "ModeCameraType", useFrontCamera ? 1 : 0),
                "Quick capture in " + mode.getValue() + " mode for " + scene.getSceneType().getValue(),
                scene.getSceneTypeConfidence()
        );
    }

    // ---------------------------------------------------------------------
    // Recommendation internals
    // ---------------------------------------------------------------------

    private List<ToolRecommendation> recommendVideoMode(SceneAnalysisResult scene) {
        List<ToolRecommendation> tools = new ArrayList<>();
        int priority = 1;

        // Camera direction for selfie video — combine scene type with camera state
        boolean isPortraitFamilyV = scene.getSceneType() == SceneType.PORTRAIT
                || scene.getSceneType() == SceneType.GROUP_PORTRAIT
                || scene.getSceneType() == SceneType.BACKLIT_PORTRAIT;
        boolean isSelfieVideo = scene.getSceneType() == SceneType.SELFIE
                || (scene.isUseFrontCamera() && isPortraitFamilyV);
        if (isSelfieVideo && !scene.isUseFrontCamera()) {
            tools.add(tool("Camera_ChangeCamera", "Camera", priority++,
                    kv("direction", "front"),
                    "Selfie video detected, switch to front camera",
                    scene.getSceneTypeConfidence()));
        }

        CameraMode mode = sceneToVideoMode.getOrDefault(scene.getSceneType(), CameraMode.VIDEO);
        if (scene.getMotionType() == MotionType.REPEATING) mode = CameraMode.SLOW_MOTION;
        else if (scene.getMotionType() == MotionType.FAST || scene.getMotionType() == MotionType.VERY_FAST) {
            mode = CameraMode.SINGLE_TAKE;
        }
        if (shouldUseNightMode(scene.getLightingCondition())) mode = CameraMode.PRO_VIDEO;

        // FPS-based mode overrides (matching Python tool_recommender.py)
        Integer recFps = scene.getRecommendedFps();
        if (recFps != null && recFps >= 240) {
            mode = CameraMode.SLOW_MOTION;
        } else if (recFps != null && recFps >= 120) {
            if (mode != CameraMode.SLOW_MOTION && mode != CameraMode.SINGLE_TAKE) {
                mode = CameraMode.SLOW_MOTION;
            }
        }

        ToolRecommendation modeRec = tool("Camera_ChangeMode", "Camera", priority++,
                kv("ModeName", mode.getValue()),
                "Optimal video mode for " + scene.getSceneType().getValue() + " scene",
                scene.getSceneTypeConfidence());

        // Annotate capture warning if present
        if (scene.getCaptureWarning() != null) {
            modeRec.getParameters().put("capture_advisory", scene.getCaptureWarning());
        }
        tools.add(modeRec);

        if (mode == CameraMode.PRO_VIDEO) {
            ProModeParameters p = getProModeParameters(scene);
            ToolRecommendation iso = tool("Camera_ChangeIso", "Camera", priority++,
                    kv("iso", p.getIso()),
                    "Optimized ISO for video", scene.getLightingConfidence());
            iso.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(iso);

            // 180° shutter rule: shutter = 1/(2 × target_fps)
            int targetFps = (recFps != null) ? recFps : 30;
            int videoShutterDenom = targetFps * 2; // 180° rule
            if (scene.getRecommendedShutter() != null) {
                try {
                    String rs = scene.getRecommendedShutter();
                    if (rs.contains("/")) {
                        int freezeDenom = Integer.parseInt(rs.split("/")[1]);
                        videoShutterDenom = Math.max(videoShutterDenom, freezeDenom);
                    }
                } catch (NumberFormatException ignored) {}
            }
            String videoShutter = "1/" + videoShutterDenom;

            ToolRecommendation shutter = tool("Camera_ChangeShutterSpeed", "Camera", priority++,
                    kv("shutter_speed", videoShutter),
                    "Video shutter speed (180° rule) for natural motion blur", scene.getMotionConfidence());
            shutter.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(shutter);

            ToolRecommendation wb = tool("Camera_ChangeWhiteBalance", "Camera", priority++,
                    kv("mode", p.getWhiteBalance().getValue()),
                    "Lock white balance for consistent video color", scene.getLightingConfidence());
            wb.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(wb);

            // Focus mode for Pro Video
            Map<String, Object> focusParams = kv("mode", p.getFocusMode().getValue());
            if (p.getFocusMode() == FocusMode.MANUAL && p.getManualFocus() != null) {
                focusParams.put("distance", p.getManualFocus());
            }
            ToolRecommendation focus = tool("Camera_ChangeFocusMode", "Camera", priority++, focusParams,
                    "Focus mode " + p.getFocusMode().getValue() + " for video tracking",
                    scene.getSubjectConfidence());
            focus.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(focus);
        }

        // Camera_VideoFPS — recommend higher FPS when motion advisor suggests it
        if (recFps != null && recFps > 30) {
            String fpsReason = "Motion tier " + (scene.getMotionSpeedTier() != null ? scene.getMotionSpeedTier() : "unknown")
                    + " requires " + recFps + " fps for faithful capture";
            ToolRecommendation fpsRec = tool("Camera_VideoFPS", "Camera", priority++,
                    kv("fps", recFps.toString()), fpsReason, scene.getMotionConfidence());
            fpsRec.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(fpsRec);
        }

        String stabilizationMode = (scene.getMotionType() == MotionType.FAST
                || scene.getMotionType() == MotionType.VERY_FAST
                || scene.getMotionType() == MotionType.CHAOTIC)
                ? "super_steady" : "standard";
        if (mode != CameraMode.HYPERLAPSE) {
            tools.add(tool("Camera_VideoStabilization", "Camera", priority++,
                    kv("mode", stabilizationMode),
                    "Enable video stabilization", 0.8f));
        }
        return tools;
    }

    private List<ToolRecommendation> recommendCameraApp(SceneAnalysisResult scene, boolean includeProModeParams) {
        List<ToolRecommendation> tools = new ArrayList<>();
        int priority = 1;

        // --- Front camera + scene-type cross-signal for selfie detection ---
        boolean isPortraitFamily = scene.getSceneType() == SceneType.PORTRAIT
                || scene.getSceneType() == SceneType.GROUP_PORTRAIT
                || scene.getSceneType() == SceneType.BACKLIT_PORTRAIT;
        boolean isSelfieContext = scene.getSceneType() == SceneType.SELFIE
                || (scene.isUseFrontCamera() && isPortraitFamily);

        // Camera direction — only when a switch is needed
        if (isSelfieContext && !scene.isUseFrontCamera()) {
            tools.add(tool("Camera_ChangeCamera", "Camera", priority++, kv("direction", "front"),
                    "Selfie detected, switch to front camera", scene.getSceneTypeConfidence()));
        } else if (!isSelfieContext && scene.isUseFrontCamera()) {
            tools.add(tool("Camera_ChangeCamera", "Camera", priority++, kv("direction", "rear"),
                    "Non-selfie scene on front camera, switch to rear", scene.getSceneTypeConfidence()));
        }

        CameraMode mode = sceneToMode.get(scene.getSceneType());
        if (mode == null) mode = getModeForSubject(scene.getMainSubject());

        // Track whether Night mode came from an explicit night scene classification
        // vs. being inferred from low-light conditions alone.
        boolean nightFromScene = (mode == CameraMode.NIGHT);

        if (shouldUseNightMode(scene.getLightingCondition()) && mode != CameraMode.PRO && mode != CameraMode.NIGHT) {
            mode = CameraMode.NIGHT;
            // nightFromScene stays false — Night was inferred from lighting only
        }

        // TEMP(2026-03-31): demo keeps portrait unavailable. For night scenes
        // inferred from lighting with human subjects, route to Pro directly.
        if (mode == CameraMode.NIGHT && !nightFromScene
                && (isHumanSubject(scene.getMainSubject()) || scene.isHasFace())) {
            mode = CameraMode.PRO;
        }

        // TEMP(2026-03-30): broaden Pro mode recommendation in demo builds.
        mode = applyTemporaryDemoModeOverride(mode, scene, "camera_app");

        tools.add(tool("Camera_ChangeMode", "Camera", priority++,
                kv("ModeName", mode.getValue()),
                "Optimal mode for current scene", scene.getSceneTypeConfidence()));

        if (scene.getMainSubject() == MainSubject.ANIMAL_WILDLIFE || scene.getMainSubject() == MainSubject.ANIMAL_BIRD) {
            tools.add(tool("Camera_ChangeZoom", "Camera", priority++, kv("zoom_level", 3.0f),
                    "Zoom in for distant subject", scene.getSubjectConfidence()));
        } else if (scene.getSubjectBoundingBox() != null && scene.getSubjectBoundingBox().length >= 4) {
            float[] sb = scene.getSubjectBoundingBox();
            float subjectArea = sb[2] * sb[3];
            if (subjectArea < 0.1f) {
                float zoom = Math.min(3.0f, (float) (1.0 / Math.sqrt(Math.max(subjectArea, 1e-6f))));
                tools.add(tool("Camera_ChangeZoom", "Camera", priority++, kv("zoom_level", Math.round(zoom * 10) / 10.0f),
                        "Subject is small in frame, zoom recommended", scene.getSubjectConfidence() * 0.8f));
            }
        }

        if (isSelfieContext) {
            tools.add(tool("Camera_SetTimer", "Camera", priority++, kv("seconds", 3),
                    "Timer for hands-free selfie capture", 0.7f));
        }

        if ((shouldSuggestFlash(scene.getLightingCondition())
                || scene.getSceneType() == SceneType.NIGHT
                || scene.getSceneType() == SceneType.NIGHT_PORTRAIT)
                && isHumanSubject(scene.getMainSubject())) {
            tools.add(tool("Camera_Flash", "Camera", priority++, kv("mode", "auto"),
                    "Flash recommended for current lighting + human subject", scene.getLightingConfidence()));
        }

        if (shouldSuggestHdr(scene.getLightingCondition())) {
            tools.add(tool("Camera_HDR", "Camera", priority++, kv("enable", true),
                    "HDR recommended for current lighting", scene.getLightingConfidence()));
        }

        if (mode == CameraMode.PRO && includeProModeParams) {
            ProModeParameters p = getProModeParameters(scene);

            ToolRecommendation iso = tool("Camera_ChangeIso", "Camera", priority++, kv("iso", p.getIso()),
                    "Optimized ISO", scene.getLightingConfidence());
            iso.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(iso);

            ToolRecommendation shutter = tool("Camera_ChangeShutterSpeed", "Camera", priority++,
                    kv("shutter_speed", p.getShutterSpeed()),
                    "Optimized shutter speed", scene.getMotionConfidence());
            shutter.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(shutter);

            if (p.getEv() != 0.0f) {
                ToolRecommendation ev = tool("Camera_ChangeEV", "Camera", priority++, kv("ev", p.getEv()),
                        "Exposure compensation", scene.getLightingConfidence());
                ev.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
                tools.add(ev);
            }

            Map<String, Object> wbParams = kv("mode", p.getWhiteBalance().getValue());
            if (p.getWhiteBalance() == WhiteBalanceMode.CUSTOM && p.getWhiteBalanceKelvin() != null) {
                wbParams.put("kelvin", p.getWhiteBalanceKelvin());
            }
            ToolRecommendation wb = tool("Camera_ChangeWhiteBalance", "Camera", priority++, wbParams,
                    "White balance for optimal color", scene.getLightingConfidence());
            wb.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(wb);

            Map<String, Object> focusParams = kv("mode", p.getFocusMode().getValue());
            if (p.getFocusMode() == FocusMode.MANUAL && p.getManualFocus() != null) {
                focusParams.put("distance", p.getManualFocus());
            }
            ToolRecommendation focus = tool("Camera_ChangeFocusMode", "Camera", priority++, focusParams,
                    "Focus mode for subject", scene.getSubjectConfidence());
            focus.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(focus);

            ToolRecommendation metering = tool("Camera_ChangeMeteringMode", "Camera", priority++,
                    kv("mode", p.getMeteringMode().getValue()),
                    "Metering mode for accurate exposure", scene.getLightingConfidence());
            metering.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(metering);
        }

        boolean motionPhoto = scene.getMotionType() == MotionType.FAST
                || scene.getMotionType() == MotionType.NORMAL
                || scene.getMotionType() == MotionType.SLOW
                || scene.getMotionType() == MotionType.REPEATING
                || ((scene.getSceneType() == SceneType.SPORTS || scene.getSceneType() == SceneType.FAST_MOVING
                || scene.getSceneType() == SceneType.REPEATING_MOTION)
                && isHumanSubject(scene.getMainSubject()))
                || ((scene.getMainSubject() == MainSubject.ANIMAL_PET
                || scene.getMainSubject() == MainSubject.ANIMAL_WILDLIFE
                || scene.getMainSubject() == MainSubject.ANIMAL_BIRD)
                && scene.getMotionType() != MotionType.STATIC);

        if (motionPhoto) {
            tools.add(tool("Camera_MotionPhoto", "Camera", priority++, kv("enable", true),
                    "Enable motion photo for dynamic subject", Math.max(scene.getMotionConfidence(), scene.getSubjectConfidence())));
        }

        if (scene.getMainSubject() == MainSubject.ANIMAL_WILDLIFE || scene.getMainSubject() == MainSubject.ANIMAL_BIRD) {
            tools.add(tool("Camera_BurstMode", "Camera", priority++, kv("enable", true),
                    "Burst mode for wildlife/bird action", scene.getSubjectConfidence()));
        }

        if (scene.getSceneType() == SceneType.DOCUMENT || scene.isHasText()) {
            tools.add(tool("Camera_AspectRatio", "Camera", priority++, kv("ratio", "3:4"),
                    "3:4 ratio for document scanning", 0.75f));
            tools.add(tool("Camera_Guideline", "Camera", priority++, kv("enable", true),
                    "Enable guidelines for document alignment", 0.85f));
        } else if (scene.isHasFace() && scene.getFaceCount() > 2) {
            // Group photo — wider ratio
            tools.add(tool("Camera_AspectRatio", "Camera", priority++, kv("ratio", "full"),
                    "Full ratio for group of " + scene.getFaceCount() + " people", 0.7f));
        } else if (scene.isTilted() || scene.getContrastValue() < 0.4f) {
            tools.add(tool("Camera_Guideline", "Camera", priority++, kv("enable", true),
                    "Enable guidelines for better composition", 0.7f));
        }

        CameraResolution resolution = sceneToResolution.getOrDefault(scene.getSceneType(), CameraResolution.MP_50);
        String reason = "Optimal resolution for current scene";

        // Front camera selfie context → 12MP (front sensor is smaller)
        if (isSelfieContext && resolution != CameraResolution.MP_12) {
            resolution = CameraResolution.MP_12;
            reason = "12MP pixel-binned mode for front camera selfie";
        }

        if ((scene.getLightingCondition() == LightingCondition.VERY_LOW_LIGHT
                || scene.getLightingCondition() == LightingCondition.LOW_LIGHT)
                && resolution != CameraResolution.MP_12) {
            resolution = CameraResolution.MP_12;
            reason = "12MP pixel-binned mode for better low-light performance";
        }

        if (scene.getMotionType() == MotionType.FAST || scene.getMotionType() == MotionType.VERY_FAST
                || scene.getMotionType() == MotionType.CHAOTIC) {
            resolution = CameraResolution.MP_12;
            reason = "12MP for faster sensor readout in fast motion";
        }

        if ((resolution == CameraResolution.MP_108 || resolution == CameraResolution.MP_200)
                && isSlowShutter(calculateShutterSpeed(scene), "1/60")
                && scene.getMotionType() != MotionType.STATIC) {
            resolution = CameraResolution.MP_50;
            reason = "50MP to reduce shake risk under slow shutter + motion";
        }

        ClampResult clamp = clampResolution(resolution);
        resolution = clamp.value;
        if (clamp.wasClamped) {
            reason += " (clamped to device-supported " + resolution.getValue() + ")";
        }
        scene.setRecommendedResolution(resolution);

        tools.add(tool("Camera_ChangeResolution", "Camera", priority++,
                kv("resolution", resolution.getValue()), reason, 0.8f));

        return tools;
    }

    private List<ToolRecommendation> recommendExpertRaw(SceneAnalysisResult scene) {
        List<ToolRecommendation> tools = new ArrayList<>();
        int priority = 1;

        ExpertRawMode mode = sceneToExpertRaw.get(scene.getSceneType());
        if (mode != null) {
            Map<ExpertRawMode, String> toolNameMap = new HashMap<>();
            toolNameMap.put(ExpertRawMode.ASTRO, "ExpertRaw_ChangeToAstroMode");
            toolNameMap.put(ExpertRawMode.ASTRO_PORTRAIT, "ExpertRaw_ChangeToAstroPortraitMode");
            toolNameMap.put(ExpertRawMode.MULTI_EXPOSURE, "ExpertRaw_ChangeToMultiExposureMode");
            toolNameMap.put(ExpertRawMode.ND_FILTER, "ExpertRaw_ChangeToNdFilterMode");
            toolNameMap.put(ExpertRawMode.VIRTUAL_APERTURE, "ExpertRaw_ChangeToVirtualApertureMode");

            Map<String, Object> modeParams = new HashMap<>();
            String modeReason = "Optimal Expert Raw mode for " + scene.getSceneType().getValue();
            if (mode == ExpertRawMode.ND_FILTER) {
                int nd = getNdFilterStrength(scene);
                modeParams.put("strength", nd);
                modeReason = "ND" + nd + " filter for smooth motion blur";
            }

            tools.add(tool(toolNameMap.get(mode), "ExpertRaw", priority++, modeParams,
                    modeReason, scene.getSceneTypeConfidence()));
        }

        tools.add(tool("ExpertRaw_ChangeIso", "ExpertRaw", priority++,
                kv("iso", calculateIso(scene)),
                "Optimized ISO for " + scene.getLightingCondition().getValue(),
                scene.getLightingConfidence()));

        tools.add(tool("ExpertRaw_ChangeShutterSpeed", "ExpertRaw", priority++,
                kv("shutter_speed", calculateShutterSpeed(scene)),
                "Optimized shutter for " + scene.getMotionType().getValue(),
                scene.getMotionConfidence()));

        return tools;
    }

    private List<ToolRecommendation> recommendPostProcessing(SceneAnalysisResult scene) {
        List<ToolRecommendation> tools = new ArrayList<>();
        int priority = 100;

        if (shouldRecommendAutoFit(scene)) {
            tools.add(tool("Gallery_AutoFit", "Gallery", priority++, new HashMap<>(),
                    buildAutoFitReason(scene), 0.8f));
        }

        ToolRecommendation composition = recommendCompositionEdit(scene);
        if (composition != null) {
            composition.setPriority(priority++);
            tools.add(composition);
        }

        // ----- Edit Tool Recommendation table (post-capture) ---------------
        // Tilt / rotation
        if (scene.isTilted()) {
            float angle = scene.getTiltAngle();
            Map<String, Object> tiltParams = new LinkedHashMap<>();
            tiltParams.put("tilt_angle_deg", angle);
            tools.add(tool("Gallery_AutoTilt", "Gallery", priority++, tiltParams,
                    String.format(Locale.US, "Straighten %.1f\u00B0 tilt", angle), 0.88f));
            tools.add(tool("PhotoEditor_GenAIAutoTilt", "PhotoEditor", priority++, new HashMap<>(),
                    "AI-powered straightening with content-aware fill", 0.78f));
        }

        // Blur correction
        if (scene.getBlurLevel() > 0.30f) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("strength", clamp01(scene.getBlurLevel()));
            tools.add(tool("PhotoEditor_Deblur", "PhotoEditor", priority++, p,
                    String.format(Locale.US, "Reduce blur (level %.2f)", scene.getBlurLevel()), 0.82f));
        }

        // Exposure correction
        float ev = brightnessToEv(scene.getBrightnessValue());
        if (Math.abs(ev) >= 0.5f) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("ev_stops", ev);
            tools.add(tool("PhotoEditor_AdjustExposure", "PhotoEditor", priority++, p,
                    String.format(Locale.US, "Adjust exposure %+.1f EV", ev), 0.85f));
        }

        // Backlight
        if (scene.getLightingCondition() == LightingCondition.BACKLIT) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("meter_mode", "spot");
            tools.add(tool("Camera_SpotMetering", "Camera", priority++, p,
                    "Backlit subject \u2014 use spot metering", scene.getLightingConfidence()));
        }

        // Low-light denoise
        if (scene.getNoiseLevel() > 0.35f
                || (scene.getEstimatedLux() != null && scene.getEstimatedLux() < 30f)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("strength", clamp01(scene.getNoiseLevel()));
            if (scene.getEstimatedLux() != null) p.put("estimated_lux", scene.getEstimatedLux());
            tools.add(tool("PhotoEditor_Denoise", "PhotoEditor", priority++, p,
                    "Reduce low-light noise", 0.83f));
        }

        // Resolution / upscale
        if (scene.getSharpnessValue() < 0.50f && scene.getBlurLevel() < 0.40f) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("factor", 2);
            tools.add(tool("PhotoEditor_Upscale", "PhotoEditor", priority++, p,
                    "Upscale 2\u00D7 to recover detail", 0.75f));
        }

        // Shadow / reflection / flare / background-people (Remove* family)
        if (scene.isHasShadow()) {
            tools.add(tool("PhotoEditor_RemoveShadow", "PhotoEditor", priority++, new HashMap<>(),
                    "Remove detected shadows", 0.85f));
        }
        if (scene.isHasReflection()) {
            tools.add(tool("PhotoEditor_removeReflection", "PhotoEditor", priority++, new HashMap<>(),
                    "Remove detected reflections", 0.85f));
        }
        if (scene.isHasBackgroundPeople()) {
            tools.add(tool("PhotoEditor_removeBackgroundPeople", "PhotoEditor", priority++, new HashMap<>(),
                    "Remove background people", 0.8f));
        }
        if (scene.isHasFlare()) {
            tools.add(tool("PhotoEditor_RemoveFlare", "PhotoEditor", priority++, new HashMap<>(),
                    "Remove lens flare and light artifacts", 0.85f));
        }
        if (scene.isHasMoire()) {
            tools.add(tool("PhotoEditor_RemoveMoire", "PhotoEditor", priority++, new HashMap<>(),
                    "Remove moir\u00E9 interference patterns", 0.85f));
        }

        // Free-form object removal — always available as a manual entry.
        tools.add(tool("Gallery_ObjectRemover", "Gallery", priority++, new HashMap<>(),
                "Tap to mark and remove unwanted objects", 0.55f));

        return tools;
    }

    /** Convert [0,1] brightness to EV stops centered around 0.5 = 0 EV. */
    private static float brightnessToEv(float brightness) {
        if (brightness <= 0f) return 0f;
        // log2(target / current); target = 0.5
        double ev = Math.log(0.5 / Math.max(brightness, 1e-3)) / Math.log(2);
        return (float) Math.max(-2.5, Math.min(2.5, ev));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private SceneAnalysisResult enrichSceneForPostProcessing(SceneAnalysisResult scene, Bitmap originalImage) {
        if (scene == null || originalImage == null) {
            return scene;
        }

        SceneAnalysisResult enriched = shallowCopyScene(scene);
        enriched.setBrightnessValue(estimateBrightness(originalImage));
        enriched.setBlurLevel(estimateBlur(originalImage));
        return enriched;
    }

    private List<ToolRecommendation> refinePostProcessing(
            List<ToolRecommendation> tools,
            SceneAnalysisResult scene,
            DefectLocalizer.LocalizationResult localization) {
        List<ToolRecommendation> refined = new ArrayList<>();
        if (tools == null) {
            tools = new ArrayList<>();
        }

        boolean autoFitNeeded = shouldRecommendAutoFit(scene);
        for (ToolRecommendation tool : tools) {
            if (tool == null || tool.getToolName() == null) {
                continue;
            }
            if ("Gallery_AutoFit".equals(tool.getToolName()) && !autoFitNeeded) {
                continue;
            }
            refined.add(tool);
        }

        if (autoFitNeeded && !containsTool(refined, "Gallery_AutoFit")) {
            refined.add(tool("Gallery_AutoFit", "Gallery", nextPriority(refined, 100),
                    new HashMap<>(), buildAutoFitReason(scene), 0.78f));
        }

        return refined;
    }

    private boolean shouldRecommendAutoFit(SceneAnalysisResult scene) {
        if (scene == null) {
            return false;
        }
        return scene.getContrastValue() < 0.45f
                || scene.getSharpnessValue() < 0.50f
                || scene.getBrightnessValue() < 0.30f
                || scene.getBrightnessValue() > 0.82f
                || scene.getBlurLevel() > 0.30f;
    }

    private String buildAutoFitReason(SceneAnalysisResult scene) {
        if (scene == null) {
            return "Auto enhance for overall quality improvement";
        }
        if (scene.getBlurLevel() > 0.30f) {
            return "Auto enhance for noticeable softness";
        }
        if (scene.getBrightnessValue() < 0.30f) {
            return "Auto enhance for underexposed image";
        }
        if (scene.getBrightnessValue() > 0.82f) {
            return "Auto enhance for overly bright image";
        }
        if (scene.getContrastValue() < 0.45f) {
            return "Auto enhance for low contrast";
        }
        return "Auto enhance for overall quality improvement";
    }

    private boolean containsTool(List<ToolRecommendation> tools, String toolName) {
        for (ToolRecommendation tool : tools) {
            if (toolName.equals(tool.getToolName())) {
                return true;
            }
        }
        return false;
    }

    private int nextPriority(List<ToolRecommendation> tools, int fallback) {
        int maxPriority = fallback - 1;
        for (ToolRecommendation tool : tools) {
            if (tool != null) {
                maxPriority = Math.max(maxPriority, tool.getPriority());
            }
        }
        return maxPriority + 1;
    }

    private float estimateBrightness(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(1, Math.max(width, height) / 128);
        double sum = 0.0;
        int count = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int pixel = bitmap.getPixel(x, y);
                double luminance = (0.2126 * Color.red(pixel)
                        + 0.7152 * Color.green(pixel)
                        + 0.0722 * Color.blue(pixel)) / 255.0;
                sum += luminance;
                count++;
            }
        }
        return count > 0 ? (float) (sum / count) : 0.5f;
    }

    private float estimateBlur(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 3 || height < 3) {
            return 0.0f;
        }

        int step = Math.max(1, Math.max(width, height) / 192);
        double sum = 0.0;
        double sumSq = 0.0;
        int count = 0;
        for (int y = step; y < height - step; y += step) {
            for (int x = step; x < width - step; x += step) {
                float center = grayscale(bitmap.getPixel(x, y));
                float left = grayscale(bitmap.getPixel(x - step, y));
                float right = grayscale(bitmap.getPixel(x + step, y));
                float up = grayscale(bitmap.getPixel(x, y - step));
                float down = grayscale(bitmap.getPixel(x, y + step));
                double lap = 4.0 * center - left - right - up - down;
                sum += lap;
                sumSq += lap * lap;
                count++;
            }
        }
        if (count < 2) {
            return 0.0f;
        }

        double mean = sum / count;
        double variance = Math.max(0.0, sumSq / count - mean * mean);
        float blur = (float) (1.0 - Math.min(variance / 500.0, 1.0));
        return Math.max(0.0f, Math.min(1.0f, blur));
    }

    private float grayscale(int pixel) {
        return (float) (0.299 * Color.red(pixel)
                + 0.587 * Color.green(pixel)
                + 0.114 * Color.blue(pixel));
    }

    private String summarizeToolNames(List<ToolRecommendation> tools) {
        if (tools == null || tools.isEmpty()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        for (ToolRecommendation tool : tools) {
            if (tool != null && tool.getToolName() != null) {
                names.add(tool.getToolName());
            }
        }
        return names.toString();
    }

    private ToolRecommendation recommendCompositionEdit(SceneAnalysisResult scene) {
        if (scene.isNeedsCompositionEdit()) {
            List<String> issues = scene.getCompositionIssues() != null
                    ? scene.getCompositionIssues() : new ArrayList<>();

            if (containsAny(issues, Arrays.asList("insufficient_headroom", "poor_framing", "awkward_cropping"))) {
                String direction = issues.contains("insufficient_headroom") ? "top" : "all";
                return tool("PhotoEditor_GenAIExpand", "PhotoEditor", 100,
                        kv("direction", direction),
                        "Expand image to improve framing", 0.85f);
            }

            if (containsAny(issues, Arrays.asList("poor_rule_of_thirds", "unbalanced", "needs_recomposition"))) {
                return tool("PhotoEditor_Recompose", "PhotoEditor", 100,
                        kv("apply_rule_of_thirds", true),
                        "Recompose for better visual balance", 0.85f);
            }

            if (issues.contains("subject_too_small")) {
                return tool("PhotoEditor_SmartCrop", "PhotoEditor", 100,
                        kv("aspect_ratio", "auto"),
                        "Smart crop to focus on subject", 0.8f);
            }

            if (issues.contains("subject_cut_off")) {
                return tool("PhotoEditor_GenAIExpand", "PhotoEditor", 100,
                        kv("direction", "all"),
                        "Expand to recover cropped subject areas", 0.85f);
            }

            if (containsAny(issues, Arrays.asList("subject_off_center", "too_much_headroom",
                    "cluttered_background", "distracting_elements"))) {
                if (scene.getSuggestedCrop() != null && scene.getSuggestedCrop().length >= 4) {
                    float[] c = scene.getSuggestedCrop();
                    return tool("Gallery_Crop", "Gallery", 100,
                            kv("x", c[0], "y", c[1], "width", c[2], "height", c[3]),
                            "Crop to improve composition", 0.85f);
                }
                return tool("PhotoEditor_CompositionEnhancer", "PhotoEditor", 100,
                        kv("mode", selectCompositionEnhancerMode(issues)),
                        "Composition enhancement for better subject positioning", 0.85f);
            }

            return tool("PhotoEditor_CompositionEnhancer", "PhotoEditor", 100,
                    kv("mode", selectCompositionEnhancerMode(issues)),
                    "Composition enhancement for overall improvement", 0.75f);
        }

        if (scene.getCompositionScore() < 0.4f) {
            return tool("PhotoEditor_CompositionEnhancer", "PhotoEditor", 100,
                    kv("mode", "auto"),
                    "Low composition score, enhance composition", 0.7f);
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Validation helpers
    // ---------------------------------------------------------------------

    private List<ToolRecommendation> reorderForMode(List<ToolRecommendation> tools) {
        List<ToolRecommendation> copy = new ArrayList<>(tools);
        copy.sort((a, b) -> {
            boolean aMode = a.getToolName().contains("ChangeMode") || a.getToolName().equals("CaptureWithMode");
            boolean bMode = b.getToolName().contains("ChangeMode") || b.getToolName().equals("CaptureWithMode");
            if (aMode != bMode) return aMode ? -1 : 1;
            return Integer.compare(a.getPriority(), b.getPriority());
        });
        return copy;
    }

    private ValidationBundle validateAndDropConflicts(List<ToolRecommendation> tools) {
        ValidationBundle bundle = new ValidationBundle();
        List<String> names = new ArrayList<>();
        Map<String, Map<String, Object>> paramsMap = new HashMap<>();
        for (ToolRecommendation t : tools) {
            names.add(t.getToolName());
            if (t.getParameters() != null && !t.getParameters().isEmpty()) {
                paramsMap.put(t.getToolName(), t.getParameters());
            }
        }

        List<String> conflicts = ToolMappings.validateToolSequenceWithParams(names, paramsMap);
        if (conflicts == null || conflicts.isEmpty()) {
            bundle.kept = tools;
            bundle.remainingConflicts = new ArrayList<>();
            return bundle;
        }

        Set<String> conflictNames = new HashSet<>();
        for (String c : conflicts) {
            String[] parts = c.split(" ");
            if (parts.length > 0) conflictNames.add(parts[0]);
        }

        List<ToolRecommendation> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (ToolRecommendation t : tools) {
            if (conflictNames.contains(t.getToolName())) removed.add(t.getToolName());
            else kept.add(t);
        }

        // Re-validate after drops with params
        List<String> keptNames = extractNames(kept);
        Map<String, Map<String, Object>> keptParams = new HashMap<>();
        for (ToolRecommendation t : kept) {
            if (t.getParameters() != null && !t.getParameters().isEmpty()) {
                keptParams.put(t.getToolName(), t.getParameters());
            }
        }
        List<String> remaining = ToolMappings.validateToolSequenceWithParams(keptNames, keptParams);

        bundle.kept = kept;
        bundle.removed = removed;
        bundle.remainingConflicts = remaining == null ? new ArrayList<>() : remaining;
        return bundle;
    }

    // ---------------------------------------------------------------------
    // Utility methods
    // ---------------------------------------------------------------------

    private ToolRecommendation getCaptureTool(CameraApp app) {
        if (app == CameraApp.EXPERT_RAW) {
            return tool("CaptureWithCurrentState", "ExpertRaw", 999, new HashMap<>(),
                    "Capture photo with current settings", 1.0f);
        }
        return tool("Camera_CaptureWithCurrentState", "Camera", 999, new HashMap<>(),
                "Capture photo with current settings", 1.0f);
    }

    public CameraMode getModeForSubject(MainSubject subject) {
        return subjectToMode.getOrDefault(subject, CameraMode.PHOTO);
    }

    public Map<String, Object> getSettingsForLighting(LightingCondition lighting) {
        return lightingToSettings.getOrDefault(lighting,
                kv("iso", 200, "shutter_base", "1/125", "use_night_mode", false, "flash_suggest", false));
    }

    public boolean shouldUseNightMode(LightingCondition lighting) {
        Object v = getSettingsForLighting(lighting).get("use_night_mode");
        return v instanceof Boolean && (Boolean) v;
    }

    public boolean shouldSuggestFlash(LightingCondition lighting) {
        Object v = getSettingsForLighting(lighting).get("flash_suggest");
        return v instanceof Boolean && (Boolean) v;
    }

    public boolean shouldSuggestHdr(LightingCondition lighting) {
        Object v = getSettingsForLighting(lighting).get("hdr_suggest");
        return v instanceof Boolean && (Boolean) v;
    }

    public int getNdFilterStrength(SceneAnalysisResult scene) {
        if (scene.getSceneType() == SceneType.WATERFALL) {
            Float speed = scene.getMotionSpeed();
            if (speed != null && speed > 50) return ndFilterStrengthMap.get("waterfall_silky");
            return ndFilterStrengthMap.get("waterfall_mild");
        }
        if (scene.getMainSubject() == MainSubject.WATER_BODY) {
            return ndFilterStrengthMap.get("ocean_waves");
        }
        if (scene.getSceneType() == SceneType.CITYSCAPE || scene.getSceneType() == SceneType.NIGHT_CITYSCAPE) {
            return ndFilterStrengthMap.get("light_trails");
        }
        return ndFilterStrengthMap.get("default");
    }

    private void adjustParamsForLighting(Map<String, Object> params,
                                         LightingCondition lighting,
                                         Float estimatedLux) {
        Map<LightingCondition, Integer> isoAdjust = new HashMap<>();
        // Phone-camera tuned defaults. Previous DSLR-like ISO suggestions
        // (e.g. indoor=800) produced blown-out preview when Pro mode was applied.
        isoAdjust.put(LightingCondition.VERY_LOW_LIGHT, 800);
        isoAdjust.put(LightingCondition.LOW_LIGHT, 400);
        isoAdjust.put(LightingCondition.INDOOR, 100);
        isoAdjust.put(LightingCondition.CLOUDY, 100);
        isoAdjust.put(LightingCondition.NORMAL, 100);
        isoAdjust.put(LightingCondition.BRIGHT, 50);
        isoAdjust.put(LightingCondition.VERY_BRIGHT, 50);
        isoAdjust.put(LightingCondition.BACKLIT, 50);
        isoAdjust.put(LightingCondition.MIXED, 100);
        isoAdjust.put(LightingCondition.ARTIFICIAL, 100);
        isoAdjust.put(LightingCondition.GOLDEN_HOUR, 50);
        isoAdjust.put(LightingCondition.BLUE_HOUR, 200);

        Map<LightingCondition, WhiteBalanceMode> wbAdjust = new HashMap<>();
        wbAdjust.put(LightingCondition.CLOUDY, WhiteBalanceMode.CLOUDY);
        wbAdjust.put(LightingCondition.ARTIFICIAL, WhiteBalanceMode.FLUORESCENT);
        wbAdjust.put(LightingCondition.GOLDEN_HOUR, WhiteBalanceMode.DAYLIGHT);
        wbAdjust.put(LightingCondition.BLUE_HOUR, WhiteBalanceMode.DAYLIGHT);

        Map<LightingCondition, Float> evAdjust = new HashMap<>();
        evAdjust.put(LightingCondition.BACKLIT, 0.3f);
        evAdjust.put(LightingCondition.VERY_BRIGHT, -0.3f);
        evAdjust.put(LightingCondition.VERY_LOW_LIGHT, 0.0f);

        if (isoAdjust.containsKey(lighting)) {
            int iso = ((Number) params.getOrDefault("iso", 200)).intValue();
            int lightingIso = isoAdjust.get(lighting);
            if (lighting == LightingCondition.VERY_LOW_LIGHT || lighting == LightingCondition.LOW_LIGHT
                    || lighting == LightingCondition.INDOOR) {
                params.put("iso", Math.max(iso, lightingIso));
            } else {
                // Only reduce ISO if the resulting exposure is still adequate
                // for the current shutter speed.  This prevents dark preview
                // when a fast-motion preset (e.g. 1/2000) is paired with a
                // bright-lighting ISO (e.g. 100).
                int proposedIso = Math.min(iso, lightingIso);
                float shutterS = parseShutter(
                        (String) params.getOrDefault("shutter_speed", "1/125"));
                float proposedProduct = proposedIso * shutterS;

                Map<LightingCondition, Float> brightMin = new EnumMap<>(LightingCondition.class);
                brightMin.put(LightingCondition.VERY_BRIGHT,  0.01f);
                brightMin.put(LightingCondition.BRIGHT,       0.03f);
                brightMin.put(LightingCondition.NORMAL,       0.06f);
                brightMin.put(LightingCondition.CLOUDY,       0.15f);
                brightMin.put(LightingCondition.GOLDEN_HOUR,  0.15f);
                brightMin.put(LightingCondition.BACKLIT,      0.10f);
                brightMin.put(LightingCondition.MIXED,        0.3f);
                brightMin.put(LightingCondition.ARTIFICIAL,   0.6f);
                brightMin.put(LightingCondition.BLUE_HOUR,    1.5f);

                Float floorObj = brightMin.get(lighting);
                float floor = (floorObj != null ? floorObj : 0.06f) * 0.5f;
                if (proposedProduct >= floor) {
                    params.put("iso", proposedIso);
                }
                // else: keep the scene preset ISO to avoid underexposure
            }
        }

        if (wbAdjust.containsKey(lighting)
                && params.getOrDefault("white_balance", WhiteBalanceMode.AUTO) == WhiteBalanceMode.AUTO) {
            params.put("white_balance", wbAdjust.get(lighting));
        }

        if (evAdjust.containsKey(lighting)) {
            float ev = ((Number) params.getOrDefault("ev", 0.0f)).floatValue();
            ev += evAdjust.get(lighting);
            params.put("ev", Math.max(-3.0f, Math.min(3.0f, ev)));
        }
    }

    private void adjustParamsForMotion(Map<String, Object> params, MotionType motion, Float motionSpeed) {
        // --- Tier-based continuous shutter refinement (like Python MotionCaptureAdvisor) ---
        if (motionSpeed != null && motionSpeed > 0) {
            int tierDenom = classifyTierShutterDenom(motionSpeed);
            String tierShutter = "1/" + tierDenom;
            String curShutter = (String) params.getOrDefault("shutter_speed", "1/125");
            if (compareShutterSpeeds(tierShutter, curShutter)) {
                params.put("shutter_speed", tierShutter);
                // Compensate exposure with higher ISO for very fast shutters
                int iso = ((Number) params.getOrDefault("iso", 200)).intValue();
                if (tierDenom >= 1000) {
                    params.put("iso", Math.min(3200, Math.max(iso, 800)));
                } else if (tierDenom >= 500) {
                    params.put("iso", Math.min(3200, Math.max(iso, 400)));
                }
            }
        } else {
            // Fallback: discrete MotionType shutter map
            Map<MotionType, String> shutterReq = new HashMap<>();
            shutterReq.put(MotionType.VERY_FAST, "1/2000");
            shutterReq.put(MotionType.FAST, "1/1000");
            shutterReq.put(MotionType.NORMAL, "1/250");
            shutterReq.put(MotionType.SLOW, "1/60");
            shutterReq.put(MotionType.REPEATING, "1/500");
            shutterReq.put(MotionType.CHAOTIC, "1/1000");

            if (shutterReq.containsKey(motion)) {
                String req = shutterReq.get(motion);
                String cur = (String) params.getOrDefault("shutter_speed", "1/125");
                if (compareShutterSpeeds(req, cur)) {
                    params.put("shutter_speed", req);
                    if (motion == MotionType.VERY_FAST || motion == MotionType.FAST) {
                        int iso = ((Number) params.getOrDefault("iso", 200)).intValue();
                        params.put("iso", Math.min(3200, Math.max(iso, 400)));
                    }
                }
            }
        }

        if (motion == MotionType.FAST || motion == MotionType.VERY_FAST || motion == MotionType.CHAOTIC) {
            params.put("focus_mode", FocusMode.MULTI_POINT);
        }
    }

    /**
     * Classify continuous motion speed to shutter denominator (Python MotionCaptureAdvisor tier logic).
     */
    private static int classifyTierShutterDenom(float speed) {
        if (speed <= 2.0f)  return 60;    // STATIC
        if (speed <= 8.0f)  return 125;   // GENTLE
        if (speed <= 20.0f) return 250;   // MODERATE
        if (speed <= 50.0f) return 500;   // FAST
        if (speed <= 100.0f) return 1000; // VERY_FAST
        if (speed <= 200.0f) return 2000; // EXTREME
        return 4000;                       // UNCAPTURABLE
    }

    private boolean compareShutterSpeeds(String speed1, String speed2) {
        try {
            return parseShutter(speed1) < parseShutter(speed2);
        } catch (Exception e) {
            return false;
        }
    }

    private float parseShutter(String s) {
        if (s.contains("/")) {
            String[] p = s.split("/");
            return Float.parseFloat(p[0]) / Float.parseFloat(p[1]);
        }
        return Float.parseFloat(s);
    }

    // ------------------------------------------------------------------
    // Exposure validation — Pro mode disables AE, so the ISO/shutter we
    // output are used verbatim.  Two corrections:
    //  1. Fold EV into ISO (AE-based EV comp has no effect when AE OFF)
    //  2. Enforce a per-lighting minimum exposure product (ISO × shutter_s)
    // ------------------------------------------------------------------
    private void ensureAdequateExposure(Map<String, Object> params,
                                        LightingCondition lighting,
                                        Float estimatedLux) {
        // 1. Fold EV into ISO
        float ev = ((Number) params.getOrDefault("ev", 0.0f)).floatValue();
        if (ev != 0.0f) {
            int iso = ((Number) params.getOrDefault("iso", 200)).intValue();
            int adjusted = Math.round(iso * (float) Math.pow(2.0, ev));
            params.put("iso", Math.max(50, Math.min(3200, adjusted)));
            params.put("ev", 0.0f); // consumed
        }

        // 2. Per-lighting minimum exposure product (ISO × shutter_seconds)
        //    Calibrated for f/1.8 phone camera.
        Map<LightingCondition, Float> minProducts = new EnumMap<>(LightingCondition.class);
        minProducts.put(LightingCondition.VERY_BRIGHT,   0.01f);
        minProducts.put(LightingCondition.BRIGHT,        0.03f);
        minProducts.put(LightingCondition.NORMAL,        0.06f);
        minProducts.put(LightingCondition.CLOUDY,        0.15f);
        minProducts.put(LightingCondition.GOLDEN_HOUR,   0.15f);
        minProducts.put(LightingCondition.BACKLIT,       0.10f);
        minProducts.put(LightingCondition.INDOOR,        0.6f);
        minProducts.put(LightingCondition.ARTIFICIAL,    0.6f);
        minProducts.put(LightingCondition.MIXED,         0.3f);
        minProducts.put(LightingCondition.BLUE_HOUR,     1.5f);
        minProducts.put(LightingCondition.LOW_LIGHT,     6.0f);
        minProducts.put(LightingCondition.VERY_LOW_LIGHT, 30.0f);

        float target;
        if (estimatedLux != null && estimatedLux > 0) {
            target = 12.5f / estimatedLux;
        } else {
            Float t = minProducts.get(lighting);
            target = t != null ? t : 0.06f;
        }

        String shutterStr = (String) params.getOrDefault("shutter_speed", "1/125");
        float shutterS = parseShutter(shutterStr);
        int iso = ((Number) params.getOrDefault("iso", 200)).intValue();
        float currentProduct = iso * shutterS;

        // Allow up to 1 stop under (0.5×) before correcting
        if (currentProduct < target * 0.5f) {
            float neededIso = target / Math.max(shutterS, 1e-9f);
            if (neededIso <= 3200) {
                params.put("iso", Math.max(iso, (int) Math.min(3200, neededIso)));
            } else {
                params.put("iso", 3200);
                float neededS = target / 3200f;
                if (neededS >= 1.0f) {
                    params.put("shutter_speed", String.valueOf(Math.round(neededS)));
                } else {
                    params.put("shutter_speed", "1/" + Math.round(1.0f / neededS));
                }
            }
        }
    }

    private void applyPreviewSafePhoneDefaults(Map<String, Object> params,
                                               SceneAnalysisResult scene) {
        if (scene == null) {
            return;
        }

        LightingCondition lighting = scene.getLightingCondition();
        MotionType motion = scene.getMotionType();

        int iso = ((Number) params.getOrDefault("iso", 200)).intValue();
        float ev = ((Number) params.getOrDefault("ev", 0.0f)).floatValue();
        String shutter = (String) params.getOrDefault("shutter_speed", "1/125");

        boolean staticLike = motion == MotionType.STATIC || motion == MotionType.SLOW;

        // Conservative preview-oriented guard rails for phone Pro mode.
        if (lighting == LightingCondition.INDOOR
                || lighting == LightingCondition.ARTIFICIAL
                || lighting == LightingCondition.MIXED) {
            if (staticLike) {
                iso = Math.min(iso, 100);
                if (parseShutter(shutter) < parseShutter("1/60")) {
                    shutter = "1/60";
                }
            } else {
                iso = Math.min(iso, 200);
            }
            ev = Math.min(ev, 0.0f);
        } else if (lighting == LightingCondition.NORMAL || lighting == LightingCondition.CLOUDY) {
            iso = Math.min(iso, 100);
            ev = Math.min(ev, 0.0f);
        } else if (lighting == LightingCondition.BRIGHT
                || lighting == LightingCondition.VERY_BRIGHT
                || lighting == LightingCondition.BACKLIT
                || lighting == LightingCondition.GOLDEN_HOUR) {
            // Skip bright-light ISO cap for night scenes — the lighting
            // classifier is often confused by bright city lights at night.
            SceneType st = scene.getSceneType();
            if (!isNightScene(st)) {
                iso = Math.min(iso, 50);
            }
            ev = Math.min(ev, 0.0f);
        }

        params.put("iso", Math.max(50, Math.min(3200, iso)));
        params.put("shutter_speed", shutter);
        params.put("ev", Math.max(-3.0f, Math.min(3.0f, ev)));
    }

    /**
     * Night scenes (NIGHT, NIGHT_CITYSCAPE, NIGHT_PORTRAIT, NIGHT_SKY) have
     * expert base exposures calibrated for darkness.  When the lighting
     * classifier is fooled by bright city lights ("Very Bright" on a night
     * cityscape), subsequent adjustments can slash ISO / shutter to daytime
     * levels, producing a nearly-black preview.
     *
     * This method enforces that the final exposure product is never lower than
     * half the scene-expert's original product.
     */
    private void enforceNightExposureFloor(Map<String, Object> params,
                                           SceneType sceneType,
                                           float baseProduct) {
        if (!isNightScene(sceneType)) {
            return;
        }
        float minProduct = baseProduct * 0.5f;
        int iso = ((Number) params.getOrDefault("iso", 200)).intValue();
        String shutterStr = (String) params.getOrDefault("shutter_speed", "1/125");
        float shutterS = parseShutter(shutterStr);
        float currentProduct = iso * shutterS;
        if (currentProduct >= minProduct) {
            return;
        }
        // Boost ISO first; if still not enough, slow the shutter.
        float neededIso = minProduct / Math.max(shutterS, 1e-9f);
        if (neededIso <= 3200) {
            params.put("iso", Math.max(iso, (int) Math.min(3200, neededIso)));
        } else {
            params.put("iso", 3200);
            float neededS = minProduct / 3200f;
            if (neededS >= 1.0f) {
                params.put("shutter_speed", String.valueOf(Math.round(neededS)));
            } else {
                params.put("shutter_speed", "1/" + Math.round(1.0f / neededS));
            }
        }
        Log.w(TAG, "[NIGHT_FLOOR] scene=" + sceneType + " baseProduct=" + baseProduct
                + " adjusted iso=" + params.get("iso")
                + " shutter=" + params.get("shutter_speed"));
    }

    private static boolean isNightScene(SceneType st) {
        return st == SceneType.NIGHT
                || st == SceneType.NIGHT_CITYSCAPE
                || st == SceneType.NIGHT_PORTRAIT
                || st == SceneType.NIGHT_SKY;
    }

    private int calculateIso(SceneAnalysisResult scene) {
        Float lux = scene.getEstimatedLux();
        float l = lux == null ? 5000f : lux;
        if (l < 10) return 3200;
        if (l < 100) return 1600;
        if (l < 500) return 800;
        if (l < 2000) return 400;
        if (l < 10000) return 200;
        return 100;
    }

    private String calculateShutterSpeed(SceneAnalysisResult scene) {
        if (scene.getMotionType() == MotionType.VERY_FAST) return "1/1000";
        if (scene.getMotionType() == MotionType.FAST) return "1/500";
        if (scene.getMotionType() == MotionType.NORMAL) return "1/250";
        if (scene.getMotionType() == MotionType.SLOW) return "1/60";
        if (scene.getSceneType() == SceneType.WATERFALL) return "1/4";
        return "1/125";
    }

    private boolean isSlowShutter(String shutter, String threshold) {
        try {
            return parseShutter(shutter) >= parseShutter(threshold);
        } catch (Exception e) {
            return false;
        }
    }

    private String selectCompositionEnhancerMode(List<String> issues) {
        Set<String> s = new HashSet<>(issues);
        if (s.contains("poor_rule_of_thirds") || s.contains("subject_off_center")) return "rule_of_thirds";
        if (s.contains("unbalanced") || s.contains("cluttered_background")) return "minimalist";
        if (s.contains("too_much_headroom") || s.contains("insufficient_headroom")) return "centered";
        return "auto";
    }

    private boolean containsAny(List<String> source, List<String> candidates) {
        Set<String> s = new HashSet<>(source);
        for (String c : candidates) {
            if (s.contains(c)) return true;
        }
        return false;
    }

    private boolean isHumanSubject(MainSubject subject) {
        return subject == MainSubject.HUMAN_SINGLE
                || subject == MainSubject.HUMAN_GROUP
                || subject == MainSubject.HUMAN_FACE
                || subject == MainSubject.HUMAN_FULL_BODY;
    }

    // TEMP(2026-03-30): centralized temporary mode override for easy rollback.
    // Rules requested for demo:
    // - Prefer Pro for portrait/person/pet/night/bright env scenes.
    // - Selfie must consider camera direction: rear-camera selfie => Portrait.
    private CameraMode applyTemporaryDemoModeOverride(
            CameraMode baseMode,
            SceneAnalysisResult scene,
            String sourceTag) {
        if (!TEMP_DEMO_FORCE_PRO_FOR_MORE_SCENES || scene == null) {
            return baseMode;
        }

        SceneType st = scene.getSceneType();
        MainSubject subject = scene.getMainSubject();
        LightingCondition lc = scene.getLightingCondition();
        boolean hasFace = scene.isHasFace();
        boolean useFront = scene.isUseFrontCamera();

        // Explicit selfie handling by camera direction.
        // TEMP(2026-03-30): Portrait mode is disabled in demo.
        // Rear-camera selfie fallback is routed to Pro instead of Portrait.
        if (st == SceneType.SELFIE) {
            CameraMode target = CameraMode.PRO;
            if (target != baseMode) {
                Log.w(TAG, "[TEMP_MODE] " + sourceTag + " override: " + baseMode
                        + " -> " + target + " (scene=SELFIE, front=" + useFront + ")");
            }
            return target;
        }

        boolean portraitLikeScene = st == SceneType.PORTRAIT
                || st == SceneType.GROUP_PORTRAIT
                || st == SceneType.BACKLIT_PORTRAIT
                || st == SceneType.NIGHT_PORTRAIT;

        boolean petLikeScene = st == SceneType.PET || subject == MainSubject.ANIMAL_PET;

        boolean nightLikeScene = st == SceneType.NIGHT
                || st == SceneType.NIGHT_CITYSCAPE
                || st == SceneType.NIGHT_SKY
                || lc == LightingCondition.VERY_LOW_LIGHT
                || lc == LightingCondition.LOW_LIGHT
                || lc == LightingCondition.BLUE_HOUR;

        boolean brightLikeEnv = lc == LightingCondition.BRIGHT
                || lc == LightingCondition.VERY_BRIGHT
                || lc == LightingCondition.BACKLIT;

        boolean humanLike = isHumanSubject(subject) || hasFace;

        if (portraitLikeScene || petLikeScene || nightLikeScene || brightLikeEnv || humanLike) {
            if (baseMode != CameraMode.PRO) {
                Log.w(TAG, "[TEMP_MODE] " + sourceTag + " override: " + baseMode
                        + " -> PRO (scene=" + st + ", subject=" + subject
                        + ", light=" + lc + ", hasFace=" + hasFace + ")");
            }
            return CameraMode.PRO;
        }

        return baseMode;
    }

    private ClampResult clampResolution(CameraResolution resolution) {
        if (supportedResolutions.contains(resolution)) {
            return new ClampResult(resolution, false);
        }
        int targetIdx = RESOLUTION_ORDER.indexOf(resolution);
        CameraResolution fallback = supportedResolutions.get(0);
        for (CameraResolution r : supportedResolutions) {
            if (RESOLUTION_ORDER.indexOf(r) <= targetIdx) fallback = r;
        }
        return new ClampResult(fallback, true);
    }

    private List<String> extractNames(List<ToolRecommendation> tools) {
        List<String> names = new ArrayList<>();
        for (ToolRecommendation t : tools) names.add(t.getToolName());
        return names;
    }

    private ToolRecommendation tool(String name, String server, int priority,
                                    Map<String, Object> params, String reason, float confidence) {
        return new ToolRecommendation(name, server, priority, params, reason, confidence);
    }

    private Map<String, Object> kv(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private SceneAnalysisResult shallowCopyScene(SceneAnalysisResult src) {
        SceneAnalysisResult c = new SceneAnalysisResult(
                src.getSceneType(), src.getSceneTypeConfidence(),
                src.getLightingCondition(), src.getLightingConfidence(),
                src.getMotionType(), src.getMotionConfidence(),
                src.getMainSubject(), src.getSubjectConfidence(),
                src.getContrastValue(), src.getSharpnessValue(), src.getNoiseLevel()
        );
        c.setEstimatedLux(src.getEstimatedLux());
        c.setMotionSpeed(src.getMotionSpeed());
        c.setMotionDirection(src.getMotionDirection());
        c.setSubjectBoundingBox(src.getSubjectBoundingBox());
        c.setHasFace(src.isHasFace());
        c.setFaceCount(src.getFaceCount());
        c.setHasText(src.isHasText());
        c.setTilted(src.isTilted());
        c.setTiltAngle(src.getTiltAngle());
        c.setHasShadow(src.isHasShadow());
        c.setHasReflection(src.isHasReflection());
        c.setHasBackgroundPeople(src.isHasBackgroundPeople());
        c.setHasFlare(src.isHasFlare());
        c.setHasMoire(src.isHasMoire());
        c.setCompositionScore(src.getCompositionScore());
        c.setCompositionIssues(src.getCompositionIssues());
        c.setNeedsCompositionEdit(src.isNeedsCompositionEdit());
        c.setSuggestedCrop(src.getSuggestedCrop());
        c.setFeatureEmbedding(src.getFeatureEmbedding());
        c.setRecommendedResolution(src.getRecommendedResolution());
        c.setPreferVideo(src.isPreferVideo());
        c.setRecommendedFps(src.getRecommendedFps());
        c.setMotionSpeedTier(src.getMotionSpeedTier());
        c.setCaptureWarning(src.getCaptureWarning());
        c.setRecommendedShutter(src.getRecommendedShutter());
        c.setSiglipFeatures(src.getSiglipFeatures());
        c.setMotionFeatures(src.getMotionFeatures());
        c.setUseFrontCamera(src.isUseFrontCamera());
        c.setHasSymmetry(src.isHasSymmetry());
        c.setHasDiagonalLines(src.isHasDiagonalLines());
        c.setHasLeadingLines(src.isHasLeadingLines());
        c.setSubjectFillRatio(src.getSubjectFillRatio());
        c.setSubjectCount(src.getSubjectCount());
        c.setVisualComplexity(src.getVisualComplexity());
        c.setSceneDepthLayers(src.getSceneDepthLayers());
        c.setBrightnessValue(src.getBrightnessValue());
        c.setBlurLevel(src.getBlurLevel());
        return c;
    }

    // ---------------------------------------------------------------------
    // Mapping tables
    // ---------------------------------------------------------------------

    private Map<SceneType, CameraMode> buildSceneModeMap() {
        Map<SceneType, CameraMode> m = new EnumMap<>(SceneType.class);
        m.put(SceneType.PORTRAIT, CameraMode.PRO);
        m.put(SceneType.GROUP_PORTRAIT, CameraMode.PRO);
        m.put(SceneType.SELFIE, CameraMode.PRO);
        m.put(SceneType.BACKLIT_PORTRAIT, CameraMode.PRO);

        // m.put(SceneType.NIGHT, CameraMode.NIGHT);
        // m.put(SceneType.NIGHT_PORTRAIT, CameraMode.PRO);
        // m.put(SceneType.NIGHT_CITYSCAPE, CameraMode.NIGHT);
        // m.put(SceneType.NIGHT_SKY, CameraMode.NIGHT);
        m.put(SceneType.NIGHT, CameraMode.PRO);
        m.put(SceneType.NIGHT_PORTRAIT, CameraMode.PRO);
        m.put(SceneType.NIGHT_CITYSCAPE, CameraMode.PRO);
        m.put(SceneType.NIGHT_SKY, CameraMode.PRO);

        m.put(SceneType.FOOD, CameraMode.FOOD);
        m.put(SceneType.PANORAMIC, CameraMode.PANORAMA);

        m.put(SceneType.FAST_MOVING, CameraMode.PRO);
        m.put(SceneType.SLOW_MOVING, CameraMode.PHOTO);
        m.put(SceneType.SPORTS, CameraMode.PRO);
        m.put(SceneType.WATERFALL, CameraMode.PRO);
        m.put(SceneType.REPEATING_MOTION, CameraMode.PRO);
        m.put(SceneType.VEHICLE, CameraMode.PRO);

        m.put(SceneType.LANDSCAPE, CameraMode.PHOTO);
        m.put(SceneType.CITYSCAPE, CameraMode.PHOTO);
        m.put(SceneType.ARCHITECTURE, CameraMode.PHOTO);
        m.put(SceneType.SUNSET_SUNRISE, CameraMode.PHOTO);

        m.put(SceneType.WILDLIFE, CameraMode.PRO);
        m.put(SceneType.PET, CameraMode.PHOTO);
        m.put(SceneType.MACRO, CameraMode.PHOTO);
        m.put(SceneType.FLOWER, CameraMode.PHOTO);

        m.put(SceneType.DOCUMENT, CameraMode.PHOTO);
        m.put(SceneType.PRODUCT, CameraMode.PHOTO);
        m.put(SceneType.GENERAL, CameraMode.PHOTO);
        return m;
    }

    private Map<SceneType, CameraResolution> buildSceneResolutionMap() {
        Map<SceneType, CameraResolution> m = new EnumMap<>(SceneType.class);
        m.put(SceneType.LANDSCAPE, CameraResolution.MP_200);
        m.put(SceneType.ARCHITECTURE, CameraResolution.MP_200);
        m.put(SceneType.PANORAMIC, CameraResolution.MP_200);
        m.put(SceneType.DOCUMENT, CameraResolution.MP_200);
        m.put(SceneType.CITYSCAPE, CameraResolution.MP_200);

        m.put(SceneType.PRODUCT, CameraResolution.MP_108);
        m.put(SceneType.MACRO, CameraResolution.MP_108);
        m.put(SceneType.FLOWER, CameraResolution.MP_108);
        m.put(SceneType.FOOD, CameraResolution.MP_108);

        m.put(SceneType.PORTRAIT, CameraResolution.MP_50);
        m.put(SceneType.GROUP_PORTRAIT, CameraResolution.MP_50);
        m.put(SceneType.SPORTS, CameraResolution.MP_50);
        m.put(SceneType.PET, CameraResolution.MP_50);
        m.put(SceneType.WILDLIFE, CameraResolution.MP_50);
        m.put(SceneType.SUNSET_SUNRISE, CameraResolution.MP_50);
        m.put(SceneType.VEHICLE, CameraResolution.MP_50);
        m.put(SceneType.SLOW_MOVING, CameraResolution.MP_50);
        m.put(SceneType.WATERFALL, CameraResolution.MP_50);
        m.put(SceneType.REPEATING_MOTION, CameraResolution.MP_50);
        m.put(SceneType.BACKLIT_PORTRAIT, CameraResolution.MP_50);

        m.put(SceneType.SELFIE, CameraResolution.MP_12);
        m.put(SceneType.FAST_MOVING, CameraResolution.MP_12);
        m.put(SceneType.NIGHT, CameraResolution.MP_12);
        m.put(SceneType.NIGHT_PORTRAIT, CameraResolution.MP_12);
        m.put(SceneType.NIGHT_CITYSCAPE, CameraResolution.MP_12);
        m.put(SceneType.NIGHT_SKY, CameraResolution.MP_12);

        m.put(SceneType.GENERAL, CameraResolution.MP_50);
        return m;
    }

    private Map<SceneType, CameraMode> buildVideoModeMap() {
        Map<SceneType, CameraMode> m = new EnumMap<>(SceneType.class);
        m.put(SceneType.PORTRAIT, CameraMode.PORTRAIT_VIDEO);
        m.put(SceneType.SELFIE, CameraMode.PORTRAIT_VIDEO);
        m.put(SceneType.SPORTS, CameraMode.SINGLE_TAKE);
        m.put(SceneType.FAST_MOVING, CameraMode.SINGLE_TAKE);
        m.put(SceneType.WATERFALL, CameraMode.SLOW_MOTION);
        m.put(SceneType.REPEATING_MOTION, CameraMode.SLOW_MOTION);
        m.put(SceneType.CITYSCAPE, CameraMode.HYPERLAPSE);
        m.put(SceneType.LANDSCAPE, CameraMode.HYPERLAPSE);
        m.put(SceneType.SUNSET_SUNRISE, CameraMode.HYPERLAPSE);
        m.put(SceneType.NIGHT, CameraMode.PRO_VIDEO);
        m.put(SceneType.NIGHT_CITYSCAPE, CameraMode.PRO_VIDEO);
        m.put(SceneType.GENERAL, CameraMode.VIDEO);
        return m;
    }

    private Map<SceneType, ExpertRawMode> buildExpertRawMap() {
        Map<SceneType, ExpertRawMode> m = new EnumMap<>(SceneType.class);
        m.put(SceneType.NIGHT_SKY, ExpertRawMode.ASTRO);
        m.put(SceneType.NIGHT_PORTRAIT, ExpertRawMode.ASTRO_PORTRAIT);
        m.put(SceneType.SUNSET_SUNRISE, ExpertRawMode.MULTI_EXPOSURE);
        m.put(SceneType.WATERFALL, ExpertRawMode.ND_FILTER);
        m.put(SceneType.BACKLIT_PORTRAIT, ExpertRawMode.MULTI_EXPOSURE);
        m.put(SceneType.PORTRAIT, ExpertRawMode.VIRTUAL_APERTURE);
        m.put(SceneType.MACRO, ExpertRawMode.VIRTUAL_APERTURE);
        m.put(SceneType.FLOWER, ExpertRawMode.VIRTUAL_APERTURE);
        return m;
    }

    private Map<String, Integer> buildNdFilterMap() {
        Map<String, Integer> m = new HashMap<>();
        m.put("waterfall_mild", 8);
        m.put("waterfall_silky", 32);
        m.put("waterfall_extreme", 64);
        m.put("clouds_moving", 16);
        m.put("clouds_dramatic", 64);
        m.put("ocean_waves", 16);
        m.put("river_stream", 32);
        m.put("light_trails", 64);
        m.put("crowd_removal", 256);
        m.put("default", 16);
        return m;
    }

    private Map<MainSubject, CameraMode> buildSubjectModeMap() {
        Map<MainSubject, CameraMode> m = new EnumMap<>(MainSubject.class);
        m.put(MainSubject.HUMAN_SINGLE, CameraMode.PRO);
        m.put(MainSubject.HUMAN_GROUP, CameraMode.PRO);
        m.put(MainSubject.HUMAN_FACE, CameraMode.PRO);
        m.put(MainSubject.HUMAN_FULL_BODY, CameraMode.PRO);
        m.put(MainSubject.ANIMAL_PET, CameraMode.PHOTO);
        m.put(MainSubject.ANIMAL_WILDLIFE, CameraMode.PRO);
        m.put(MainSubject.ANIMAL_BIRD, CameraMode.PRO);
        m.put(MainSubject.FOOD_DISH, CameraMode.FOOD);
        m.put(MainSubject.FOOD_INGREDIENT, CameraMode.FOOD);
        m.put(MainSubject.FOOD_DRINK, CameraMode.FOOD);
        m.put(MainSubject.LANDSCAPE_NATURE, CameraMode.PHOTO);
        m.put(MainSubject.LANDSCAPE_URBAN, CameraMode.PHOTO);
        m.put(MainSubject.ARCHITECTURE_EXTERIOR, CameraMode.PHOTO);
        m.put(MainSubject.ARCHITECTURE_INTERIOR, CameraMode.PHOTO);
        m.put(MainSubject.OBJECT_PRODUCT, CameraMode.PHOTO);
        m.put(MainSubject.OBJECT_VEHICLE, CameraMode.PHOTO);
        m.put(MainSubject.OBJECT_DOCUMENT, CameraMode.PHOTO);
        m.put(MainSubject.PLANT_FLOWER, CameraMode.PHOTO);
        m.put(MainSubject.PLANT_TREE, CameraMode.PHOTO);
        m.put(MainSubject.SKY_DAY, CameraMode.PHOTO);
        m.put(MainSubject.SKY_NIGHT, CameraMode.NIGHT);
        m.put(MainSubject.WATER_BODY, CameraMode.PHOTO);
        m.put(MainSubject.NONE, CameraMode.PHOTO);
        return m;
    }

    private Map<LightingCondition, Map<String, Object>> buildLightingSettingsMap() {
        Map<LightingCondition, Map<String, Object>> m = new EnumMap<>(LightingCondition.class);
        m.put(LightingCondition.VERY_LOW_LIGHT, kv("iso", 3200, "shutter_base", "1/30", "use_night_mode", true, "flash_suggest", true));
        m.put(LightingCondition.LOW_LIGHT, kv("iso", 1600, "shutter_base", "1/60", "use_night_mode", true, "flash_suggest", false));
        m.put(LightingCondition.INDOOR, kv("iso", 800, "shutter_base", "1/60", "use_night_mode", false, "flash_suggest", false));
        m.put(LightingCondition.CLOUDY, kv("iso", 400, "shutter_base", "1/125", "use_night_mode", false, "flash_suggest", false));
        m.put(LightingCondition.NORMAL, kv("iso", 200, "shutter_base", "1/250", "use_night_mode", false, "flash_suggest", false));
        m.put(LightingCondition.BRIGHT, kv("iso", 100, "shutter_base", "1/500", "use_night_mode", false, "flash_suggest", false));
        m.put(LightingCondition.VERY_BRIGHT, kv("iso", 50, "shutter_base", "1/1000", "use_night_mode", false, "flash_suggest", false));
        m.put(LightingCondition.BACKLIT, kv("iso", 200, "shutter_base", "1/250", "use_night_mode", false, "flash_suggest", true, "hdr_suggest", true));
        m.put(LightingCondition.MIXED, kv("iso", 400, "shutter_base", "1/125", "use_night_mode", false, "flash_suggest", false, "hdr_suggest", true));
        m.put(LightingCondition.ARTIFICIAL, kv("iso", 800, "shutter_base", "1/60", "use_night_mode", false, "flash_suggest", false));
        m.put(LightingCondition.GOLDEN_HOUR, kv("iso", 200, "shutter_base", "1/250", "use_night_mode", false, "flash_suggest", false));
        m.put(LightingCondition.BLUE_HOUR, kv("iso", 800, "shutter_base", "1/60", "use_night_mode", true, "flash_suggest", false));
        return m;
    }

    private Map<SceneType, Map<String, Object>> buildProModeParamsMap() {
        Map<SceneType, Map<String, Object>> m = new EnumMap<>(SceneType.class);

        m.put(SceneType.PORTRAIT, kv("iso", 100, "shutter_speed", "1/125", "ev", 0.3f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.CENTER_WEIGHTED));
        m.put(SceneType.GROUP_PORTRAIT, kv("iso", 200, "shutter_speed", "1/125", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.SELFIE, kv("iso", 100, "shutter_speed", "1/60", "ev", 0.3f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.CENTER_WEIGHTED));
        m.put(SceneType.BACKLIT_PORTRAIT, kv("iso", 100, "shutter_speed", "1/200", "ev", 1.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.SPOT));

        m.put(SceneType.LANDSCAPE, kv("iso", 100, "shutter_speed", "1/250", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.DAYLIGHT, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.CITYSCAPE, kv("iso", 100, "shutter_speed", "1/250", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.DAYLIGHT, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.ARCHITECTURE, kv("iso", 100, "shutter_speed", "1/200", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.PANORAMIC, kv("iso", 100, "shutter_speed", "1/250", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.DAYLIGHT, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));

        m.put(SceneType.NIGHT, kv("iso", 800, "shutter_speed", "1/30", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.NIGHT_PORTRAIT, kv("iso", 1600, "shutter_speed", "1/60", "ev", 0.3f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.CENTER_WEIGHTED));
        m.put(SceneType.NIGHT_CITYSCAPE, kv("iso", 400, "shutter_speed", "1/15", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.NIGHT_SKY, kv("iso", 3200, "shutter_speed", "25", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.DAYLIGHT, "focus_mode", FocusMode.MANUAL, "manual_focus", 1.0f,
                "metering_mode", MeteringMode.MATRIX));

        m.put(SceneType.FAST_MOVING, kv("iso", 400, "shutter_speed", "1/1000", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.SLOW_MOVING, kv("iso", 200, "shutter_speed", "1/250", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.SPORTS, kv("iso", 800, "shutter_speed", "1/2000", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.VEHICLE, kv("iso", 400, "shutter_speed", "1/1000", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.REPEATING_MOTION, kv("iso", 200, "shutter_speed", "1/500", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));

        m.put(SceneType.WILDLIFE, kv("iso", 800, "shutter_speed", "1/1000", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.DAYLIGHT, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.SPOT));
        m.put(SceneType.PET, kv("iso", 400, "shutter_speed", "1/250", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.CENTER_WEIGHTED));
        m.put(SceneType.MACRO, kv("iso", 100, "shutter_speed", "1/125", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MANUAL, "manual_focus", 0.2f,
                "metering_mode", MeteringMode.SPOT));
        m.put(SceneType.FLOWER, kv("iso", 100, "shutter_speed", "1/200", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.CENTER_WEIGHTED));
        m.put(SceneType.WATERFALL, kv("iso", 50, "shutter_speed", "1/4", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.DAYLIGHT, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));

        m.put(SceneType.FOOD, kv("iso", 200, "shutter_speed", "1/60", "ev", 0.3f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.CENTER_WEIGHTED));
        m.put(SceneType.PRODUCT, kv("iso", 100, "shutter_speed", "1/125", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.CENTER, "metering_mode", MeteringMode.CENTER_WEIGHTED));
        m.put(SceneType.DOCUMENT, kv("iso", 200, "shutter_speed", "1/60", "ev", 0.3f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));
        m.put(SceneType.SUNSET_SUNRISE, kv("iso", 100, "shutter_speed", "1/250", "ev", -0.7f,
                "white_balance", WhiteBalanceMode.DAYLIGHT, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));

        m.put(SceneType.GENERAL, kv("iso", 200, "shutter_speed", "1/125", "ev", 0.0f,
                "white_balance", WhiteBalanceMode.AUTO, "focus_mode", FocusMode.MULTI_POINT, "metering_mode", MeteringMode.MATRIX));

        return m;
    }

    // ---------------------------------------------------------------------
    // Convenience / Specialist APIs (ported from Python)
    // ---------------------------------------------------------------------

    /**
     * Get specialized recommendations for macro/close-up photography.
     */
    public List<ToolRecommendation> getMacroRecommendations(SceneAnalysisResult scene) {
        List<ToolRecommendation> tools = new ArrayList<>();
        int priority = 1;

        // Pro mode for manual focus control
        tools.add(tool("Camera_ChangeMode", "Camera", priority++,
                kv("ModeName", CameraMode.PRO.getValue()),
                "Pro mode for manual focus in macro photography", 0.9f));

        // Manual focus for precise control
        ToolRecommendation mf = tool("Camera_ChangeFocusMode", "Camera", priority++,
                kv("mode", FocusMode.MANUAL.getValue(), "distance", 0.1f),
                "Manual focus for close-up subjects", 0.85f);
        mf.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
        tools.add(mf);

        // Spot metering for subject
        ToolRecommendation meter = tool("Camera_ChangeMeteringMode", "Camera", priority++,
                kv("mode", MeteringMode.SPOT.getValue()),
                "Spot metering for accurate macro exposure", 0.85f);
        meter.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
        tools.add(meter);

        // Low ISO for maximum detail
        ToolRecommendation iso = tool("Camera_ChangeIso", "Camera", priority++,
                kv("iso", 100),
                "Low ISO for maximum detail and minimum noise", 0.8f);
        iso.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
        tools.add(iso);

        return tools;
    }

    /**
     * Get specialized recommendations for low-light scenarios.
     */
    public List<ToolRecommendation> getLowLightRecommendations(SceneAnalysisResult scene, boolean useFlash) {
        List<ToolRecommendation> tools = new ArrayList<>();
        int priority = 1;

        if (scene.getMotionType() == MotionType.STATIC && !useFlash) {
            // Night mode for static scenes
            tools.add(tool("Camera_ChangeMode", "Camera", priority++,
                    kv("ModeName", CameraMode.NIGHT.getValue()),
                    "Night mode for best low-light quality with stable scene", 0.9f));
        } else {
            // Pro mode for more control
            tools.add(tool("Camera_ChangeMode", "Camera", priority++,
                    kv("ModeName", CameraMode.PRO.getValue()),
                    "Pro mode for low-light manual control", 0.85f));

            // High ISO
            ToolRecommendation hi = tool("Camera_ChangeIso", "Camera", priority++,
                    kv("iso", 1600),
                    "High ISO for low light", scene.getLightingConfidence());
            hi.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
            tools.add(hi);

            // Slower shutter if stable
            if (scene.getMotionType() == MotionType.STATIC) {
                ToolRecommendation sh = tool("Camera_ChangeShutterSpeed", "Camera", priority++,
                        kv("shutter_speed", "1/30"),
                        "Slower shutter for more light gathering", 0.8f);
                sh.setPrerequisites(Collections.singletonList("Camera_ChangeMode"));
                tools.add(sh);
            }
        }

        priority++;

        if (useFlash) {
            tools.add(tool("Camera_Flash", "Camera", priority,
                    kv("mode", "auto"),
                    "Flash to illuminate subject in low light", 0.85f));
        }

        return tools;
    }

    /**
     * Validate that tools are compatible with the selected mode.
     *
     * @return List of incompatible tool messages (empty if all compatible)
     */
    public List<String> validateModeCompatibility(CameraMode mode, List<String> toolNames) {
        List<String> incompatible = new ArrayList<>();

        List<String> proOnlyTools = Arrays.asList(
                "Camera_ChangeIso", "Camera_ChangeShutterSpeed",
                "Camera_ChangeEV", "Camera_ChangeWhiteBalance",
                "Camera_ChangeFocusMode", "Camera_ChangeMeteringMode"
        );

        if (mode != CameraMode.PRO && mode != CameraMode.PRO_VIDEO) {
            for (String t : toolNames) {
                if (proOnlyTools.contains(t)) {
                    incompatible.add(t + " requires Pro or Pro_video mode");
                }
            }
        }

        // Night mode restrictions
        if (mode == CameraMode.NIGHT) {
            for (String t : toolNames) {
                if ("Camera_Flash".equals(t)) {
                    incompatible.add(t + " is not recommended in Night mode");
                }
            }
        }

        // Food mode restrictions
        if (mode == CameraMode.FOOD) {
            if (toolNames.contains("Camera_ChangeWhiteBalance")) {
                incompatible.add("Food mode has optimized white balance for food colors");
            }
        }

        return incompatible;
    }

    /**
     * Get all available camera modes organized by category.
     */
    public Map<String, List<String>> getAllAvailableModes() {
        Map<String, List<String>> modes = new LinkedHashMap<>();

        modes.put("photo_modes", Arrays.asList(
                CameraMode.PHOTO.getValue(), CameraMode.PORTRAIT.getValue(),
                CameraMode.NIGHT.getValue(), CameraMode.PRO.getValue(),
                CameraMode.FOOD.getValue(), CameraMode.PANORAMA.getValue()
        ));
        modes.put("video_modes", Arrays.asList(
                CameraMode.VIDEO.getValue(), CameraMode.PRO_VIDEO.getValue(),
                CameraMode.PORTRAIT_VIDEO.getValue(), CameraMode.SLOW_MOTION.getValue(),
                CameraMode.HYPERLAPSE.getValue(), CameraMode.DUAL_RECORDING.getValue()
        ));
        modes.put("special_modes", Collections.singletonList(
                CameraMode.SINGLE_TAKE.getValue()
        ));
        modes.put("expert_raw_labs", Arrays.asList(
                ExpertRawMode.ASTRO.getValue(), ExpertRawMode.ASTRO_PORTRAIT.getValue(),
                ExpertRawMode.MULTI_EXPOSURE.getValue(), ExpertRawMode.ND_FILTER.getValue(),
                ExpertRawMode.VIRTUAL_APERTURE.getValue()
        ));

        return modes;
    }

    /**
     * Get tools to check Expert Raw capabilities on this device.
     */
    public List<ToolRecommendation> getExpertRawCapabilityCheck() {
        List<ToolRecommendation> checks = new ArrayList<>();
        checks.add(tool("ExpertRaw_CheckLabs", "ExpertRaw", 0, new HashMap<>(),
                "Check which Expert Raw labs/modes are supported", 1.0f));
        checks.add(tool("ExpertRaw_CheckPonFile", "ExpertRaw", 0, new HashMap<>(),
                "Verify PON file is loaded correctly", 1.0f));
        return checks;
    }

    /**
     * Get object removal recommendation for post-processing.
     *
     * @return ToolRecommendation or null if no unwanted objects
     */
    public ToolRecommendation getObjectRemovalRecommendation(boolean hasUnwantedObjects, String objectDescription) {
        if (!hasUnwantedObjects) return null;
        return tool("Gallery_ObjectRemover", "Gallery", 100, new HashMap<>(),
                "Remove " + (objectDescription != null ? objectDescription : "unwanted objects") + " from photo", 0.85f);
    }

    /**
     * Get scene detection tool for the specified app.
     */
    public ToolRecommendation getSceneDetectionTool(CameraApp app) {
        if (app == CameraApp.EXPERT_RAW) {
            return tool("ExpertRaw_DetectScene", "ExpertRaw", 0, new HashMap<>(),
                    "Detect current scene from Expert Raw preview", 1.0f);
        }
        return tool("DetectScene", "Camera", 0, new HashMap<>(),
                "Detect current scene from camera preview", 1.0f);
    }

    // ---------------------------------------------------------------------
    // Small DTOs
    // ---------------------------------------------------------------------

    private static class ClampResult {
        final CameraResolution value;
        final boolean wasClamped;
        ClampResult(CameraResolution value, boolean wasClamped) {
            this.value = value;
            this.wasClamped = wasClamped;
        }
    }

    private static class ValidationBundle {
        List<ToolRecommendation> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> remainingConflicts = new ArrayList<>();
    }

    public static class RecommendationWithPro {
        private final ToolRecommendationResult recommendation;
        private final ProModeParameters proModeParameters;

        public RecommendationWithPro(ToolRecommendationResult recommendation, ProModeParameters proModeParameters) {
            this.recommendation = recommendation;
            this.proModeParameters = proModeParameters;
        }

        public ToolRecommendationResult getRecommendation() { return recommendation; }
        public ProModeParameters getProModeParameters() { return proModeParameters; }
    }
}
