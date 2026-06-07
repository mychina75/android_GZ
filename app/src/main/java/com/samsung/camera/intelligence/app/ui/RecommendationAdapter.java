package com.samsung.camera.intelligence.app.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.samsung.camera.intelligence.app.R;
import com.samsung.camera.intelligence.models.ToolRecommendation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecommendationAdapter extends RecyclerView.Adapter<RecommendationAdapter.Holder> {

    private final List<ToolRecommendation> items = new ArrayList<>();
    private String highlightedToolName;

    public void submit(List<ToolRecommendation> tools) {
        items.clear();
        if (tools != null) {
            items.addAll(tools);
        }
        notifyDataSetChanged();
    }

    public void setHighlightedToolName(String toolName) {
        highlightedToolName = toolName;
        notifyDataSetChanged();
    }

    public int findPositionForTool(String toolName) {
        if (toolName == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (toolName.equals(items.get(i).getToolName())) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recommendation, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ToolRecommendation item = items.get(position);
        holder.title.setText(getFriendlyTitle(item));
        holder.badge.setText(getCategoryLabel(item));
        holder.subtitle.setText(item.getReason());
        boolean highlighted = item != null && item.getToolName() != null
            && item.getToolName().equals(highlightedToolName);
        holder.itemView.setBackgroundResource(highlighted
            ? R.drawable.bg_input_chip_highlight
            : R.drawable.bg_input_chip);
        holder.badge.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
            highlighted ? R.color.white : R.color.guide_suggestion));

        String detail = getParameterSummary(item);
        if (TextUtils.isEmpty(detail)) {
            holder.detail.setVisibility(View.GONE);
        } else {
            holder.detail.setVisibility(View.VISIBLE);
            holder.detail.setText(detail);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView badge;
        final TextView subtitle;
        final TextView detail;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.recommendation_title);
            badge = itemView.findViewById(R.id.recommendation_badge);
            subtitle = itemView.findViewById(R.id.recommendation_subtitle);
            detail = itemView.findViewById(R.id.recommendation_detail);
        }
    }

    private String getFriendlyTitle(ToolRecommendation item) {
        String name = item == null ? null : item.getToolName();
        if (name == null) {
            return "Recommendation";
        }
        switch (name) {
            case "Camera_ChangeMode":
                return "Switch shooting mode";
            case "Camera_ChangeIso":
                return "Adjust ISO";
            case "Camera_ChangeShutterSpeed":
                return "Adjust shutter speed";
            case "Camera_ChangeEV":
                return "Adjust exposure";
            case "Camera_ChangeWhiteBalance":
                return "Set white balance";
            case "Camera_ChangeFocusMode":
                return "Set focus mode";
            case "Camera_ChangeMeteringMode":
                return "Set metering mode";
            case "Camera_ChangeZoom":
                return "Adjust zoom";
            case "Camera_VideoFPS":
                return "Set video frame rate";
            case "Camera_VideoStabilization":
                return "Enable video stabilization";
            case "Camera_HDR":
                return "Enable HDR";
            case "Camera_Flash":
                return "Set flash";
            case "Camera_BurstMode":
                return "Enable burst mode";
            case "Camera_MotionPhoto":
                return "Enable motion photo";
            case "Camera_AspectRatio":
                return "Set aspect ratio";
            case "Camera_Guideline":
                return "Show composition guideline";
            case "Camera_SetTimer":
                return "Set self-timer";
            case "Camera_ChangeResolution":
                return "Set capture resolution";
            case "Camera_ChangeCamera":
                return "Switch camera";
            case "ExpertRaw_ChangeToAstroMode":
                return "Expert Raw: Astro mode";
            case "ExpertRaw_ChangeToAstroPortraitMode":
                return "Expert Raw: Astro Portrait";
            case "ExpertRaw_ChangeToMultiExposureMode":
                return "Expert Raw: Multi-Exposure";
            case "ExpertRaw_ChangeToNdFilterMode":
                return "Expert Raw: ND Filter";
            case "ExpertRaw_ChangeToVirtualApertureMode":
                return "Expert Raw: Virtual Aperture";
            case "ExpertRaw_ChangeIso":
                return "Expert Raw: Adjust ISO";
            case "ExpertRaw_ChangeShutterSpeed":
                return "Expert Raw: Adjust Shutter";
            case "PhotoEditor_RemoveShadow":
                return "Remove shadow";
            case "PhotoEditor_removeReflection":
                return "Remove reflection";
            case "PhotoEditor_removeBackgroundPeople":
                return "Remove background people";
            case "PhotoEditor_RemoveFlare":
                return "Reduce flare";
            case "PhotoEditor_GenAIAutoTilt":
                return "AI straighten";
            case "PhotoEditor_GenAIExpand":
                return "AI expand";
            case "PhotoEditor_SmartCrop":
                return "Smart crop (AI)";
            case "PhotoEditor_Recompose":
                return "Recompose";
            case "PhotoEditor_CompositionEnhancer":
                return "Enhance composition";
            case "Gallery_AutoFit":
                return "Auto enhance";
            case "Gallery_AutoTilt":
                return "Auto straighten";
            case "Gallery_Crop":
                return "Crop";
            case "Gallery_ObjectRemover":
                return "Object remover";
            default:
                return titleCase(name.replace("PhotoEditor_", "").replace("Gallery_", "").replace("Camera_", "").replace("ExpertRaw_", ""));
        }
    }

    private String getCategoryLabel(ToolRecommendation item) {
        String name = item == null ? null : item.getToolName();
        if (name == null) {
            return "AI";
        }
        if (name.startsWith("ExpertRaw_")) {
            return "ExpertRaw";
        }
        if (name.startsWith("PhotoEditor_") || name.startsWith("Gallery_")) {
            return "Edit";
        }
        if ("Camera_ChangeMode".equals(name)) {
            return "Mode";
        }
        if (name.contains("Iso") || name.contains("Shutter") || name.contains("EV")
                || name.contains("WhiteBalance") || name.contains("Focus") || name.contains("Metering")) {
            return "Pro";
        }
        if (name.contains("Video") || name.contains("Stabilization")) {
            return "Video";
        }
        return "Capture";
    }

    private String getParameterSummary(ToolRecommendation item) {
        if (item == null || item.getParameters() == null) {
            return null;
        }
        Map<String, Object> params = item.getParameters();
        String name = item.getToolName();
        if (name == null) {
            return null;
        }
        switch (name) {
            case "Camera_ChangeMode":
                return formatValue("Mode", firstPresent(params, "ModeName", "mode"));
            case "Camera_ChangeIso":
            case "ExpertRaw_ChangeIso":
                return formatValue("ISO", firstPresent(params, "IsoValue", "iso"));
            case "Camera_ChangeShutterSpeed":
            case "ExpertRaw_ChangeShutterSpeed":
                return formatValue("Shutter", firstPresent(params, "ShutterSpeed", "shutter_speed", "shutter"));
            case "Camera_ChangeEV":
                return formatValue("EV", firstPresent(params, "EvValue", "ev"));
            case "Camera_ChangeWhiteBalance":
                return formatValue("WB", firstPresent(params, "WbValue", "mode", "white_balance"));
            case "Camera_ChangeFocusMode":
                return formatValue("Focus", firstPresent(params, "FocusMode", "mode", "focus_mode"));
            case "Camera_ChangeMeteringMode":
                return formatValue("Metering", firstPresent(params, "mode"));
            case "Camera_ChangeZoom":
                return formatValue("Zoom", firstPresent(params, "zoom_level"));
            case "Camera_VideoFPS":
                return formatValue("FPS", firstPresent(params, "fps"));
            case "Camera_VideoStabilization":
                return formatValue("Stabilization", firstPresent(params, "mode"));
            case "Camera_HDR":
            case "Camera_Flash":
                return formatValue("Mode", firstPresent(params, "mode", "enable"));
            case "Camera_BurstMode":
            case "Camera_MotionPhoto":
            case "Camera_Guideline":
                return formatValue("Enable", firstPresent(params, "enable"));
            case "Camera_AspectRatio":
                return formatValue("Ratio", firstPresent(params, "ratio"));
            case "Camera_SetTimer":
                return formatValue("Seconds", firstPresent(params, "seconds"));
            case "Camera_ChangeResolution":
                return formatValue("Resolution", firstPresent(params, "resolution"));
            case "Camera_ChangeCamera":
                return formatValue("Camera", firstPresent(params, "direction"));
            case "ExpertRaw_ChangeToNdFilterMode":
                return formatValue("ND Strength", firstPresent(params, "strength"));
            case "ExpertRaw_ChangeToAstroMode":
            case "ExpertRaw_ChangeToAstroPortraitMode":
            case "ExpertRaw_ChangeToMultiExposureMode":
            case "ExpertRaw_ChangeToVirtualApertureMode":
                return null; // No additional parameters to display
            case "PhotoEditor_RemoveShadow":
            case "PhotoEditor_removeReflection":
            case "PhotoEditor_removeBackgroundPeople":
            case "PhotoEditor_RemoveFlare":
                Object areas = firstPresent(params, "target_areas");
                if (areas instanceof List) {
                    return formatValue("Targets", ((List<?>) areas).size());
                }
                return null;
            case "PhotoEditor_CompositionEnhancer":
                return formatValue("Mode", firstPresent(params, "mode"));
            case "PhotoEditor_SmartCrop":
                return formatValue("Ratio", firstPresent(params, "aspect_ratio"));
            case "PhotoEditor_Recompose":
                return formatValue("Rule of thirds", firstPresent(params, "apply_rule_of_thirds"));
            case "PhotoEditor_GenAIExpand":
                return formatValue("Direction", firstPresent(params, "direction"));
            default:
                return null;
        }
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

    private String formatValue(String label, Object value) {
        return value == null ? null : label + ": " + String.valueOf(value);
    }

    private String titleCase(String raw) {
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
}
