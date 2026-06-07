package com.samsung.camera.intelligence.config;

import java.util.*;

/**
 * Central repository of all tool definitions and mappings.
 * Ported from Python ToolMappings class in config/tool_mappings.py.
 *
 * Contains 40+ tool definitions across Camera, ExpertRaw, Gallery, and PhotoEditor servers.
 */
public class ToolMappings {

    private static final Map<String, ToolDefinition> CAMERA_TOOLS = new LinkedHashMap<>();
    private static final Map<String, ToolDefinition> EXPERT_RAW_TOOLS = new LinkedHashMap<>();
    private static final Map<String, ToolDefinition> GALLERY_TOOLS = new LinkedHashMap<>();

    static {
        initCameraTools();
        initExpertRawTools();
        initGalleryTools();
    }

    // ---------------------------------------------------------------
    // Camera App Tools
    // ---------------------------------------------------------------
    private static void initCameraTools() {
        CAMERA_TOOLS.put("Camera_ChangeMode", ToolDefinition.simple(
                "Camera_ChangeMode", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("ModeName",
                                Arrays.asList("Portrait", "Photo", "Video", "Pro", "Night",
                                        "Single_take", "Hyperlapse", "Slow_motion",
                                        "Dual_recording", "Pro_video", "Portrait_video",
                                        "Food", "Panorama"),
                                "Camera mode to switch to"),
                        ToolParameter.optionalIntParam("ModeCameraType", 0, 0, 1,
                                "0=Rear, 1=Front")
                ),
                "Switch camera shooting mode"
        ));

        CAMERA_TOOLS.put("Camera_ChangeCamera", ToolDefinition.simple(
                "Camera_ChangeCamera", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("direction",
                                Arrays.asList("front", "rear"),
                                "Camera direction")
                ),
                "Switch between front and rear camera"
        ));

        CAMERA_TOOLS.put("Camera_ChangeIso", ToolDefinition.withMode(
                "Camera_ChangeIso", "Camera",
                Arrays.asList(
                        ToolParameter.intParam("iso", 50, 6400, "ISO sensitivity value")
                ),
                "Adjust camera ISO",
                Arrays.asList("Pro", "Pro_video"),
                Arrays.asList("ExpertRaw_*")
        ));

        CAMERA_TOOLS.put("Camera_ChangeShutterSpeed", ToolDefinition.withMode(
                "Camera_ChangeShutterSpeed", "Camera",
                Arrays.asList(
                        ToolParameter.strParam("shutter_speed", "Shutter speed (e.g., '1/500', '1/60', '2')")
                ),
                "Adjust shutter speed",
                Arrays.asList("Pro", "Pro_video"),
                Arrays.asList("ExpertRaw_*")
        ));

        CAMERA_TOOLS.put("Camera_Guideline", ToolDefinition.simple(
                "Camera_Guideline", "Camera",
                Arrays.asList(
                        ToolParameter.boolParam("enable", true, "Enable/disable composition guidelines")
                ),
                "Toggle composition guidelines"
        ));

        CAMERA_TOOLS.put("Camera_MotionPhoto", ToolDefinition.simple(
                "Camera_MotionPhoto", "Camera",
                Arrays.asList(
                        ToolParameter.boolParam("enable", true, "Enable/disable motion photo")
                ),
                "Capture short video clip with photo"
        ));

        CAMERA_TOOLS.put("Camera_CaptureWithCurrentState", ToolDefinition.simple(
                "Camera_CaptureWithCurrentState", "Camera",
                Collections.emptyList(),
                "Capture photo with current settings"
        ));

        CAMERA_TOOLS.put("CaptureWithMode", ToolDefinition.simple(
                "CaptureWithMode", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("ModeName",
                                Arrays.asList("Portrait", "Photo", "Video", "Pro", "Night",
                                        "Single_take", "Hyperlapse", "Slow_motion",
                                        "Dual_recording", "Pro_video", "Portrait_video",
                                        "Food", "Panorama"),
                                "Camera mode to use for capture"),
                        ToolParameter.optionalIntParam("ModeCameraType", 0, 0, 1,
                                "0=Rear, 1=Front")
                ),
                "Change mode and capture photo in one step"
        ));

        CAMERA_TOOLS.put("DetectScene", ToolDefinition.simple(
                "DetectScene", "Camera",
                Collections.emptyList(),
                "Detect current scene from camera preview (returns Base64 encoded image)"
        ));

        CAMERA_TOOLS.put("Camera_Flash", ToolDefinition.simple(
                "Camera_Flash", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("mode",
                                Arrays.asList("off", "on", "auto"),
                                "Flash mode")
                ),
                "Control camera flash settings"
        ));

        CAMERA_TOOLS.put("Camera_HDR", ToolDefinition.simple(
                "Camera_HDR", "Camera",
                Arrays.asList(
                        ToolParameter.boolParam("enable", true, "Enable/disable HDR")
                ),
                "Toggle HDR mode for high dynamic range"
        ));

        CAMERA_TOOLS.put("Camera_BurstMode", ToolDefinition.simple(
                "Camera_BurstMode", "Camera",
                Arrays.asList(
                        ToolParameter.boolParam("enable", true, "Enable/disable burst mode")
                ),
                "Enable burst mode for continuous shooting"
        ));

        CAMERA_TOOLS.put("Camera_ChangeZoom", ToolDefinition.simple(
                "Camera_ChangeZoom", "Camera",
                Arrays.asList(
                        ToolParameter.floatParam("zoom_level", 0.5f, 10.0f, 1.0f,
                                "Zoom level (0.5x ultra-wide to 10x telephoto)")
                ),
                "Adjust camera zoom level"
        ));

        CAMERA_TOOLS.put("Camera_SetTimer", ToolDefinition.simple(
                "Camera_SetTimer", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("seconds",
                                Arrays.asList("0", "2", "3", "5", "10"),
                                "Timer delay in seconds (0 = off)")
                ),
                "Set capture timer delay"
        ));

        CAMERA_TOOLS.put("Camera_VideoStabilization", ToolDefinition.simple(
                "Camera_VideoStabilization", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("mode",
                                Arrays.asList("off", "standard", "super_steady"),
                                "Video stabilization mode")
                ),
                "Configure video stabilization"
        ));

        CAMERA_TOOLS.put("Camera_VideoFPS", ToolDefinition.simple(
                "Camera_VideoFPS", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("fps",
                                Arrays.asList("30", "60", "120", "240"),
                                "Video frame rate (frames per second)")
                ),
                "Set video recording frame rate"
        ));

        CAMERA_TOOLS.put("Camera_AspectRatio", ToolDefinition.simple(
                "Camera_AspectRatio", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("ratio",
                                Arrays.asList("1:1", "3:4", "9:16", "full"),
                                "Photo aspect ratio")
                ),
                "Change photo aspect ratio"
        ));

        CAMERA_TOOLS.put("Camera_ChangeResolution", new ToolDefinition(
                "Camera_ChangeResolution", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("resolution",
                                Arrays.asList("12MP", "50MP", "108MP", "200MP"),
                                "Photo capture resolution (megapixels)")
                ),
                "Change photo capture resolution for optimal detail vs file size",
                null, Arrays.asList("ExpertRaw_*"), null, false
        ));

        // Pro Mode Parameter Tools
        CAMERA_TOOLS.put("Camera_ChangeEV", ToolDefinition.withMode(
                "Camera_ChangeEV", "Camera",
                Arrays.asList(
                        ToolParameter.floatParam("ev", -3.0f, 3.0f, 0.0f,
                                "Exposure compensation value (-3.0 to +3.0)")
                ),
                "Adjust exposure compensation in Pro mode",
                Arrays.asList("Pro", "Pro_video"),
                Arrays.asList("ExpertRaw_*")
        ));

        CAMERA_TOOLS.put("Camera_ChangeWhiteBalance", ToolDefinition.withMode(
                "Camera_ChangeWhiteBalance", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("mode",
                                Arrays.asList("auto", "daylight", "cloudy", "tungsten",
                                        "fluorescent", "shade", "manual"),
                                "White balance mode"),
                        new ToolParameter("kelvin", "int", false, null,
                                2300f, 10000f, null,
                                "Color temperature in Kelvin (only for manual mode)")
                ),
                "Adjust white balance in Pro mode",
                Arrays.asList("Pro", "Pro_video"),
                Arrays.asList("ExpertRaw_*")
        ));

        CAMERA_TOOLS.put("Camera_ChangeFocusMode", ToolDefinition.withMode(
                "Camera_ChangeFocusMode", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("mode",
                                Arrays.asList("center", "multi_point", "manual"),
                                "Focus mode"),
                        ToolParameter.optionalFloatParam("distance", 0.5f, 0.0f, 1.0f,
                                "Manual focus distance (0=near, 1=far)")
                ),
                "Adjust focus mode in Pro mode",
                Arrays.asList("Pro", "Pro_video"),
                Arrays.asList("ExpertRaw_*")
        ));

        CAMERA_TOOLS.put("Camera_ChangeMeteringMode", ToolDefinition.withMode(
                "Camera_ChangeMeteringMode", "Camera",
                Arrays.asList(
                        ToolParameter.enumParam("mode",
                                Arrays.asList("center_weighted", "matrix", "spot"),
                                "Metering mode")
                ),
                "Adjust metering mode in Pro mode",
                Arrays.asList("Pro", "Pro_video"),
                Arrays.asList("ExpertRaw_*")
        ));
    }

    // ---------------------------------------------------------------
    // Expert Raw Tools
    // ---------------------------------------------------------------
    private static void initExpertRawTools() {
        List<String> incompatCamera = Arrays.asList("Camera_*");

        EXPERT_RAW_TOOLS.put("ExpertRaw_ChangeToAstroMode", new ToolDefinition(
                "ExpertRaw_ChangeToAstroMode", "ExpertRaw",
                Collections.emptyList(),
                "Switch to astrophotography mode",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_ChangeToAstroPortraitMode", new ToolDefinition(
                "ExpertRaw_ChangeToAstroPortraitMode", "ExpertRaw",
                Collections.emptyList(),
                "Switch to astro portrait mode",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_ChangeToMultiExposureMode", new ToolDefinition(
                "ExpertRaw_ChangeToMultiExposureMode", "ExpertRaw",
                Collections.emptyList(),
                "Switch to multi-exposure HDR mode",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_ChangeToNdFilterMode", new ToolDefinition(
                "ExpertRaw_ChangeToNdFilterMode", "ExpertRaw",
                Arrays.asList(
                        new ToolParameter("strength", "int", false, 16, null, null,
                                Arrays.asList("2", "4", "8", "16", "32", "64", "256", "1000"),
                                "ND filter strength")
                ),
                "Switch to ND filter mode for motion blur",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_ChangeToVirtualApertureMode", new ToolDefinition(
                "ExpertRaw_ChangeToVirtualApertureMode", "ExpertRaw",
                Collections.emptyList(),
                "Switch to virtual aperture mode for bokeh control",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_ChangeIso", new ToolDefinition(
                "ExpertRaw_ChangeIso", "ExpertRaw",
                Arrays.asList(
                        ToolParameter.intParam("iso", 50, 6400, "ISO sensitivity value")
                ),
                "Adjust ISO in Expert Raw",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_ChangeShutterSpeed", new ToolDefinition(
                "ExpertRaw_ChangeShutterSpeed", "ExpertRaw",
                Arrays.asList(
                        ToolParameter.strParam("shutter_speed", "Shutter speed value")
                ),
                "Adjust shutter speed in Expert Raw",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("CaptureWithCurrentState", new ToolDefinition(
                "CaptureWithCurrentState", "ExpertRaw",
                Collections.emptyList(),
                "Capture RAW photo with current settings",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_DetectScene", new ToolDefinition(
                "ExpertRaw_DetectScene", "ExpertRaw",
                Collections.emptyList(),
                "Detect current scene from Expert Raw preview",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_CheckLabs", new ToolDefinition(
                "ExpertRaw_CheckLabs", "ExpertRaw",
                Collections.emptyList(),
                "Check which labs/modes are supported on this device",
                null, incompatCamera, null, false
        ));

        EXPERT_RAW_TOOLS.put("ExpertRaw_CheckPonFile", new ToolDefinition(
                "ExpertRaw_CheckPonFile", "ExpertRaw",
                Collections.emptyList(),
                "Check if PON file is read successfully",
                null, incompatCamera, null, false
        ));
    }

    // ---------------------------------------------------------------
    // Gallery / Photo Editor Tools
    // ---------------------------------------------------------------
    private static void initGalleryTools() {
        List<String> incompatCapture = Arrays.asList("Camera_*", "ExpertRaw_*");

        GALLERY_TOOLS.put("Gallery_ObjectRemover", ToolDefinition.simple(
                "Gallery_ObjectRemover", "Gallery",
                Collections.emptyList(),
                "Remove unwanted objects from photo"
        ));

        GALLERY_TOOLS.put("Gallery_AutoFit", ToolDefinition.simple(
                "Gallery_AutoFit", "Gallery",
                Collections.emptyList(),
                "Auto-enhance photo quality"
        ));

        GALLERY_TOOLS.put("Gallery_AutoTilt", ToolDefinition.simple(
                "Gallery_AutoTilt", "Gallery",
                Collections.emptyList(),
                "Automatically straighten tilted photo"
        ));

        GALLERY_TOOLS.put("PhotoEditor_CompositionEnhancer", new ToolDefinition(
                "PhotoEditor_CompositionEnhancer", "PhotoEditor",
                Arrays.asList(
                        new ToolParameter("mode", "enum", false, "auto",
                                null, null,
                                Arrays.asList("auto", "rule_of_thirds", "centered", "minimalist"),
                                "Composition preset to apply")
                ),
                "AI-powered composition enhancement (crop/reframe/perspective)",
                null, incompatCapture, null, false
        ));

        GALLERY_TOOLS.put("PhotoEditor_RemoveShadow", new ToolDefinition(
                "PhotoEditor_RemoveShadow", "PhotoEditor",
                Collections.emptyList(),
                "Remove unwanted shadows",
                null, incompatCapture, null, false
        ));

        GALLERY_TOOLS.put("PhotoEditor_GenAIAutoTilt", new ToolDefinition(
                "PhotoEditor_GenAIAutoTilt", "PhotoEditor",
                Collections.emptyList(),
                "AI-powered straightening with content preservation",
                null, null, null, true
        ));

        GALLERY_TOOLS.put("PhotoEditor_removeReflection", new ToolDefinition(
                "PhotoEditor_removeReflection", "PhotoEditor",
                Collections.emptyList(),
                "Remove glass/window reflections",
                null, incompatCapture, null, false
        ));

        GALLERY_TOOLS.put("PhotoEditor_removeBackgroundPeople", new ToolDefinition(
                "PhotoEditor_removeBackgroundPeople", "PhotoEditor",
                Collections.emptyList(),
                "Remove unwanted people from background",
                null, incompatCapture, null, true
        ));

        GALLERY_TOOLS.put("PhotoEditor_RemoveFlare", new ToolDefinition(
                "PhotoEditor_RemoveFlare", "PhotoEditor",
                Collections.emptyList(),
                "Remove lens flare and light artifacts",
                null, incompatCapture, null, false
        ));

        GALLERY_TOOLS.put("PhotoEditor_SmartCrop", ToolDefinition.simple(
                "PhotoEditor_SmartCrop", "PhotoEditor",
                Arrays.asList(
                        new ToolParameter("aspect_ratio", "enum", false, "auto",
                                null, null,
                                Arrays.asList("1:1", "4:3", "3:4", "16:9", "9:16", "original", "auto"),
                                "Target aspect ratio for cropping")
                ),
                "AI-powered smart crop for better composition"
        ));

        GALLERY_TOOLS.put("PhotoEditor_Recompose", new ToolDefinition(
                "PhotoEditor_Recompose", "PhotoEditor",
                Arrays.asList(
                        ToolParameter.boolParam("apply_rule_of_thirds", true,
                                "Apply rule of thirds composition")
                ),
                "AI-powered recomposition for improved visual balance",
                null, incompatCapture, null, true
        ));

        GALLERY_TOOLS.put("Gallery_Crop", ToolDefinition.simple(
                "Gallery_Crop", "Gallery",
                Arrays.asList(
                        ToolParameter.optionalFloatParam("x", 0.0f, 0.0f, 1.0f,
                                "Crop start X (normalized 0-1)"),
                        ToolParameter.optionalFloatParam("y", 0.0f, 0.0f, 1.0f,
                                "Crop start Y (normalized 0-1)"),
                        ToolParameter.optionalFloatParam("width", 1.0f, 0.0f, 1.0f,
                                "Crop width (normalized 0-1)"),
                        ToolParameter.optionalFloatParam("height", 1.0f, 0.0f, 1.0f,
                                "Crop height (normalized 0-1)")
                ),
                "Manual crop with specified coordinates"
        ));

        GALLERY_TOOLS.put("PhotoEditor_GenAIExpand", new ToolDefinition(
                "PhotoEditor_GenAIExpand", "PhotoEditor",
                Arrays.asList(
                        new ToolParameter("direction", "enum", false, "all",
                                null, null,
                                Arrays.asList("top", "bottom", "left", "right", "all"),
                                "Direction to expand the image")
                ),
                "AI-powered image expansion for better framing",
                null, null, null, true
        ));
    }

    // ---------------------------------------------------------------
    // Public accessors
    // ---------------------------------------------------------------

    /** Get all tool definitions across all servers. */
    public static Map<String, ToolDefinition> getAllTools() {
        Map<String, ToolDefinition> all = new LinkedHashMap<>();
        all.putAll(CAMERA_TOOLS);
        all.putAll(EXPERT_RAW_TOOLS);
        all.putAll(GALLERY_TOOLS);
        return all;
    }

    /** Get a specific tool definition by name. */
    public static ToolDefinition getTool(String toolName) {
        return getAllTools().get(toolName);
    }

    /** Get all tools for a specific server. */
    public static Map<String, ToolDefinition> getToolsByServer(String server) {
        Map<String, ToolDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, ToolDefinition> entry : getAllTools().entrySet()) {
            if (entry.getValue().getServer().equals(server)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    // ---------------------------------------------------------------
    // Tool sequence validation
    // ---------------------------------------------------------------

    /**
     * Validate a sequence of tool names and return any conflicts.
     * Ported from Python validate_tool_sequence().
     *
     * @param toolNames Ordered list of tool names
     * @return List of conflict/error messages (empty if valid)
     */
    public static List<String> validateToolSequence(List<String> toolNames) {
        return validateToolSequenceWithParams(toolNames, null);
    }

    /**
     * Validate a sequence of tools with optional params.
     *
     * @param toolNames Ordered list of tool names
     * @param paramsMap Optional map of toolName → parameters map (for mode detection)
     * @return List of conflict/error messages
     */
    public static List<String> validateToolSequenceWithParams(
            List<String> toolNames,
            Map<String, Map<String, Object>> paramsMap) {

        List<String> conflicts = new ArrayList<>();
        String activeMode = null;
        String activeServer = null;
        List<String> usedToolNames = new ArrayList<>();

        for (String toolName : toolNames) {
            if (toolName == null || toolName.isEmpty()) {
                conflicts.add("Unknown tool entry (missing name)");
                continue;
            }

            ToolDefinition tool = getTool(toolName);
            if (tool == null) {
                conflicts.add("Unknown tool: " + toolName);
                continue;
            }

            boolean isPostProcessing = tool.getServer().equals("Gallery")
                    || tool.getServer().equals("PhotoEditor");

            // Check incompatible_with patterns
            for (String pattern : tool.getIncompatibleWith()) {
                for (String prevName : usedToolNames) {
                    ToolDefinition prevTool = getTool(prevName);
                    if (isPostProcessing && prevTool != null
                            && (prevTool.getServer().equals("Camera")
                            || prevTool.getServer().equals("ExpertRaw"))) {
                        continue; // Allow post-processing after capture
                    }
                    if (matchesPattern(prevName, pattern)) {
                        conflicts.add(toolName + " incompatible with previously used " + prevName);
                        break;
                    }
                }
            }

            // Check reverse incompatibilities
            for (String prevName : usedToolNames) {
                ToolDefinition prevTool = getTool(prevName);
                if (prevTool != null) {
                    if (isPostProcessing && (prevTool.getServer().equals("Camera")
                            || prevTool.getServer().equals("ExpertRaw"))) {
                        continue;
                    }
                    for (String pattern : prevTool.getIncompatibleWith()) {
                        if (matchesPattern(toolName, pattern)) {
                            conflicts.add(toolName + " blocked by " + prevName + "'s incompatible_with");
                            break;
                        }
                    }
                }
            }

            // Check server compatibility
            if (activeServer != null && !tool.getServer().equals(activeServer)) {
                if (activeServer.equals("ExpertRaw") || activeServer.equals("Camera")) {
                    if (tool.getServer().equals("Gallery") || tool.getServer().equals("PhotoEditor")) {
                        // Allow post-processing after capture
                    } else {
                        conflicts.add("Cannot mix " + activeServer + " and " + tool.getServer() + " tools");
                    }
                } else if ((activeServer.equals("Gallery") || activeServer.equals("PhotoEditor"))
                        && (tool.getServer().equals("Camera") || tool.getServer().equals("ExpertRaw"))) {
                    conflicts.add("Cannot return to " + tool.getServer() + " after post-processing");
                }
            }

            // Check mode requirements
            if (tool.getRequiresMode() != null && !tool.getRequiresMode().isEmpty()) {
                if (activeMode == null || !tool.getRequiresMode().contains(activeMode)) {
                    conflicts.add(toolName + " requires mode in " + tool.getRequiresMode());
                }
            }

            // Update active context
            if (toolName.contains("ChangeMode") || toolName.equals("CaptureWithMode")) {
                if (paramsMap != null && paramsMap.containsKey(toolName)) {
                    Object modeName = paramsMap.get(toolName).get("ModeName");
                    if (modeName instanceof String) {
                        activeMode = (String) modeName;
                    }
                }
            }
            if (Arrays.asList("ExpertRaw", "Camera", "Gallery", "PhotoEditor")
                    .contains(tool.getServer())) {
                activeServer = tool.getServer();
            }
            usedToolNames.add(toolName);
        }

        return conflicts;
    }

    /**
     * Check if a tool name matches a pattern (supports "_*" glob suffix).
     */
    private static boolean matchesPattern(String name, String pattern) {
        if (pattern.endsWith("_*")) {
            return name.startsWith(pattern.substring(0, pattern.length() - 2));
        }
        return name.equals(pattern);
    }
}
