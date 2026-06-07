package com.samsung.camera.intelligence.config;

import java.util.Arrays;
import java.util.List;

/**
 * Definition of a camera/editor tool.
 * Ported from Python ToolDefinition dataclass in config/tool_mappings.py.
 */
public class ToolDefinition {

    private final String toolName;
    private final String server; // "Camera", "ExpertRaw", "Gallery", "PhotoEditor"
    private final List<ToolParameter> parameters;
    private final String description;
    private final List<String> prerequisites;
    private final List<String> incompatibleWith;
    private final List<String> requiresMode; // null = no mode requirement
    private final boolean requiresSamsungAccount;

    public ToolDefinition(String toolName, String server,
                          List<ToolParameter> parameters, String description,
                          List<String> prerequisites, List<String> incompatibleWith,
                          List<String> requiresMode, boolean requiresSamsungAccount) {
        this.toolName = toolName;
        this.server = server;
        this.parameters = parameters != null ? parameters : Arrays.asList();
        this.description = description != null ? description : "";
        this.prerequisites = prerequisites != null ? prerequisites : Arrays.asList();
        this.incompatibleWith = incompatibleWith != null ? incompatibleWith : Arrays.asList();
        this.requiresMode = requiresMode;
        this.requiresSamsungAccount = requiresSamsungAccount;
    }

    /** Convenience: simple tool with no restrictions. */
    public static ToolDefinition simple(String toolName, String server,
                                        List<ToolParameter> params, String description) {
        return new ToolDefinition(toolName, server, params, description, null, null, null, false);
    }

    /** Convenience: tool requiring specific mode(s). */
    public static ToolDefinition withMode(String toolName, String server,
                                          List<ToolParameter> params, String description,
                                          List<String> requiresMode,
                                          List<String> incompatibleWith) {
        return new ToolDefinition(toolName, server, params, description,
                null, incompatibleWith, requiresMode, false);
    }

    // Getters
    public String getToolName() { return toolName; }
    public String getServer() { return server; }
    public List<ToolParameter> getParameters() { return parameters; }
    public String getDescription() { return description; }
    public List<String> getPrerequisites() { return prerequisites; }
    public List<String> getIncompatibleWith() { return incompatibleWith; }
    public List<String> getRequiresMode() { return requiresMode; }
    public boolean isRequiresSamsungAccount() { return requiresSamsungAccount; }
}
