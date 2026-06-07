package com.samsung.camera.intelligence.app;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.TextView;

public final class BottomPanelController {

    public enum SummaryTab {
        LIVE,
        FEATURES,
        PIPELINE
    }

    private static final long BOTTOM_CONSOLE_ANIM_MS = 220L;
    private static final int BOTTOM_CONSOLE_PEEK_DP = 34;

    private final View bottomConsolePanel;
    private final View bottomConsoleHandle;
    private final View bottomExtrasContainer;
    private final Button toggleExtrasButton;
    private final View proControlsContainer;
    private final Button toggleProControlsButton;
    private final TextView analysisSummaryText;
    private final TextView featureSummaryText;
    private final TextView capabilitySummaryText;
    private final Button summaryLiveTabButton;
    private final Button summaryFeatureTabButton;
    private final Button summaryPipelineTabButton;

    private boolean proControlsExpanded = false;
    private boolean extrasExpanded = false;
    private boolean bottomConsoleCollapsed = false;
    private boolean bottomConsoleDragging = false;
    private float bottomConsoleDragStartY = 0f;
    private float bottomConsoleStartTranslationY = 0f;
    private float bottomConsoleMaxTranslationY = 0f;
    private int bottomConsoleTouchSlop = 0;
    private SummaryTab currentSummaryTab = SummaryTab.LIVE;

    public BottomPanelController(
            View bottomConsolePanel,
            View bottomConsoleHandle,
            View bottomExtrasContainer,
            Button toggleExtrasButton,
            View proControlsContainer,
            Button toggleProControlsButton,
            TextView analysisSummaryText,
            TextView featureSummaryText,
            TextView capabilitySummaryText,
            Button summaryLiveTabButton,
            Button summaryFeatureTabButton,
            Button summaryPipelineTabButton
    ) {
        this.bottomConsolePanel = bottomConsolePanel;
        this.bottomConsoleHandle = bottomConsoleHandle;
        this.bottomExtrasContainer = bottomExtrasContainer;
        this.toggleExtrasButton = toggleExtrasButton;
        this.proControlsContainer = proControlsContainer;
        this.toggleProControlsButton = toggleProControlsButton;
        this.analysisSummaryText = analysisSummaryText;
        this.featureSummaryText = featureSummaryText;
        this.capabilitySummaryText = capabilitySummaryText;
        this.summaryLiveTabButton = summaryLiveTabButton;
        this.summaryFeatureTabButton = summaryFeatureTabButton;
        this.summaryPipelineTabButton = summaryPipelineTabButton;
    }

    public void bind() {
        if (bottomConsolePanel == null || bottomConsoleHandle == null) {
            return;
        }
        bottomConsoleTouchSlop = ViewConfiguration.get(bottomConsolePanel.getContext()).getScaledTouchSlop();
        bottomConsoleHandle.setOnTouchListener(this::handleBottomConsoleTouch);
        bottomConsolePanel.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                refreshBottomConsoleBounds(false));
        bottomConsolePanel.post(() -> refreshBottomConsoleBounds(false));
    }

    public boolean isProControlsExpanded() {
        return proControlsExpanded;
    }

    public boolean isExtrasExpanded() {
        return extrasExpanded;
    }

    public void setProControlsExpanded(boolean expanded, boolean animate) {
        proControlsExpanded = expanded;
        if (toggleProControlsButton == null || proControlsContainer == null) return;
        toggleProControlsButton.setText(expanded
                ? R.string.hide_pro_controls
                : R.string.show_pro_controls);

        if (!animate) {
            proControlsContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            return;
        }

        if (expanded) {
            proControlsContainer.setVisibility(View.VISIBLE);
            proControlsContainer.setAlpha(0f);
            proControlsContainer.setTranslationY(20f);
            proControlsContainer.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            proControlsContainer.animate().alpha(0f).translationY(16f).setDuration(160)
                    .withEndAction(() -> {
                        proControlsContainer.setVisibility(View.GONE);
                        proControlsContainer.setAlpha(1f);
                        proControlsContainer.setTranslationY(0f);
                    }).start();
        }
    }

    public void setExtrasExpanded(boolean expanded, boolean animate) {
        extrasExpanded = expanded;
        if (toggleExtrasButton == null || bottomExtrasContainer == null) return;
        toggleExtrasButton.setText(expanded ? "\u25b2" : "\u25bc");

        if (expanded) {
            setBottomConsoleCollapsed(false, true);
        }

        if (!animate) {
            bottomExtrasContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            return;
        }

        if (expanded) {
            bottomExtrasContainer.setVisibility(View.VISIBLE);
            bottomExtrasContainer.setAlpha(0f);
            bottomExtrasContainer.animate().alpha(1f).setDuration(200).start();
        } else {
            bottomExtrasContainer.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> {
                        bottomExtrasContainer.setVisibility(View.GONE);
                        bottomExtrasContainer.setAlpha(1f);
                    }).start();
        }
    }

    public void setSummaryTab(SummaryTab tab) {
        currentSummaryTab = tab;
        if (summaryLiveTabButton != null) {
            summaryLiveTabButton.setSelected(tab == SummaryTab.LIVE);
        }
        if (summaryFeatureTabButton != null) {
            summaryFeatureTabButton.setSelected(tab == SummaryTab.FEATURES);
        }
        if (summaryPipelineTabButton != null) {
            summaryPipelineTabButton.setSelected(tab == SummaryTab.PIPELINE);
        }
        if (analysisSummaryText != null) {
            analysisSummaryText.setVisibility(tab == SummaryTab.LIVE ? View.VISIBLE : View.GONE);
        }
        if (featureSummaryText != null) {
            featureSummaryText.setVisibility(tab == SummaryTab.FEATURES ? View.VISIBLE : View.GONE);
        }
        if (capabilitySummaryText != null) {
            capabilitySummaryText.setVisibility(tab == SummaryTab.PIPELINE ? View.VISIBLE : View.GONE);
        }
    }

    private boolean handleBottomConsoleTouch(View view, MotionEvent event) {
        if (bottomConsolePanel == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                bottomConsoleDragStartY = event.getRawY();
                bottomConsoleStartTranslationY = bottomConsolePanel.getTranslationY();
                bottomConsoleDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float deltaY = event.getRawY() - bottomConsoleDragStartY;
                if (!bottomConsoleDragging && Math.abs(deltaY) > bottomConsoleTouchSlop) {
                    bottomConsoleDragging = true;
                }
                if (bottomConsoleDragging) {
                    bottomConsolePanel.setTranslationY(clampBottomConsoleTranslation(
                            bottomConsoleStartTranslationY + deltaY));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float totalDeltaY = event.getRawY() - bottomConsoleDragStartY;
                if (!bottomConsoleDragging || Math.abs(totalDeltaY) <= bottomConsoleTouchSlop) {
                    setBottomConsoleCollapsed(!bottomConsoleCollapsed, true);
                } else {
                    boolean shouldCollapse = bottomConsolePanel.getTranslationY()
                            > bottomConsoleMaxTranslationY * 0.45f;
                    setBottomConsoleCollapsed(shouldCollapse, true);
                }
                bottomConsoleDragging = false;
                return true;
            default:
                return false;
        }
    }

    private void refreshBottomConsoleBounds(boolean animate) {
        if (bottomConsolePanel == null) {
            return;
        }
        int panelHeight = bottomConsolePanel.getHeight();
        if (panelHeight <= 0) {
            return;
        }
        bottomConsoleMaxTranslationY = Math.max(0f, panelHeight - dpToPx(BOTTOM_CONSOLE_PEEK_DP));
        float targetTranslation = bottomConsoleCollapsed ? bottomConsoleMaxTranslationY : 0f;
        if (animate) {
            bottomConsolePanel.animate()
                    .translationY(targetTranslation)
                    .setDuration(BOTTOM_CONSOLE_ANIM_MS)
                    .start();
        } else {
            bottomConsolePanel.setTranslationY(targetTranslation);
        }
    }

    private void setBottomConsoleCollapsed(boolean collapsed, boolean animate) {
        bottomConsoleCollapsed = collapsed;
        refreshBottomConsoleBounds(animate);
    }

    private float clampBottomConsoleTranslation(float translationY) {
        return Math.max(0f, Math.min(translationY, bottomConsoleMaxTranslationY));
    }

    private float dpToPx(int dp) {
        return dp * bottomConsolePanel.getResources().getDisplayMetrics().density;
    }
}
