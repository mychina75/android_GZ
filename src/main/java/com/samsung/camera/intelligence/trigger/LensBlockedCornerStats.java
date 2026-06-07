package com.samsung.camera.intelligence.trigger;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight on-device Production implementation for detecting a finger / dirt obstruction 
 * on the camera lens — trigger lens_blocked.
 * 
 * Optimized for ZERO-ALLOCATION in the hot path to avoid GC stutter.
 */
public final class LensBlockedCornerStats {

    public static final int PATCH = 32;

    /** 18-d feature: [min,mean,max] x [meanY, stdY, gradMag, darkRatio, ...] */
    public static final int FEATURE_DIM = 18;

    // --- 算法核心阈值调优 ---
    private static final float DARK_LUMA = 0.22f;      // < 22% 亮度 = "暗"
    private static final float LOW_GRAD = 12.0f;       // < 12 = "平坦/无纹理补丁"
    private static final int MIN_BLOCKED_PATCHES = 2;  // 至少需要 N 个补丁被遮挡才触发

    // 空间拓扑定义：前 8 个固定为边缘/角落补丁
    private static final int EDGE_PATCH_COUNT = 8; 
    // 最大可能生成的补丁总数（8个边缘 + 5*7内部网格拓扑最大理论值）
    private static final int MAX_PATCH_COUNT = 32;

    // --- 性能优化：预分配复用内存对象，实现零内存分配（线程不安全，建议单线程调用） ---
    private final int[] patchBuf = new int[PATCH * PATCH];
    private final float[] lumaBuf = new float[PATCH * PATCH]; 
    
    // 预分配固定大小的数组坐标，彻底消除 Rect 对象的分配
    private final int[] rectLefts = new int[MAX_PATCH_COUNT]; 
    private final int[] rectTops = new int[MAX_PATCH_COUNT];
    private int totalRectCount = 0;

    // 复用输出对象与特征数组
    private final float[] cachedFeatures = new float[FEATURE_DIM];
    private final PatchStats[] cachedPerPatchStats = new PatchStats[MAX_PATCH_COUNT];
    private final Result cachedResult;

    // 预分配调试用字符串数组，避免 debugMap 在运行时高频拼接 String
    private final String[] cachedDebugKeys;

    public LensBlockedCornerStats() {
        for (int i = 0; i < cachedPerPatchStats.length; i++) {
            cachedPerPatchStats[i] = new PatchStats();
        }
        this.cachedResult = new Result(0f, cachedFeatures, 0, cachedPerPatchStats, 0);

        // 预先生成调试 Key，防止 debugMap 拼接字符串产生内存碎片
        cachedDebugKeys = new String[MAX_PATCH_COUNT];
        for (int i = 0; i < cachedDebugKeys.length; i++) {
            cachedDebugKeys[i] = "p" + i + "_";
        }
    }

    public static final class PatchStats {
        public float meanLuma;
        public float stdLuma;
        public float gradMag;
        public float darkRatio;
        public float warmRatio;
        public boolean isBlocked;

        void update(float meanLuma, float stdLuma, float gradMag, float darkRatio, float warmRatio, boolean isBlocked) {
            this.meanLuma = meanLuma;
            this.stdLuma = stdLuma;
            this.gradMag = gradMag;
            this.darkRatio = darkRatio;
            this.warmRatio = warmRatio;
            this.isBlocked = isBlocked;
        }
    }

    public static final class Result {
        public float probability;     // 基于规则的触发概率
        public float[] features;      // 18维特征向量
        public int numBlockedPatches; // 匹配到暗+平坦的补丁总数
        public PatchStats[] perPatch; // 固定大小的补丁数组
        public int patchCount;        // 实际有效的补丁数量

        Result(float p, float[] f, int n, PatchStats[] per, int count) {
            this.probability = p;
            this.features = f;
            this.numBlockedPatches = n;
            this.perPatch = per;
            this.patchCount = count;
        }
    }

    /** Run the detection on a single frame. Fully Zero-allocation. */
    public Result analyze(Bitmap frame) {
        if (frame == null || frame.getWidth() < PATCH * 4 || frame.getHeight() < PATCH * 4) {
            cachedResult.probability = 0f;
            cachedResult.numBlockedPatches = 0;
            cachedResult.patchCount = 0;
            return cachedResult;
        }
        
        int W = frame.getWidth();
        int H = frame.getHeight();
        totalRectCount = 0;

        // 1. 顺时针填充 8 个边缘/角落补丁（固定顺序用于空间连通性校验）
        addRect(0, 0);                         // 0: 左上角
        addRect(W - PATCH, 0);                 // 1: 右上角
        addRect(W - PATCH, H - PATCH);         // 2: 右下角
        addRect(0, H - PATCH);                 // 3: 左下角
        addRect((W - PATCH) / 2, 0);           // 4: 上中
        addRect(W - PATCH, (H - PATCH) / 2);   // 5: 右中
        addRect((W - PATCH) / 2, H - PATCH);   // 6: 下中
        addRect(0, (H - PATCH) / 2);           // 7: 左中

        // 2. 填充全图稀疏内部网格
        final int gridCols = 5;
        final int gridRows = 7;
        for (int row = 1; row < gridRows - 1; row++) {
            for (int col = 1; col < gridCols - 1; col++) {
                int x = Math.round(col * (W - PATCH) / (float) (gridCols - 1));
                int y = Math.round(row * (H - PATCH) / (float) (gridRows - 1));
                addRect(x, y);
            }
        }

        // 第一遍循环：提取各补丁的色彩与纹理统计量，并累加全局亮度
        float globalLumaSum = 0f;
        for (int i = 0; i < totalRectCount; i++) {
            computePatchMetrics(frame, rectLefts[i], rectTops[i], cachedPerPatchStats[i]);
            globalLumaSum += cachedPerPatchStats[i].meanLuma;
        }
        float globalMeanLuma = globalLumaSum / totalRectCount;

        // 第二遍循环：结合全局上下文，过滤暗光/环境干扰并识别真正的遮挡
        int blockedCount = 0;
        int edgeBlockedCount = 0; 
        
        for (int i = 0; i < totalRectCount; i++) {
            PatchStats s = cachedPerPatchStats[i];
            
            // 【自适应夜景环境调优】：如果全图处于暗光，提升单补丁判暗的严苛度（必须比全局平均更暗）
            float relativeDarkThresh = Math.min(DARK_LUMA, globalMeanLuma * 0.7f);
            
            boolean darkSmooth = (s.meanLuma < relativeDarkThresh && s.gradMag < LOW_GRAD)
                    || (s.darkRatio > 0.75f && s.gradMag < (LOW_GRAD + 4f) && s.meanLuma < globalMeanLuma * 0.8f);
            
            boolean warmSmooth = s.warmRatio > 0.55f && s.gradMag < 18f && s.stdLuma < 0.18f;
            
            s.isBlocked = darkSmooth || warmSmooth;
            
            if (s.isBlocked) {
                blockedCount++;
                if (i < EDGE_PATCH_COUNT) {
                    edgeBlockedCount++;
                }
            }
        }

        // 【空间连通性拦截】：手指遮挡一定伴随物理边缘侵入。如果内部有零星触发，但边缘 8 补丁完好，直接断定为白墙/衣服等纯色误报
        if (blockedCount >= MIN_BLOCKED_PATCHES && edgeBlockedCount == 0) {
            blockedCount = 0; 
        }

        // 3. 聚合特征与最终评分
        aggregate(cachedPerPatchStats, totalRectCount, cachedFeatures, blockedCount);
        
        cachedResult.probability = scoreFromCount(blockedCount, totalRectCount, edgeBlockedCount);
        cachedResult.numBlockedPatches = blockedCount;
        cachedResult.patchCount = totalRectCount;

        return cachedResult;
    }

    private void addRect(int left, int top) {
        if (totalRectCount >= MAX_PATCH_COUNT) return;
        rectLefts[totalRectCount] = left;
        rectTops[totalRectCount] = top;
        totalRectCount++;
    }

    /** 核心 Patch 级计算：引入像素下采样，降本增效并抹平高频传感器噪点 */
    private void computePatchMetrics(Bitmap frame, int left, int top, PatchStats outStats) {
        frame.getPixels(patchBuf, 0, PATCH, left, top, PATCH, PATCH);

        float sum = 0f, sumSq = 0f;
        int darkCount = 0;
        int warmCount = 0;
        
        // 性能与抗噪调优：Step=2 隔行隔列采样，循环次数缩减至 1/4，天然过滤高频 Noise
        final int step = 2;
        int sampleCount = 0;

        Arrays.fill(lumaBuf, 0f);

        for (int y = 0; y < PATCH; y += step) {
            for (int x = 0; x < PATCH; x += step) {
                int i = y * PATCH + x;
                int px = patchBuf[i];
                int r8 = (px >> 16) & 0xFF;
                int g8 = (px >> 8) & 0xFF;
                int b8 = px & 0xFF;
                
                float lumaVal = (0.299f * r8 + 0.587f * g8 + 0.114f * b8) / 255f;
                lumaBuf[i] = lumaVal;
                
                sum += lumaVal;
                sumSq += lumaVal * lumaVal;
                sampleCount++;

                if (lumaVal < DARK_LUMA) darkCount++;
                
                // 肤色/肉粉色/皮下漫反射特征谱判定
                if (r8 > 55 && r8 > g8 * 1.06f && r8 > b8 * 1.12f && g8 > b8 * 0.75f) {
                    warmCount++;
                }
            }
        }

        float mean = sum / sampleCount;
        float var = Math.max(0f, sumSq / sampleCount - mean * mean);
        float std = (float) Math.sqrt(var);
        float darkRatio = (float) darkCount / sampleCount;
        float warmRatio = (float) warmCount / sampleCount;

        // Sobel 梯度计算（基于相同的采样步长，计算越界保护处理）
        float gradSum = 0f;
        int gradCount = 0;
        for (int y = step; y < PATCH - step; y += step) {
            for (int x = step; x < PATCH - step; x += step) {
                int c = y * PATCH + x;
                float gx = lumaBuf[c + step] - lumaBuf[c - step];
                float gy = lumaBuf[c + step * PATCH] - lumaBuf[c - step * PATCH];
                gradSum += Math.abs(gx) + Math.abs(gy);
                gradCount++;
            }
        }
        
        float gradMag = (gradSum / Math.max(gradCount, 1)) * 255f;

        // 更新状态结构体
        outStats.update(mean, std, gradMag, darkRatio, warmRatio, false);
    }

    /** 聚合所有特征至 18 维向量 */
    private static void aggregate(PatchStats[] stats, int count, float[] f, int blockedCount) {
        float minMean = Float.MAX_VALUE, maxMean = -Float.MAX_VALUE, sumMean = 0;
        float minStd = Float.MAX_VALUE, maxStd = -Float.MAX_VALUE, sumStd = 0;
        float minGrad = Float.MAX_VALUE, maxGrad = -Float.MAX_VALUE, sumGrad = 0;
        float minDark = Float.MAX_VALUE, maxDark = -Float.MAX_VALUE, sumDark = 0;

        for (int i = 0; i < count; i++) {
            PatchStats s = stats[i];
            sumMean += s.meanLuma; minMean = Math.min(minMean, s.meanLuma); maxMean = Math.max(maxMean, s.meanLuma);
            sumStd += s.stdLuma;   minStd = Math.min(minStd, s.stdLuma);   maxStd = Math.max(maxStd, s.stdLuma);
            sumGrad += s.gradMag;  minGrad = Math.min(minGrad, s.gradMag); maxGrad = Math.max(maxGrad, s.gradMag);
            // 这一步在 aggregate 方法的循环内部尾部
            sumDark += s.darkRatio;
            minDark = Math.min(minDark, s.darkRatio);
            maxDark = Math.max(maxDark, s.darkRatio);
        }

        int k = 0;
        f[k++] = minMean; 
        f[k++] = sumMean / count; 
        f[k++] = maxMean;
        
        f[k++] = minStd;  
        f[k++] = sumStd / count;  
        f[k++] = maxStd;
        
        f[k++] = minGrad; 
        f[k++] = sumGrad / count; 
        f[k++] = maxGrad;
        
        f[k++] = minDark; 
        f[k++] = sumDark / count; 
        f[k++] = maxDark;
        
        f[k++] = blockedCount;
        f[k++] = 0f;
        f[k++] = 0f;
    }

    private static float scoreFromCount(int blocked, int total, int edgeBlocked) {
        if (blocked < MIN_BLOCKED_PATCHES || edgeBlocked == 0) {
            return 0f;
        }
        
        float ratio = blocked / Math.max(1f, (float) total);
        
        // 增加边缘遮挡权重奖惩：边缘点触发越多，置信度成正比例上调
        float edgeBonus = 0.05f * edgeBlocked;
        float p = 0.20f + 0.10f * blocked + 0.65f * ratio + edgeBonus;
        
        if (p > 0.98f) {
            p = 0.98f;
        }
        return p;
    }

    public float probability(Bitmap frame) {
        return analyze(frame).probability;
    }

    public void injectInto(Bitmap frame, Map<String, Float> signals) {
        signals.put("lens_blocked_corner_prob", probability(frame));
    }

    /**
     * 调试映射方法（Debug / Logging）
     * 采用 LinkedHashMap 接收零碎统计，提供可视化追溯能力。
     */
    public Map<String, Float> debugMap(Result r) {
        Map<String, Float> m = new LinkedHashMap<>();
        m.put("lens_blocked_prob", r.probability);
        m.put("lens_blocked_count", (float) r.numBlockedPatches);
        
        for (int i = 0; i < r.patchCount; i++) {
            PatchStats s = r.perPatch[i];
            String tag = (i < cachedDebugKeys.length) ? cachedDebugKeys[i] : "p" + i + "_";
            
            m.put(tag + "mean", s.meanLuma);
            m.put(tag + "std", s.stdLuma);
            m.put(tag + "grad", s.gradMag);
            m.put(tag + "dark", s.darkRatio);
            m.put(tag + "warm", s.warmRatio);
            m.put(tag + "blocked", s.isBlocked ? 1.0f : 0.0f);
        }
        return m;
    }
}
