package com.samsung.camera.intelligence.app.camera;

import android.net.Uri;

import java.util.Map;

/**
 * Optional bag of side-data passed to {@link CameraController#capturePhoto} so
 * the controller can drop a comparison snapshot triplet next to the result
 * JPEG (saved original + reference image + meta).
 *
 * <p>All fields are optional. If {@code saveOriginal} is {@code true}, the
 * untouched sensor JPEG is byte-copied to {@code <base>_orig.jpg}. If a
 * reference asset path or URI is provided, that image is copied to
 * {@code <base>_ref.<ext>}. {@link #meta} is dumped to {@code <base>_meta.txt}
 * as one {@code key=value} line per entry. The user-facing result and the
 * original / reference copies are also published into the system Gallery via
 * {@link com.samsung.camera.intelligence.app.util.MediaStoreSaver}.
 */
public final class CaptureExtras {

    /** When true, save the unmodified sensor JPEG alongside the graded result. */
    public boolean saveOriginal;

    /** Asset path (relative to {@code assets/}) of the reference image, e.g. {@code master_match/thumbnails/abc.webp}. */
    public String referenceAssetPath;

    /** Content URI of the reference image (used for gallery-straw selections). */
    public Uri referenceUri;

    /** Human-readable label for the reference (photographer · title) — written to meta. */
    public String referenceLabel;

    /** Free-form metadata; written one {@code key=value} per line. */
    public Map<String, Object> meta;

    public CaptureExtras() {}
}
