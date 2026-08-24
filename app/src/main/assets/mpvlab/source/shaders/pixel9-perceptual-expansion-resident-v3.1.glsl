//!PARAM R08_BYPASS
//!DESC Internal R08 no-flash original comparison
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 1
0

//!PARAM LUMA_MASTER
//!DESC Luma master
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 2.0
1.0

//!PARAM CHROMA_MASTER
//!DESC Chroma master
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 3.0
1.0

//!PARAM LUMA_PIVOT
//!DESC Luma pivot
//!TYPE float
//!MINIMUM 0.05
//!MAXIMUM 0.50
0.18

//!PARAM LUMA_CONTRAST
//!DESC Curve contrast
//!TYPE float
//!MINIMUM -1.0
//!MAXIMUM 1.5
0.28

//!PARAM LUMA_HIGHLIGHT_START
//!DESC Highlight gate start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.22

//!PARAM LUMA_HIGHLIGHT_END
//!DESC Highlight gate full
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.92

//!PARAM LUMA_HIGHLIGHT
//!DESC Highlight lift
//!TYPE float
//!MINIMUM -0.5
//!MAXIMUM 1.0
0.129

//!PARAM SAT_L_FLOOR
//!DESC Saturation lightness floor
//!TYPE float
//!MINIMUM 0.001
//!MAXIMUM 0.50
0.080

//!PARAM SAT_GATE_START
//!DESC Saturation gate start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.025

//!PARAM SAT_GATE_FULL
//!DESC Saturation gate full
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.5
0.260

//!PARAM SHADOW_GATE_START
//!DESC Shadow gate start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.50
0.025

//!PARAM SHADOW_GATE_FULL
//!DESC Shadow gate full
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.75
0.120

//!PARAM MIDTONE_START
//!DESC Midtone start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.10

//!PARAM MIDTONE_FULL
//!DESC Midtone full
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.30

//!PARAM MIDTONE_FADE_START
//!DESC Midtone fade start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.56

//!PARAM MIDTONE_FADE_END
//!DESC Midtone fade end
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.80

//!PARAM BRIGHT_START
//!DESC Bright gate start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.34

//!PARAM BRIGHT_FULL
//!DESC Bright gate full
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.90

//!PARAM BASE_CHROMA
//!DESC Base chroma
//!TYPE float
//!MINIMUM -0.50
//!MAXIMUM 1.50
0.0129

//!PARAM MID_CHROMA
//!DESC Mid chroma
//!TYPE float
//!MINIMUM -0.50
//!MAXIMUM 2.00
0.05375

//!PARAM BRIGHT_CHROMA
//!DESC Bright chroma
//!TYPE float
//!MINIMUM -0.50
//!MAXIMUM 3.00
0.252625

//!PARAM SKIN_RETAIN
//!DESC Skin boost retained
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.22

//!PARAM SKIN_CENTER
//!DESC Skin hue center
//!TYPE float
//!MINIMUM -3.14159265
//!MAXIMUM 3.14159265
0.87

//!PARAM SKIN_HUE_INNER
//!DESC Skin hue inner
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 3.14159265
0.24

//!PARAM SKIN_HUE_OUTER
//!DESC Skin hue outer
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 3.14159265
0.72

//!PARAM SKIN_L_LOW_START
//!DESC Skin L low start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.28

//!PARAM SKIN_L_LOW_FULL
//!DESC Skin L low full
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.46

//!PARAM SKIN_L_HIGH_START
//!DESC Skin L high start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.82

//!PARAM SKIN_L_HIGH_END
//!DESC Skin L high end
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.96

//!PARAM SKIN_C_LOW_START
//!DESC Skin C low start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.50
0.018

//!PARAM SKIN_C_LOW_FULL
//!DESC Skin C low full
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.50
0.050

//!PARAM SKIN_C_HIGH_START
//!DESC Skin C high start
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.75
0.165

//!PARAM SKIN_C_HIGH_END
//!DESC Skin C high end
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.75
0.255

//!PARAM RGB_LOW
//!DESC RGB low boundary
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.02
0.00005

//!PARAM RGB_HIGH
//!DESC RGB high boundary
//!TYPE float
//!MINIMUM 0.98
//!MAXIMUM 1.0
0.99995

//!PARAM GAMUT_MARGIN
//!DESC Gamut margin
//!TYPE float
//!MINIMUM 0.90
//!MAXIMUM 1.0
0.997

//!PARAM GAMUT_ITERATIONS
//!DESC Gamut iterations
//!TYPE int
//!MINIMUM 1
//!MAXIMUM 12
7

//!PARAM SDR_COMPRESS
//!DESC HDR to SDR compression
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM DEBUG_VIEW
//!DESC Clipping indicator
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 3
0

//!HOOK LINEAR
//!BIND HOOKED
//!DESC Pixel 9 Pro XL - Perceptual SDR Expansion v3.1 Resident R08

/*
    Pixel 9 Pro XL - Perceptual SDR Expansion v3.1
    R08 resident-parameter edition.

    The render math is intentionally the V3.1 workstation math. Shader Lab
    values are resident vo=gpu PARAM uniforms. R08_BYPASS is an internal-only
    comparison uniform so original/tuned switching never tears down the hook.
*/

const vec3 LUMA709 = vec3(0.2126, 0.7152, 0.0722);

vec3 linear709_to_oklab(vec3 c)
{
    float l =
        0.4122214708 * c.r +
        0.5363325363 * c.g +
        0.0514459929 * c.b;

    float m =
        0.2119034982 * c.r +
        0.6806995451 * c.g +
        0.1073969566 * c.b;

    float s =
        0.0883024619 * c.r +
        0.2817188376 * c.g +
        0.6299787005 * c.b;

    float l_ = pow(max(l, 0.0), 1.0 / 3.0);
    float m_ = pow(max(m, 0.0), 1.0 / 3.0);
    float s_ = pow(max(s, 0.0), 1.0 / 3.0);

    return vec3(
         0.2104542553 * l_
       + 0.7936177850 * m_
       - 0.0040720468 * s_,

         1.9779984951 * l_
       - 2.4285922050 * m_
       + 0.4505937099 * s_,

         0.0259040371 * l_
       + 0.7827717662 * m_
       - 0.8086757660 * s_
    );
}

vec3 oklab_to_linear709(vec3 lab)
{
    float l_ =
        lab.x
      + 0.3963377774 * lab.y
      + 0.2158037573 * lab.z;

    float m_ =
        lab.x
      - 0.1055613458 * lab.y
      - 0.0638541728 * lab.z;

    float s_ =
        lab.x
      - 0.0894841775 * lab.y
      - 1.2914855480 * lab.z;

    float l = l_ * l_ * l_;
    float m = m_ * m_ * m_;
    float s = s_ * s_ * s_;

    return vec3(
         4.0767416621 * l
       - 3.3077115913 * m
       + 0.2309699292 * s,

        -1.2684380046 * l
       + 2.6097574011 * m
       - 0.3413193965 * s,

        -0.0041960863 * l
       - 0.7034186147 * m
       + 1.7076147010 * s
    );
}

bool in_gamut(vec3 c)
{
    return
        c.r >= RGB_LOW  && c.r <= RGB_HIGH &&
        c.g >= RGB_LOW  && c.g <= RGB_HIGH &&
        c.b >= RGB_LOW  && c.b <= RGB_HIGH;
}

float angular_distance(float a, float b)
{
    float d = a - b;
    return abs(atan(sin(d), cos(d)));
}

float expand_luminance(float y)
{
    y = clamp(y, 0.0, 1.0);

    float contrastTerm =
        (LUMA_CONTRAST * LUMA_MASTER) *
        (y - LUMA_PIVOT) * y * (1.0 - y);

    float highlightGate =
        smoothstep(LUMA_HIGHLIGHT_START, LUMA_HIGHLIGHT_END, y);

    float highlightTerm =
        (LUMA_HIGHLIGHT * LUMA_MASTER) *
        highlightGate * y * (1.0 - y);

    return clamp(
        y + contrastTerm + highlightTerm,
        0.0,
        1.0
    );
}

float skin_mask(vec3 lab)
{
    float L = lab.x;
    float C = length(lab.yz);

    if (C < 0.000001)
        return 0.0;

    float hue = atan(lab.z, lab.y);
    float hd = angular_distance(hue, SKIN_CENTER);

    float hueMask =
        1.0 - smoothstep(SKIN_HUE_INNER, SKIN_HUE_OUTER, hd);

    float lightMask =
        smoothstep(SKIN_L_LOW_START, SKIN_L_LOW_FULL, L) *
        (1.0 - smoothstep(SKIN_L_HIGH_START, SKIN_L_HIGH_END, L));

    float chromaMask =
        smoothstep(SKIN_C_LOW_START, SKIN_C_LOW_FULL, C) *
        (1.0 - smoothstep(SKIN_C_HIGH_START, SKIN_C_HIGH_END, C));

    return clamp(hueMask * lightMask * chromaMask, 0.0, 1.0);
}

float find_gamut_chroma_scale(vec3 lab, float requestedScale)
{
    if (requestedScale <= 1.000001)
        return 1.0;

    vec3 direct =
        oklab_to_linear709(
            vec3(lab.x, lab.yz * requestedScale)
        );

    if (in_gamut(direct))
        return requestedScale;

    float lo = 1.0;
    float hi = requestedScale;

    for (int i = 0; i < GAMUT_ITERATIONS; i++) {
        float mid = 0.5 * (lo + hi);

        vec3 candidate =
            oklab_to_linear709(
                vec3(lab.x, lab.yz * mid)
            );

        if (in_gamut(candidate))
            lo = mid;
        else
            hi = mid;
    }

    return 1.0 + (lo - 1.0) * GAMUT_MARGIN;
}

vec4 hook()
{
    vec4 src = HOOKED_tex(HOOKED_pos);

    if (R08_BYPASS != 0)
        return src;

    vec3 rgb = max(src.rgb, vec3(0.0));

    float Y = dot(rgb, LUMA709);

    if (Y <= 0.000001)
        return vec4(vec3(0.0), src.a);

    float targetY = expand_luminance(Y);

    float requestedLumScale =
        targetY / max(Y, 0.000001);

    float maxChannel =
        max(max(rgb.r, rgb.g), rgb.b);

    float maxLumScale =
        RGB_HIGH / max(maxChannel, 0.000001);

    float lumScale =
        min(requestedLumScale, maxLumScale);

    bool lumaHighLimited =
        requestedLumScale > maxLumScale + 0.000001;

    bool lumaLowLimited =
        targetY <= RGB_LOW && Y > RGB_LOW + 0.000001;

    vec3 expandedRGB =
        rgb * lumScale;

    float expandedY =
        dot(expandedRGB, LUMA709);

    vec3 lab =
        linear709_to_oklab(expandedRGB);

    float L = lab.x;
    float C = length(lab.yz);

    float perceptualSaturation =
        C / max(L, SAT_L_FLOOR);

    float saturationGate =
        smoothstep(
            SAT_GATE_START,
            SAT_GATE_FULL,
            perceptualSaturation
        );

    float shadowGate =
        smoothstep(
            SHADOW_GATE_START,
            SHADOW_GATE_FULL,
            expandedY
        );

    float midtoneGate =
        smoothstep(MIDTONE_START, MIDTONE_FULL, expandedY) *
        (1.0 - smoothstep(MIDTONE_FADE_START, MIDTONE_FADE_END, expandedY));

    float highlightGate =
        smoothstep(BRIGHT_START, BRIGHT_FULL, expandedY);

    float chromaBoost =
          (BASE_CHROMA * CHROMA_MASTER)
        + (MID_CHROMA * CHROMA_MASTER) * midtoneGate
        + (BRIGHT_CHROMA * CHROMA_MASTER) * highlightGate;

    float skin =
        skin_mask(lab);

    float skinProtection =
        mix(1.0, SKIN_RETAIN, skin);

    float requestedChromaScale =
        1.0 +
        chromaBoost *
        saturationGate *
        shadowGate *
        skinProtection;

    float chromaScale =
        find_gamut_chroma_scale(
            lab,
            requestedChromaScale
        );

    bool gamutLimited =
        chromaScale + 0.000001 < requestedChromaScale;

    vec3 outLab =
        vec3(
            lab.x,
            lab.yz * chromaScale
        );

    vec3 outRGB =
        oklab_to_linear709(outLab);

    vec3 finalRGB = outRGB;

    if (SDR_COMPRESS > 0.000001)
        finalRGB = mix(outRGB, rgb, SDR_COMPRESS);

    if (DEBUG_VIEW != 0) {
        vec3 context = finalRGB * 0.16;

        if (DEBUG_VIEW == 1) {
            if (gamutLimited)
                return vec4(1.0, 0.0, 1.0, src.a);
            return vec4(context, src.a);
        }

        if (DEBUG_VIEW == 2) {
            if (lumaHighLimited)
                return vec4(1.0, 0.0, 0.0, src.a);
            if (lumaLowLimited)
                return vec4(0.0, 0.25, 1.0, src.a);
            return vec4(context, src.a);
        }

        if (gamutLimited && lumaHighLimited)
            return vec4(1.0, 1.0, 0.0, src.a);
        if (gamutLimited && lumaLowLimited)
            return vec4(0.0, 1.0, 1.0, src.a);
        if (lumaHighLimited)
            return vec4(1.0, 0.0, 0.0, src.a);
        if (lumaLowLimited)
            return vec4(0.0, 0.25, 1.0, src.a);
        if (gamutLimited)
            return vec4(1.0, 0.0, 1.0, src.a);
        return vec4(context, src.a);
    }

    return vec4(finalRGB, src.a);
}
