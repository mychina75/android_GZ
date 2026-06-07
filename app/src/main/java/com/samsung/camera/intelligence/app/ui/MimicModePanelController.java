package com.samsung.camera.intelligence.app.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.samsung.camera.intelligence.guidance.MasterMatchOverlay;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controls the right-side Mimic Mode reference panel showing up to 3 matched
 * professional reference cards plus an optional gallery "style straw" card,
 * and the parameter display bar above the bottom console.  Selecting a card
 * updates the parameter bar and notifies the listener to apply the
 * corresponding Pro Mode settings to the camera.
 */
public class MimicModePanelController {

    /** Callback when the user selects a different reference card. */
    public interface OnSelectionChangedListener {
        void onSelectionChanged(MasterMatchOverlay selected, int index);
    }

    /** Callback when the user taps the "+" (add style straw) button. */
    public interface OnAddStrawClickListener {
        void onAddStrawClicked();
    }

    /** Callback when the user taps the close (×) button on the gallery card. */
    public interface OnCloseStrawClickListener {
        void onCloseStrawClicked();
    }

    private final View panelContainer;
    private final TextView paramBar;
    private final LinearLayout[] cards = new LinearLayout[4];
    private final TextView[] cardLabels = new TextView[4];
    private final TextView[] cardInfos = new TextView[4];
    private final ImageView[] cardThumbs = new ImageView[4];
    private final String[] cardThumbAssetKeys = new String[4];
    private final int[] thumbRequestTokens = new int[4];
    private final AssetManager assets;
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<>(96);
    private final Set<String> missingThumbs = new HashSet<>();
    private final int bgNormal;
    private final int bgSelected;

    private final List<MasterMatchOverlay> overlays = new ArrayList<>();
    private int selectedIndex = 0;
    private boolean selectionPinnedByUser = false;
    private OnSelectionChangedListener listener;
    private OnAddStrawClickListener addStrawListener;
    private OnCloseStrawClickListener closeStrawListener;

    // Style straw (gallery photo): slot 3
    private static final int GALLERY_SLOT = 3;
    private View addStrawButton;
    private View cardWrapper;       // FrameLayout wrapping card3 + overlay buttons
    private View closeStrawBtn;
    private View replaceStrawBtn;
    private MasterMatchOverlay galleryOverlay;
    private Bitmap galleryThumbBitmap;
    // Track which photo was last auto-applied so we don't re-apply the same
    // tone when the overlay list reshuffles (which happens because our tone
    // curve changes the preview → slightly different feature embedding →
    // new Top3 ranking → re-apply → brighter preview → loop).
    private String lastAutoAppliedPhotoId = null;

    // Settling period: after auto-applying parameters, suppress further
    // auto-applies for this duration to let the camera preview stabilize.
    private static final long AUTO_APPLY_SETTLE_MS = 3000;
    private long lastAutoApplyTimeMs = 0;

    public MimicModePanelController(
            Context context,
            View panelContainer,
            TextView paramBar,
            LinearLayout card0, LinearLayout card1, LinearLayout card2,
            TextView label0, TextView label1, TextView label2,
            TextView info0, TextView info1, TextView info2,
            ImageView thumb0, ImageView thumb1, ImageView thumb2,
            LinearLayout card3, TextView label3, TextView info3, ImageView thumb3,
            View addStrawButton,
            View cardWrapper, View closeStrawBtn, View replaceStrawBtn) {

        this.assets = context.getAssets();
        this.panelContainer = panelContainer;
        this.paramBar = paramBar;
        this.cards[0] = card0;
        this.cards[1] = card1;
        this.cards[2] = card2;
        this.cards[3] = card3;
        this.cardLabels[0] = label0;
        this.cardLabels[1] = label1;
        this.cardLabels[2] = label2;
        this.cardLabels[3] = label3;
        this.cardInfos[0] = info0;
        this.cardInfos[1] = info1;
        this.cardInfos[2] = info2;
        this.cardInfos[3] = info3;
        this.cardThumbs[0] = thumb0;
        this.cardThumbs[1] = thumb1;
        this.cardThumbs[2] = thumb2;
        this.cardThumbs[3] = thumb3;
        this.addStrawButton = addStrawButton;
        this.cardWrapper = cardWrapper;
        this.closeStrawBtn = closeStrawBtn;
        this.replaceStrawBtn = replaceStrawBtn;

        this.bgNormal = com.samsung.camera.intelligence.app.R.drawable.bg_mimic_card;
        this.bgSelected = com.samsung.camera.intelligence.app.R.drawable.bg_mimic_card_selected;

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            if (cards[i] != null) {
                cards[i].setOnClickListener(v -> onUserSelectCard(idx));
            }
        }
        if (addStrawButton != null) {
            addStrawButton.setOnClickListener(v -> {
                if (addStrawListener != null) addStrawListener.onAddStrawClicked();
            });
        }
        if (closeStrawBtn != null) {
            closeStrawBtn.setOnClickListener(v -> {
                clearGalleryOverlay();
                if (closeStrawListener != null) closeStrawListener.onCloseStrawClicked();
            });
        }
        if (replaceStrawBtn != null) {
            replaceStrawBtn.setOnClickListener(v -> {
                if (addStrawListener != null) addStrawListener.onAddStrawClicked();
            });
        }
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.listener = listener;
    }

    public void setOnAddStrawClickListener(OnAddStrawClickListener listener) {
        this.addStrawListener = listener;
    }

    public void setOnCloseStrawClickListener(OnCloseStrawClickListener listener) {
        this.closeStrawListener = listener;
    }

    /**
     * Update the panel with a new list of matched overlays (0-3 items).
     * Automatically selects the first card and notifies the listener.
     */
    public void updateOverlays(List<MasterMatchOverlay> newOverlays) {
        if (selectionPinnedByUser) {
            return;
        }

        List<MasterMatchOverlay> incoming = new ArrayList<>();
        if (newOverlays != null) {
            for (int i = 0; i < newOverlays.size() && i < 3; i++) {
                incoming.add(newOverlays.get(i));
            }
        }

        // In auto-refresh mode, only refresh card text/param UI if Top3 really changed.
        if (isSameTop3(incoming)) {
            return;
        }

        overlays.clear();
        overlays.addAll(incoming);

        for (int i = 0; i < 3; i++) {
            if (i < overlays.size()) {
                cards[i].setVisibility(View.VISIBLE);
                MasterMatchOverlay o = overlays.get(i);
                cardLabels[i].setText(buildLabelText(o, i));
                cardInfos[i].setText(buildCardText(o));
            } else {
                cards[i].setVisibility(View.GONE);
            }
        }

        // Load thumbnails asynchronously
        for (int i = 0; i < 3; i++) {
            if (i < overlays.size()) {
                bindThumbnail(i, overlays.get(i));
            } else {
                cardThumbs[i].setImageDrawable(null);
                cardThumbs[i].setVisibility(View.INVISIBLE);
                cardThumbAssetKeys[i] = null;
            }
        }

        if (!overlays.isEmpty()) {
            // Auto-show the panel when real data arrives
            panelContainer.setVisibility(View.VISIBLE);
            paramBar.setVisibility(View.VISIBLE);

            // Only auto-apply when a genuinely NEW photo appears at the top
            // for the first time.  Subsequent re-shuffles of the same Top3
            // are caused by our own tone curve changing the preview, so we
            // must NOT re-apply (that would create a feedback loop).
            String topPhotoId = overlays.get(0).getPhotoId();
            long now = System.currentTimeMillis();
            boolean withinSettlePeriod = (now - lastAutoApplyTimeMs) < AUTO_APPLY_SETTLE_MS;
            boolean isNewPhoto = !Objects.equals(topPhotoId, lastAutoAppliedPhotoId);

            if (isNewPhoto && !withinSettlePeriod) {
                lastAutoApplyTimeMs = now;
                lastAutoAppliedPhotoId = topPhotoId;
                selectCard(0, true);
            } else {
                selectCard(0, false); // update display only
            }
        } else {
            // No matches — hide cards, show only the param bar with info message
            selectedIndex = 0;
            panelContainer.setVisibility(View.GONE);
            paramBar.setVisibility(View.VISIBLE);
            paramBar.setText("Searching for matching pro shots…");
        }
    }

    private boolean isSameTop3(List<MasterMatchOverlay> incoming) {
        int oldN = Math.min(3, overlays.size());
        int newN = incoming == null ? 0 : Math.min(3, incoming.size());
        if (oldN != newN) {
            return false;
        }
        for (int i = 0; i < newN; i++) {
            String oldId = overlays.get(i) != null ? overlays.get(i).getPhotoId() : null;
            String newId = incoming.get(i) != null ? incoming.get(i).getPhotoId() : null;
            if (!Objects.equals(oldId, newId)) {
                return false;
            }
        }
        return true;
    }

    /** Select a card by index and update the UI + parameter bar. */
    public void selectCard(int index) {
        selectCard(index, true);
    }

    private void onUserSelectCard(int index) {
        // Validate: master cards need overlay, gallery card needs galleryOverlay
        if (index == GALLERY_SLOT && galleryOverlay == null) return;
        if (index != GALLERY_SLOT && (index < 0 || index >= overlays.size())) return;

        if (selectionPinnedByUser && index == selectedIndex) {
            selectionPinnedByUser = false;
            return;
        }
        selectionPinnedByUser = true;
        selectCard(index, true);
    }

    private void selectCard(int index, boolean notifyListener) {
        // index 0-2: master cards,  index 3: gallery card
        if (index == GALLERY_SLOT) {
            if (galleryOverlay == null) return;
        } else if (index < 0 || index >= overlays.size()) {
            return;
        }
        selectedIndex = index;

        // Update card backgrounds for all visible cards (0-2 master + 3 gallery)
        for (int i = 0; i < 3; i++) {
            if (i < overlays.size()) {
                cards[i].setBackgroundResource(i == selectedIndex ? bgSelected : bgNormal);
            }
        }
        if (cards[GALLERY_SLOT] != null && galleryOverlay != null) {
            cards[GALLERY_SLOT].setBackgroundResource(
                    GALLERY_SLOT == selectedIndex ? bgSelected : bgNormal);
        }

        // Update parameter bar
        MasterMatchOverlay selected = getSelectedOverlay();
        if (selected != null) {
            paramBar.setText(buildParamText(selected));
        }

        if (notifyListener && listener != null && selected != null) {
            listener.onSelectionChanged(selected, selectedIndex);
        }
    }

    /** Show the panel (used when entering Mimic Mode with available matches). */
    public void show() {
        panelContainer.setVisibility(View.VISIBLE);
        paramBar.setVisibility(View.VISIBLE);
    }

    /** Hide the panel (used when leaving Mimic Mode). */
    public void hide() {
        panelContainer.setVisibility(View.GONE);
        paramBar.setVisibility(View.GONE);
        // Gallery card persists across mode switches — do NOT clear here.
    }

    /** Whether the panel is currently visible. */
    public boolean isVisible() {
        return panelContainer.getVisibility() == View.VISIBLE;
    }

    public boolean isSelectionPinnedByUser() {
        return selectionPinnedByUser;
    }

    public void resetSelectionPin() {
        selectionPinnedByUser = false;
        lastAutoAppliedPhotoId = null;
    }

    /** Get the currently selected overlay, or null if none. */
    public MasterMatchOverlay getSelectedOverlay() {
        if (selectedIndex == GALLERY_SLOT && galleryOverlay != null) {
            return galleryOverlay;
        }
        if (selectedIndex >= 0 && selectedIndex < overlays.size()) {
            return overlays.get(selectedIndex);
        }
        return null;
    }

    // ── Style Straw (gallery photo) ──────────────────────────────────────────

    /**
     * Set a gallery photo as the style straw card (slot 3).
     * Shows the card with the provided thumbnail and overlay data.
     *
     * @param overlay   MasterMatchOverlay built from extracted tone params
     * @param thumbnail display thumbnail (~150 px), shown directly (not from assets)
     */
    public void setGalleryOverlay(MasterMatchOverlay overlay, Bitmap thumbnail) {
        this.galleryOverlay = overlay;
        this.galleryThumbBitmap = thumbnail;

        if (cards[GALLERY_SLOT] != null) {
            cards[GALLERY_SLOT].setVisibility(View.VISIBLE);
            cardLabels[GALLERY_SLOT].setText(buildGalleryLabelText(overlay));
            cardInfos[GALLERY_SLOT].setText(buildGalleryCardText(overlay));
            if (thumbnail != null && cardThumbs[GALLERY_SLOT] != null) {
                cardThumbs[GALLERY_SLOT].setImageBitmap(thumbnail);
                cardThumbs[GALLERY_SLOT].setVisibility(View.VISIBLE);
            }
        }
        // Show the wrapper and hide the standalone "+" button
        if (cardWrapper != null) cardWrapper.setVisibility(View.VISIBLE);
        if (addStrawButton != null) addStrawButton.setVisibility(View.GONE);

        // Ensure panel is visible even if no master matches yet
        panelContainer.setVisibility(View.VISIBLE);
        paramBar.setVisibility(View.VISIBLE);

        // Auto-select the gallery card and apply
        selectionPinnedByUser = true;
        selectCard(GALLERY_SLOT, true);
    }

    /** Clear the gallery style straw card. */
    public void clearGalleryOverlay() {
        galleryOverlay = null;
        galleryThumbBitmap = null;
        if (cards[GALLERY_SLOT] != null) {
            cards[GALLERY_SLOT].setVisibility(View.GONE);
            if (cardThumbs[GALLERY_SLOT] != null) {
                cardThumbs[GALLERY_SLOT].setImageDrawable(null);
                cardThumbs[GALLERY_SLOT].setVisibility(View.INVISIBLE);
            }
        }
        // Hide wrapper, show standalone "+" button again
        if (cardWrapper != null) cardWrapper.setVisibility(View.GONE);
        if (addStrawButton != null) addStrawButton.setVisibility(View.VISIBLE);
        // If gallery card was selected, fall back to card 0
        if (selectedIndex == GALLERY_SLOT && !overlays.isEmpty()) {
            selectionPinnedByUser = false;
            selectCard(0, true);
        }
    }

    /** Whether a gallery overlay is currently loaded. */
    public boolean hasGalleryOverlay() {
        return galleryOverlay != null;
    }

    /** Get the current gallery overlay (may be null). */
    public MasterMatchOverlay getGalleryOverlay() {
        return galleryOverlay;
    }

    private String buildGalleryLabelText(MasterMatchOverlay o) {
        String title = o.getPhotoTitle();
        if (title != null && !title.isEmpty()) {
            if (title.length() > 20) {
                title = title.substring(0, 18) + "…";
            }
            return "\uD83C\uDFA8 " + title;  // 🎨 emoji prefix
        }
        return "\uD83C\uDFA8 Gallery Style";
    }

    private String buildGalleryCardText(MasterMatchOverlay o) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> params = o.getProModeParams();
        if (params != null && !params.isEmpty()) {
            appendToneParam(sb, params, "contrast", "C");
            appendToneParam(sb, params, "highlights", "H");
            appendToneParam(sb, params, "shadows", "Sh");
            appendToneParam(sb, params, "saturation", "Sat");
            Object warmth = params.get("highlight_warmth");
            Object tint = params.get("shadow_tint");
            if (warmth != null) {
                if (sb.length() > 0) sb.append(" ");
                sb.append("W:").append(formatTone(warmth));
            }
            if (tint != null) {
                if (sb.length() > 0) sb.append(" ");
                sb.append("T:").append(formatTone(tint));
            }
        }
        return sb.toString();
    }

    private void appendToneParam(StringBuilder sb, Map<String, Object> params, String key, String label) {
        Object val = params.get(key);
        if (val != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(label).append(":").append(formatTone(val));
        }
    }

    // ── Thumbnail loading ────────────────────────────────────────────────────

    private void bindThumbnail(int slot, MasterMatchOverlay overlay) {
        ImageView iv = cardThumbs[slot];
        String assetPath = resolveThumbnailAssetPath(overlay);

        if (assetPath == null || assetPath.isEmpty()) {
            cardThumbAssetKeys[slot] = null;
            iv.setImageDrawable(null);
            iv.setVisibility(View.INVISIBLE);
            return;
        }

        // Same asset in the same slot: keep the current bitmap and skip reload.
        if (assetPath.equals(cardThumbAssetKeys[slot])) {
            return;
        }

        cardThumbAssetKeys[slot] = assetPath;
        Bitmap cached = thumbCache.get(assetPath);
        if (cached != null) {
            iv.setImageBitmap(cached);
            iv.setVisibility(View.VISIBLE);
            return;
        }

        if (missingThumbs.contains(assetPath)) {
            iv.setImageDrawable(null);
            iv.setVisibility(View.INVISIBLE);
            return;
        }

        // Keep card height stable while loading to avoid visual jitter.
        iv.setImageDrawable(null);
        iv.setVisibility(View.INVISIBLE);

        final int token = ++thumbRequestTokens[slot];
        thumbExecutor.execute(() -> {
            try (InputStream is = assets.open(assetPath)) {
                Bitmap bm = BitmapFactory.decodeStream(is);
                if (bm == null) {
                    missingThumbs.add(assetPath);
                    return;
                }
                thumbCache.put(assetPath, bm);
                iv.post(() -> {
                    if (thumbRequestTokens[slot] != token) {
                        return;
                    }
                    if (!assetPath.equals(cardThumbAssetKeys[slot])) {
                        return;
                    }
                    iv.setImageBitmap(bm);
                    iv.setVisibility(View.VISIBLE);
                });
            } catch (IOException ignored) {
                missingThumbs.add(assetPath);
            }
        });
    }

    private String resolveThumbnailAssetPath(MasterMatchOverlay overlay) {
        if (overlay == null) {
            return null;
        }

        String thumbnailUrl = overlay.getThumbnailUrl();
        if (thumbnailUrl != null) {
            String normalized = thumbnailUrl.trim().replace('\\', '/');
            if (!normalized.isEmpty() && !normalized.contains("://")) {
                return normalized.startsWith("/") ? normalized.substring(1) : normalized;
            }
        }

        String photoId = overlay.getPhotoId();
        if (photoId == null || photoId.isEmpty()) {
            return null;
        }
        return "master_match/thumbnails/" + photoId + ".webp";
    }

    // ── Formatting ──────────────────────────────────────────────────

    private String buildLabelText(MasterMatchOverlay o, int rank) {
        String title = o.getPhotoTitle();
        if (title != null && !title.isEmpty() && !"Untitled".equals(title)) {
            // Truncate long titles
            if (title.length() > 18) {
                title = title.substring(0, 16) + "…";
            }
            return String.format(Locale.US, "#%d %s", rank + 1, title);
        }
        return String.format(Locale.US, "Pro's Shot %d", rank + 1);
    }

    private String buildCardText(MasterMatchOverlay o) {
        StringBuilder sb = new StringBuilder();
        String photographer = o.getPhotographerName();
        if (photographer != null && !photographer.isEmpty() && !"Unknown".equals(photographer)) {
            sb.append("By ").append(photographer);
        }

        Map<String, Object> params = o.getProModeParams();
        if (params != null) {
            Object brand = params.get("camera_brand");
            Object model = params.get("camera_model");
            if (brand != null || model != null) {
                if (sb.length() > 0) sb.append("\n");
                if (brand != null) sb.append(brand);
                if (model != null) {
                    if (brand != null) sb.append(" ");
                    sb.append(model);
                }
            }
        }

        // Compact tone parameter summary
        if (params != null && !params.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            appendToneParam(sb, params, "contrast", "C");
            appendToneParam(sb, params, "highlights", "H");
            appendToneParam(sb, params, "shadows", "Sh");
            appendToneParam(sb, params, "saturation", "Sat");
        }
        return sb.toString();
    }

    private String buildParamText(MasterMatchOverlay o) {
        Map<String, Object> params = o.getProModeParams();
        if (params == null || params.isEmpty()) {
            return "No parameters";
        }
        StringBuilder sb = new StringBuilder();
        Object iso = params.get("iso");
        Object shutter = params.get("shutter_speed");
        Object ev = params.get("ev");
        Object wb = params.get("white_balance_kelvin");
        if (wb == null) {
            wb = params.get("white_balance");
        }
        Object focus = params.get("focus_mode");

        if (iso != null) sb.append("ISO ").append(iso).append(" · ");
        if (shutter != null) sb.append(shutter).append(" · ");
        if (ev != null) sb.append("EV ").append(ev).append(" · ");
        if (wb != null) sb.append("WB ").append(wb).append(" · ");
        if (focus != null) sb.append(focus);

        // Append tone style parameters if present
        Object contrast = params.get("contrast");
        Object highlights = params.get("highlights");
        Object shadows = params.get("shadows");
        Object saturation = params.get("saturation");
        boolean hasTone = contrast != null || highlights != null
                || shadows != null || saturation != null;
        if (hasTone) {
            sb.append("\n");
            if (contrast != null) sb.append("C:").append(formatTone(contrast)).append(" ");
            if (highlights != null) sb.append("H:").append(formatTone(highlights)).append(" ");
            if (shadows != null) sb.append("Sh:").append(formatTone(shadows)).append(" ");
            if (saturation != null) sb.append("Sat:").append(formatTone(saturation));
        }

        // Trim trailing separator
        String result = sb.toString().trim();
        if (result.endsWith("·")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static String formatTone(Object value) {
        if (value instanceof Number) {
            int v = ((Number) value).intValue();
            return v >= 0 ? "+" + v : String.valueOf(v);
        }
        return String.valueOf(value);
    }

    private void appendParam(StringBuilder sb, Map<String, Object> params, String key, String prefix) {
        Object val = params.get(key);
        if (val == null) return;
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append(" ");
        }
        if (!prefix.isEmpty()) {
            sb.append(prefix);
        }
        sb.append(val);
    }
}
