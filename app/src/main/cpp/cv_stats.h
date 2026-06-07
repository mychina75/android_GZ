// Native CV proxies used by the trigger pipeline.
//
// Mirrors the Java BackgroundBokehStats (variance-of-Laplacian bokeh estimate)
// and LensBlockedCornerStats (corner/edge obstruction heuristic). Both operate
// on a row-major ARGB_8888 pixel buffer (one int per pixel, 0xAARRGGBB).
#ifndef CAMERA_NATIVE_CV_STATS_H
#define CAMERA_NATIVE_CV_STATS_H

#include <cstdint>

namespace camera_native {

struct BokehResult {
    float bgSharpRatio;
    float bgBlurStrength;
};

struct LensResult {
    float probability;
    int numBlockedPatches;
};

// Variance-of-Laplacian background-defocus estimate.
// bx,by,bw,bh are the normalized subject bbox (top-left + size, 0..1).
BokehResult bokeh_stats(const int32_t* argb, int width, int height,
                        float bx, float by, float bw, float bh);

// Corner/edge finger-obstruction heuristic. Returns probability in [0,0.98]
// and the number of blocked patches.
LensResult lens_corner(const int32_t* argb, int width, int height);

}  // namespace camera_native

#endif  // CAMERA_NATIVE_CV_STATS_H
