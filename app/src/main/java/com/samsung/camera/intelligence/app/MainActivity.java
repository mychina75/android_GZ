package com.samsung.camera.intelligence.app;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.samsung.camera.intelligence.app.camera.CameraGLPreview;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.samsung.camera.intelligence.CameraIntelligenceManager;
import com.samsung.camera.intelligence.app.camera.CameraController;import com.samsung.camera.intelligence.app.camera.CameraProSettings;
import com.samsung.camera.intelligence.app.camera.CameraWorkflowStateMachine;
import com.samsung.camera.intelligence.app.camera.PostProcessingDispatcher;
import com.samsung.camera.intelligence.app.camera.RecommendationExecutor;
import com.samsung.camera.intelligence.guidance.GuidanceCategory;
import com.samsung.camera.intelligence.guidance.GuidanceFrame;
import com.samsung.camera.intelligence.guidance.GuidanceOverlay;
import com.samsung.camera.intelligence.guidance.GuidanceUrgency;
import com.samsung.camera.intelligence.guidance.MasterMatchGuide;
import com.samsung.camera.intelligence.guidance.MasterMatchOverlay;
import com.samsung.camera.intelligence.guidance.TemporalSmoother;
import com.samsung.camera.intelligence.app.ui.GuidanceOverlayView;
import com.samsung.camera.intelligence.app.ui.MimicModePanelController;
import com.samsung.camera.intelligence.app.ui.PhotoDefectOverlayView;
import com.samsung.camera.intelligence.app.ui.StyleStrawExtractor;
import com.samsung.camera.intelligence.app.ui.RecommendationAdapter;
import com.samsung.camera.intelligence.guidance.FrameAnalysis;
import com.samsung.camera.intelligence.models.ToolRecommendation;
import com.samsung.camera.intelligence.models.ToolRecommendationResult;
import com.samsung.camera.intelligence.models.SceneAnalysisResult;
import com.samsung.camera.intelligence.models.CameraResolution;
import com.samsung.camera.intelligence.recommendation.SceneToneOptimizer;
import com.samsung.camera.intelligence.recommendation.SceneEnhancementOptimizer;
import com.samsung.camera.intelligence.recommendation.ExposureMapper;
import com.samsung.camera.intelligence.trigger.TriggerNames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private CameraGLPreview glPreview;
    private GuidanceOverlayView guidanceOverlayView;
    private TextView statusText;
    private TextView guideText;
    private TextView modelRuntimeHintText;
    private Button filterToggleButton;
    private View filterStripScroll;
    private LinearLayout filterStripContainer;
    private View triggerRecommendationScroll;
    private LinearLayout triggerPillContainer;
    private TextView triggerDebugText;
    private TextView analysisSummaryText;
    private TextView featureSummaryText;
    private TextView capabilitySummaryText;
    private EditText isoInput;
    private EditText shutterInput;
    private EditText evInput;
    private EditText wbInput;
    private EditText focusInput;
    private View proControlsContainer;
    private View recordingIndicator;
    private View recordDot;
    private TextView recordTimerText;
    private Button currentModeButton;
    private Button captureButton;
    private Button applyActionButton;
    private Button toggleProControlsButton;
    private Button settingsButton;
    private View mainRoot;
    private View topOverlayContainer;
    private Button closePreviewButton;
    private View mimicPanelContainer;
    private TextView mimicParamBar;
    private LinearLayout mimicCard0, mimicCard1, mimicCard2;
    private TextView mimicCardLabel0, mimicCardLabel1, mimicCardLabel2;
    private TextView mimicCardInfo0, mimicCardInfo1, mimicCardInfo2;
    private android.widget.ImageView mimicCardThumb0, mimicCardThumb1, mimicCardThumb2;
    // Style Straw: gallery card (slot 3)
    private LinearLayout mimicCard3;
    private TextView mimicCardLabel3, mimicCardInfo3;
    private android.widget.ImageView mimicCardThumb3;
    private View mimicAddStrawBtn;
    private View mimicCard3Wrapper;
    private View mimicStrawCloseBtn, mimicStrawReplaceBtn;
    private MimicModePanelController mimicPanelController;
    private final ExecutorService styleStrawExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService portraitSegmentationExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean portraitSegmentationInFlight = new AtomicBoolean(false);
    private com.samsung.camera.intelligence.inference.SegmentationRunner portraitSegmentationRunner;
    private com.samsung.camera.intelligence.inference.SubjectDetectorEngine portraitFallbackDetector;
    private long lastPortraitSegmentationAtMs = 0L;
    private static final long PORTRAIT_SEGMENT_INTERVAL_MS = 120L;

    private CameraController cameraController;
    private com.samsung.camera.intelligence.app.camera.AutoZoomController autoZoomController;
    private com.samsung.camera.intelligence.app.camera.CameraStylePresetManager stylePresetManager;
    private CameraIntelligenceManager intelligenceManager;
    private RecommendationExecutor recommendationExecutor;
    private PostProcessingDispatcher postProcessingDispatcher;
    private ModelRuntimeController modelRuntimeController;
    private BottomPanelController bottomPanelController;
    private PhotoPreviewPostToolsController photoPreviewController;
    private final CameraWorkflowStateMachine stateMachine = new CameraWorkflowStateMachine();
    private RecommendationAdapter recommendationAdapter;
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private Sensor gravitySensor;
    private Sensor accelerometerSensor;
    private final float[] sensorRotationMatrix = new float[9];
    private final float[] remappedRotationMatrix = new float[9];
    private final float[] sensorOrientationAngles = new float[3];
    private final float[] accelGravityEstimate = new float[3];
    private boolean levelSensorRegistered = false;
    private boolean hasDeviceLevelPose = false;
    private float smoothedHorizonAngleDeg = 0.0f;
    private float smoothedPitchDeg = 0.0f;
    private boolean accelGravityInitialized = false;
    private boolean shootingLandscape = false;

    private final AtomicBoolean processing = new AtomicBoolean(false);
    private int frameCounter = 0;
    private ToolRecommendationResult lastToolResult;
    private String latestPhotoPath;
    private long recordingStartMs = 0L;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ObjectAnimator recordDotPulseAnimator;
    private boolean modeDialogShowing = false;
    private String lastRecommendedMode = null;
    private final Map<String, Long> dismissedModes = new HashMap<>();
    private final Map<String, Integer> modeDismissCount = new HashMap<>();
    private static final long DISMISSED_MODE_EXPIRY_MS = 30_000L;
    private static final long BASE_FATIGUE_COOLDOWN_MS = 8_000L;
    private static final long MAX_FATIGUE_COOLDOWN_MS = 60_000L;
    private String currentSuggestedModeName = null;
    private String displayedModeLabel = null;
    private int consecutiveModeFrames = 0;
    private String pendingModeName = null;
    private static final int MODE_SUGGEST_MIN_FRAMES = 2;
    private static final long MODE_DIALOG_COOLDOWN_MS = 5_000L;
    private static final long MODE_SWITCH_SETTLE_MS = 3_000L;
    private static final int UI_UPDATE_EVERY_N_FRAMES = 2;
    private static final long RECOMMEND_UPDATE_INTERVAL_MS = 800L;
    // After applying tone-affecting parameters, pause frame analysis briefly
    // so the preview can settle before it is analyzed again.
    private static final long TONE_SETTLE_PAUSE_MS = 2200L;
    private volatile long toneSettleUntilMs = 0;
    private View photoPreviewContainer;
    private ImageView photoPreviewImage;
    private PhotoDefectOverlayView photoDefectOverlayView;
    private TextView photoDefectSummaryText;
    private RecyclerView photoPostToolsList;
    private View bottomConsolePanel;
    private View bottomConsoleHandle;
    private View bottomExtrasContainer;
    private Button toggleExtrasButton;
    private Button summaryLiveTabButton;
    private Button summaryFeatureTabButton;
    private Button summaryPipelineTabButton;
    private long lastModeDialogAtMs = 0L;
    private long lastModeSwitchAtMs = 0L;
    private boolean suppressProInputWatcher = false;

    // Samsung-style floating Pro parameter bar
    private View proParamBar;

    // Mode suggestion pill state
    private View modeSuggestPill;
    private TextView modeSuggestLabel;
    private TextView modeSuggestIcon;
    private String pillModeName = null;  // mode name currently shown on the pill
    private ToolRecommendationResult pillToolResult = null;  // cached result for dialog

    // Auto-tone state (managed in Settings only, no preview pill)
    private boolean autoToneEnabled = false;
    private SceneToneOptimizer sceneToneOptimizer;
    private SceneToneOptimizer.ToneParams lastAppliedAutoTone = null;

    // Auto Scene Optimization state
    private boolean autoOptimizationEnabled = false;
    private boolean saveCompareTriplet = true;
    /** Tone params + reference info captured at the moment of capture, dumped into _meta.txt. */
    private final java.util.Map<String, Object> lastAppliedMimicMeta = new java.util.LinkedHashMap<>();
    private String lastAppliedMimicReferenceLabel = null;
    private String lastAppliedMimicReferenceAsset = null;
    private android.net.Uri lastAppliedMimicReferenceUri = null;
    private SceneEnhancementOptimizer sceneEnhancementOptimizer;
    private String activeEnhancementLabel = null;

    // Settings state
    private static final String PREFS_NAME = "intelligent_camera_prefs";
    private static final String PREF_DEMO_MODE = "demo_mode";
    private static final String PREF_SHOW_COMPOSITION = "show_composition";
    private static final String PREF_SHOW_GUIDANCE_OVERLAY = "show_guidance_overlay";
    private static final String PREF_AUTO_MODE_SUGGEST = "auto_mode_suggest";
    private static final String PREF_AUTO_TONE = "auto_tone_enabled";
    private static final String PREF_MIMIC_TONE = "mimic_tone_enabled";
    private static final String PREF_FILM_SIMULATION = "film_simulation_enabled";
    private static final String PREF_STYLE_STRENGTH = "style_strength";
    private static final String PREF_MIMIC_BRIGHTNESS = "mimic_brightness_enabled";
    private static final String PREF_MIMIC_BRIGHTNESS_BIAS = "mimic_brightness_bias";
    private static final String PREF_AUTO_OPTIMIZATION = "auto_optimization_enabled";
    private static final String PREF_SAVE_COMPARISON = "save_comparison_enabled";
    // Phase 8–10 (Composition v2 — Plan A) — silhouette overlay kill-switch.
    private static final String PREF_USE_SILHOUETTE_OVERLAY = "use_silhouette_overlay";
    private static final String PREF_STRAW_TITLE = "straw_title";
    private static final String PREF_STRAW_URI = "straw_uri";
    private static final String PREF_STRAW_CONTRAST = "straw_contrast";
    private static final String PREF_STRAW_HIGHLIGHTS = "straw_highlights";
    private static final String PREF_STRAW_SHADOWS = "straw_shadows";
    private static final String PREF_STRAW_SATURATION = "straw_saturation";
    private static final String PREF_STRAW_WARMTH = "straw_warmth";
    private static final String PREF_STRAW_TINT = "straw_tint";
    private static final String STRAW_THUMB_FILENAME = "straw_thumb.png";
    private boolean demoMode = true;
    private boolean showComposition = false;
    private boolean showGuidanceOverlay = false;
    private boolean autoModeSuggest = true;
    private boolean filterStripVisible = false;
    @Nullable
    private Integer activeFilterClusterId = null;
    @Nullable
    private Integer highlightedFilterClusterId = null;
    private long lastFilterThumbnailRefreshMs = 0L;
    private static final long FILTER_THUMB_REFRESH_INTERVAL_MS = 1500L;
    private final ExecutorService filterPreviewExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean filterPreviewInFlight = new AtomicBoolean(false);
    private Bitmap latestFilterPreviewSource = null;
    private boolean mimicToneEnabled = true;
    // Film Simulation: applies the camera-cluster baked LUT (per-brand color
    // signature). Default ON. When OFF, falls back to parametric tone curves
    // only — no baked film/brand color cast.
    private boolean filmSimulationEnabled = true;
    private float styleStrength = 1.0f;
    // Mimic low-light brightness engine.  Auto-adaptive lift toward the
    // reference photo's absolute brightness target, biased by an optional
    // manual slider in [-1, +1] (0 = pure auto).  When ON, low-light scenes
    // are brightened via a coarse camera EV nudge plus a fine global LUT lift,
    // giving brightness/contrast/colour tones distinct from Photo mode without
    // HDR.
    private boolean mimicBrightnessEnabled = true;
    private float mimicBrightnessBias = 0.0f;
    @Nullable
    private volatile FrameAnalysis latestFrameAnalysis = null;
    // Phase A.5 — most recent suggested crop bbox (normalized x,y,w,h).
    @Nullable
    private volatile float[] latestSuggestedCropBox = null;

    private final Runnable delayedProInputApply = new Runnable() {
        @Override
        public void run() {
            if (suppressProInputWatcher) {
                return;
            }
            CameraWorkflowStateMachine.Mode currentMode = stateMachine.getCurrentMode();
            if (currentMode != CameraWorkflowStateMachine.Mode.PRO
                    && currentMode != CameraWorkflowStateMachine.Mode.PRO_VIDEO) {
                return;
            }
            applyCurrentProInputs(false, "Manual Pro synced");
        }
    };

    private final TextWatcher proInputWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (suppressProInputWatcher) {
                return;
            }
            uiHandler.removeCallbacks(delayedProInputApply);
            uiHandler.postDelayed(delayedProInputApply, 300L);
        }
    };

    private final Runnable recordingTicker = new Runnable() {
        @Override
        public void run() {
            if (recordingStartMs <= 0L) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - recordingStartMs;
            long totalSec = elapsed / 1000L;
            long mm = totalSec / 60L;
            long ss = totalSec % 60L;
            recordTimerText.setText(String.format(Locale.US, "%02d:%02d", mm, ss));
            uiHandler.postDelayed(this, 300L);
        }
    };

    private final SensorEventListener levelSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.sensor == null || guidanceOverlayView == null) {
                return;
            }

            int rotation = Surface.ROTATION_0;
            Display display = guidanceOverlayView.getDisplay();
            if (display != null) {
                rotation = display.getRotation();
            }

            if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
                processGravityVector(event.values[0], event.values[1], event.values[2], rotation);
                return;
            }

            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                updateAccelerometerGravity(event.values);
                processGravityVector(
                        accelGravityEstimate[0],
                        accelGravityEstimate[1],
                        accelGravityEstimate[2],
                        rotation
                );
                return;
            }

            if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
                return;
            }

            SensorManager.getRotationMatrixFromVector(sensorRotationMatrix, event.values);
            remapRotationMatrixForDisplay(rotation);
            float gravityX = -remappedRotationMatrix[6];
            float gravityY = -remappedRotationMatrix[7];
            float gravityZ = -remappedRotationMatrix[8];
            processResolvedScreenGravity(gravityX, gravityY, gravityZ, rotation);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                if (cameraGranted) {
                    startPipeline();
                } else {
                    Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show();
                }
            });

    // Style Straw: gallery photo picker
    private final ActivityResultLauncher<String> galleryPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                onGalleryPhotoSelected(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.w("MimicDiag", "[DIAG] MainActivity onCreate");
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        bindViews();
        initLevelSensor();
        applySystemBarInsets();
        // The demo bundles CLIP ViT-B/32, CLIP ViT-B/16 and MobileNetV3;
        // ModelAssetSelector can switch among them or load external overrides.
        setupRecommendationList();
        setupManagers();
        setupButtons();
        loadSettingsPreferences();
        updateModeUi(CameraWorkflowStateMachine.Mode.PHOTO, false);
        bottomPanelController.bind();
        syncProUiForMode(CameraWorkflowStateMachine.Mode.PHOTO, false, false);
        bottomPanelController.setSummaryTab(BottomPanelController.SummaryTab.LIVE);
        applyDemoModeVisibility();

        requestPermissionLauncher.launch(new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        });
    }

    private void bindViews() {
        mainRoot = findViewById(R.id.main_root);
        glPreview = findViewById(R.id.gl_preview);
        guidanceOverlayView = findViewById(R.id.guidance_overlay);
        topOverlayContainer = findViewById(R.id.top_overlay_container);
        statusText = findViewById(R.id.status_text);
        guideText = findViewById(R.id.guide_text);
        guideText.setVisibility(View.GONE);
        modelRuntimeHintText = findViewById(R.id.model_runtime_hint_text);
        filterToggleButton = findViewById(R.id.btn_filter_toggle);
        filterStripScroll = findViewById(R.id.filter_strip_scroll);
        filterStripContainer = findViewById(R.id.filter_strip_container);
        triggerRecommendationScroll = findViewById(R.id.trigger_recommendation_scroll);
        triggerPillContainer = findViewById(R.id.trigger_pill_container);
        triggerDebugText = findViewById(R.id.trigger_debug_text);
        analysisSummaryText = findViewById(R.id.analysis_summary_text);
        featureSummaryText = findViewById(R.id.feature_summary_text);
        capabilitySummaryText = findViewById(R.id.capability_summary_text);

        isoInput = findViewById(R.id.input_iso);
        shutterInput = findViewById(R.id.input_shutter);
        evInput = findViewById(R.id.input_ev);
        wbInput = findViewById(R.id.input_wb);
        focusInput = findViewById(R.id.input_focus);
        recordingIndicator = findViewById(R.id.recording_indicator);
        recordDot = findViewById(R.id.record_dot);
        recordTimerText = findViewById(R.id.record_timer_text);

        currentModeButton = findViewById(R.id.btn_mode_current);
        captureButton = findViewById(R.id.btn_capture);
        applyActionButton = findViewById(R.id.btn_apply_recommended);
        settingsButton = findViewById(R.id.btn_settings);

        photoPreviewContainer = findViewById(R.id.photo_preview_container);
        photoPreviewImage = findViewById(R.id.photo_preview_image);
        photoDefectOverlayView = findViewById(R.id.photo_defect_overlay);
        photoDefectSummaryText = findViewById(R.id.photo_defect_summary_text);
        photoPostToolsList = findViewById(R.id.photo_post_tools_list);
        closePreviewButton = findViewById(R.id.btn_close_preview);
        bottomConsolePanel = findViewById(R.id.bottom_console_panel);

        proParamBar = findViewById(R.id.pro_param_bar);

        modeSuggestPill = findViewById(R.id.mode_suggest_pill);
        modeSuggestLabel = findViewById(R.id.mode_suggest_label);
        modeSuggestIcon = findViewById(R.id.mode_suggest_icon);
        modeSuggestPill.setOnClickListener(v -> onModeSuggestPillClicked());

        sceneToneOptimizer = new SceneToneOptimizer();
        sceneEnhancementOptimizer = new SceneEnhancementOptimizer();

        mimicPanelContainer = findViewById(R.id.mimic_panel_container);
        mimicParamBar = findViewById(R.id.mimic_param_bar);
        mimicCard0 = findViewById(R.id.mimic_card_0);
        mimicCard1 = findViewById(R.id.mimic_card_1);
        mimicCard2 = findViewById(R.id.mimic_card_2);
        mimicCardLabel0 = findViewById(R.id.mimic_card_label_0);
        mimicCardLabel1 = findViewById(R.id.mimic_card_label_1);
        mimicCardLabel2 = findViewById(R.id.mimic_card_label_2);
        mimicCardInfo0 = findViewById(R.id.mimic_card_info_0);
        mimicCardInfo1 = findViewById(R.id.mimic_card_info_1);
        mimicCardInfo2 = findViewById(R.id.mimic_card_info_2);
        mimicCardThumb0 = findViewById(R.id.mimic_card_thumb_0);
        mimicCardThumb1 = findViewById(R.id.mimic_card_thumb_1);
        mimicCardThumb2 = findViewById(R.id.mimic_card_thumb_2);
        mimicCard3 = findViewById(R.id.mimic_card_3);
        mimicCardLabel3 = findViewById(R.id.mimic_card_label_3);
        mimicCardInfo3 = findViewById(R.id.mimic_card_info_3);
        mimicCardThumb3 = findViewById(R.id.mimic_card_thumb_3);
        mimicAddStrawBtn = findViewById(R.id.mimic_add_straw_btn);
        mimicCard3Wrapper = findViewById(R.id.mimic_card_3_wrapper);
        mimicStrawCloseBtn = findViewById(R.id.mimic_straw_close_btn);
        mimicStrawReplaceBtn = findViewById(R.id.mimic_straw_replace_btn);
        summaryLiveTabButton = findViewById(R.id.btn_summary_live);
        summaryFeatureTabButton = findViewById(R.id.btn_summary_features);
        summaryPipelineTabButton = findViewById(R.id.btn_summary_pipeline);
    }

    private void initLevelSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    private void updateAccelerometerGravity(float[] values) {
        if (values == null || values.length < 3) {
            return;
        }
        if (!accelGravityInitialized) {
            System.arraycopy(values, 0, accelGravityEstimate, 0, 3);
            accelGravityInitialized = true;
            return;
        }
        for (int i = 0; i < 3; i++) {
            accelGravityEstimate[i] = 0.88f * accelGravityEstimate[i] + 0.12f * values[i];
        }
    }

    private void processGravityVector(float rawX, float rawY, float rawZ, int rotation) {
        float screenX;
        float screenY;
        switch (rotation) {
            case Surface.ROTATION_90:
                screenX = rawY;
                screenY = -rawX;
                break;
            case Surface.ROTATION_180:
                screenX = -rawX;
                screenY = -rawY;
                break;
            case Surface.ROTATION_270:
                screenX = -rawY;
                screenY = rawX;
                break;
            case Surface.ROTATION_0:
            default:
                screenX = rawX;
                screenY = rawY;
                break;
        }
        processResolvedScreenGravity(screenX, screenY, rawZ, rotation);
    }

    private void processResolvedScreenGravity(float gravityX, float gravityY, float gravityZ, int rotation) {
        // Keep projected gravity pointing toward the lower half of the current screen.
        if (gravityY < 0.0f) {
            gravityX = -gravityX;
            gravityY = -gravityY;
            gravityZ = -gravityZ;
        }

        shootingLandscape = resolveShootingLandscape(gravityX, gravityY, shootingLandscape);

        float horizonAngleDeg = (float) Math.toDegrees(Math.atan2(-gravityX, gravityY));
        float inPlaneMagnitude = (float) Math.sqrt(gravityX * gravityX + gravityY * gravityY);
        // Zero pitch is the normal shooting pose: screen approximately vertical, so gravity stays in the screen plane.
        float pitchDeg = (float) Math.toDegrees(Math.atan2(gravityZ, Math.max(inPlaneMagnitude, 1e-4f)));

        if (!hasDeviceLevelPose) {
            smoothedHorizonAngleDeg = horizonAngleDeg;
            smoothedPitchDeg = pitchDeg;
            hasDeviceLevelPose = true;
        } else {
            smoothedHorizonAngleDeg = 0.18f * horizonAngleDeg + 0.82f * smoothedHorizonAngleDeg;
            smoothedPitchDeg = 0.15f * pitchDeg + 0.85f * smoothedPitchDeg;
        }
        guidanceOverlayView.setDeviceLevelPose(
                smoothedHorizonAngleDeg,
                smoothedPitchDeg,
                rotation,
                shootingLandscape
        );
    }

    private boolean resolveShootingLandscape(float gravityX, float gravityY, boolean previousLandscape) {
        float absX = Math.abs(gravityX);
        float absY = Math.abs(gravityY);
        float enterMargin = 1.15f;
        float exitMargin = 0.87f;

        if (previousLandscape) {
            return absX >= absY * exitMargin;
        }
        return absX >= absY * enterMargin;
    }

    private void remapRotationMatrixForDisplay(int rotation) {
        int axisX = SensorManager.AXIS_X;
        int axisY = SensorManager.AXIS_Y;

        switch (rotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            case Surface.ROTATION_0:
            default:
                axisX = SensorManager.AXIS_X;
                axisY = SensorManager.AXIS_Y;
                break;
        }

        SensorManager.remapCoordinateSystem(
                sensorRotationMatrix,
                axisX,
                axisY,
                remappedRotationMatrix
        );
    }

    private void registerLevelSensor() {
        if (sensorManager == null || levelSensorRegistered) {
            return;
        }
        Sensor chosenSensor = rotationVectorSensor != null
                ? rotationVectorSensor
                : (gravitySensor != null ? gravitySensor : accelerometerSensor);
        if (chosenSensor == null) {
            return;
        }
        accelGravityInitialized = false;
        hasDeviceLevelPose = false;
        shootingLandscape = false;
        levelSensorRegistered = sensorManager.registerListener(levelSensorListener, chosenSensor, SensorManager.SENSOR_DELAY_UI);
    }

    private void unregisterLevelSensor() {
        if (sensorManager == null || !levelSensorRegistered) {
            return;
        }
        sensorManager.unregisterListener(levelSensorListener);
        levelSensorRegistered = false;
        hasDeviceLevelPose = false;
        accelGravityInitialized = false;
        shootingLandscape = false;
    }

    private void applySystemBarInsets() {
        if (mainRoot == null) {
            return;
        }

        final int topOverlayPaddingLeft = topOverlayContainer.getPaddingLeft();
        final int topOverlayPaddingTop = topOverlayContainer.getPaddingTop();
        final int topOverlayPaddingRight = topOverlayContainer.getPaddingRight();
        final int topOverlayPaddingBottom = topOverlayContainer.getPaddingBottom();

        final android.view.ViewGroup.MarginLayoutParams bottomConsoleLayoutParams =
            (android.view.ViewGroup.MarginLayoutParams) bottomConsolePanel.getLayoutParams();
        final int bottomConsoleMarginLeft = bottomConsoleLayoutParams.leftMargin;
        final int bottomConsoleMarginTop = bottomConsoleLayoutParams.topMargin;
        final int bottomConsoleMarginRight = bottomConsoleLayoutParams.rightMargin;
        final int bottomConsoleMarginBottom = bottomConsoleLayoutParams.bottomMargin;

        final android.view.ViewGroup.MarginLayoutParams previewCloseLayoutParams =
            (android.view.ViewGroup.MarginLayoutParams) closePreviewButton.getLayoutParams();
        final int previewCloseMarginLeft = previewCloseLayoutParams.leftMargin;
        final int previewCloseMarginTop = previewCloseLayoutParams.topMargin;
        final int previewCloseMarginRight = previewCloseLayoutParams.rightMargin;
        final int previewCloseMarginBottom = previewCloseLayoutParams.bottomMargin;

        final android.view.ViewGroup.MarginLayoutParams previewListLayoutParams =
            (android.view.ViewGroup.MarginLayoutParams) photoPostToolsList.getLayoutParams();
        final int previewListMarginLeft = previewListLayoutParams.leftMargin;
        final int previewListMarginTop = previewListLayoutParams.topMargin;
        final int previewListMarginRight = previewListLayoutParams.rightMargin;
        final int previewListMarginBottom = previewListLayoutParams.bottomMargin;

        final android.view.ViewGroup.MarginLayoutParams previewSummaryLayoutParams =
            (android.view.ViewGroup.MarginLayoutParams) photoDefectSummaryText.getLayoutParams();
        final int previewSummaryMarginLeft = previewSummaryLayoutParams.leftMargin;
        final int previewSummaryMarginTop = previewSummaryLayoutParams.topMargin;
        final int previewSummaryMarginRight = previewSummaryLayoutParams.rightMargin;
        final int previewSummaryMarginBottom = previewSummaryLayoutParams.bottomMargin;

        ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            topOverlayContainer.setPadding(
                topOverlayPaddingLeft + systemBars.left,
                topOverlayPaddingTop + systemBars.top,
                topOverlayPaddingRight + systemBars.right,
                topOverlayPaddingBottom
            );

            bottomConsoleLayoutParams.setMargins(
                bottomConsoleMarginLeft + systemBars.left,
                bottomConsoleMarginTop,
                bottomConsoleMarginRight + systemBars.right,
                bottomConsoleMarginBottom + systemBars.bottom
            );
            bottomConsolePanel.setLayoutParams(bottomConsoleLayoutParams);

            previewCloseLayoutParams.setMargins(
                previewCloseMarginLeft + systemBars.left,
                previewCloseMarginTop + systemBars.top,
                previewCloseMarginRight + systemBars.right,
                previewCloseMarginBottom
            );
            closePreviewButton.setLayoutParams(previewCloseLayoutParams);

            previewListLayoutParams.setMargins(
                previewListMarginLeft + systemBars.left,
                previewListMarginTop,
                previewListMarginRight + systemBars.right,
                previewListMarginBottom + systemBars.bottom
            );
            photoPostToolsList.setLayoutParams(previewListLayoutParams);

            previewSummaryLayoutParams.setMargins(
                previewSummaryMarginLeft + systemBars.left,
                previewSummaryMarginTop,
                previewSummaryMarginRight + systemBars.right,
                previewSummaryMarginBottom + systemBars.bottom
            );
            photoDefectSummaryText.setLayoutParams(previewSummaryLayoutParams);

            return windowInsets;
        });
        ViewCompat.requestApplyInsets(mainRoot);
    }

    private void setupRecommendationList() {
        recommendationAdapter = new RecommendationAdapter();
    }

    private void setupManagers() {
        intelligenceManager = new CameraIntelligenceManager(this);
        intelligenceManager.setRecommendIntervalMs(RECOMMEND_UPDATE_INTERVAL_MS);
        modelRuntimeController = new ModelRuntimeController(
                this,
                intelligenceManager,
                modelRuntimeHintText,
                this::setStatus,
                () -> updateCapabilitySummary(null)
        );
        modelRuntimeController.reloadModel();

        cameraController = new CameraController(
                this,
                this,
                glPreview,
                this::setStatus
        );
            portraitSegmentationRunner = new com.samsung.camera.intelligence.inference.SegmentationRunner(
                this,
                "models/sinet_float.tflite",
                256,
                256
            );
            portraitFallbackDetector = new com.samsung.camera.intelligence.inference.SubjectDetectorEngine(this);

        // Phase 6 — Auto-Zoom on Lock controller (gated by Settings).
        autoZoomController = new com.samsung.camera.intelligence.app.camera.AutoZoomController(
                cameraController,
            intelligenceManager.getSettings().getCompositionGuidance(),
            guidanceOverlayView);
        guidanceOverlayView.setMatchLockListener(autoZoomController);
        // Phase 4 (Composition v2) — when MATCH_LOCK fires, briefly silence
        // the technique chip so the lock affirm UI stands alone for ~1.5s.
        guidanceOverlayView.setMatchLockListener((locked, lockedCropNorm) -> {
            try {
                if (autoZoomController != null) {
                    autoZoomController.onMatchLockChanged(locked, lockedCropNorm);
                }
            } finally {
                if (locked && intelligenceManager != null) {
                    com.samsung.camera.intelligence.guidance.OverlayGenerator gen =
                            intelligenceManager.getOverlayGenerator();
                    if (gen != null) {
                        gen.getCompositionAdvisor().muteFor(1500L);
                        gen.setTargetLockState(true, 1f);
                    }
                } else if (intelligenceManager != null) {
                    com.samsung.camera.intelligence.guidance.OverlayGenerator gen =
                            intelligenceManager.getOverlayGenerator();
                    if (gen != null) {
                        gen.setTargetLockState(false, 0f);
                    }
                }
            }
        });
        // Apply current settings to the overlay view.
        com.samsung.camera.intelligence.config.Settings.CompositionGuidanceSettings cgInit =
                intelligenceManager.getSettings().getCompositionGuidance();
        guidanceOverlayView.setProHudVisible(cgInit.isProHudVisible());
        guidanceOverlayView.setShowExternalComparisonOverlay(
                cgInit.isShowExternalDetectorOverlay()
                        || cgInit.isShowExternalCropAdvisorOverlay());
        // Phase 4 (Composition v2) — chip / horizon / target-frame toggles.
        guidanceOverlayView.setShowAdviceChips(cgInit.isShowAdviceChips());
        guidanceOverlayView.setShowHorizonLevelGuide(cgInit.isShowHorizonLevelGuide());
        guidanceOverlayView.setShowTargetCropFrameInConsumer(
                cgInit.isShowTargetCropFrameInConsumer());
        if (intelligenceManager.getOverlayGenerator() != null) {
            intelligenceManager.getOverlayGenerator()
                    .setAdviceCoolDownMs(cgInit.getAdviceCoolDownMs());
        }

        // Camera-style baked LUT manager (loads assets/camera_luts/manifest.json).
        // Safe to instantiate even if assets are missing — isAvailable() returns false.
        stylePresetManager = new com.samsung.camera.intelligence.app.camera.CameraStylePresetManager(this);

        recommendationExecutor = new RecommendationExecutor(cameraController, message ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());

        postProcessingDispatcher = new PostProcessingDispatcher(message ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());

    photoPreviewController = new PhotoPreviewPostToolsController(
        this,
        intelligenceManager,
        photoPreviewContainer,
        photoPreviewImage,
        photoDefectOverlayView,
        photoDefectSummaryText,
        photoPostToolsList,
        this::setStatus,
        () -> {
            processing.set(false);
            restoreUiAfterPhotoPreviewClosed();
        },
        this::buildPhotoPreviewInfoText
    );

    bottomPanelController = new BottomPanelController(
        bottomConsolePanel,
        bottomConsoleHandle,
        bottomExtrasContainer,
        toggleExtrasButton,
        proControlsContainer,
        toggleProControlsButton,
        analysisSummaryText,
        featureSummaryText,
        capabilitySummaryText,
        summaryLiveTabButton,
        summaryFeatureTabButton,
        summaryPipelineTabButton
    );

    mimicPanelController = new MimicModePanelController(
        this,
        mimicPanelContainer,
        mimicParamBar,
        mimicCard0, mimicCard1, mimicCard2,
        mimicCardLabel0, mimicCardLabel1, mimicCardLabel2,
        mimicCardInfo0, mimicCardInfo1, mimicCardInfo2,
        mimicCardThumb0, mimicCardThumb1, mimicCardThumb2,
        mimicCard3, mimicCardLabel3, mimicCardInfo3, mimicCardThumb3,
        mimicAddStrawBtn,
        mimicCard3Wrapper, mimicStrawCloseBtn, mimicStrawReplaceBtn
    );
    mimicPanelController.setOnSelectionChangedListener((selected, index) -> {
        applyMimicOverlayParams(selected);
        // Notify the MasterMatch guide that this photo's params are now applied.
        // This enables sticky-bias so minor embedding drift won't oscillate.
        if (selected != null) {
            MasterMatchGuide mmGuide = intelligenceManager.getOverlayGenerator().getMasterMatchGuide();
            if (mmGuide != null) {
                mmGuide.setAppliedPhotoId(selected.getPhotoId());
            }
        }
        scheduleToneSettle();
    });
    mimicPanelController.setOnAddStrawClickListener(() -> galleryPickerLauncher.launch("image/*"));
    mimicPanelController.setOnCloseStrawClickListener(() -> clearPersistedGalleryStraw());
    }

    private void setupButtons() {
        applyActionButton.setOnClickListener(v -> {
            animateTap(v);
            CameraWorkflowStateMachine.Mode currentMode = stateMachine.getCurrentMode();
            if (currentMode == CameraWorkflowStateMachine.Mode.PRO
                    || currentMode == CameraWorkflowStateMachine.Mode.PRO_VIDEO) {
                applyManualProSettings();
                return;
            }
            if (lastToolResult == null) {
                Toast.makeText(this, "No recommendation yet", Toast.LENGTH_SHORT).show();
                return;
            }
            recommendationExecutor.apply(lastToolResult);
            CameraWorkflowStateMachine.Mode mapped = syncModeFromRecommendation(lastToolResult);
            if (mapped == CameraWorkflowStateMachine.Mode.PRO) {
                applyRecommendedProSettings(lastToolResult, true);
            }
            setStatus("Applied recommended tools/params");
        });

        captureButton.setOnClickListener(v -> {
            animateShutter(v);
            if (stateMachine.getCurrentMode() == CameraWorkflowStateMachine.Mode.MIMIC) {
                triggerMimicCapture();
            } else {
                triggerCapture();
            }
        });

        currentModeButton.setOnClickListener(v -> {
            animateTap(v);
            showModePickerDialog();
        });

        if (toggleProControlsButton != null) {
            toggleProControlsButton.setOnClickListener(v -> {
                // Pro controls now in floating bar; no-op
            });
        }

        settingsButton.setOnClickListener(v -> {
            animateTap(v);
            showSettingsDialog();
        });

        if (filterToggleButton != null) {
            filterToggleButton.setOnClickListener(v -> {
                animateTap(v);
                toggleFilterStrip();
            });
        }

        summaryLiveTabButton.setOnClickListener(v -> bottomPanelController.setSummaryTab(BottomPanelController.SummaryTab.LIVE));
        summaryFeatureTabButton.setOnClickListener(v -> bottomPanelController.setSummaryTab(BottomPanelController.SummaryTab.FEATURES));
        summaryPipelineTabButton.setOnClickListener(v -> bottomPanelController.setSummaryTab(BottomPanelController.SummaryTab.PIPELINE));

        photoPreviewController.bindCloseButton(closePreviewButton);

        photoDefectOverlayView.setOnRegionClickListener(region ->
                photoPreviewController.highlightPostTool(region == null ? null : region.label));

        setupProInputAutoApply();
    }

    private void setupProInputAutoApply() {
        isoInput.addTextChangedListener(proInputWatcher);
        shutterInput.addTextChangedListener(proInputWatcher);
        evInput.addTextChangedListener(proInputWatcher);
        wbInput.addTextChangedListener(proInputWatcher);
        focusInput.addTextChangedListener(proInputWatcher);
    }

    private void startPipeline() {
        cameraController.start(this::handleFrame);
    }

    private void showModePickerDialog() {
        final CameraWorkflowStateMachine.Mode[] modes = CameraWorkflowStateMachine.getDemoModes();
        String[] labels = new String[modes.length];
        int checkedIndex = 0;
        for (int i = 0; i < modes.length; i++) {
            labels[i] = getModeLabel(modes[i]);
            if (modes[i] == stateMachine.getCurrentMode()) {
                checkedIndex = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.select_shooting_mode)
                .setSingleChoiceItems(labels, checkedIndex, null)
                .setPositiveButton(R.string.apply_model_selection, (dialog, which) -> {
                    AlertDialog alertDialog = (AlertDialog) dialog;
                    int selected = alertDialog.getListView().getCheckedItemPosition();
                    if (selected >= 0 && selected < modes.length) {
                        switchMode(modes[selected]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void handleFrame(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        frameCounter++;
        if (frameCounter % UI_UPDATE_EVERY_N_FRAMES != 0) {
            return;
        }
        if (photoPreviewController != null && photoPreviewController.isPreviewVisible()) {
            return;
        }
        if (stateMachine.getCurrentMode() == CameraWorkflowStateMachine.Mode.PORTRAIT) {
            schedulePortraitSegmentation(bitmap);
        }
        if (filterStripVisible) {
            scheduleFilterThumbnailRefresh(bitmap);
        }
        // After tone-affecting parameter application, skip analysis while the
        // preview settles to avoid feeding processed preview back into analysis.
        if (System.currentTimeMillis() < toneSettleUntilMs) {
            return;
        }
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        try {
            intelligenceManager.setUseFrontCamera(cameraController.isFrontCamera());
            CameraIntelligenceManager.FrameResult result = intelligenceManager.processFrame(bitmap);
            if (result == null) {
                final int fc = frameCounter;
                final boolean init = intelligenceManager.isInitialized();
                latestFrameAnalysis = null;
                lastToolResult = null;
                runOnUiThread(() -> {
                    clearLiveAnalysisUi();
                    setStatus("Frame #" + fc
                            + " processFrame→null (init=" + init + ")");
                });
                return;
            }
            latestFrameAnalysis = result.frameAnalysis;
            lastToolResult = result.toolResult;

            runOnUiThread(() -> {
                guidanceOverlayView.setGuidanceFrame(result.guidanceFrame);
                // Prefer model-decoded bounding boxes; fall back to heuristic estimates
                float[] subjectBoundingBox = null;
                float[] cropBoundingBox = null;
                if (result.frameAnalysis != null) {
                    subjectBoundingBox = result.frameAnalysis.getSubjectBbox();
                    cropBoundingBox = result.frameAnalysis.getSuggestedCrop();
                }
                if (subjectBoundingBox == null) {
                    subjectBoundingBox = estimateSubjectBoundingBox(result.frameAnalysis);
                }
                if (cropBoundingBox == null) {
                    cropBoundingBox = estimateSuggestedCropBoundingBox(result.frameAnalysis, subjectBoundingBox);
                }
                guidanceOverlayView.setCompositionBoundingBoxes(subjectBoundingBox, cropBoundingBox);
                // Phase 1 (Composition v2) — push the latest scheduler output.
                if (result.frameAnalysis != null) {
                    guidanceOverlayView.setCompositionAdvice(
                            result.frameAnalysis.getLatestAdvice());
                }
                // Phase A.4 / B — push frame analysis to overlay HUD widgets (score badge,
                // fill-ratio meter, color chips). Gated by the existing composition toggle:
                // when off the overlay view itself is GONE, so passing null is also safe.
                guidanceOverlayView.applyFrameAnalysis(showGuidanceOverlay ? result.frameAnalysis : null);
                latestSuggestedCropBox = cropBoundingBox;
                ToolRecommendationResult toolResult = result.toolResult;
                String recommendedModeName = extractRecommendedModeName(toolResult);
                currentSuggestedModeName = recommendedModeName;

                List<ToolRecommendation> displayTools = new ArrayList<>();
                if (toolResult != null) {
                    for (ToolRecommendation t : toolResult.getOrderedTools()) {
                        String name = t.getToolName();
                        if (name != null && !name.startsWith("PhotoEditor_") && !name.startsWith("Gallery_")) {
                            displayTools.add(t);
                        }
                    }
                    // Append ExpertRaw alternative recommendations so they are visible
                    List<ToolRecommendation> altTools = toolResult.getAlternativeTools();
                    if (altTools != null) {
                        for (ToolRecommendation t : altTools) {
                            String name = t.getToolName();
                            if (name != null && name.startsWith("ExpertRaw_")) {
                                displayTools.add(t);
                            }
                        }
                    }
                    checkAndSuggestModeSwitch(toolResult);
                }
                recommendationAdapter.submit(displayTools);

                // Extract MasterMatch overlays for Mimic Mode panel
                // if (stateMachine.getCurrentMode() == CameraWorkflowStateMachine.Mode.MIMIC
                //         && mimicPanelController != null) {
                //     List<MasterMatchOverlay> masterOverlays = new ArrayList<>();
                //     if (result.guidanceFrame != null && result.guidanceFrame.getOverlays() != null) {
                //         for (GuidanceOverlay ov : result.guidanceFrame.getOverlays()) {
                //             if (ov instanceof MasterMatchOverlay) {
                //                 masterOverlays.add((MasterMatchOverlay) ov);
                //             }
                //         }
                //     }
                //     mimicPanelController.updateOverlays(masterOverlays);
                // }
                // Extract MasterMatch overlays for Mimic Mode panel
                if (stateMachine.getCurrentMode() == CameraWorkflowStateMachine.Mode.MIMIC
                        && mimicPanelController != null) {
                    List<MasterMatchOverlay> masterOverlays = new ArrayList<>();
                    int totalOverlays = 0;
                    if (result.guidanceFrame != null && result.guidanceFrame.getOverlays() != null) {
                        totalOverlays = result.guidanceFrame.getOverlays().size();
                        for (GuidanceOverlay ov : result.guidanceFrame.getOverlays()) {
                            if (ov instanceof MasterMatchOverlay) {
                                masterOverlays.add((MasterMatchOverlay) ov);
                            }
                        }
                    }
                    if (frameCounter % 30 == 1) {
                        Log.w("MimicDiag", "[DIAG] MIMIC frame: totalOverlays="
                                + totalOverlays + " masterMatch=" + masterOverlays.size());
                    }
                    mimicPanelController.updateOverlays(masterOverlays);
                }

                // Auto Scene Optimization: compute and apply scene-based enhancements
                if (autoOptimizationEnabled
                        && stateMachine.getCurrentMode() != CameraWorkflowStateMachine.Mode.MIMIC
                        && result.frameAnalysis != null) {
                    SceneEnhancementOptimizer.EnhancementResult enhancement =
                            sceneEnhancementOptimizer.computeSmoothed(result.frameAnalysis);
                    if (enhancement != null) {
                        // New or updated enhancement
                        applySceneEnhancement(enhancement);
                    } else if (sceneEnhancementOptimizer.getCurrent() == null) {
                        // No scene matched (not just locked) → clear
                        clearSceneEnhancement();
                    }
                    // else: locked → keep current enhancement, do nothing
                }

                // Pro Auto-Tone: compute and apply scene-based tone curve
                if (autoToneEnabled
                        && isProEditingMode(stateMachine.getCurrentMode())
                        && result.frameAnalysis != null) {
                    SceneToneOptimizer.ToneParams tone =
                            sceneToneOptimizer.computeSmoothed(result.frameAnalysis);
                    if (tone != null) {
                        applyAutoToneParams(tone);
                    }
                }

                if (result.frameAnalysis != null) {
                    updateTriggerNudges(result.frameAnalysis);
                    updateRealtimeStatus(result, recommendedModeName, result.guidanceFrame, displayTools);
                }
            });
        } catch (Exception e) {
            final String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e("FrameDiag", "handleFrame exception", e);
            runOnUiThread(() -> setStatus("Frame error: " + msg));
        } finally {
            processing.set(false);
        }
    }

    private void applyManualProSettings() {
        applyCurrentProInputs(true, "Manual Pro applied");
    }

    private void applyCurrentProInputs(boolean forceProMode, String statusPrefix) {
        String isoStr = isoInput.getText().toString().trim();
        Integer iso = isoStr.isEmpty() ? null : parseIntOrDefault(isoStr, 200);
        String shutter = shutterInput.getText().toString().trim();
        Long shutterNs = shutter.isEmpty() ? null : CameraProSettings.parseShutterToNs(shutter);
        Float ev = parseFloatOrDefault(evInput.getText().toString(), 0f);
        String wb = safeText(wbInput, "auto");
        String focus = safeText(focusInput, "multi_point");

        if (forceProMode && stateMachine.getCurrentMode() != CameraWorkflowStateMachine.Mode.PRO) {
            switchMode(CameraWorkflowStateMachine.Mode.PRO);
        }
        cameraController.applyDirectProSettings(new CameraProSettings(
                iso,
                shutterNs,
                ev,
                wb,
                null,
                focus,
                null,
                "matrix"
        ));

            setStatus(statusPrefix + ": ISO=" + iso + " shutter=" + shutter + " EV=" + ev);
    }

    private void triggerCapture() {
        // Phase 4 \u2014 if the framing template is locked, snap-to-template:
        // pass the locked crop down to CameraController so the saved JPEG\n        // is cropped to match what the user lined up to.
        final float[] aiCrop = (guidanceOverlayView != null && guidanceOverlayView.isMatchLocked())
                ? guidanceOverlayView.getLockedCropNorm() : null;
        final com.samsung.camera.intelligence.app.camera.CaptureExtras extras = buildMimicCaptureExtras();
        cameraController.capturePhoto(new CameraController.CaptureListener() {
            @Override
            public void onPhotoSaved(String path) {
                latestPhotoPath = path;
                runOnUiThread(() -> {
                    photoPreviewController.showPhotoPreview(path);
                    hideUiForPhotoPreview();
                });
                photoPreviewController.analyzePhotoForPostProcessing(path);
            }

            @Override
            public void onVideoSaved(String path) {
                // no-op for photo callback
            }

            @Override
            public void onCaptureError(String reason) {
                runOnUiThread(() -> setStatus("Capture failed: " + reason));
            }

            @Override
            public void onComparisonReady(String resultPath, String origPath,
                                          String refPath, String metaPath) {
                runOnUiThread(() -> openComparisonViewer(resultPath, origPath, refPath, metaPath));
            }
        }, aiCrop, extras);
    }

    private void switchMode(CameraWorkflowStateMachine.Mode mode) {
        // Apply demo mode mapping (video modes disabled, etc.)
        mode = CameraWorkflowStateMachine.toDemoMode(mode);
        stateMachine.forceMode(mode);
        cameraController.setLogicalMode(stateMachine.getCurrentModeName());
        lastModeSwitchAtMs = SystemClock.elapsedRealtime();
        currentSuggestedModeName = stateMachine.getCurrentModeName();
        updateModeUi(mode, true);
        setStatus("Mode switched -> " + stateMachine.getCurrentModeName());
        // Clear dismissed-mode memory when user actively switches
        dismissedModes.clear();
        modeDismissCount.clear();
        lastRecommendedMode = null;
        pendingModeName = null;
        consecutiveModeFrames = 0;
        // Hide any visible mode suggestion pill
        hideModeSuggestPill(false);
        sceneToneOptimizer.reset();
        sceneEnhancementOptimizer.reset();
        lastAppliedAutoTone = null;
        clearSceneEnhancement();
        applyModePreviewEffect(mode);
        if (mode == CameraWorkflowStateMachine.Mode.PRO) {
            // Reset to auto-exposure defaults so preview stays bright
            cameraController.clearLutOverride();
            cameraController.applyDirectProSettings(CameraProSettings.defaults());
            // Fill UI fields from recommendation (display only, not applied to camera)
            if (lastToolResult != null) {
                fillProParamsFromRecommendation(lastToolResult);
            }
            syncProUiForMode(mode, true, true);
        } else {
            // Restore camera to auto-exposure defaults when leaving Pro mode
            cameraController.clearLutOverride();
            cameraController.applyDirectProSettings(CameraProSettings.defaults());
            syncProInputsFromCamera();
            syncProUiForMode(mode, true, false);
        }
        if (mode != CameraWorkflowStateMachine.Mode.MIMIC) {
            applyPreviewFilter(activeFilterClusterId);
        }

        // Show/hide mimic reference panel based on mode.
        if (mimicPanelController != null) {
            if (mode == CameraWorkflowStateMachine.Mode.MIMIC) {
                mimicPanelController.resetSelectionPin();
                // Show param bar immediately with searching message;
                // cards will appear when MasterMatch overlays arrive in handleFrame()
                mimicPanelController.updateOverlays(null);
                // Restore persisted gallery straw card if available
                if (!mimicPanelController.hasGalleryOverlay()) {
                    restoreGalleryStraw();
                }
            } else {
                mimicPanelController.resetSelectionPin();
                mimicPanelController.hide();
            }
        }
    }

    private CameraWorkflowStateMachine.Mode syncModeFromRecommendation(ToolRecommendationResult result) {
        if (result == null || result.getTools() == null) {
            return stateMachine.getCurrentMode();
        }
        for (int i = 0; i < result.getTools().size(); i++) {
            if (!"Camera_ChangeMode".equals(result.getTools().get(i).getToolName())) {
                continue;
            }
            Map<String, Object> params = result.getTools().get(i).getParameters();
            Object modeName = params == null ? null : params.get("ModeName");
            if (modeName == null) {
                return stateMachine.getCurrentMode();
            }
            CameraWorkflowStateMachine.Mode mapped = CameraWorkflowStateMachine.fromModeName(String.valueOf(modeName));
            stateMachine.updateFromRecommendation(String.valueOf(modeName));
            currentSuggestedModeName = stateMachine.getCurrentModeName();
            updateModeUi(mapped, true);
            syncProUiForMode(mapped, true, false);
            return mapped;
        }
        return stateMachine.getCurrentMode();
    }

    private void checkAndSuggestModeSwitch(ToolRecommendationResult result) {
        if (result == null || result.getTools() == null || modeDialogShowing) {
            return;
        }
        if (!autoModeSuggest) {
            return;
        }
        // Suppress mode suggestions while user is in Mimic mode
        if (stateMachine.getCurrentMode() == CameraWorkflowStateMachine.Mode.MIMIC) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (cameraController != null && cameraController.isRecording()) {
            return;
        }
        if (now - lastModeDialogAtMs < MODE_DIALOG_COOLDOWN_MS) {
            return;
        }
        if (now - lastModeSwitchAtMs < MODE_SWITCH_SETTLE_MS) {
            return;
        }
        if (photoPreviewContainer != null && photoPreviewContainer.getVisibility() == View.VISIBLE) {
            return;
        }
        // Extract the recommended mode first so we can compare it against the
        // last recommendation before applying stability suppression.
        String recommendedModeName = null;
        ToolRecommendation changeModeRec = null;
        for (ToolRecommendation tool : result.getTools()) {
            if ("Camera_ChangeMode".equals(tool.getToolName())) {
                Map<String, Object> params = tool.getParameters();
                Object modeObj = params == null ? null : params.get("ModeName");
                if (modeObj != null) {
                    recommendedModeName = String.valueOf(modeObj);
                    changeModeRec = tool;
                }
                break;
            }
        }
        if (recommendedModeName == null) {
            consecutiveModeFrames = 0;
            pendingModeName = null;
            lastRecommendedMode = null;
            hideModeSuggestPill(false);
            return;
        }
        // Map recommended mode through demo mode mapping (disable video modes, etc.)
        CameraWorkflowStateMachine.Mode mappedDemoMode =
                CameraWorkflowStateMachine.toDemoMode(CameraWorkflowStateMachine.fromModeName(recommendedModeName));
        recommendedModeName = CameraWorkflowStateMachine.toModeName(mappedDemoMode);

        // Embedding-based scene stability: when the scene hasn't actually changed
        // (cosine similarity > 0.92) AND the recommended mode is the SAME as the
        // last one we already showed, suppress re-suggesting to avoid flicker from
        // noisy classification on the same static scene (e.g. hand shake).
        // IMPORTANT: If the recommended mode differs from the last one (meaning the
        // scene changed enough to warrant a different mode), allow the new suggestion
        // through even if the embedding is still similar.
        TemporalSmoother smoother = intelligenceManager.getOverlayGenerator().getSmoother();
        if (smoother != null && smoother.isSceneStable()
                && lastRecommendedMode != null
                && lastRecommendedMode.equalsIgnoreCase(recommendedModeName)) {
            return;
        }

        if (lastRecommendedMode != null && !lastRecommendedMode.equalsIgnoreCase(recommendedModeName)) {
            lastRecommendedMode = null;
        }
        String currentModeName = stateMachine.getCurrentModeName();
        if (currentModeName.equalsIgnoreCase(recommendedModeName)) {
            consecutiveModeFrames = 0;
            pendingModeName = null;
            lastRecommendedMode = null;
            hideModeSuggestPill(false);
            return;
        }
        // Don't re-suggest a mode the user already dismissed (with expiry)
        Long dismissedAt = dismissedModes.get(recommendedModeName);
        if (dismissedAt != null) {
            // Check fatigue-based cooldown
            int dismissCount = modeDismissCount.getOrDefault(recommendedModeName, 0);
            long fatigueCooldown = Math.min(
                    BASE_FATIGUE_COOLDOWN_MS * (1L << Math.min(dismissCount, 4)),
                    MAX_FATIGUE_COOLDOWN_MS);
            long effectiveExpiry = Math.max(DISMISSED_MODE_EXPIRY_MS, fatigueCooldown);
            if (now - dismissedAt < effectiveExpiry) {
                return;
            }
            // Expired — remove stale dismissal
            dismissedModes.remove(recommendedModeName);
        }
        // Only show dialog once per recommended mode
        if (recommendedModeName.equals(lastRecommendedMode)) {
            return;
        }
        // Require the same mode recommendation for multiple consecutive frames
        if (recommendedModeName.equals(pendingModeName)) {
            consecutiveModeFrames++;
        } else {
            pendingModeName = recommendedModeName;
            consecutiveModeFrames = 1;
        }
        if (consecutiveModeFrames < MODE_SUGGEST_MIN_FRAMES) {
            return;
        }

        lastRecommendedMode = recommendedModeName;
        consecutiveModeFrames = 0;
        pendingModeName = null;
        lastModeDialogAtMs = now;

        // Show the suggestion pill button instead of a dialog
        pillModeName = recommendedModeName;
        pillToolResult = result;
        modeSuggestLabel.setText(recommendedModeName);
        modeSuggestIcon.setText(modeIconEmoji(recommendedModeName));
        modeSuggestPill.setAlpha(0f);
        modeSuggestPill.setVisibility(View.VISIBLE);
        modeSuggestPill.animate().alpha(1f).setDuration(250).start();

        // Auto-hide the pill after 8 seconds if not tapped
        modeSuggestPill.removeCallbacks(pillAutoHideRunnable);
        modeSuggestPill.postDelayed(pillAutoHideRunnable, 8_000L);
    }

    private final Runnable pillAutoHideRunnable = () -> hideModeSuggestPill(true);

    private void hideModeSuggestPill(boolean countAsDismiss) {
        if (modeSuggestPill.getVisibility() != View.VISIBLE) return;
        modeSuggestPill.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            modeSuggestPill.setVisibility(View.GONE);
            if (countAsDismiss && pillModeName != null) {
                dismissedModes.put(pillModeName, SystemClock.elapsedRealtime());
                modeDismissCount.put(pillModeName,
                        modeDismissCount.getOrDefault(pillModeName, 0) + 1);
            }
            pillModeName = null;
            pillToolResult = null;
        }).start();
    }

    private void onModeSuggestPillClicked() {
        modeSuggestPill.removeCallbacks(pillAutoHideRunnable);
        modeSuggestPill.setVisibility(View.GONE);

        final String finalModeName = pillModeName;
        final ToolRecommendationResult finalResult = pillToolResult;
        if (finalModeName == null) return;

        pillModeName = null;
        pillToolResult = null;

        // Switch directly without confirmation dialog
        CameraWorkflowStateMachine.Mode mapped =
                CameraWorkflowStateMachine.fromModeName(finalModeName);
        switchMode(mapped);
        if (mapped == CameraWorkflowStateMachine.Mode.PRO) {
            applyRecommendedProSettings(finalResult, true);
        }
        dismissedModes.clear();
        modeDismissCount.clear();
    }

    private static String modeIconEmoji(String modeName) {
        if (modeName == null) return "📷";
        switch (modeName.toUpperCase(java.util.Locale.ROOT)) {
            case "PRO":        return "🎛️";
            case "NIGHT":      return "🌙";
            case "PORTRAIT":   return "🧑";
            case "FOOD":       return "🍽️";
            case "MACRO":      return "🔬";
            case "PANORAMA":   return "🏔️";
            case "VIDEO":      return "🎬";
            case "PRO_VIDEO":  return "🎞️";
            case "MIMIC":      return "🎨";
            default:           return "📷";
        }
    }

    private boolean applyRecommendedProSettings(ToolRecommendationResult result, boolean expandPanels) {
        boolean updated = fillProParamsFromRecommendation(result);
        if (!updated) {
            return false;
        }
        if (expandPanels) {
            bottomPanelController.setExtrasExpanded(true, true);
            bottomPanelController.setProControlsExpanded(true, true);
        }
        applyCurrentProInputs(false, "Recommended Pro applied");
        return true;
    }

    private boolean fillProParamsFromRecommendation(ToolRecommendationResult result) {
        if (result == null || result.getTools() == null) {
            return false;
        }
        boolean updated = false;
        suppressProInputWatcher = true;
        for (ToolRecommendation tool : result.getTools()) {
            Map<String, Object> params = tool.getParameters();
            if (params == null) {
                continue;
            }
            String name = tool.getToolName();
            if (name == null) {
                continue;
            }
            switch (name) {
                case "Camera_ChangeIso":
                    Object iso = firstPresent(params, "IsoValue", "iso");
                    if (iso != null) {
                        isoInput.setText(String.valueOf(iso));
                        updated = true;
                    }
                    break;
                case "Camera_ChangeShutterSpeed":
                    Object shutter = firstPresent(params, "ShutterSpeed", "shutter_speed", "shutter");
                    if (shutter != null) {
                        shutterInput.setText(String.valueOf(shutter));
                        updated = true;
                    }
                    break;
                case "Camera_ChangeEV":
                    Object ev = firstPresent(params, "EvValue", "ev");
                    if (ev != null) {
                        evInput.setText(String.valueOf(ev));
                        updated = true;
                    }
                    break;
                case "Camera_ChangeWhiteBalance":
                    Object wb = firstPresent(params, "WbValue", "mode", "white_balance");
                    if (wb != null) {
                        wbInput.setText(String.valueOf(wb));
                        updated = true;
                    }
                    break;
                case "Camera_ChangeFocusMode":
                    Object focus = firstPresent(params, "FocusMode", "mode", "focus_mode");
                    if (focus != null) {
                        focusInput.setText(String.valueOf(focus));
                        updated = true;
                    }
                    break;
            }
        }
        suppressProInputWatcher = false;
        return updated;
    }

    private void updateModeUi(CameraWorkflowStateMachine.Mode mode, boolean animate) {
        String label = getModeLabel(mode);
        boolean changed = displayedModeLabel == null || !displayedModeLabel.equals(label);
        displayedModeLabel = label;
        currentModeButton.setText(label);
        currentModeButton.setSelected(true);
        if (applyActionButton != null) {
            boolean manualMode = mode == CameraWorkflowStateMachine.Mode.PRO
                || mode == CameraWorkflowStateMachine.Mode.PRO_VIDEO;
            boolean mimicMode = mode == CameraWorkflowStateMachine.Mode.MIMIC;
            applyActionButton.setText(manualMode
                ? R.string.apply_all
                : R.string.apply_recommended);
            applyActionButton.setVisibility(mimicMode ? View.GONE : View.VISIBLE);
        }

        if (animate && changed) {
            currentModeButton.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120)
                    .withEndAction(() -> currentModeButton.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                    .start();
        }
    }

    private boolean isProEditingMode(CameraWorkflowStateMachine.Mode mode) {
        return mode == CameraWorkflowStateMachine.Mode.PRO
                || mode == CameraWorkflowStateMachine.Mode.PRO_VIDEO;
    }

    private void setProInputsEnabled(boolean enabled) {
        isoInput.setEnabled(enabled);
        shutterInput.setEnabled(enabled);
        evInput.setEnabled(enabled);
        wbInput.setEnabled(enabled);
        focusInput.setEnabled(enabled);

        float alpha = enabled ? 1.0f : 0.55f;
        isoInput.setAlpha(alpha);
        shutterInput.setAlpha(alpha);
        evInput.setAlpha(alpha);
        wbInput.setAlpha(alpha);
        focusInput.setAlpha(alpha);
    }

    private void syncProUiForMode(CameraWorkflowStateMachine.Mode mode, boolean animate, boolean expandOnPro) {
        if (photoPreviewController != null && photoPreviewController.isPreviewVisible()) {
            proParamBar.setVisibility(View.GONE);
            return;
        }

        boolean proEditing = isProEditingMode(mode);
        setProInputsEnabled(proEditing);

        // Show / hide floating Samsung-style Pro parameter bar
        if (proEditing) {
            proParamBar.setVisibility(View.VISIBLE);
        } else {
            proParamBar.setVisibility(View.GONE);
        }
    }

    private void hideUiForPhotoPreview() {
        if (proParamBar != null) {
            proParamBar.setVisibility(View.GONE);
        }
    }

    private void restoreUiAfterPhotoPreviewClosed() {
        syncProUiForMode(stateMachine.getCurrentMode(), false, false);
    }

    private CameraWorkflowStateMachine.Mode resolveDisplayMode(String recommendedModeName) {
        if (recommendedModeName == null || recommendedModeName.trim().isEmpty()) {
            return stateMachine.getCurrentMode();
        }
        CameraWorkflowStateMachine.Mode mapped = CameraWorkflowStateMachine.fromModeName(recommendedModeName);
        return mapped == CameraWorkflowStateMachine.Mode.UNKNOWN ? stateMachine.getCurrentMode() : mapped;
    }

    private String getModeLabel(CameraWorkflowStateMachine.Mode mode) {
        if (mode == null) {
            return getString(R.string.mode_general);
        }
        switch (mode) {
            case VIDEO:
                return getString(R.string.mode_video);
            case PRO:
                return getString(R.string.mode_pro);
            case PRO_VIDEO:
                return getString(R.string.mode_pro_video);
            case NIGHT:
                return getString(R.string.mode_night);
            case PORTRAIT:
                return getString(R.string.mode_portrait);
            case FOOD:
                return getString(R.string.mode_food);
            case MACRO:
                return getString(R.string.mode_macro);
            case PANORAMA:
                return getString(R.string.mode_panorama);
            case SINGLE_TAKE:
                return getString(R.string.mode_single_take);
            case SLOW_MOTION:
                return getString(R.string.mode_slow_motion);
            case HYPERLAPSE:
                return getString(R.string.mode_hyperlapse);
            case DUAL_RECORDING:
                return getString(R.string.mode_dual_recording);
            case PORTRAIT_VIDEO:
                return getString(R.string.mode_portrait_video);
            case MIMIC:
                return getString(R.string.mode_mimic);
            case PHOTO:
                return getString(R.string.mode_photo);
            case UNKNOWN:
            default:
                return getString(R.string.mode_general);
        }
    }

    private String extractRecommendedModeName(ToolRecommendationResult result) {
        if (result == null || result.getTools() == null) {
            return null;
        }
        for (ToolRecommendation tool : result.getTools()) {
            if (!"Camera_ChangeMode".equals(tool.getToolName())) {
                continue;
            }
            Map<String, Object> params = tool.getParameters();
            Object modeName = params == null ? null : firstPresent(params, "ModeName", "mode", "mode_name");
            if (modeName != null) {
                return String.valueOf(modeName);
            }
        }
        return null;
    }

    private String extractGuideText(List<ToolRecommendation> displayTools) {
        if (displayTools == null) {
            return null;
        }
        for (ToolRecommendation tool : displayTools) {
            String reason = tool.getReason();
            if (reason != null) {
                String trimmed = reason.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private void updateRealtimeStatus(CameraIntelligenceManager.FrameResult result,
                                      String recommendedModeName,
                                      GuidanceFrame guidanceFrame,
                                      List<ToolRecommendation> displayTools) {
        if (result == null || result.frameAnalysis == null) {
            return;
        }
        String fallbackGuide = extractGuideText(displayTools);
        // Prepend scene enhancement label if active
        if (activeEnhancementLabel != null) {
            fallbackGuide = activeEnhancementLabel
                    + (fallbackGuide != null ? "  " + fallbackGuide : "");
        }
        guidanceOverlayView.setFallbackMessage(null);
        setGuideText(selectPrimaryGuidance(guidanceFrame), fallbackGuide);
        String currentLabel = getModeLabel(stateMachine.getCurrentMode());
        String suggestedLabel = getModeLabel(resolveDisplayMode(recommendedModeName));
        updateAnalysisSummary(result.frameAnalysis, result.toolResult, currentLabel, suggestedLabel);
        updateCapabilitySummary(guidanceFrame);
    }

    private void clearLiveAnalysisUi() {
        if (guidanceOverlayView != null) {
            guidanceOverlayView.setGuidanceFrame(null);
            guidanceOverlayView.applyFrameAnalysis(null);
            guidanceOverlayView.setCompositionBoundingBoxes(null, null);
            guidanceOverlayView.setCompositionAdvice(null);
        }
        if (triggerPillContainer != null) {
            triggerPillContainer.removeAllViews();
        }
        if (triggerRecommendationScroll != null) {
            triggerRecommendationScroll.setVisibility(View.GONE);
        }
        if (triggerDebugText != null) {
            triggerDebugText.setVisibility(View.GONE);
            triggerDebugText.setText("");
        }
        if (analysisSummaryText != null) {
            analysisSummaryText.setText("");
        }
        if (featureSummaryText != null) {
            featureSummaryText.setText("");
        }
        if (capabilitySummaryText != null) {
            updateCapabilitySummary(null);
        }
        if (recommendationAdapter != null) {
            recommendationAdapter.submit(new ArrayList<>());
        }
    }

    private void updateTriggerNudges(FrameAnalysis analysis) {
        if (analysis == null) {
            if (triggerRecommendationScroll != null) triggerRecommendationScroll.setVisibility(View.GONE);
            if (triggerDebugText != null) triggerDebugText.setVisibility(View.GONE);
            return;
        }
        Map<String, Float> scores = analysis.getTriggerScores();
        Map<String, Float> thresholds = analysis.getTriggerThresholds();
        Map<String, Float> signals = analysis.getTriggerSignals();
        List<String> active = new ArrayList<>();
        for (String name : TriggerNames.ALL) {
            float score = valueOf(scores, name);
            float threshold = thresholds.containsKey(name) ? valueOf(thresholds, name) : 0.5f;
            if (score >= threshold) {
                active.add(name);
            }
        }
        active.sort((left, right) -> Float.compare(valueOf(scores, right), valueOf(scores, left)));

        if (triggerPillContainer != null) {
            triggerPillContainer.removeAllViews();
            int showCount = Math.min(active.size(), 4);
            for (int i = 0; i < showCount; i++) {
                String name = active.get(i);
                Button pill = new Button(this);
                pill.setAllCaps(false);
                pill.setText(triggerTitle(name) + " " + formatDecimal(valueOf(scores, name)));
                pill.setTextSize(11f);
                pill.setMinHeight(0);
                pill.setMinimumHeight(0);
                pill.setMinWidth(0);
                pill.setMinimumWidth(0);
                int horizontal = dp(10);
                pill.setPadding(horizontal, 0, horizontal, 0);
                pill.setSingleLine(true);
                pill.setEllipsize(TextUtils.TruncateAt.END);
                String detail = triggerDescription(name)
                        + "  score=" + formatDecimal(valueOf(scores, name))
                        + "/" + formatDecimal(thresholds.containsKey(name) ? valueOf(thresholds, name) : 0.5f);
                pill.setContentDescription(detail);
                pill.setOnClickListener(v -> {
                    animateTap(v);
                    Toast.makeText(this, detail, Toast.LENGTH_SHORT).show();
                    setStatus("Trigger: " + detail);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
                lp.setMarginEnd(dp(6));
                triggerPillContainer.addView(pill, lp);
            }
        }
        if (triggerRecommendationScroll != null) {
            triggerRecommendationScroll.setVisibility(active.isEmpty() ? View.GONE : View.VISIBLE);
        }

        if (triggerDebugText != null) {
            boolean showDebug = !demoMode && (!scores.isEmpty() || !signals.isEmpty());
            triggerDebugText.setVisibility(showDebug ? View.VISIBLE : View.GONE);
            if (showDebug) {
                triggerDebugText.setText(buildTriggerDebugText(scores, thresholds, signals, active));
            }
        }
    }

    private String buildTriggerDebugText(Map<String, Float> scores,
                                         Map<String, Float> thresholds,
                                         Map<String, Float> signals,
                                         List<String> active) {
        StringBuilder out = new StringBuilder();
        String loadedBackbone = modelRuntimeController == null
            ? ModelAssetSelector.getPreferredBackbone(this)
            : modelRuntimeController.getActiveBackbone();
        out.append("Model ")
            .append(ModelAssetSelector.toDisplayName(loadedBackbone))
                .append("  Active ")
                .append(active == null || active.isEmpty() ? "none" : TextUtils.join(", ", active));
        out.append("\nScores ");
        for (String name : TriggerNames.ALL) {
            if (out.charAt(out.length() - 1) != ' ') {
                out.append("  ");
            }
            out.append(triggerShortName(name))
                    .append('=')
                    .append(formatDecimal(valueOf(scores, name)))
                    .append('/')
                    .append(formatDecimal(thresholds.containsKey(name) ? valueOf(thresholds, name) : 0.5f));
        }
        out.append("\nVars scene=").append(formatDecimal(valueOf(signals, "scene_type_id")))
                .append('@').append(formatDecimal(valueOf(signals, "scene_type_prob")))
                .append(" light=").append(formatDecimal(valueOf(signals, "lighting_id")))
                .append('@').append(formatDecimal(valueOf(signals, "lighting_prob")))
                .append(" subject=").append(formatDecimal(valueOf(signals, "subject_id")))
                .append('@').append(formatDecimal(valueOf(signals, "subject_prob")))
                .append(" text=").append(formatDecimal(valueOf(signals, "has_text")))
                .append(" face=").append(formatDecimal(valueOf(signals, "has_face")))
                .append(" blur=").append(formatDecimal(valueOf(signals, "blur_level")))
                .append(" sharpQ=").append(formatDecimal(valueOf(signals, "sharpness_quality")))
                .append(" lensCorner=").append(formatDecimal(valueOf(signals, "lens_blocked_corner_prob")));
        return out.toString();
    }

    private String triggerTitle(String name) {
        if (TriggerNames.ND_FILTER.equals(name)) return "ND filter";
        if (TriggerNames.ONLY_ME.equals(name)) return "Only me";
        if (TriggerNames.MINIATURE.equals(name)) return "Miniature";
        if (TriggerNames.FLASH.equals(name)) return "Flash";
        if (TriggerNames.TELE_PORTRAIT.equals(name)) return "Tele portrait";
        if (TriggerNames.LIGHT_BOX.equals(name)) return "Light box";
        if (TriggerNames.UW_SELFIE.equals(name)) return "Ultra-wide selfie";
        if (TriggerNames.LENS_BLOCKED.equals(name)) return "Lens blocked";
        if (TriggerNames.OUT_OF_FOCUS.equals(name)) return "Out of focus";
        if (TriggerNames.BUSINESS_CARD.equals(name)) return "Business card";
        if (TriggerNames.WIFI_CREDENTIAL.equals(name)) return "Wi-Fi credential";
        return titleCase(name);
    }

    private String triggerShortName(String name) {
        if (TriggerNames.ND_FILTER.equals(name)) return "nd";
        if (TriggerNames.ONLY_ME.equals(name)) return "only";
        if (TriggerNames.MINIATURE.equals(name)) return "mini";
        if (TriggerNames.FLASH.equals(name)) return "flash";
        if (TriggerNames.TELE_PORTRAIT.equals(name)) return "tele";
        if (TriggerNames.LIGHT_BOX.equals(name)) return "box";
        if (TriggerNames.UW_SELFIE.equals(name)) return "uw";
        if (TriggerNames.LENS_BLOCKED.equals(name)) return "lens";
        if (TriggerNames.OUT_OF_FOCUS.equals(name)) return "focus";
        if (TriggerNames.BUSINESS_CARD.equals(name)) return "card";
        if (TriggerNames.WIFI_CREDENTIAL.equals(name)) return "wifi";
        return name;
    }

    private String triggerDescription(String name) {
        if (TriggerNames.ND_FILTER.equals(name)) return "Bright water or motion scene; try an ND filter look.";
        if (TriggerNames.ONLY_ME.equals(name)) return "Person or group cleanup trigger; isolate the main subject.";
        if (TriggerNames.MINIATURE.equals(name)) return "High-angle city or landscape; try miniature effect.";
        if (TriggerNames.FLASH.equals(name)) return "Low-light portrait or face scene; try flash.";
        if (TriggerNames.TELE_PORTRAIT.equals(name)) return "Portrait subject detected; try tele portrait framing.";
        if (TriggerNames.LIGHT_BOX.equals(name)) return "Food or product close shot; try light box lighting.";
        if (TriggerNames.UW_SELFIE.equals(name)) return "Selfie or group scene; try ultra-wide selfie.";
        if (TriggerNames.LENS_BLOCKED.equals(name)) return "Lens obstruction detected; clean lens or move your finger.";
        if (TriggerNames.OUT_OF_FOCUS.equals(name)) return "Blur/focus miss detected; refocus before capture.";
        if (TriggerNames.BUSINESS_CARD.equals(name)) return "Card/document text detected; capture as business card.";
        if (TriggerNames.WIFI_CREDENTIAL.equals(name)) return "Wi-Fi credential text detected; scan network info.";
        return titleCase(name);
    }

    private float valueOf(Map<String, Float> values, String key) {
        if (values == null || key == null) {
            return 0f;
        }
        Float value = values.get(key);
        return value == null || Float.isNaN(value) || Float.isInfinite(value) ? 0f : value;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GuidanceOverlay selectPrimaryGuidance(GuidanceFrame frame) {
        if (frame == null || frame.getOverlays() == null || frame.getOverlays().isEmpty()) {
            return null;
        }
        GuidanceOverlay best = null;
        int bestScore = Integer.MIN_VALUE;
        for (GuidanceOverlay overlay : frame.getOverlays()) {
            if (overlay == null || TextUtils.isEmpty(overlay.getMessage())) {
                continue;
            }
            int score = urgencyScore(overlay.getUrgency()) * 10 + categoryScore(overlay.getCategory());
            if (best == null || score > bestScore) {
                best = overlay;
                bestScore = score;
            }
        }
        return best;
    }

    private int urgencyScore(GuidanceUrgency urgency) {
        if (urgency == null) {
            return 0;
        }
        switch (urgency) {
            case CRITICAL:
                return 4;
            case WARNING:
                return 3;
            case SUGGESTION:
                return 2;
            case INFO:
            default:
                return 1;
        }
    }

    private int categoryScore(GuidanceCategory category) {
        if (category == null) {
            return 0;
        }
        switch (category) {
            case OBSTRUCTION:
                return 4;
            case TECHNICAL:
                return 3;
            case ANGLE:
                return 2;
            case COMPOSITION:
            default:
                return 1;
        }
    }

    private void updateAnalysisSummary(FrameAnalysis analysis, ToolRecommendationResult toolResult,
                                       String currentLabel, String suggestedLabel) {
        if (analysis == null || analysisSummaryText == null) {
            return;
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Scene ")
            .append(titleCase(analysis.getSceneType()))
            .append("(").append(formatConfidence(analysis.getSceneConfidence())).append(")")
            .append("  Ltg ")
            .append(titleCase(analysis.getLightingCondition()))
            .append("(").append(formatConfidence(analysis.getLightingConfidence())).append(")")
            .append("  Mot ")
            .append(titleCase(analysis.getMotionType()))
            .append("(").append(formatConfidence(analysis.getMotionConfidence())).append(")");

        summary.append("\nCurrent ")
                .append(currentLabel)
                .append("  Suggested ")
                .append(suggestedLabel)
                .append("  Conf ")
                .append(formatConfidence(analysis.getSceneConfidence()));

        summary.append("\nSubject ")
                .append(titleCase(analysis.getMainSubject()))
                .append(" (")
                .append(formatConfidence(analysis.getSubjectConfidence()))
                .append(")  Faces ")
                .append(analysis.getFaceCount())
                .append("  Text ")
                .append(analysis.isHasText() ? "Yes" : "No");

        summary.append("\nComp ")
                .append(formatDecimal(analysis.getCompositionScore()))
                .append("  Fill ")
                .append(Math.round(analysis.getSubjectFillRatio() * 100f))
                .append("%  Count ")
                .append(analysis.getSubjectCount())
                .append("  Depth ")
                .append(analysis.getSceneDepthLayers());

        summary.append("\nQuality C ")
                .append(formatDecimal(analysis.getContrastValue()))
                .append("  S ")
                .append(formatDecimal(analysis.getSharpnessValue()))
                .append("  N ")
                .append(formatDecimal(analysis.getNoiseLevel()))
                .append("  Flow ")
                .append(formatDecimal(analysis.getFlowMagnitude()));
        analysisSummaryText.setText(summary.toString());
        updateFeatureSummary(analysis, toolResult);
    }

    private void updateFeatureSummary(FrameAnalysis analysis, ToolRecommendationResult toolResult) {
        if (featureSummaryText == null || analysis == null) {
            return;
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Phase1b: ").append(buildFeatureFlags(analysis));
        if (analysis.isTilted()) {
            summary.append("\nTilt: ").append(formatDecimal(analysis.getTiltAngle())).append(" deg");
        }
        if (analysis.getDominantColors() != null && !analysis.getDominantColors().isEmpty()) {
            summary.append("\nColors: ").append(TextUtils.join(", ", analysis.getDominantColors()));
        }
        String issues = buildCompositionIssues(analysis);
        if (!issues.isEmpty()) {
            summary.append("\nIssues: ").append(issues);
        }
        String motionAdvice = buildMotionAdvice(toolResult);
        if (!motionAdvice.isEmpty()) {
            summary.append("\n").append(motionAdvice);
        }
        featureSummaryText.setText(summary.toString());
    }

    private String buildFeatureFlags(FrameAnalysis analysis) {
        List<String> flags = new ArrayList<>();
        if (analysis.isHasSymmetry()) {
            flags.add("Symmetry");
        }
        if (analysis.isHasLeadingLines()) {
            flags.add("Leading lines");
        }
        if (analysis.isHasDiagonalLines()) {
            flags.add("Diagonals");
        }
        if (analysis.isFingerObstruction()) {
            flags.add("Obstruction");
        }
        if (analysis.isHasComplementaryColors()) {
            flags.add("Complementary color");
        }
        if (analysis.isSuggestBw()) {
            flags.add("B/W");
        }
        return flags.isEmpty() ? "No special features detected" : TextUtils.join(", ", flags);
    }

    private String buildCompositionIssues(FrameAnalysis analysis) {
        if (analysis.getCompositionIssues() == null || analysis.getCompositionIssues().isEmpty()) {
            return "None";
        }
        List<String> pretty = new ArrayList<>();
        for (String issue : analysis.getCompositionIssues()) {
            pretty.add(titleCase(issue));
        }
        return TextUtils.join(", ", pretty);
    }

    private String buildMotionAdvice(ToolRecommendationResult toolResult) {
        if (toolResult == null || toolResult.getTools() == null) {
            return "";
        }
        String fps = null;
        String shutter = null;
        String warning = null;
        for (ToolRecommendation tool : toolResult.getTools()) {
            if (tool == null || tool.getToolName() == null) {
                continue;
            }
            Map<String, Object> params = tool.getParameters();
            switch (tool.getToolName()) {
                case "Camera_VideoFPS":
                    fps = asString(firstPresent(params, "fps"), null);
                    break;
                case "Camera_ChangeShutterSpeed":
                    shutter = asString(firstPresent(params, "shutter_speed", "ShutterSpeed", "shutter"), null);
                    break;
                case "Camera_ChangeMode":
                    warning = asString(firstPresent(params, "capture_advisory"), null);
                    break;
            }
        }
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(shutter)) {
            parts.add("Shutter " + shutter);
        }
        if (!TextUtils.isEmpty(fps)) {
            parts.add("FPS " + fps);
        }
        if (!TextUtils.isEmpty(warning)) {
            parts.add(warning);
        }
        return parts.isEmpty() ? "" : "Motion: " + TextUtils.join(" | ", parts);
    }

    private void updateCapabilitySummary(GuidanceFrame guidanceFrame) {
        if (capabilitySummaryText == null) {
            return;
        }
        boolean masterMatchEnabled = modelRuntimeController != null && modelRuntimeController.isMasterMatchEnabled();
        String activeBackbone = modelRuntimeController == null
                ? "N/A"
                : modelRuntimeController.getActiveBackbone();
        StringBuilder summary = new StringBuilder();
        summary.append("On-device covered:");
        summary.append("\n• Live inference, scene parsing, quality metrics");
        summary.append("\n• Guidance overlays, Phase1b composition, motion advisor");
        summary.append("\n• Mode/pro recommendation, Pro auto-apply, post defect targets");
        summary.append("\nHybrid feature:");
        summary.append("\n• MasterMatch ").append(masterMatchEnabled ? "enabled" : "available but asset-off");
        summary.append("\nOffline Python workflow:");
        summary.append("\n• Annotation, training, evaluation, export feed this Android demo");
        summary.append("\nModel ")
                .append(ModelAssetSelector.toDisplayName(activeBackbone))
                .append("  MasterMatch ")
                .append(masterMatchEnabled ? "ON" : "OFF");
        if (guidanceFrame != null) {
            summary.append("  Overlays ")
                    .append(guidanceFrame.getOverlays() == null ? 0 : guidanceFrame.getOverlays().size())
                    .append("  Latency ")
                    .append(formatLatency(guidanceFrame.getPipelineLatencyMs()))
                    .append("/")
                    .append(formatLatency(guidanceFrame.getModelLatencyMs()))
                    .append(" ms");
        }
        capabilitySummaryText.setText(summary.toString());
    }

    private String formatLatency(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String formatConfidence(float value) {
        return String.format(Locale.US, "%.0f%%", value * 100f);
    }

    private String formatDecimal(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String titleCase(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "Unknown";
        }
        String normalized = raw.replace('_', ' ').trim();
        String[] parts = normalized.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.US));
            }
        }
        return out.toString();
    }

    private String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Object firstPresent(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = params.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }


    private void animateTap(View view) {
        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(110).start())
                .start();
    }

    private void animateShutter(View view) {
        view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(65)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).start())
                .start();
    }

    private void startRecordingUi() {
        recordingStartMs = SystemClock.elapsedRealtime();
        recordingIndicator.setVisibility(View.VISIBLE);
        recordTimerText.setText(R.string.recording_time_default);

        uiHandler.removeCallbacks(recordingTicker);
        uiHandler.post(recordingTicker);

        if (recordDotPulseAnimator != null) {
            recordDotPulseAnimator.cancel();
        }
        recordDotPulseAnimator = ObjectAnimator.ofFloat(recordDot, View.ALPHA, 1f, 0.25f);
        recordDotPulseAnimator.setDuration(620);
        recordDotPulseAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        recordDotPulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        recordDotPulseAnimator.start();
    }

    private void stopRecordingUi() {
        recordingStartMs = 0L;
        uiHandler.removeCallbacks(recordingTicker);
        recordTimerText.setText(R.string.recording_time_default);
        recordingIndicator.setVisibility(View.GONE);
        if (recordDotPulseAnimator != null) {
            recordDotPulseAnimator.cancel();
            recordDotPulseAnimator = null;
        }
        recordDot.setAlpha(1f);
    }

    private String safeText(EditText view, String fallback) {
        String txt = view.getText() == null ? "" : view.getText().toString().trim();
        return txt.isEmpty() ? fallback : txt;
    }

    private Integer parseIntOrDefault(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private Float parseFloatOrDefault(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private void setStatus(String msg) {
        runOnUiThread(() -> statusText.setText(msg));
    }

    private void setGuideText(GuidanceOverlay overlay, String fallbackMessage) {
        String message = fallbackMessage;
        String prefix = getString(R.string.guide_prefix_plain);
        int colorRes = R.color.guide_info;
        if (overlay != null && !TextUtils.isEmpty(overlay.getMessage())) {
            message = overlay.getMessage().trim();
            prefix = getString(
                    R.string.guide_prefix_rich,
                    prettyUrgency(overlay.getUrgency()),
                    prettyCategory(overlay.getCategory())
            );
            colorRes = getGuideColorRes(overlay.getUrgency());
        }
        String display = (message == null || message.trim().isEmpty())
                ? getString(R.string.guide_idle)
                : prefix + message.trim();
        final int textColor = ContextCompat.getColor(this, colorRes);
        final int visibility = showComposition ? View.VISIBLE : View.GONE;
        // Phase B.5 — also push the trimmed message to the overlay's anchored
        // tip card. Gated by the showGuidanceOverlay (composition) toggle so
        // the card disappears together with the rest of the canvas widgets.
        // INFO-level messages (e.g. "Level" status) are intentionally suppressed
        // from the anchored tip card because they are not actionable and would
        // otherwise dominate the HUD when no real suggestion is present.
        final boolean isActionable = overlay != null
                && overlay.getUrgency() != null
                && overlay.getUrgency() != GuidanceUrgency.INFO;
        final String anchoredTip = (showGuidanceOverlay && isActionable
                && message != null && !message.trim().isEmpty())
                ? message.trim() : null;
        runOnUiThread(() -> {
            guideText.setText(display);
            guideText.setTextColor(textColor);
            guideText.setVisibility(visibility);
            if (guidanceOverlayView != null) {
                guidanceOverlayView.setCompositionTip(anchoredTip);
            }
        });
    }

    private String prettyUrgency(GuidanceUrgency urgency) {
        if (urgency == null) {
            return "Info";
        }
        switch (urgency) {
            case CRITICAL:
                return "Critical";
            case WARNING:
                return "Warning";
            case SUGGESTION:
                return "Suggestion";
            case INFO:
            default:
                return "Info";
        }
    }

    private String prettyCategory(GuidanceCategory category) {
        if (category == null) {
            return "General";
        }
        switch (category) {
            case OBSTRUCTION:
                return "Obstruction";
            case TECHNICAL:
                return "Technical";
            case ANGLE:
                return "Angle";
            case COMPOSITION:
            default:
                return "Composition";
        }
    }

    private int getGuideColorRes(GuidanceUrgency urgency) {
        if (urgency == null) {
            return R.color.guide_info;
        }
        switch (urgency) {
            case CRITICAL:
                return R.color.guide_critical;
            case WARNING:
                return R.color.guide_warning;
            case SUGGESTION:
                return R.color.guide_suggestion;
            case INFO:
            default:
                return R.color.guide_info;
        }
    }

    @Nullable
    private float[] estimateSubjectBoundingBox(@Nullable FrameAnalysis analysis) {
        if (analysis == null) {
            return null;
        }
        Float centerX = analysis.getSubjectCenterX();
        Float centerY = analysis.getSubjectCenterY();
        if (centerX == null || centerY == null) {
            return null;
        }

        float fillRatio = analysis.getSubjectFillRatio();
        float targetArea = fillRatio > 0.01f ? clamp(fillRatio, 0.02f, 0.60f) : 0.12f;

        String scene = analysis.getSceneType() == null ? "" : analysis.getSceneType().toLowerCase(Locale.ROOT);
        boolean portraitLike = scene.contains("portrait") || analysis.isHasFace();
        float aspect = portraitLike ? 0.75f : 1.0f; // width / height

        float boxW = (float) Math.sqrt(targetArea * aspect);
        float boxH = targetArea / Math.max(boxW, 1e-4f);

        boxW = clamp(boxW, 0.12f, 0.92f);
        boxH = clamp(boxH, 0.12f, 0.92f);

        float x = centerX - boxW * 0.5f;
        float y = centerY - boxH * 0.5f;
        return normalizeBox(x, y, boxW, boxH);
    }

    @Nullable
    private float[] estimateSuggestedCropBoundingBox(@Nullable FrameAnalysis analysis,
                                                     @Nullable float[] subjectBoundingBox) {
        if (analysis == null) {
            return null;
        }

        List<String> issues = analysis.getCompositionIssues();
        boolean hasIssues = issues != null && !issues.isEmpty();
        if (!analysis.isNeedsCompositionEdit() && analysis.getCompositionScore() >= 0.55f && !hasIssues) {
            return null;
        }

        float[] crop;
        if (subjectBoundingBox != null) {
            float sbX = subjectBoundingBox[0];
            float sbY = subjectBoundingBox[1];
            float sbW = subjectBoundingBox[2];
            float sbH = subjectBoundingBox[3];

            float scale = containsIssue(issues, "subject_too_small") ? 1.35f : 1.65f;
            float cropW = clamp(sbW * scale, 0.28f, 0.98f);
            float cropH = clamp(sbH * scale, 0.28f, 0.98f);
            float centerX = sbX + sbW * 0.5f;
            float centerY = sbY + sbH * 0.5f;

            if (containsIssue(issues, "too_much_headroom")) {
                centerY += 0.06f;
            }
            if (containsIssue(issues, "insufficient_headroom")) {
                centerY -= 0.04f;
            }

            crop = normalizeBox(centerX - cropW * 0.5f, centerY - cropH * 0.5f, cropW, cropH);
        } else if (containsIssue(issues, "too_much_headroom")) {
            crop = normalizeBox(0.0f, 0.08f, 1.0f, 0.92f);
        } else {
            crop = null;
        }

        if (crop == null) {
            return null;
        }
        if (crop[2] >= 0.98f && crop[3] >= 0.98f) {
            return null;
        }
        return crop;
    }

    private boolean containsIssue(@Nullable List<String> issues, String issue) {
        return issues != null && issues.contains(issue);
    }

    @Nullable
    private float[] normalizeBox(float x, float y, float w, float h) {
        float nx = clamp01(x);
        float ny = clamp01(y);
        float nw = clamp(w, 0.0f, 1.0f);
        float nh = clamp(h, 0.0f, 1.0f);

        if (nx + nw > 1.0f) {
            nw = 1.0f - nx;
        }
        if (ny + nh > 1.0f) {
            nh = 1.0f - ny;
        }
        if (nw <= 0.01f || nh <= 0.01f) {
            return null;
        }
        return new float[]{nx, ny, nw, nh};
    }

    private float clamp01(float v) {
        return clamp(v, 0.0f, 1.0f);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ── Settings ──────────────────────────────────────────────────────

    private void loadSettingsPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        demoMode = prefs.getBoolean(PREF_DEMO_MODE, true);
        showGuidanceOverlay = prefs.getBoolean(PREF_SHOW_GUIDANCE_OVERLAY, false);
        showComposition = prefs.getBoolean(PREF_SHOW_COMPOSITION, false);
        autoToneEnabled = prefs.getBoolean(PREF_AUTO_TONE, false);
        mimicToneEnabled = prefs.getBoolean(PREF_MIMIC_TONE, true);
        filmSimulationEnabled = prefs.getBoolean(PREF_FILM_SIMULATION, true);
        styleStrength = prefs.getFloat(PREF_STYLE_STRENGTH, 1.0f);
        mimicBrightnessEnabled = prefs.getBoolean(PREF_MIMIC_BRIGHTNESS, true);
        mimicBrightnessBias = prefs.getFloat(PREF_MIMIC_BRIGHTNESS_BIAS, 0.0f);
        autoOptimizationEnabled = prefs.getBoolean(PREF_AUTO_OPTIMIZATION, false);
        saveCompareTriplet = prefs.getBoolean(PREF_SAVE_COMPARISON, true);
        autoModeSuggest = true;
        // Phase 8–10 silhouette flag is read straight from prefs into the
        // CompositionGuidanceSettings the manager owns, so it survives an
        // intelligenceManager release/reload triggered from the dialog.
        if (intelligenceManager != null) {
            com.samsung.camera.intelligence.config.Settings.CompositionGuidanceSettings cg =
                    intelligenceManager.getSettings().getCompositionGuidance();
            if (cg != null) {
                cg.setUseSilhouetteOverlay(
                        prefs.getBoolean(PREF_USE_SILHOUETTE_OVERLAY, false));
            }
        }
    }

    private void saveSettingsPreferences() {
        android.content.SharedPreferences.Editor edit =
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_DEMO_MODE, demoMode)
                .putBoolean(PREF_SHOW_GUIDANCE_OVERLAY, showGuidanceOverlay)
                .putBoolean(PREF_SHOW_COMPOSITION, showComposition)
                .putBoolean(PREF_AUTO_TONE, autoToneEnabled)
                .putBoolean(PREF_MIMIC_TONE, mimicToneEnabled)
                .putBoolean(PREF_FILM_SIMULATION, filmSimulationEnabled)
                .putFloat(PREF_STYLE_STRENGTH, styleStrength)
                .putBoolean(PREF_MIMIC_BRIGHTNESS, mimicBrightnessEnabled)
                .putFloat(PREF_MIMIC_BRIGHTNESS_BIAS, mimicBrightnessBias)
                .putBoolean(PREF_AUTO_OPTIMIZATION, autoOptimizationEnabled)
                .putBoolean(PREF_SAVE_COMPARISON, saveCompareTriplet);
        if (intelligenceManager != null) {
            com.samsung.camera.intelligence.config.Settings.CompositionGuidanceSettings cg =
                    intelligenceManager.getSettings().getCompositionGuidance();
            if (cg != null) {
                edit.putBoolean(PREF_USE_SILHOUETTE_OVERLAY, cg.isUseSilhouetteOverlay());
            }
        }
        edit.apply();
    }

    private void showSettingsDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad / 2);
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.addView(layout, new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        // Row 1: Debug info ON (checked = show debug, unchecked = hide debug)
        CheckBox cbDebug = addSettingsRow(layout, R.string.settings_debug_info, !demoMode);
        // Row 2: Composition Guide ON
        CheckBox cbCompositionGuide = addSettingsRow(layout, R.string.settings_composition_guide, showGuidanceOverlay);
        // Row 3: Mimic Tone Mapping
        CheckBox cbMimicTone = addSettingsRow(layout, R.string.settings_mimic_tone, mimicToneEnabled);
        // Row: Film Simulation (camera-cluster baked LUT). Default ON.
        CheckBox cbFilmSim = addSettingsRow(layout, R.string.settings_film_simulation, filmSimulationEnabled);
        // Row: Mimic low-light brightness lift. Default ON.
        CheckBox cbMimicBrightness = addSettingsRow(layout, R.string.settings_mimic_brightness, mimicBrightnessEnabled);
        // Row 4: Pro Auto-Tone
        CheckBox cbAutoTone = addSettingsRow(layout, R.string.settings_auto_tone, autoToneEnabled);
        // Row 5: Auto Scene Optimization
        CheckBox cbAutoOpt = addSettingsRow(layout, R.string.settings_auto_optimization, autoOptimizationEnabled);
        // Row 5b: Save comparison snapshot (Mimic mode)
        CheckBox cbSaveCompare = addSettingsRow(layout, R.string.settings_save_comparison, saveCompareTriplet);

        // ---- Phase 6 — Aim & Capture composition controls ----
        com.samsung.camera.intelligence.config.Settings.CompositionGuidanceSettings cgSet =
            intelligenceManager != null
                ? intelligenceManager.getSettings().getCompositionGuidance()
                : new com.samsung.camera.intelligence.config.Settings.CompositionGuidanceSettings();
        CheckBox cbProHud = addSettingsRow(layout, R.string.settings_pro_hud, cgSet.isProHudVisible());
        CheckBox cbAutoZoom = addSettingsRow(layout, R.string.settings_auto_zoom_on_lock, cgSet.isAutoZoomOnLock());
        CheckBox cbAiSubject = addSettingsRow(layout, R.string.settings_use_ai_subject, cgSet.isUseAiSubjectDetector());
        CheckBox cbAiCrop = addSettingsRow(layout, R.string.settings_use_ai_crop, cgSet.isUseAiCropAdvisor());
        CheckBox cbExtOverlay = addSettingsRow(layout, R.string.settings_show_external_overlay,
                cgSet.isShowExternalDetectorOverlay() || cgSet.isShowExternalCropAdvisorOverlay());
        // Phase 8–10 (Plan A) — silhouette / ghost-frame outline. Off by
        // default; acts as the 7-day kill-switch flag.
        CheckBox cbSilhouette = addSettingsRow(layout, R.string.settings_silhouette_overlay,
                cgSet.isUseSilhouetteOverlay());

        // Row 6: Style Strength slider (0..100%, maps to 0.0..1.0)
        android.widget.SeekBar sbStrength = addSettingsSeekBarRow(layout,
                R.string.settings_style_strength, styleStrength);

        // Row 6b: Mimic Brightness bias slider.  Slider 0..100% maps to a
        // bias of -1..+1 (50% = neutral auto).  Negative darkens, positive
        // brightens beyond the auto-computed low-light lift.
        android.widget.SeekBar sbBrightnessBias = addSettingsSeekBarRow(layout,
                R.string.settings_mimic_brightness_bias, (mimicBrightnessBias + 1f) / 2f);

        // Row 7: Inference model selection.
        List<String> modelOptions = ModelAssetSelector.getSelectableBackbones();
        android.widget.Spinner modelSpinner = modelOptions.isEmpty()
            ? null : addModelSelectionSpinnerRow(layout, modelOptions);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
            .setView(scrollView)
                .setPositiveButton(R.string.apply_model_selection, (dialog, which) -> {
                    try {
                        demoMode = !cbDebug.isChecked();
                        showGuidanceOverlay = cbCompositionGuide.isChecked();
                        mimicToneEnabled = cbMimicTone.isChecked();
                        filmSimulationEnabled = cbFilmSim.isChecked();
                        mimicBrightnessEnabled = cbMimicBrightness.isChecked();
                        autoToneEnabled = cbAutoTone.isChecked();
                        autoOptimizationEnabled = cbAutoOpt.isChecked();
                        saveCompareTriplet = cbSaveCompare.isChecked();

                        boolean proHudChecked = cbProHud.isChecked();
                        boolean autoZoomChecked = cbAutoZoom.isChecked();
                        boolean aiSubjectChecked = cbAiSubject.isChecked();
                        boolean aiCropChecked = cbAiCrop.isChecked();
                        boolean externalOverlayChecked = cbExtOverlay.isChecked();
                        boolean silhouetteChecked = cbSilhouette.isChecked();

                        com.samsung.camera.intelligence.config.Settings.CompositionGuidanceSettings cgApply =
                                intelligenceManager != null
                                        ? intelligenceManager.getSettings().getCompositionGuidance()
                                        : null;
                        if (cgApply != null) {
                            cgApply.setProHudVisible(proHudChecked);
                            cgApply.setAutoZoomOnLock(autoZoomChecked);
                            cgApply.setUseAiSubjectDetector(aiSubjectChecked);
                            cgApply.setUseAiCropAdvisor(aiCropChecked);
                            cgApply.setShowExternalDetectorOverlay(externalOverlayChecked);
                            cgApply.setShowExternalCropAdvisorOverlay(externalOverlayChecked);
                            cgApply.setUseSilhouetteOverlay(silhouetteChecked);
                            intelligenceManager.applyCompositionGuidanceSettings();
                        }
                        if (guidanceOverlayView != null) {
                            guidanceOverlayView.setProHudVisible(proHudChecked);
                            guidanceOverlayView.setShowExternalComparisonOverlay(externalOverlayChecked);
                        }
                        styleStrength = sbStrength.getProgress() / 100f;
                        mimicBrightnessBias = clamp(sbBrightnessBias.getProgress() / 100f * 2f - 1f, -1f, 1f);
                        saveSettingsPreferences();
                        applyDemoModeVisibility();
                        // Only trigger an actual model reload when the user
                        // changed the spinner selection. Reloading is heavy
                        // (closes & re-opens the TFLite Interpreter on the UI
                        // thread) and races with the camera worker thread
                        // calling processFrame() — historically the #1 cause
                        // of "apply settings → app crashes" reports.
                        if (modelSpinner != null && !modelOptions.isEmpty()) {
                            int selectedPosition = modelSpinner.getSelectedItemPosition();
                            if (selectedPosition >= 0 && selectedPosition < modelOptions.size()) {
                                String selectedModel = modelOptions.get(selectedPosition);
                                String currentPreferred =
                                        ModelAssetSelector.getPreferredBackbone(this);
                                if (!java.util.Objects.equals(selectedModel, currentPreferred)) {
                                    if (!ModelAssetSelector.isBackboneAvailable(this, selectedModel)) {
                                        setStatus("Model \""
                                                + ModelAssetSelector.toDisplayName(selectedModel)
                                                + "\" is not installed in this APK.");
                                        return;
                                    }
                                    if (modelRuntimeController != null
                                            && !modelRuntimeController.applyBackboneSelection(selectedModel)) {
                                        setStatus(getString(R.string.model_switch_failed));
                                    }
                                }
                            }
                        }
                        // Clear enhancements if optimization disabled
                        if (!autoOptimizationEnabled) {
                            clearSceneEnhancement();
                        }
                        // Re-apply current Mimic overlay so tone toggle / strength
                        // changes take effect immediately on the GPU LUT.
                        if (stateMachine != null
                                && stateMachine.getCurrentMode() == CameraWorkflowStateMachine.Mode.MIMIC
                                && !usesDedicatedModePreviewEffect(stateMachine.getCurrentMode())
                                && mimicPanelController != null) {
                            MasterMatchOverlay sel = mimicPanelController.getSelectedOverlay();
                            if (sel != null) {
                                applyMimicOverlayParams(sel);
                            } else if (!mimicToneEnabled && cameraController != null
                                    && cameraController.getGlPreview() != null) {
                                cameraController.getGlPreview().clearLut();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to apply settings", e);
                        Toast.makeText(this, R.string.settings_apply_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private CheckBox addSettingsRow(android.widget.LinearLayout parent, int labelResId, boolean checked) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int vPad = (int) (8 * getResources().getDisplayMetrics().density);
        row.setPadding(0, vPad, 0, vPad);

        TextView label = new TextView(this);
        label.setText(labelResId);
        label.setTextSize(16);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(lp);
        row.addView(label);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(checked);
        row.addView(cb);

        parent.addView(row);
        return cb;
    }

    private android.widget.Spinner addModelSelectionSpinnerRow(android.widget.LinearLayout parent,
                                                              List<String> options) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        int vPad = (int) (8 * getResources().getDisplayMetrics().density);
        row.setPadding(0, vPad, 0, vPad);

        TextView label = new TextView(this);
        label.setText(R.string.settings_model_select);
        label.setTextSize(16);
        row.addView(label);

        List<String> labels = new ArrayList<>();
        for (String option : options) {
            labels.add(ModelAssetSelector.toDisplayNameWithAvailability(this, option));
        }
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String preferred = ModelAssetSelector.getPreferredBackbone(this);
        int selectedIndex = Math.max(0, options.indexOf(preferred));
        spinner.setSelection(selectedIndex);
        row.addView(spinner, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(row);
        return spinner;
    }

    private android.widget.SeekBar addSettingsSeekBarRow(android.widget.LinearLayout parent,
                                                         int labelResId, float value) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int vPad = (int) (8 * getResources().getDisplayMetrics().density);
        row.setPadding(0, vPad, 0, vPad);

        TextView label = new TextView(this);
        label.setText(labelResId);
        label.setTextSize(16);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(lp);
        row.addView(label);

        TextView valueLabel = new TextView(this);
        int pct = Math.round(value * 100);
        valueLabel.setText(pct + "%");
        valueLabel.setTextSize(14);
        valueLabel.setMinWidth((int) (40 * getResources().getDisplayMetrics().density));
        row.addView(valueLabel);

        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(100);
        sb.setProgress(pct);
        android.widget.LinearLayout.LayoutParams sbLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);
        sb.setLayoutParams(sbLp);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                valueLabel.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        row.addView(sb);

        parent.addView(row);
        return sb;
    }

    private void applyDemoModeVisibility() {
        // Top overlay debug information
        int debugVisibility = demoMode ? View.GONE : View.VISIBLE;
        if (statusText != null) statusText.setVisibility(debugVisibility);
        if (modelRuntimeHintText != null) modelRuntimeHintText.setVisibility(debugVisibility);
        if (triggerDebugText != null && demoMode) triggerDebugText.setVisibility(View.GONE);
        if (summaryLiveTabButton != null) summaryLiveTabButton.setVisibility(debugVisibility);
        if (summaryFeatureTabButton != null) summaryFeatureTabButton.setVisibility(debugVisibility);
        if (summaryPipelineTabButton != null) summaryPipelineTabButton.setVisibility(debugVisibility);
        if (analysisSummaryText != null) analysisSummaryText.setVisibility(debugVisibility);
        if (featureSummaryText != null) featureSummaryText.setVisibility(demoMode ? View.GONE : featureSummaryText.getVisibility());
        if (capabilitySummaryText != null) capabilitySummaryText.setVisibility(demoMode ? View.GONE : capabilitySummaryText.getVisibility());

        // Composition guidance overlay
        if (guidanceOverlayView != null) {
            guidanceOverlayView.setVisibility(showGuidanceOverlay ? View.VISIBLE : View.GONE);
        }
        // Composition tip (in guidance frame)
        if (guideText != null) {
            guideText.setVisibility(showComposition ? View.VISIBLE : View.GONE);
        }
    }



    private void applyAutoToneParams(SceneToneOptimizer.ToneParams tone) {
        if (tone == null) {
            return;
        }
        if (lastAppliedAutoTone != null
                && !tone.significantlyDifferentFrom(lastAppliedAutoTone, 4f)) {
            return;
        }

        // Preserve the current manual exposure settings (ISO/shutter) so that
        // auto-tone doesn't flip the camera from AE_MODE_OFF back to AE_MODE_ON,
        // which can stall the preview pipeline on some devices.
        CameraProSettings current = cameraController.getCurrentProSettings();
        Integer iso = current != null ? current.iso : null;
        Long shutterNs = current != null ? current.shutterNs : null;
        Float ev = parseFloatOrDefault(evInput.getText().toString(), 0f);
        String wb = safeText(wbInput, "auto");
        String focus = safeText(focusInput, "multi_point");

        cameraController.applyDirectProSettings(new CameraProSettings(
                iso, shutterNs, ev, wb, null, focus, null, "matrix",
                tone.contrast, tone.highlights, tone.shadows, tone.saturation
        ));
        lastAppliedAutoTone = tone;
        scheduleToneSettle();
    }

    private void scheduleToneSettle() {
        toneSettleUntilMs = System.currentTimeMillis() + TONE_SETTLE_PAUSE_MS;
    }

    // ── Auto Scene Enhancement ───────────────────────────────────────

    private void applySceneEnhancement(SceneEnhancementOptimizer.EnhancementResult result) {
        if (result == null) return;
        com.samsung.camera.intelligence.app.camera.CameraGLPreview preview =
                cameraController.getGlPreview();
        if (preview == null) return;

        // Apply LUT from 6-param tone grading
        preview.updateLutFromToneParams(
                result.contrast, result.highlights, result.shadows,
                result.saturation, result.highlightWarmth, result.shadowTint);

        // Apply T2 shader enhancements
        preview.updateEnhancement(result.shaderMode, result.shaderParams);

        // Apply T3 USM if requested
        preview.updateUsm(result.usmEnabled, result.usmRadius, result.usmStrength);

        // Update status label
        activeEnhancementLabel = result.mode.emoji + " " + result.mode.displayName;
    }

    private void clearSceneEnhancement() {
        com.samsung.camera.intelligence.app.camera.CameraGLPreview preview =
                cameraController.getGlPreview();
        if (preview != null) {
            preview.clearEnhancements();
            preview.clearPortraitMask();
            // Don't clear LUT here — other features (Mimic, Auto-Tone) may be using it
        }
        activeEnhancementLabel = null;
    }

    private boolean usesDedicatedModePreviewEffect(CameraWorkflowStateMachine.Mode mode) {
        if (mode == null) {
            return false;
        }
        switch (mode) {
            case NIGHT:
            case FOOD:
            case PANORAMA:
            case PORTRAIT:
            case MACRO:
                return true;
            default:
                return false;
        }
    }

    private void applyModePreviewEffect(CameraWorkflowStateMachine.Mode mode) {
        if (cameraController == null) {
            return;
        }
        com.samsung.camera.intelligence.app.camera.CameraGLPreview preview =
                cameraController.getGlPreview();
        if (preview == null) {
            return;
        }
        preview.clearEnhancements();
        preview.clearPortraitMask();
        switch (mode) {
            case NIGHT:
                preview.updateEnhancement(4, new float[]{1.45f, 0.92f, 0.28f});
                activeEnhancementLabel = "Night preview";
                break;
            case FOOD:
                preview.updateEnhancement(5, new float[]{1.18f, 0.07f, 0.04f});
                activeEnhancementLabel = "Food preview";
                break;
            case PANORAMA:
                preview.updateEnhancement(6, new float[]{0.30f, 0.0045f});
                activeEnhancementLabel = "Panorama preview";
                break;
            case MACRO:
                preview.updateUsm(true, 1.2f, 0.35f);
                activeEnhancementLabel = "Macro preview";
                break;
            case PORTRAIT:
                preview.updateEnhancement(7, new float[]{0.5f, 0.5f, 0.12f, 0.55f, 0.95f});
                activeEnhancementLabel = "Portrait preview";
                break;
            default:
                activeEnhancementLabel = null;
                break;
        }
    }

    private void schedulePortraitSegmentation(Bitmap bitmap) {
        if (bitmap == null || cameraController == null) {
            return;
        }
        if (stateMachine.getCurrentMode() != CameraWorkflowStateMachine.Mode.PORTRAIT) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPortraitSegmentationAtMs < PORTRAIT_SEGMENT_INTERVAL_MS) {
            return;
        }
        if (!portraitSegmentationInFlight.compareAndSet(false, true)) {
            return;
        }
        lastPortraitSegmentationAtMs = now;
        final Bitmap portraitFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        portraitSegmentationExecutor.execute(() -> {
            Bitmap maskBitmap = null;
            float centerX = 0.5f;
            float centerY = 0.5f;
            try {
                if (portraitSegmentationRunner != null) {
                    float[][] mask = portraitSegmentationRunner.runSegmentation(portraitFrame);
                    if (mask != null) {
                        maskBitmap = buildPortraitMaskBitmap(mask);
                        float[] center = computePortraitMaskCenter(mask);
                        centerX = center[0];
                        centerY = center[1];
                    }
                }
                if (maskBitmap == null && portraitFallbackDetector != null && portraitFallbackDetector.isAvailable()) {
                    com.samsung.camera.intelligence.inference.SubjectDetectorEngine.SaliencyResult saliency =
                            portraitFallbackDetector.detect(portraitFrame);
                    if (saliency != null) {
                        maskBitmap = buildPortraitMaskBitmap(saliency.maskBytes, saliency.maskW, saliency.maskH);
                        if (saliency.subjectCenterNorm != null && saliency.subjectCenterNorm.length >= 2) {
                            centerX = saliency.subjectCenterNorm[0];
                            centerY = saliency.subjectCenterNorm[1];
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Portrait segmentation preview failed", t);
            } finally {
                portraitFrame.recycle();
            }

            final Bitmap finalMaskBitmap = maskBitmap;
            final float finalCenterX = centerX;
            final float finalCenterY = centerY;
            runOnUiThread(() -> {
                portraitSegmentationInFlight.set(false);
                if (stateMachine.getCurrentMode() != CameraWorkflowStateMachine.Mode.PORTRAIT
                        || cameraController == null) {
                    if (finalMaskBitmap != null) {
                        finalMaskBitmap.recycle();
                    }
                    return;
                }
                com.samsung.camera.intelligence.app.camera.CameraGLPreview preview =
                        cameraController.getGlPreview();
                if (preview == null) {
                    if (finalMaskBitmap != null) {
                        finalMaskBitmap.recycle();
                    }
                    return;
                }
                if (finalMaskBitmap != null) {
                    preview.updatePortraitMask(finalMaskBitmap);
                } else {
                    preview.clearPortraitMask();
                }
                preview.updateEnhancement(7, new float[]{finalCenterX, finalCenterY, 0.12f, 0.55f, 0.95f});
            });
        });
    }

    private static Bitmap buildPortraitMaskBitmap(float[][] mask) {
        int h = mask.length;
        int w = mask[0].length;
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int value = Math.max(0, Math.min(255, Math.round(mask[y][x] * 255f)));
                pixels[y * w + x] = 0xFF000000 | (value << 16) | (value << 8) | value;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    private static Bitmap buildPortraitMaskBitmap(byte[] maskBytes, int width, int height) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int value = maskBytes[i] & 0xFF;
            pixels[i] = 0xFF000000 | (value << 16) | (value << 8) | value;
        }
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, width, 0, 0, width, height);
        return bmp;
    }

    private static float[] computePortraitMaskCenter(float[][] mask) {
        float sumX = 0f;
        float sumY = 0f;
        float sumW = 0f;
        int h = mask.length;
        int w = mask[0].length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float weight = mask[y][x];
                if (weight <= 0.05f) {
                    continue;
                }
                sumX += x * weight;
                sumY += y * weight;
                sumW += weight;
            }
        }
        if (sumW <= 1e-4f) {
            return new float[]{0.5f, 0.5f};
        }
        return new float[]{
                (sumX / sumW) / Math.max(1f, (float) (w - 1)),
                (sumY / sumW) / Math.max(1f, (float) (h - 1)),
        };
    }

    private void toggleFilterStrip() {
        if (filterStripScroll == null || filterStripContainer == null) {
            return;
        }
        filterStripVisible = !filterStripVisible;
        filterStripScroll.setVisibility(filterStripVisible ? View.VISIBLE : View.GONE);
        if (filterStripVisible) {
            highlightedFilterClusterId = activeFilterClusterId;
            if (highlightedFilterClusterId == null && stylePresetManager != null && stylePresetManager.isAvailable()) {
                List<Integer> ids = stylePresetManager.clusterIds();
                java.util.Collections.sort(ids);
                if (!ids.isEmpty()) {
                    highlightedFilterClusterId = ids.get(0);
                }
            }
            renderFilterStrip(null);
            scheduleFilterThumbnailRefresh(null);
        }
    }

    private void applyPreviewFilter(@Nullable Integer clusterId) {
        activeFilterClusterId = clusterId;
        highlightedFilterClusterId = clusterId;
        if (filterToggleButton != null) {
            filterToggleButton.setText(clusterId == null
                    ? getString(R.string.filter_original)
                    : summarizeFilterLabel(stylePresetManager != null ? stylePresetManager.labelOf(clusterId) : null));
        }
        if (cameraController == null || stylePresetManager == null || !stylePresetManager.isAvailable()) {
            renderFilterStrip(null);
            return;
        }
        if (clusterId == null) {
            cameraController.clearLutOverride();
            renderFilterStrip(null);
            return;
        }
        Bitmap base = stylePresetManager.loadClusterLut(clusterId);
        if (base != null) {
            cameraController.applyDirectLutBitmap(base.copy(Bitmap.Config.ARGB_8888, false));
        }
        renderFilterStrip(null);
    }

    private void scheduleFilterThumbnailRefresh(@Nullable Bitmap bitmap) {
        if (!filterStripVisible || stylePresetManager == null || !stylePresetManager.isAvailable()) {
            return;
        }
        if (bitmap != null) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 96, 96, true);
            if (latestFilterPreviewSource != null) {
                latestFilterPreviewSource.recycle();
            }
            latestFilterPreviewSource = scaled;
        }
        if (latestFilterPreviewSource == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastFilterThumbnailRefreshMs < FILTER_THUMB_REFRESH_INTERVAL_MS) {
            return;
        }
        if (!filterPreviewInFlight.compareAndSet(false, true)) {
            return;
        }
        lastFilterThumbnailRefreshMs = now;
        final Bitmap source = latestFilterPreviewSource.copy(Bitmap.Config.ARGB_8888, false);
        filterPreviewExecutor.execute(() -> {
            Map<Integer, Bitmap> thumbs = new HashMap<>();
            try {
                List<Integer> ids = stylePresetManager.clusterIds();
                java.util.Collections.sort(ids);
                for (Integer id : ids) {
                    Bitmap lut = stylePresetManager.loadClusterLut(id);
                    if (lut != null) {
                        thumbs.put(id, applyLutPreviewToBitmap(source, lut));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Filter thumbnail refresh failed", t);
            } finally {
                source.recycle();
            }
            runOnUiThread(() -> {
                filterPreviewInFlight.set(false);
                renderFilterStrip(thumbs);
            });
        });
    }

    private void renderFilterStrip(@Nullable Map<Integer, Bitmap> thumbs) {
        if (filterStripContainer == null || stylePresetManager == null || !stylePresetManager.isAvailable()) {
            return;
        }
        filterStripContainer.removeAllViews();
        addFilterChip(null, getString(R.string.filter_original), null, activeFilterClusterId == null);
        List<Integer> ids = stylePresetManager.clusterIds();
        java.util.Collections.sort(ids);
        for (Integer id : ids) {
            addFilterChip(
                    id,
                    summarizeFilterLabel(stylePresetManager.labelOf(id)),
                    thumbs != null ? thumbs.get(id) : null,
                    id.equals(activeFilterClusterId) || (activeFilterClusterId == null && id.equals(highlightedFilterClusterId))
            );
        }
    }

    private void addFilterChip(@Nullable Integer clusterId, String label,
                               @Nullable Bitmap thumb, boolean selected) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackgroundResource(selected
                ? R.drawable.bg_mimic_card_selected
                : R.drawable.bg_mimic_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        card.setLayoutParams(lp);

        ImageView image = new ImageView(this);
        image.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (thumb != null) {
            image.setImageBitmap(thumb);
        } else {
            image.setBackgroundColor(0x22000000);
        }

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xFFF5F7FF);
        name.setTextSize(11f);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        name.setPadding(0, dp(6), 0, 0);

        card.addView(image);
        card.addView(name);
        card.setOnClickListener(v -> applyPreviewFilter(clusterId));
        filterStripContainer.addView(card);
    }

    private static String summarizeFilterLabel(@Nullable String label) {
        if (label == null || label.trim().isEmpty()) {
            return "LUT";
        }
        String[] parts = label.split("·");
        String last = parts[parts.length - 1].trim();
        return last.isEmpty() ? label.trim() : last;
    }

    private static Bitmap applyLutPreviewToBitmap(Bitmap src, Bitmap lut) {
        int width = src.getWidth();
        int height = src.getHeight();
        int[] srcPixels = new int[width * height];
        int[] outPixels = new int[width * height];
        src.getPixels(srcPixels, 0, width, 0, 0, width, height);
        int lutSize = com.samsung.camera.intelligence.app.camera.LutToneMapper.LUT_SIZE;
        int lutWidth = lut.getWidth();
        int[] lutPixels = new int[lutWidth * lut.getHeight()];
        lut.getPixels(lutPixels, 0, lutWidth, 0, 0, lutWidth, lut.getHeight());
        int sizeM1 = lutSize - 1;
        for (int i = 0; i < srcPixels.length; i++) {
            int pixel = srcPixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            int ri = Math.min(sizeM1, Math.round((r / 255f) * sizeM1));
            int gi = Math.min(sizeM1, Math.round((g / 255f) * sizeM1));
            int bi = Math.min(sizeM1, Math.round((b / 255f) * sizeM1));
            int x = bi * lutSize + ri;
            int y = gi;
            outPixels[i] = lutPixels[y * lutWidth + x];
        }
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        out.setPixels(outPixels, 0, width, 0, 0, width, height);
        return out;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ── Mimic (MasterMatch) Mode ─────────────────────────────────────

    /**
     * Style Straw: handle a gallery photo selected by the user.
     * Decodes the image, extracts tone parameters on a background thread,
     * then populates gallery card slot 3 and applies the parameters.
     */
    private void onGalleryPhotoSelected(Uri uri) {
        styleStrawExecutor.execute(() -> {
            try {
                // --- Resolve display name ---
                String displayName = "Gallery";
                try (Cursor c = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String n = c.getString(idx);
                            if (n != null) displayName = n;
                        }
                    }
                }

                // --- Decode at ~256px for analysis ---
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, opts);
                }
                int maxDim = Math.max(opts.outWidth, opts.outHeight);
                int sampleSize = 1;
                while (maxDim / sampleSize > 512) sampleSize *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sampleSize;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                opts.inMutable = false;
                Bitmap decoded;
                try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                    decoded = BitmapFactory.decodeStream(is, null, opts);
                }
                if (decoded == null) return;

                // Force a software ARGB_8888 bitmap before any pixel-level work.
                // BitmapFactory normally returns software bitmaps, but on some
                // OEM image pipelines (and any path that may route through
                // ImageDecoder) the result can be HARDWARE-backed, which would
                // crash both Bitmap.createScaledBitmap(filter=true) and
                // StyleStrawExtractor.getPixels().
                Bitmap analysisBitmap = StyleStrawExtractor.ensureReadableBitmap(decoded);
                if (analysisBitmap != decoded) {
                    decoded.recycle();
                }

                // --- 150px thumbnail for card ---
                int tw = analysisBitmap.getWidth();
                int th = analysisBitmap.getHeight();
                float scale = 150f / Math.max(tw, th);
                Bitmap thumb;
                try {
                    thumb = Bitmap.createScaledBitmap(analysisBitmap,
                            Math.max(1, (int)(tw * scale)),
                            Math.max(1, (int)(th * scale)), true);
                } catch (Throwable t) {
                    Log.w("StyleStraw", "createScaledBitmap failed, using source", t);
                    thumb = analysisBitmap;
                }

                // --- Extract parameters ---
                // Prefer EXIF->ExposureMapper (same path as master DB mapping) when EXIF
                // contains enough capture metadata, then backfill missing tone keys using
                // StyleStrawExtractor so gallery style still has full 6D tone vector.
                Map<String, Object> toneParams = mapGalleryExifToPhoneParams(uri);
                Map<String, Object> extractedTone = null;
                if (toneParams == null) {
                    toneParams = StyleStrawExtractor.extractToneParams(analysisBitmap);
                } else {
                    extractedTone = StyleStrawExtractor.extractToneParams(analysisBitmap);
                    fillMissingToneKeys(toneParams, extractedTone);
                    Log.i("StyleStraw", "Gallery EXIF mapped via ExposureMapper and tone-backfilled");
                }
                if (thumb != analysisBitmap) {
                    analysisBitmap.recycle();
                }

                // --- Build overlay ---
                MasterMatchOverlay overlay = new MasterMatchOverlay();
                overlay.setPhotoTitle(displayName);
                overlay.setProModeParams(toneParams);

                // --- Persist to SharedPreferences + save thumbnail ---
                persistGalleryStraw(uri.toString(), displayName, toneParams, thumb);

                // --- Apply on UI thread ---
                final Bitmap finalThumb = thumb;
                runOnUiThread(() -> {
                    mimicPanelController.setGalleryOverlay(overlay, finalThumb);
                    applyMimicOverlayParams(overlay);
                    scheduleToneSettle();
                });
            } catch (Throwable e) {
                Log.e("StyleStraw", "Failed to process gallery photo", e);
                runOnUiThread(() ->
                    Toast.makeText(this, "Failed to extract style", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Persist gallery straw data to SharedPreferences + thumbnail to internal file. */
    private void persistGalleryStraw(String uriStr, String title,
                                     Map<String, Object> params, Bitmap thumb) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putString(PREF_STRAW_URI, uriStr);
        ed.putString(PREF_STRAW_TITLE, title);
        ed.putFloat(PREF_STRAW_CONTRAST, toFloat(params.get("contrast")));
        ed.putFloat(PREF_STRAW_HIGHLIGHTS, toFloat(params.get("highlights")));
        ed.putFloat(PREF_STRAW_SHADOWS, toFloat(params.get("shadows")));
        ed.putFloat(PREF_STRAW_SATURATION, toFloat(params.get("saturation")));
        ed.putFloat(PREF_STRAW_WARMTH, toFloat(params.get("highlight_warmth")));
        ed.putFloat(PREF_STRAW_TINT, toFloat(params.get("shadow_tint")));
        ed.apply();

        // Save thumbnail as PNG to internal storage
        if (thumb != null) {
            try (java.io.FileOutputStream fos =
                         openFileOutput(STRAW_THUMB_FILENAME, MODE_PRIVATE)) {
                thumb.compress(Bitmap.CompressFormat.PNG, 90, fos);
            } catch (Exception e) {
                Log.w("StyleStraw", "Failed to save thumbnail", e);
            }
        }
    }

    /** Clear persisted gallery straw data. */
    private void clearPersistedGalleryStraw() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_STRAW_URI).remove(PREF_STRAW_TITLE)
                .remove(PREF_STRAW_CONTRAST).remove(PREF_STRAW_HIGHLIGHTS)
                .remove(PREF_STRAW_SHADOWS).remove(PREF_STRAW_SATURATION)
                .remove(PREF_STRAW_WARMTH).remove(PREF_STRAW_TINT)
                .apply();
        deleteFile(STRAW_THUMB_FILENAME);
    }

    /**
     * Restore persisted gallery straw card if available.
     * Called when entering Mimic mode.
     */
    private void restoreGalleryStraw() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String title = prefs.getString(PREF_STRAW_TITLE, null);
        if (title == null) return; // nothing persisted

        Map<String, Object> params = new HashMap<>();
        params.put("contrast", prefs.getFloat(PREF_STRAW_CONTRAST, 0f));
        params.put("highlights", prefs.getFloat(PREF_STRAW_HIGHLIGHTS, 0f));
        params.put("shadows", prefs.getFloat(PREF_STRAW_SHADOWS, 0f));
        params.put("saturation", prefs.getFloat(PREF_STRAW_SATURATION, 0f));
        params.put("highlight_warmth", prefs.getFloat(PREF_STRAW_WARMTH, 0f));
        params.put("shadow_tint", prefs.getFloat(PREF_STRAW_TINT, 0f));

        MasterMatchOverlay overlay = new MasterMatchOverlay();
        overlay.setPhotoTitle(title);
        overlay.setProModeParams(params);

        // Load saved thumbnail
        Bitmap thumb = null;
        java.io.File thumbFile = getFileStreamPath(STRAW_THUMB_FILENAME);
        if (thumbFile != null && thumbFile.exists()) {
            thumb = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
        }

        mimicPanelController.setGalleryOverlay(overlay, thumb);
    }

    private static float toFloat(Object v) {
        return v instanceof Number ? ((Number) v).floatValue() : 0f;
    }

    @Nullable
    private Map<String, Object> mapGalleryExifToPhoneParams(Uri uri) {
        if (uri == null) {
            return null;
        }
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return null;
            }
            ExifInterface exif = new ExifInterface(is);
            Map<String, Object> exifMap = new HashMap<>();

            double aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN);
            if (!Double.isNaN(aperture) && aperture > 0) {
                exifMap.put("aperture", (float) aperture);
            }

            String exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (exposureTime != null && !exposureTime.trim().isEmpty()) {
                exifMap.put("shutter_speed", exposureTime.trim());
            }

            int iso = exif.getAttributeInt("PhotographicSensitivity", -1);
            if (iso <= 0) {
                iso = parsePositiveInt(exif.getAttribute("ISOSpeedRatings"), -1);
            }
            if (iso > 0) {
                exifMap.put("iso", iso);
            }

            int wbKelvin = parsePositiveInt(exif.getAttribute("ColorTemperature"), -1);
            if (wbKelvin > 0) {
                exifMap.put("white_balance_kelvin", wbKelvin);
            }

            String model = exif.getAttribute(ExifInterface.TAG_MODEL);
            if (model != null && !model.trim().isEmpty()) {
                exifMap.put("sensor_type", inferSensorType(model));
            }

            if (!hasMasterLikeExif(exifMap)) {
                return null;
            }

            FrameAnalysis analysis = latestFrameAnalysis;
            float currentNoise = analysis != null ? analysis.getNoiseLevel() : 0.35f;
            String currentLighting = analysis != null ? analysis.getLightingCondition() : "indoor";
            String currentMotion = analysis != null ? analysis.getMotionType() : "static";

            ExposureMapper mapper = new ExposureMapper(android.os.Build.MODEL);
            Map<String, Object> mapped = mapper.mapToPhone(exifMap, currentNoise, currentLighting, currentMotion);
            if (mapped == null || mapped.isEmpty()) {
                return null;
            }

            // Preserve parsed source EXIF values for diagnostics/UI display.
            putIfAbsent(mapped, "aperture", exifMap.get("aperture"));
            putIfAbsent(mapped, "shutter_speed", exifMap.get("shutter_speed"));
            putIfAbsent(mapped, "iso", exifMap.get("iso"));
            putIfAbsent(mapped, "white_balance_kelvin", exifMap.get("white_balance_kelvin"));
            return mapped;
        } catch (Throwable t) {
            Log.w("StyleStraw", "Failed to parse EXIF for gallery style mapping", t);
            return null;
        }
    }

    private static boolean hasMasterLikeExif(Map<String, Object> exifMap) {
        if (exifMap == null || exifMap.isEmpty()) {
            return false;
        }
        return exifMap.containsKey("aperture")
                || exifMap.containsKey("shutter_speed")
                || exifMap.containsKey("iso");
    }

    private static String inferSensorType(String model) {
        if (model == null) {
            return "mobile";
        }
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("sm-") || m.contains("iphone") || m.contains("pixel")
                || m.contains("mi ") || m.contains("redmi") || m.contains("huawei")
                || m.contains("oppo") || m.contains("vivo") || m.contains("oneplus")) {
            return "mobile";
        }
        return "full_frame";
    }

    private static int parsePositiveInt(@Nullable String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            int val = Integer.parseInt(s.trim());
            return val > 0 ? val : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void putIfAbsent(Map<String, Object> map, String key, @Nullable Object value) {
        if (map != null && key != null && value != null && !map.containsKey(key)) {
            map.put(key, value);
        }
    }

    private static void fillMissingToneKeys(Map<String, Object> dst, Map<String, Object> toneSource) {
        if (dst == null || toneSource == null) {
            return;
        }
        putIfAbsent(dst, "contrast", toneSource.get("contrast"));
        putIfAbsent(dst, "highlights", toneSource.get("highlights"));
        putIfAbsent(dst, "shadows", toneSource.get("shadows"));
        putIfAbsent(dst, "saturation", toneSource.get("saturation"));
        putIfAbsent(dst, "highlight_warmth", toneSource.get("highlight_warmth"));
        putIfAbsent(dst, "shadow_tint", toneSource.get("shadow_tint"));
    }

    /**
     * Apply Pro Mode parameters from a selected MasterMatch overlay to the camera.
     * Called when the user taps a card in the Mimic panel.
     */
    private void applyMimicOverlayParams(MasterMatchOverlay overlay) {
        if (overlay == null) return;
        Map<String, Object> params = overlay.getProModeParams();
        if (params == null || params.isEmpty()) return;

        suppressProInputWatcher = true;
        // Show reference ISO/shutter in UI for information, but do NOT apply
        // them to the camera hardware.  Reference photos are often shot in
        // very different lighting (e.g. bright daylight → 1/3200); applying
        // that shutter verbatim to the current (possibly darker) scene would
        // produce a nearly-black preview.  We keep AE_MODE_ON and rely on the
        // tone curve alone to approximate the reference photo's "look".
        Object iso = params.get("iso");
        if (iso != null) isoInput.setText(String.valueOf(iso));
        Object shutter = params.get("shutter_speed");
        if (shutter != null) shutterInput.setText(String.valueOf(shutter));
        Object ev = params.get("ev");
        if (ev != null) evInput.setText(String.valueOf(ev));
        Object wb = params.get("white_balance_kelvin");
        if (wb == null) wb = params.get("white_balance");
        if (wb != null) wbInput.setText(String.valueOf(wb));
        Object focus = params.get("focus_mode");
        if (focus != null) focusInput.setText(String.valueOf(focus));
        suppressProInputWatcher = false;

        // Extract tone style parameters from overlay and scale by styleStrength
        Float contrast = mimicToneEnabled ? scaleByStrength(extractFloat(params, "contrast")) : null;
        Float highlights = mimicToneEnabled ? scaleByStrength(extractFloat(params, "highlights")) : null;
        Float shadows = mimicToneEnabled ? scaleByStrength(extractFloat(params, "shadows")) : null;
        Float saturation = mimicToneEnabled ? scaleByStrength(extractFloat(params, "saturation")) : null;
        Float highlightWarmth = mimicToneEnabled ? scaleByStrength(extractFloat(params, "highlight_warmth")) : null;
        Float shadowTint = mimicToneEnabled ? scaleByStrength(extractFloat(params, "shadow_tint")) : null;

        // 3-zone split-tone vectors (richer colour transfer than the single
        // global warmth/tint pair).  Scaled by styleStrength like the other
        // style params so the user's strength slider governs them too.
        float[] splitTone = null;
        if (mimicToneEnabled) {
            splitTone = extractSplitTone(params);
        }

        // Low-light brightness engine: aim the live scene toward the reference
        // photo's absolute brightness target.  Returns {evCompensation,
        // lutBrightness}; brightness correction is deliberately NOT scaled by
        // styleStrength (it must reach the target regardless of style intensity).
        float[] brightnessPlan = computeMimicBrightnessPlan(params);
        float mimicEv = brightnessPlan[0];
        float lutBrightness = brightnessPlan[1];

        // Camera-cluster baked-LUT path (format_version >= 3 records).
        // If the matched master photo has a camera_cluster_id and the preset
        // manager has the LUT available, compose <cluster LUT> + <photo tone
        // overlay> into one bitmap and push it directly to the GPU preview.
        // Camera2 ISO/shutter/EV/WB/AF still flow through applyDirectProSettings.
        boolean usedClusterLut = false;
        Integer clusterId = null;
        Object clusterIdObj = params.get("camera_cluster_id");
        if (clusterIdObj instanceof Number) {
            clusterId = ((Number) clusterIdObj).intValue();
        }
        if (filmSimulationEnabled && stylePresetManager != null && stylePresetManager.isAvailable()
                && clusterId != null && clusterId >= 0) {
            android.graphics.Bitmap base = stylePresetManager.loadClusterLut(clusterId);
            if (base != null) {
                float c  = contrast        != null ? contrast        : 0f;
                float h  = highlights      != null ? highlights      : 0f;
                float s  = shadows         != null ? shadows         : 0f;
                float sa = saturation      != null ? saturation      : 0f;
                float w  = highlightWarmth != null ? highlightWarmth : 0f;
                float t  = shadowTint      != null ? shadowTint      : 0f;
                android.graphics.Bitmap composed =
                        com.samsung.camera.intelligence.app.camera.LutComposer.compose(
                                base, c, h, s, sa, w, t, lutBrightness, splitTone);
                if (composed != null) {
                    cameraController.applyDirectLutBitmap(composed);
                    usedClusterLut = true;
                }
            }
        }
        if (!usedClusterLut) {
            // No cluster LUT (legacy v2 records or assets missing) — clear
            // any prior override so the next applyToneCurve writes the LUT.
            cameraController.clearLutOverride();
        }

        // Keep auto-exposure AND auto white balance so camera hardware
        // adapts to current lighting.  Reference photo WB was calibrated for
        // the original scene's illuminant; applying it verbatim to a
        // differently-lit scene causes heavy colour casts (e.g. green under
        // fluorescent lights when the reference was shot in tungsten).
        // The LUT highlight_warmth / shadow_tint already encode the colour
        // "style" and are safe to transfer across scenes.
        String focusStr = safeText(focusInput, "multi_point");

        cameraController.applyDirectProSettings(new CameraProSettings(
                null, null, mimicEv, "auto", null, focusStr, null, "matrix",
                contrast, highlights, shadows, saturation, highlightWarmth, shadowTint
        ));
        scheduleToneSettle();

        String shutterStr = shutter != null ? String.valueOf(shutter) : "";
        Float refEv = ev != null ? parseFloatOrDefault(String.valueOf(ev), 0f) : 0f;
        String cameraLabel = (clusterId != null && stylePresetManager != null)
                ? stylePresetManager.labelOf(clusterId) : "";

        // Snapshot the just-applied tone params + matched-photo info so the
        // next shutter press can drop them into _meta.txt for the comparison
        // snapshot.
        lastAppliedMimicMeta.clear();
        if (contrast        != null) lastAppliedMimicMeta.put("contrast", contrast);
        if (highlights      != null) lastAppliedMimicMeta.put("highlights", highlights);
        if (shadows         != null) lastAppliedMimicMeta.put("shadows", shadows);
        if (saturation      != null) lastAppliedMimicMeta.put("saturation", saturation);
        if (highlightWarmth != null) lastAppliedMimicMeta.put("highlight_warmth", highlightWarmth);
        if (shadowTint      != null) lastAppliedMimicMeta.put("shadow_tint", shadowTint);
        lastAppliedMimicMeta.put("style_strength", styleStrength);
        lastAppliedMimicMeta.put("mimic_tone_enabled", mimicToneEnabled);
        lastAppliedMimicMeta.put("film_simulation_enabled", filmSimulationEnabled);
        lastAppliedMimicMeta.put("used_cluster_lut", usedClusterLut);
        lastAppliedMimicMeta.put("mimic_brightness_enabled", mimicBrightnessEnabled);
        lastAppliedMimicMeta.put("mimic_brightness_bias", mimicBrightnessBias);
        if (Math.abs(mimicEv) > 0.001f) lastAppliedMimicMeta.put("mimic_ev", mimicEv);
        if (Math.abs(lutBrightness) > 0.1f) lastAppliedMimicMeta.put("mimic_lut_brightness", lutBrightness);
        if (clusterId != null) lastAppliedMimicMeta.put("camera_cluster_id", clusterId);
        if (cameraLabel != null && !cameraLabel.isEmpty()) lastAppliedMimicMeta.put("cluster_label", cameraLabel);
        if (overlay.getPhotoId() != null && !overlay.getPhotoId().isEmpty()) lastAppliedMimicMeta.put("photo_id", overlay.getPhotoId());
        if (overlay.getPhotographerName() != null) lastAppliedMimicMeta.put("photographer", overlay.getPhotographerName());
        if (overlay.getPhotoTitle() != null) lastAppliedMimicMeta.put("photo_title", overlay.getPhotoTitle());
        lastAppliedMimicReferenceLabel =
                (overlay.getPhotographerName() != null ? overlay.getPhotographerName() : "")
                + " \u00b7 "
                + (overlay.getPhotoTitle() != null ? overlay.getPhotoTitle() : "");
        String thumbnailUrl = overlay.getThumbnailUrl();
        if (thumbnailUrl != null) {
            thumbnailUrl = thumbnailUrl.trim().replace('\\', '/');
        }
        if (thumbnailUrl != null && !thumbnailUrl.isEmpty() && !thumbnailUrl.contains("://")) {
            lastAppliedMimicReferenceAsset = thumbnailUrl.startsWith("/")
                    ? thumbnailUrl.substring(1)
                    : thumbnailUrl;
            lastAppliedMimicReferenceUri = null;
        } else if (overlay.getPhotoId() != null && !overlay.getPhotoId().isEmpty()) {
            lastAppliedMimicReferenceAsset = "master_match/thumbnails/" + overlay.getPhotoId() + ".webp";
            lastAppliedMimicReferenceUri = null;
        } else {
            lastAppliedMimicReferenceAsset = null;
            lastAppliedMimicReferenceUri = null;
        }

        setStatus(getString(R.string.mimic_match_applied)
                + ": ISO=Auto shutter=Auto EV=0"
                + " (ref: " + iso + " " + shutterStr + " EV" + refEv + ")"
                + (contrast != null ? " C=" + contrast.intValue() : "")
                + (highlights != null ? " H=" + highlights.intValue() : "")
                + (shadows != null ? " Sh=" + shadows.intValue() : "")
                + (saturation != null ? " Sat=" + saturation.intValue() : "")
                + (highlightWarmth != null ? " W=" + highlightWarmth.intValue() : "")
                + (shadowTint != null ? " T=" + shadowTint.intValue() : "")
                + " str=" + Math.round(styleStrength * 100) + "%"
                + (usedClusterLut ? " LUT=" + cameraLabel : ""));
    }

    private Float scaleByStrength(Float value) {
        if (value == null) return null;
        return value * styleStrength;
    }

    /**
     * Build the 6-element split-tone array
     * {shadowWc, shadowGm, midWc, midGm, highWc, highGm} from overlay params,
     * scaled by {@link #styleStrength}.  Returns {@code null} when no band
     * carries a meaningful cast (so the native compose fast-path is preserved).
     */
    @Nullable
    private float[] extractSplitTone(Map<String, Object> params) {
        float[] st = new float[6];
        String[] keys = {
                "split_shadow_wc", "split_shadow_gm",
                "split_mid_wc", "split_mid_gm",
                "split_high_wc", "split_high_gm"
        };
        boolean any = false;
        for (int i = 0; i < 6; i++) {
            Float v = extractFloat(params, keys[i]);
            st[i] = (v != null ? v : 0f) * styleStrength;
            if (Math.abs(st[i]) > 1f) any = true;
        }
        return any ? st : null;
    }

    /**
     * Plan the Mimic low-light brightness lift toward the reference photo's
     * absolute brightness target.
     *
     * <p>Returns {@code {evCompensation, lutBrightness}} where
     * {@code lutBrightness} is in the [-100, +100] units consumed by
     * {@link com.samsung.camera.intelligence.app.camera.LutComposer}.  The
     * brightness gap (reference target − live scene brightness) is split into a
     * coarse, low-noise camera EV nudge (clamped to ±EV_MAX) plus a fine global
     * LUT lift that finishes the correction in real time.  A strong lift is only
     * engaged in low-light scenes; the manual bias slider shifts the target in
     * either direction.  Brightness correction is intentionally independent of
     * {@code styleStrength}.
     */
    private float[] computeMimicBrightnessPlan(Map<String, Object> params) {
        final float EV_MAX = 2.0f;          // clamp coarse sensor nudge
        final float LUT_MAX = 70f;          // clamp fine LUT lift (units of LutComposer)

        if (!mimicBrightnessEnabled) {
            return new float[]{0f, 0f};
        }

        Float target = extractFloat(params, "target_brightness");   // absolute 0..1
        FrameAnalysis analysis = latestFrameAnalysis;
        Float live = (analysis != null) ? analysis.getBrightnessValue() : null;
        String lighting = (analysis != null) ? analysis.getLightingCondition() : null;

        boolean lowLight = "low_light".equals(lighting) || "very_low_light".equals(lighting)
                || (live != null && live < 0.30f);

        // Base gap from the reference's absolute brightness target.  Without a
        // live reading or target we fall back to the manual bias alone.
        float gap = 0f;
        if (target != null && live != null) {
            gap = target - live;            // positive => scene is darker than ref
        }

        // Only pursue a meaningful auto-lift in low light; in well-lit scenes the
        // sensor AE already matches the reference key closely, and an aggressive
        // lift would only wash the image out.
        if (!lowLight) {
            gap *= 0.25f;
        }

        // Manual bias maps [-1, +1] to ±0.35 of brightness target.
        gap += mimicBrightnessBias * 0.35f;
        gap = clamp(gap, -0.6f, 0.6f);

        if (Math.abs(gap) < 0.02f) {
            return new float[]{0f, 0f};
        }

        // EV first (sensor-level, low noise): ~2.5 stops over the full gap,
        // clamped.  LUT lift finishes whatever EV could not reach.
        float ev = clamp(gap * 4.0f, -EV_MAX, EV_MAX);
        float evBrightnessEquiv = ev / 4.0f;          // brightness covered by EV
        float residual = gap - evBrightnessEquiv;      // remaining for the LUT
        float lutBrightness = clamp(residual * 220f, -LUT_MAX, LUT_MAX);

        return new float[]{ev, lutBrightness};
    }

    private static Float extractFloat(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).floatValue();
        }
        return null;
    }

    private void triggerMimicCapture() {
        if (!modelRuntimeController.isMasterMatchEnabled()) {
            Toast.makeText(this, R.string.mimic_no_master_match, Toast.LENGTH_LONG).show();
            return;
        }
        setStatus(getString(R.string.mimic_matching));
        final com.samsung.camera.intelligence.app.camera.CaptureExtras extras = buildMimicCaptureExtras();
        cameraController.capturePhoto(new CameraController.CaptureListener() {
            @Override
            public void onPhotoSaved(String path) {
                latestPhotoPath = path;
                runOnUiThread(() -> {
                    photoPreviewController.showPhotoPreview(path);
                    hideUiForPhotoPreview();
                });
                performMimicMatch(path);
            }

            @Override
            public void onVideoSaved(String path) { }

            @Override
            public void onCaptureError(String reason) {
                runOnUiThread(() -> setStatus("Capture failed: " + reason));
            }

            @Override
            public void onComparisonReady(String resultPath, String origPath,
                                          String refPath, String metaPath) {
                runOnUiThread(() -> openComparisonViewer(resultPath, origPath, refPath, metaPath));
            }
        }, null, extras);
    }

    /**
     * Build the {@link com.samsung.camera.intelligence.app.camera.CaptureExtras}
     * payload that drives comparison-snapshot saving + system-Gallery publish.
     * Returns {@code null} when the user disabled the toggle, or no Mimic
     * reference is currently selected (so we don't pollute the Gallery for
     * non-Mimic captures).
     */
    @androidx.annotation.Nullable
    private com.samsung.camera.intelligence.app.camera.CaptureExtras buildMimicCaptureExtras() {
        if (!saveCompareTriplet) return null;
        boolean inMimic = stateMachine.getCurrentMode() == CameraWorkflowStateMachine.Mode.MIMIC;
        boolean hasReference = lastAppliedMimicReferenceAsset != null
                || lastAppliedMimicReferenceUri != null
                || (mimicPanelController != null && mimicPanelController.getSelectedOverlay() != null);
        if (!inMimic && !hasReference) return null;
        com.samsung.camera.intelligence.app.camera.CaptureExtras extras =
                new com.samsung.camera.intelligence.app.camera.CaptureExtras();
        extras.saveOriginal = true;
        extras.referenceAssetPath = lastAppliedMimicReferenceAsset;
        extras.referenceUri = lastAppliedMimicReferenceUri;
        extras.referenceLabel = lastAppliedMimicReferenceLabel;
        if (!lastAppliedMimicMeta.isEmpty()) {
            extras.meta = new java.util.LinkedHashMap<>(lastAppliedMimicMeta);
        }
        return extras;
    }

    private void openComparisonViewer(String resultPath, String origPath,
                                      String refPath, String metaPath) {
        if (resultPath == null) return;
        try {
            android.content.Intent intent =
                    com.samsung.camera.intelligence.app.ui.MimicComparisonActivity.intent(
                            this, resultPath, origPath, refPath, metaPath);
            startActivity(intent);
            setStatus(getString(R.string.comparison_saved_status));
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "openComparisonViewer failed", t);
        }
    }

    private void performMimicMatch(String photoPath) {
        final MasterMatchOverlay selectedOverlay =
                mimicPanelController != null ? mimicPanelController.getSelectedOverlay() : null;
        new Thread(() -> {
            try {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap photo = android.graphics.BitmapFactory.decodeFile(photoPath, opts);
                if (photo == null) {
                    runOnUiThread(() -> setStatus("Failed to load photo for MasterMatch"));
                    return;
                }
                CameraIntelligenceManager.FrameResult result = intelligenceManager.processFrame(photo);
                photo.recycle();
                if (result == null || result.toolResult == null) {
                    runOnUiThread(() -> {
                        setStatus(getString(R.string.mimic_no_match));
                        Toast.makeText(MainActivity.this, R.string.mimic_no_match, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                // Build match info, preferring the currently selected Mimic card.
                StringBuilder matchInfo = new StringBuilder();
                matchInfo.append("Scene: ").append(result.frameAnalysis != null
                        ? titleCase(result.frameAnalysis.getSceneType()) : "Unknown");
                matchInfo.append("\nLighting: ").append(result.frameAnalysis != null
                        ? titleCase(result.frameAnalysis.getLightingCondition()) : "Unknown");
                matchInfo.append("\n\nRecommended parameters:");
                boolean hasParams = false;
                if (selectedOverlay != null && selectedOverlay.getProModeParams() != null
                        && !selectedOverlay.getProModeParams().isEmpty()) {
                    if (selectedOverlay.getPhotoTitle() != null && !selectedOverlay.getPhotoTitle().isEmpty()) {
                        matchInfo.append("\n• Title: ").append(selectedOverlay.getPhotoTitle());
                    }
                    if (selectedOverlay.getPhotographerName() != null && !selectedOverlay.getPhotographerName().isEmpty()) {
                        matchInfo.append("\n• Photographer: ").append(selectedOverlay.getPhotographerName());
                    }
                    matchInfo.append("\n• Similarity: ")
                            .append(String.format(Locale.US, "%.0f%%", selectedOverlay.getSimilarityScore() * 100f));
                    if (selectedOverlay.getStyleTags() != null && !selectedOverlay.getStyleTags().isEmpty()) {
                        matchInfo.append("\n• Style tags: ")
                                .append(TextUtils.join(", ", selectedOverlay.getStyleTags()));
                    }
                    for (Map.Entry<String, Object> entry : selectedOverlay.getProModeParams().entrySet()) {
                        matchInfo.append("\n    ").append(entry.getKey()).append(": ").append(entry.getValue());
                    }
                    hasParams = true;
                } else if (result.toolResult.getTools() != null) {
                    for (ToolRecommendation tool : result.toolResult.getTools()) {
                        if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                            matchInfo.append("\n• ").append(tool.getToolName());
                            for (Map.Entry<String, Object> entry : tool.getParameters().entrySet()) {
                                matchInfo.append("\n    ").append(entry.getKey()).append(": ").append(entry.getValue());
                            }
                            hasParams = true;
                        }
                    }
                }
                if (!hasParams) {
                    matchInfo.append("\n  No specific parameters matched.");
                }

                final String info = matchInfo.toString();
                final ToolRecommendationResult finalResult = result.toolResult;
                final MasterMatchOverlay finalSelectedOverlay = selectedOverlay;
                runOnUiThread(() -> {
                    View dialogView = getLayoutInflater().inflate(R.layout.dialog_mode_switch, null);
                    ((TextView) dialogView.findViewById(R.id.dialog_title))
                            .setText(R.string.mimic_match_title);
                    ((TextView) dialogView.findViewById(R.id.dialog_message))
                            .setText(info);
                    TextView positiveBtn = dialogView.findViewById(R.id.dialog_positive);
                    TextView negativeBtn = dialogView.findViewById(R.id.dialog_negative);
                    positiveBtn.setText(R.string.mimic_match_apply);
                    negativeBtn.setText(android.R.string.cancel);

                    AlertDialog matchDialog = new AlertDialog.Builder(this, R.style.Theme_IntelligentCamera_TransparentDialog)
                            .setView(dialogView)
                            .create();
                    if (matchDialog.getWindow() != null) {
                        matchDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                    }
                    positiveBtn.setOnClickListener(v -> {
                        // Apply the currently selected Mimic card when available.
                        if (finalSelectedOverlay != null) {
                            applyMimicOverlayParams(finalSelectedOverlay);
                        } else {
                            fillProParamsFromRecommendation(finalResult);
                            applyCurrentProInputs(false, getString(R.string.mimic_match_applied));
                        }
                        matchDialog.dismiss();
                    });
                    negativeBtn.setOnClickListener(v -> matchDialog.dismiss());
                    matchDialog.show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("MasterMatch error: " + e.getMessage()));
            }
        }, "mimic-match").start();
    }

    // ── Parameter Change Description for Mode Suggestion ─────────────

    private String buildParameterChangeDescription(ToolRecommendationResult result) {
        if (result == null || result.getTools() == null) {
            return "AI recommendation";
        }
        List<String> changes = new ArrayList<>();
        for (ToolRecommendation tool : result.getTools()) {
            String name = tool.getToolName();
            if (name == null || "Camera_ChangeMode".equals(name)) continue;
            Map<String, Object> params = tool.getParameters();
            if (params == null || params.isEmpty()) continue;
            switch (name) {
                case "Camera_ChangeIso":
                    Object iso = firstPresent(params, "IsoValue", "iso");
                    if (iso != null) changes.add("ISO → " + iso);
                    break;
                case "Camera_ChangeShutterSpeed":
                    Object shutter = firstPresent(params, "ShutterSpeed", "shutter_speed", "shutter");
                    if (shutter != null) changes.add("Shutter → " + shutter);
                    break;
                case "Camera_ChangeEV":
                    Object ev = firstPresent(params, "EvValue", "ev");
                    if (ev != null) changes.add("EV → " + ev);
                    break;
                case "Camera_ChangeWhiteBalance":
                    Object wb = firstPresent(params, "WbValue", "mode", "white_balance");
                    if (wb != null) changes.add("WB → " + wb);
                    break;
                case "Camera_ChangeFocusMode":
                    Object focus = firstPresent(params, "FocusMode", "mode", "focus_mode");
                    if (focus != null) changes.add("Focus → " + focus);
                    break;
                case "Camera_HDR":
                    changes.add("HDR on");
                    break;
                case "Camera_Flash":
                    Object flash = firstPresent(params, "mode", "enable");
                    if (flash != null) changes.add("Flash → " + flash);
                    break;
                default:
                    if (name.startsWith("Camera_")) {
                        String paramStr = params.toString();
                        if (paramStr.length() > 60) paramStr = paramStr.substring(0, 57) + "...";
                        changes.add(name.replace("Camera_", "") + ": " + paramStr);
                    }
                    break;
            }
        }
        if (changes.isEmpty()) {
            return "AI recommendation based on scene analysis";
        }
        return "Parameters: " + android.text.TextUtils.join(", ", changes);
    }

    // ── Pro Parameter Sync on Mode Switch ────────────────────────────

    private void syncProInputsFromCamera() {
        if (cameraController == null) return;
        suppressProInputWatcher = true;
        CameraProSettings current = cameraController.getCurrentProSettings();
        if (current != null) {
            if (current.getIso() > 0) {
                isoInput.setText(String.valueOf(current.getIso()));
            }
            if (current.getShutterSpeedNs() > 0) {
                shutterInput.setText(CameraProSettings.formatShutterFromNs(current.getShutterSpeedNs()));
            }
            if (current.getEvCompensation() != null) {
                evInput.setText(String.format(Locale.US, "%.1f", current.getEvCompensation()));
            }
            if (current.getWhiteBalanceMode() != null && !current.getWhiteBalanceMode().isEmpty()) {
                wbInput.setText(current.getWhiteBalanceMode());
            }
            if (current.getFocusMode() != null && !current.getFocusMode().isEmpty()) {
                focusInput.setText(current.getFocusMode());
            }
        }
        suppressProInputWatcher = false;
    }

    private String buildPhotoPreviewInfoText() {
        CameraWorkflowStateMachine.Mode mode = stateMachine.getCurrentMode();
        if (mode == null) {
            mode = CameraWorkflowStateMachine.Mode.PHOTO;
        }

        CameraProSettings current = cameraController != null
                ? cameraController.getCurrentProSettings() : null;
        ToolRecommendationResult tr = lastToolResult;
        SceneAnalysisResult sa = tr != null ? tr.getSceneAnalysis() : null;

        String modeLabel = titleCase(stateMachine.getCurrentModeName());
        String resolution = "Unknown";
        String scene = "Unknown";
        String lighting = "Unknown";

        if (sa != null) {
            CameraResolution rr = sa.getRecommendedResolution();
            if (rr != null && rr.getValue() != null) {
                resolution = rr.getValue();
            }
            scene = titleCase(sa.getSceneType() == null ? null : sa.getSceneType().getValue());
            lighting = titleCase(sa.getLightingCondition() == null ? null : sa.getLightingCondition().getValue());
        }

        String iso = isoInput != null ? safeText(isoInput, "-") : "-";
        String shutter = shutterInput != null ? safeText(shutterInput, "-") : "-";
        String ev = evInput != null ? safeText(evInput, "0.0") : "0.0";

        if ((iso == null || iso.isEmpty() || "-".equals(iso)) && current != null && current.getIso() > 0) {
            iso = String.valueOf(current.getIso());
        }
        if ((shutter == null || shutter.isEmpty() || "-".equals(shutter))
                && current != null && current.getShutterSpeedNs() > 0) {
            shutter = CameraProSettings.formatShutterFromNs(current.getShutterSpeedNs());
        }
        if ((ev == null || ev.isEmpty() || "-".equals(ev))
                && current != null && current.getEvCompensation() != null) {
            ev = String.format(Locale.US, "%.1f", current.getEvCompensation());
        }

        if (mode == CameraWorkflowStateMachine.Mode.MIMIC && mimicPanelController != null
                && mimicPanelController.getSelectedOverlay() != null
                && mimicPanelController.getSelectedOverlay().getProModeParams() != null) {
            Map<String, Object> params = mimicPanelController.getSelectedOverlay().getProModeParams();
            Object pIso = params.get("iso");
            Object pShutter = params.get("shutter_speed");
            Object pEv = params.get("ev");
            if (pIso != null) iso = String.valueOf(pIso);
            if (pShutter != null) shutter = String.valueOf(pShutter);
            if (pEv != null) ev = String.valueOf(pEv);
        }

        StringBuilder info = new StringBuilder();
        info.append("Mode ").append(modeLabel)
                .append("  Res ").append(resolution)
                .append("  Scene ").append(scene);

        if (mode == CameraWorkflowStateMachine.Mode.PRO || mode == CameraWorkflowStateMachine.Mode.PRO_VIDEO
                || mode == CameraWorkflowStateMachine.Mode.MIMIC) {
            info.append("\nISO ").append(iso)
                    .append("  Shutter ").append(shutter)
                    .append("  EV ").append(ev);
            if (mode == CameraWorkflowStateMachine.Mode.MIMIC) {
                info.append("  Src Pro");
            }
        } else {
            info.append("\nLtg ").append(lighting);
        }
        return info.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerLevelSensor();
        if (glPreview != null) {
            glPreview.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (glPreview != null) {
            glPreview.onPause();
        }
        unregisterLevelSensor();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecordingUi();
        uiHandler.removeCallbacks(delayedProInputApply);
        unregisterLevelSensor();
        styleStrawExecutor.shutdownNow();
        portraitSegmentationExecutor.shutdownNow();
        filterPreviewExecutor.shutdownNow();
        if (latestFilterPreviewSource != null) {
            latestFilterPreviewSource.recycle();
            latestFilterPreviewSource = null;
        }
        if (cameraController != null) {
            cameraController.stop();
        }
        if (modelRuntimeController != null) {
            modelRuntimeController.release();
        }
        if (portraitSegmentationRunner != null) {
            portraitSegmentationRunner.close();
        }
        if (portraitFallbackDetector != null) {
            portraitFallbackDetector.close();
        }
    }
}
