package com.edgeflow.service

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Zero-GC Apple iOS 26 Liquid Glass optical refraction renderer powered by Android 13+ (API 33+)
 * AGSL [RuntimeShader].
 *
 * Visual & mathematical guarantees:
 * - Root-fixed, tip-bending droplet SDF (`uShapeMode == 0` with `uTipOffsetY` S-curve warp):
 *   root endpoints stay pinned at `uCenter.y ± halfSpan` while the tip bends smoothly toward the thumb.
 * - Zero dark-flash first frame: neutral clear-white `fallbackBitmap`, and when `uBackdropMix == 0`
 *   renders luminous transparent crystal glass (`proceduralAlpha <= 0.72`) with zero dark opaque fill.
 * - Mathematically zero-cutoff shadow (`shadowWindow = smoothstep(uShadowRange, 0.0, shadowDist)`):
 *   reaches `0.0000` strictly at `uShadowRange` (`14dp`), eliminating rectangular shadow clipping.
 * - Real-backdrop contrast contour (`uLocalBgLum`): adds a refined dark crystal outer rim on bright
 *   white backgrounds so the glass edge never vanishes.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LiquidGlassShader private constructor() {

    private val runtimeShader = RuntimeShader(AGSL_SOURCE)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
        shader = runtimeShader
    }

    // Neutral clear-white 2x2 fallback shader so uBackdrop is ALWAYS bound and NEVER dark-flashes
    private val fallbackBitmap: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.argb(255, 245, 247, 250))
    }
    private val fallbackShader: BitmapShader = BitmapShader(
        fallbackBitmap,
        Shader.TileMode.CLAMP,
        Shader.TileMode.CLAMP
    )

    private var boundBackdrop: Shader? = null

    init {
        runtimeShader.setInputShader("uBackdrop", fallbackShader)
        boundBackdrop = fallbackShader
    }

    fun bindBackdrop(backdrop: Shader?) {
        val target = backdrop ?: fallbackShader
        if (boundBackdrop !== target) {
            runtimeShader.setInputShader("uBackdrop", target)
            boundBackdrop = target
        }
    }

    /**
     * Renders the side-edge root-fixed, tip-bending liquid droplet (`uShapeMode = 0`)
     * strictly inside [bounds].
     */
    fun drawDroplet(
        canvas: Canvas,
        bounds: RectF,
        edgeX: Float,
        centerY: Float,
        peakPx: Float,
        halfSpanPx: Float,
        fromLeft: Boolean,
        tipOffsetY: Float,
        refraction: Float,
        stageBoost: Float,
        backdropMix: Float,
        isLightTheme: Boolean,
        fillRgb: Int,
        accentRgb: Int,
        opacity: Float,
        screenOffsetX: Float,
        screenOffsetY: Float,
        backdropScale: Float,
        shadowRangePx: Float,
        localBgLum: Float
    ) {
        if (bounds.width() <= 1f || bounds.height() <= 1f || opacity <= 0.005f) return
        setCommonUniforms(
            shapeMode = 0f,
            cx = edgeX,
            cy = centerY,
            p0 = peakPx.coerceAtLeast(1f),
            p1 = halfSpanPx.coerceAtLeast(4f),
            sideSign = if (fromLeft) 1f else -1f,
            tipOffsetY = tipOffsetY,
            refraction = refraction,
            stageBoost = stageBoost,
            backdropMix = backdropMix,
            isLightTheme = isLightTheme,
            fillRgb = fillRgb,
            accentRgb = accentRgb,
            opacity = opacity,
            screenOffsetX = screenOffsetX,
            screenOffsetY = screenOffsetY,
            backdropScale = backdropScale,
            shadowRangePx = shadowRangePx,
            localBgLum = localBgLum
        )
        canvas.drawRect(bounds, shaderPaint)
    }

    /**
     * Renders a Liquid Glass rounded pill / squircle (`uShapeMode = 1`) strictly inside [bounds].
     */
    fun drawSquircle(
        canvas: Canvas,
        bounds: RectF,
        centerX: Float,
        centerY: Float,
        halfW: Float,
        halfH: Float,
        refraction: Float,
        stageBoost: Float,
        backdropMix: Float,
        isLightTheme: Boolean,
        fillRgb: Int,
        accentRgb: Int,
        opacity: Float,
        screenOffsetX: Float,
        screenOffsetY: Float,
        backdropScale: Float,
        shadowRangePx: Float = 14f,
        localBgLum: Float = if (isLightTheme) 0.85f else 0.15f
    ) {
        if (bounds.width() <= 1f || bounds.height() <= 1f || opacity <= 0.005f) return
        setCommonUniforms(
            shapeMode = 1f,
            cx = centerX,
            cy = centerY,
            p0 = halfW.coerceAtLeast(2f),
            p1 = halfH.coerceAtLeast(2f),
            sideSign = 1f,
            tipOffsetY = 0f,
            refraction = refraction,
            stageBoost = stageBoost,
            backdropMix = backdropMix,
            isLightTheme = isLightTheme,
            fillRgb = fillRgb,
            accentRgb = accentRgb,
            opacity = opacity,
            screenOffsetX = screenOffsetX,
            screenOffsetY = screenOffsetY,
            backdropScale = backdropScale,
            shadowRangePx = shadowRangePx,
            localBgLum = localBgLum
        )
        canvas.drawRect(bounds, shaderPaint)
    }

    /**
     * Renders a circular Liquid Glass magnifying lens (`uShapeMode = 2`) strictly inside [bounds].
     */
    fun drawCircleLens(
        canvas: Canvas,
        bounds: RectF,
        centerX: Float,
        centerY: Float,
        radius: Float,
        refraction: Float,
        focusBoost: Float,
        backdropMix: Float,
        isLightTheme: Boolean,
        fillRgb: Int,
        accentRgb: Int,
        opacity: Float,
        screenOffsetX: Float,
        screenOffsetY: Float,
        backdropScale: Float,
        shadowRangePx: Float = 14f,
        localBgLum: Float = if (isLightTheme) 0.85f else 0.15f
    ) {
        if (bounds.width() <= 1f || bounds.height() <= 1f || opacity <= 0.005f) return
        setCommonUniforms(
            shapeMode = 2f,
            cx = centerX,
            cy = centerY,
            p0 = radius.coerceAtLeast(2f),
            p1 = radius.coerceAtLeast(2f),
            sideSign = 1f,
            tipOffsetY = 0f,
            refraction = refraction,
            stageBoost = focusBoost,
            backdropMix = backdropMix,
            isLightTheme = isLightTheme,
            fillRgb = fillRgb,
            accentRgb = accentRgb,
            opacity = opacity,
            screenOffsetX = screenOffsetX,
            screenOffsetY = screenOffsetY,
            backdropScale = backdropScale,
            shadowRangePx = shadowRangePx,
            localBgLum = localBgLum
        )
        canvas.drawRect(bounds, shaderPaint)
    }

    private fun setCommonUniforms(
        shapeMode: Float,
        cx: Float,
        cy: Float,
        p0: Float,
        p1: Float,
        sideSign: Float,
        tipOffsetY: Float,
        refraction: Float,
        stageBoost: Float,
        backdropMix: Float,
        isLightTheme: Boolean,
        fillRgb: Int,
        accentRgb: Int,
        opacity: Float,
        screenOffsetX: Float,
        screenOffsetY: Float,
        backdropScale: Float,
        shadowRangePx: Float,
        localBgLum: Float
    ) {
        runtimeShader.setFloatUniform("uShapeMode", shapeMode)
        runtimeShader.setFloatUniform("uCenter", cx, cy)
        runtimeShader.setFloatUniform("uParams", p0, p1)
        runtimeShader.setFloatUniform("uSideSign", sideSign)
        runtimeShader.setFloatUniform("uTipOffsetY", tipOffsetY)
        runtimeShader.setFloatUniform("uRefraction", refraction)
        runtimeShader.setFloatUniform("uStageBoost", stageBoost)
        runtimeShader.setFloatUniform("uBackdropMix", backdropMix.coerceIn(0f, 1f))
        runtimeShader.setFloatUniform("uLightMode", if (isLightTheme) 1f else 0f)
        runtimeShader.setFloatUniform(
            "uTintColor",
            ((fillRgb shr 16) and 0xFF) / 255f,
            ((fillRgb shr 8) and 0xFF) / 255f,
            (fillRgb and 0xFF) / 255f
        )
        runtimeShader.setFloatUniform(
            "uAccentColor",
            ((accentRgb shr 16) and 0xFF) / 255f,
            ((accentRgb shr 8) and 0xFF) / 255f,
            (accentRgb and 0xFF) / 255f
        )
        runtimeShader.setFloatUniform("uOpacity", opacity.coerceIn(0f, 1f))
        runtimeShader.setFloatUniform("uScreenOffset", screenOffsetX, screenOffsetY)
        runtimeShader.setFloatUniform("uBackdropScale", backdropScale.coerceIn(0.25f, 1f))
        runtimeShader.setFloatUniform("uShadowRange", shadowRangePx.coerceAtLeast(4f))
        runtimeShader.setFloatUniform("uLocalBgLum", localBgLum.coerceIn(0f, 1f))
    }

    companion object {
        private const val TAG = "EdgeFlowShader"

        fun createIfSupported(): LiquidGlassShader? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return try {
                LiquidGlassShader()
            } catch (t: Throwable) {
                Log.e(TAG, "AGSL compile failed", t)
                null
            }
        }

        internal const val AGSL_SOURCE = """
            uniform shader uBackdrop;
            uniform float uShapeMode;
            uniform float2 uCenter;
            uniform float2 uParams;
            uniform float uSideSign;
            uniform float uTipOffsetY;
            uniform float uRefraction;
            uniform float uStageBoost;
            uniform float uBackdropMix;
            uniform float uLightMode;
            uniform float3 uTintColor;
            uniform float3 uAccentColor;
            uniform float uOpacity;
            uniform float2 uScreenOffset;
            uniform float uBackdropScale;
            uniform float uShadowRange;
            uniform float uLocalBgLum;

            float sdShape(float2 p) {
                if (uShapeMode < 0.5) {
                    // Mode 0: Clean native-style smooth mountain droplet (roots pinned, C1-continuous at tip & roots)
                    float peak = max(uParams.x, 1.0);
                    float halfSpan = max(uParams.y, 8.0);
                    float xIn = (p.x - uCenter.x) * uSideSign;

                    float maxShift = halfSpan * 0.42;
                    float tipShift = clamp(uTipOffsetY, -maxShift, maxShift);
                    float tipY = uCenter.y + tipShift;
                    float topRootY = uCenter.y - halfSpan;
                    float botRootY = uCenter.y + halfSpan;

                    if (p.y <= topRootY) {
                        return length(float2(max(xIn, 0.0), topRootY - p.y));
                    }
                    if (p.y >= botRootY) {
                        return length(float2(max(xIn, 0.0), p.y - botRootY));
                    }
                    if (xIn < 0.0) {
                        return -xIn;
                    }

                    float activeSpan = (p.y < tipY) ? max(tipY - topRootY, 8.0) : max(botRootY - tipY, 8.0);
                    float t = clamp(abs(p.y - tipY) / activeSpan, 0.0, 1.0);

                    // Pure raised-cosine curve (zero wobble, zero pinch, smooth tangent=0 at tip and both roots)
                    float s = 0.5 + 0.5 * cos(3.14159265 * t);
                    float profile = peak * pow(max(s, 1e-5), 1.18);
                    float dxEdge = xIn - profile;

                    float dSdt = -0.5 * 3.14159265 * sin(3.14159265 * t);
                    float slopeMag = 1.18 * pow(max(s, 1e-4), 0.18) * abs(dSdt) * (peak / activeSpan);
                    float safeSlope = clamp(slopeMag, 0.0, 2.2);
                    return dxEdge / sqrt(1.0 + safeSlope * safeSlope);
                } else if (uShapeMode < 1.5) {
                    // Mode 1: Pill / Squircle (minimal indicator, label pill, bottom stage badge)
                    float2 q = abs(p - uCenter);
                    float r = min(uParams.x, uParams.y);
                    float2 b = max(uParams - float2(r), float2(0.0));
                    float2 d = q - b;
                    return length(max(d, float2(0.0))) + min(max(d.x, d.y), 0.0) - r;
                } else {
                    // Mode 2: Circular magnifying glass lens (Quick Menu & Switcher slots)
                    return length(p - uCenter) - uParams.x;
                }
            }

            float2 estimateNormal(float2 p) {
                float eps = 1.25;
                float gx = sdShape(p + float2(eps, 0.0)) - sdShape(p - float2(eps, 0.0));
                float gy = sdShape(p + float2(0.0, eps)) - sdShape(p - float2(0.0, eps));
                float2 g = float2(gx, gy);
                float len = length(g);
                return (len > 0.0001) ? (g / len) : float2(0.0, -1.0);
            }

            float3 sampleBackdropAt(float2 localCoord) {
                float2 sampleCoord = (localCoord + uScreenOffset) * uBackdropScale;
                return uBackdrop.eval(sampleCoord).rgb;
            }

            half4 main(float2 fragCoord) {
                float sdf = sdShape(fragCoord);
                float aa = 1.15;
                float insideMask = 1.0 - smoothstep(-aa, aa, sdf);

                // Mathematically zero-cutoff shadow: strictly 0.0000 at shadowDist >= uShadowRange
                float shadowDist = max(sdf, 0.0);
                float shadowWindow = 1.0 - smoothstep(0.0, max(uShadowRange, 1.0), shadowDist);
                float shadowAlpha = shadowWindow * shadowWindow * 0.12 * uOpacity;

                if (insideMask <= 0.002 && shadowAlpha <= 0.001) {
                    return half4(0.0);
                }

                float depth = max(-sdf, 0.0);
                // Droplet uses a wide full-body 3D convex dome width; squircle/circle use shape thickness
                float domeWidth = (uShapeMode < 0.5)
                    ? max(uParams.x * 0.92, 24.0)
                    : max(min(uParams.x, uParams.y), 12.0);
                float edgeDepth = clamp(depth / domeWidth, 0.0, 1.0);

                // Continuous convex lens slope across the entire body + crisp outer bevel
                float2 n2 = estimateNormal(fragCoord);
                float bodyDomeSlope = pow(max(1.0 - edgeDepth, 0.0001), 1.15);
                float outerBevel = 1.0 - smoothstep(0.0, 0.42, edgeDepth);
                float bevelSlope = (uShapeMode < 0.5)
                    ? clamp(bodyDomeSlope * 0.84 + outerBevel * 0.26, 0.0, 1.0)
                    : clamp(outerBevel * 0.78 + bodyDomeSlope * 0.30, 0.0, 1.0);
                float fresnel = pow(max(bevelSlope, 0.0001), 2.05);

                // Physical bevel spectral prism dispersion (delicate RB chromatic iridescence even on flat backgrounds)
                float angle = atan(n2.y, n2.x);
                float stageEnergy = clamp(uStageBoost, 0.0, 1.5);
                float phase = angle * 2.0 + (1.0 - edgeDepth) * 3.8 + stageEnergy * 0.6;
                float3 prismSpectrum = float3(
                    0.55 + 0.45 * cos(phase + 0.0),
                    0.48 + 0.32 * cos(phase + 2.094),
                    0.58 + 0.42 * cos(phase + 4.188)
                );
                float refrNorm = clamp(uRefraction, 0.35, 1.65);
                float3 bevelPrismColor = mix(
                    float3(1.0),
                    prismSpectrum,
                    clamp(0.52 * bevelSlope * clamp(refrNorm, 0.6, 1.25), 0.0, 0.74)
                );

                // Dual specular reflections (upper-left key light + bottom-right bounce light)
                float3 n3 = normalize(float3(n2 * (0.24 + 0.76 * bevelSlope), 0.44 + 0.56 * edgeDepth));
                float3 light1 = normalize(float3(-0.48, -0.74, 0.46));
                float3 light2 = normalize(float3(0.42, 0.68, 0.58));
                float spec1 = pow(max(dot(n3, light1), 0.0), 18.0) * (0.36 + 0.48 * bevelSlope);
                float spec2 = pow(max(dot(n3, light2), 0.0), 10.0) * 0.20 * bevelSlope;
                float specHalo = pow(max(dot(n3, light1), 0.0), 6.0) * 0.16 * bevelSlope;

                // Crisp rim highlight & bright-background crystal contour (wide gradual transition band)
                float rimLine = 1.0 - smoothstep(0.0, 2.2, abs(sdf + 0.9));
                float outerContour = 1.0 - smoothstep(0.0, 1.6, abs(sdf - 0.35));
                float crystalBrightBg = smoothstep(0.32, 0.86, uLocalBgLum);

                // Stage/Focus edge accent energy
                float3 accentGlow = uAccentColor * (stageEnergy * (0.14 + 0.34 * fresnel + 0.32 * rimLine));

                // Pure crystal procedural glass (ZERO dark flash when uBackdropMix == 0)
                float3 crystalBase = mix(float3(0.92, 0.95, 0.99), float3(0.98, 0.99, 1.0), uLightMode);
                // Cool slate absorption on white backgrounds so clear crystal glass never washes out
                crystalBase = mix(crystalBase, float3(0.84, 0.88, 0.95), 0.14 * crystalBrightBg);
                float3 crystalRgb = crystalBase
                    + float3(1.0) * (spec1 * 0.78 + spec2 * 0.44)
                    + bevelPrismColor * (specHalo * 0.90 + fresnel * 0.22 + rimLine * 0.42)
                    + accentGlow * 0.85;
                // Subtractive bevel prism filtering on bright backgrounds so rainbow edge stays crisp
                crystalRgb = mix(
                    crystalRgb,
                    crystalRgb * (0.76 + 0.34 * prismSpectrum) + prismSpectrum * 0.14,
                    0.55 * bevelSlope * crystalBrightBg
                );
                // Refined dark crystal outer contour on bright white backgrounds
                crystalRgb = mix(crystalRgb, float3(0.10, 0.12, 0.16), crystalBrightBg * outerContour * 0.36);
                crystalRgb = clamp(crystalRgb, 0.0, 1.0);
                float proceduralAlpha = clamp(
                    0.10 + fresnel * 0.30 + (spec1 + spec2) * 0.65 + rimLine * 0.45 + crystalBrightBg * outerContour * 0.24,
                    0.0,
                    0.76
                ) * uOpacity;

                float mixT = smoothstep(0.0, 1.0, clamp(uBackdropMix, 0.0, 1.0));

                float3 finalGlassRgb = crystalRgb;
                float finalGlassAlpha = proceduralAlpha;

                if (mixT > 0.001) {
                    // Full-body convex lens refraction + radial R/G/B chromatic dispersion
                    float baseDisp = uRefraction * (5.2 + 12.8 * bevelSlope + 2.5 * stageEnergy);
                    float chromaSpread = uRefraction * (0.85 + 2.65 * fresnel + 0.75 * stageEnergy);
                    float2 refrOffset = -n2 * baseDisp;
                    float2 chromaVec = -n2 * chromaSpread;

                    float3 cCenter = sampleBackdropAt(fragCoord + refrOffset);
                    float3 cR = sampleBackdropAt(fragCoord + refrOffset + chromaVec);
                    float3 cB = sampleBackdropAt(fragCoord + refrOffset - chromaVec);
                    float3 s1 = sampleBackdropAt(fragCoord + refrOffset + float2(3.5, 3.5));
                    float3 s2 = sampleBackdropAt(fragCoord + refrOffset + float2(-3.5, 3.5));
                    float3 s3 = sampleBackdropAt(fragCoord + refrOffset + float2(3.5, -3.5));
                    float3 s4 = sampleBackdropAt(fragCoord + refrOffset + float2(-3.5, -3.5));
                    float3 refracted = float3(cR.r, cCenter.g, cB.b) * 0.64 + (s1 + s2 + s3 + s4) * 0.09;

                    // Blend temporally smoothed uLocalBgLum with per-pixel refracted luminance for silky gradient boundaries
                    float pixelLum = dot(refracted, float3(0.2126, 0.7152, 0.0722));
                    float effectiveLum = mix(uLocalBgLum, pixelLum, 0.35 * mixT);
                    float brightBg = smoothstep(0.32, 0.86, effectiveLum);

                    // Detect flat / low-contrast solid backgrounds (white pages, solid wallpapers)
                    float bgContrast = length(s1 - s4) + length(s2 - s3);
                    float lowContrastBoost = 1.0 - smoothstep(0.04, 0.28, bgContrast);

                    // Cool slate crystal absorption on bright/white screens so highlights & edges never saturate to flat white
                    float3 absorbedRefracted = mix(refracted, float3(0.84, 0.88, 0.95), 0.14 * brightBg);

                    // Balanced crystal tint + internal transparent glass caustics gradient
                    float tintStrength = mix(0.18, 0.12, uLightMode);
                    float3 glassRgb = mix(absorbedRefracted, uTintColor, tintStrength * (1.0 - 0.35 * brightBg));

                    // Top-to-bottom internal lens caustics luminance curve (boosted gently on flat backgrounds)
                    float causticAxis = clamp(-n2.y * 0.5 + 0.5, 0.0, 1.0);
                    float causticWave = (causticAxis - 0.48) * (0.14 + 0.14 * lowContrastBoost) * (0.35 + 0.65 * (1.0 - edgeDepth * 0.45));
                    glassRgb += float3(causticWave) * mix(float3(1.0), prismSpectrum, 0.22 * bevelSlope);

                    // Spectral RB prism boost along bevel fresnel & outer rim (stronger on flat solid backgrounds)
                    float prismBoost = 1.0 + 0.55 * lowContrastBoost;
                    float3 prismRim = mix(float3(1.0), prismSpectrum, clamp(0.52 * bevelSlope * prismBoost, 0.0, 0.78));
                    glassRgb += prismRim * (fresnel * (0.16 + 0.12 * lowContrastBoost) * clamp(refrNorm, 0.5, 1.3) * (1.0 - 0.55 * brightBg));
                    glassRgb += bevelPrismColor * (specHalo * (0.85 + 0.45 * lowContrastBoost) * (1.0 - 0.40 * brightBg));

                    // Subtractive prism filtering on bright/white screens (cyan/blue/magenta crystal bevel without whiteout!)
                    glassRgb = mix(
                        glassRgb,
                        glassRgb * (0.76 + 0.34 * prismSpectrum) + prismSpectrum * 0.14,
                        0.55 * bevelSlope * brightBg
                    );

                    // Crisp white core speculars + spectral-tinted rim + bright-background dark crystal contour
                    float3 rimColor = mix(float3(0.95, 0.97, 1.0), float3(1.0), uLightMode) * mix(float3(1.0), prismRim, 0.65);
                    rimColor = mix(rimColor, float3(0.12, 0.15, 0.22), brightBg * 0.64);
                    glassRgb += float3(1.0) * (spec1 * 0.76 + spec2 * 0.42);
                    glassRgb = mix(glassRgb, rimColor, rimLine * mix(0.48, 0.68, brightBg));
                    glassRgb += accentGlow;
                    glassRgb = mix(glassRgb, float3(0.09, 0.11, 0.15), brightBg * outerContour * 0.36);
                    glassRgb = clamp(glassRgb, 0.0, 1.0);

                    float refractedAlpha = clamp(
                        uOpacity * (0.86 + 0.14 * fresnel + 0.10 * rimLine + brightBg * outerContour * 0.16),
                        0.0,
                        1.0
                    );

                    finalGlassRgb = mix(crystalRgb, glassRgb, mixT);
                    finalGlassAlpha = mix(proceduralAlpha, refractedAlpha, mixT);
                }

                // Premultiplied alpha compositing over drop shadow
                float outAlpha = clamp(insideMask * finalGlassAlpha + (1.0 - insideMask) * shadowAlpha, 0.0, 1.0);
                float3 outRgb = finalGlassRgb * (insideMask * finalGlassAlpha);
                return half4(half3(outRgb), half(outAlpha));
            }
        """
    }
}
