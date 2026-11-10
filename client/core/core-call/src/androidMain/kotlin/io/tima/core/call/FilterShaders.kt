package io.tima.core.call

// Шейдеры фильтров звонка (ПЛАН-(ФЗ)). «Красота», изменение лица и макияж — из gpupixel (pixpark/gpupixel 1.2.4,
// MIT, © 2023 PixPark: beauty_face_unit_filter.cc, box_*_filter.cc, face_reshape_filter.cc, face_makeup_filter.cc);
// зоны морщин, цвет и сборка кадра — свои. GLSL ES 1.00: и Mali, и Adreno старых телефонов.
//
// Пространство координат — текстурное, как у WebRTC: (0,0) — левый низ кадра буфера (ещё не повёрнутого).

/** Общая вершина: прямоугольник и текстурные координаты через матрицу кадра. */
internal const val VS_QUAD = """
attribute vec2 aPos;
attribute vec2 aTex;
uniform mat4 uTexMatrix;
varying vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
}
"""

internal const val FS_COPY_OES = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES uTex;
void main() { gl_FragColor = texture2D(uTex, vTex); }
"""

internal const val FS_COPY = """
precision mediump float;
varying vec2 vTex;
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vTex); }
"""

/** Коробочное размытие на 9 отсчётов с шагом [uStep] — gpupixel `BoxBlurFilter` (радиус 4, шаг ×4). */
internal const val FS_BOX_BLUR = """
precision mediump float;
varying vec2 vTex;
uniform sampler2D uTex;
uniform vec2 uStep;
void main() {
    vec4 s = vec4(0.0);
    s += texture2D(uTex, vTex - 4.0 * uStep);
    s += texture2D(uTex, vTex - 3.0 * uStep);
    s += texture2D(uTex, vTex - 2.0 * uStep);
    s += texture2D(uTex, vTex - uStep);
    s += texture2D(uTex, vTex);
    s += texture2D(uTex, vTex + uStep);
    s += texture2D(uTex, vTex + 2.0 * uStep);
    s += texture2D(uTex, vTex + 3.0 * uStep);
    s += texture2D(uTex, vTex + 4.0 * uStep);
    gl_FragColor = s / 9.0;
}
"""

/**
 * «Красота» — gpupixel `BeautyFaceUnitFilter`: сглаживание там, где кожа и ровно (среднее против разброса),
 * резкость высоких частот, осветление тремя таблицами цвета. Свои — зоны морщин (ПЛАН-(ФЗ) ФЗ6): в них сглаживание
 * сильнее и порог разброса мягче — складка контрастнее кожи, и общий порог её бы оставил.
 */
internal const val FS_BEAUTY = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uTex;
uniform sampler2D uMean;
uniform sampler2D uLookGray;
uniform sampler2D uLookOrigin;
uniform sampler2D uLookSkin;
uniform sampler2D uLookCustom;
uniform vec2 uTexel;
uniform float uSharpen;
uniform float uSmooth;
uniform float uWhiten;
uniform float uDelta;
uniform float uAspect;
uniform vec4 uZone[8];
uniform vec2 uZoneAxis[8];
uniform float uZoneK[8];

const float levelRangeInv = 1.02657;
const float levelBlack = 0.0258820;
const float alpha = 0.7;

float zones(vec2 t) {
    float m = 0.0;
    for (int i = 0; i < 8; i++) {
        if (uZoneK[i] <= 0.0) continue;
        vec2 d = t - uZone[i].xy;
        d.x *= uAspect;
        vec2 r = vec2(dot(d, uZoneAxis[i]), dot(d, vec2(-uZoneAxis[i].y, uZoneAxis[i].x)));
        float e = length(r / uZone[i].zw);
        m = max(m, uZoneK[i] * (1.0 - smoothstep(0.55, 1.0, e)));
    }
    return m;
}

vec3 lut4(sampler2D table, vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float blue = c.b * 15.0;
    vec2 q1; q1.y = floor(floor(blue) * 0.25); q1.x = floor(blue) - q1.y * 4.0;
    vec2 q2; q2.y = floor(ceil(blue) * 0.25); q2.x = ceil(blue) - q2.y * 4.0;
    vec2 p2 = c.rg * 0.234375 + 0.0078125;
    vec2 p1 = q1 * 0.25 + p2;
    p2 = q2 * 0.25 + p2;
    return mix(texture2D(table, p1).rgb, texture2D(table, p2).rgb, fract(blue));
}

vec3 lut8(sampler2D table, vec3 c) {
    float blue = c.b * 63.0;
    vec2 q1; q1.y = floor(floor(blue) / 8.0); q1.x = floor(blue) - q1.y * 8.0;
    vec2 q2; q2.y = floor(ceil(blue) / 8.0); q2.x = ceil(blue) - q2.y * 8.0;
    vec2 p1 = vec2(q1.x / 8.0 + 0.5 / 512.0 + (1.0 / 8.0 - 1.0 / 512.0) * c.r, q1.y / 8.0 + 0.5 / 512.0 + (1.0 / 8.0 - 1.0 / 512.0) * c.g);
    vec2 p2 = vec2(q2.x / 8.0 + 0.5 / 512.0 + (1.0 / 8.0 - 1.0 / 512.0) * c.r, q2.y / 8.0 + 0.5 / 512.0 + (1.0 / 8.0 - 1.0 / 512.0) * c.g);
    return mix(texture2D(table, p1).rgb, texture2D(table, p2).rgb, fract(blue));
}

void main() {
    vec3 iColor = texture2D(uTex, vTex).rgb;
    vec3 meanColor = texture2D(uMean, vTex).rgb;
    vec3 diff = (iColor - meanColor) * uDelta;
    vec3 varColor = min(diff * diff, 1.0);
    vec3 color = iColor;
    float zone = zones(vTex);
    if (uSmooth > 0.0 || zone > 0.0 || uSharpen > 0.0) {
        float theta = 0.1;
        float p = clamp((min(iColor.r, meanColor.r - 0.1) - 0.2) * 4.0, 0.0, 1.0);
        float meanVar = (varColor.r + varColor.g + varColor.b) / 3.0;
        float k = (1.0 - meanVar / (meanVar + theta)) * p * uSmooth;
        float kz = zone * clamp(p * 1.5, 0.0, 1.0) * (1.0 - meanVar / (meanVar + 0.6));
        k = clamp(max(k, kz), 0.0, 1.0);
        vec3 resultColor = mix(iColor, meanColor, k);
        vec3 sum = 0.25 * iColor;
        sum += 0.125 * texture2D(uTex, vTex + vec2(-uTexel.x, 0.0)).rgb;
        sum += 0.125 * texture2D(uTex, vTex + vec2(uTexel.x, 0.0)).rgb;
        sum += 0.125 * texture2D(uTex, vTex + vec2(0.0, -uTexel.y)).rgb;
        sum += 0.125 * texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb;
        sum += 0.0625 * texture2D(uTex, vTex + uTexel).rgb;
        sum += 0.0625 * texture2D(uTex, vTex - uTexel).rgb;
        sum += 0.0625 * texture2D(uTex, vTex + vec2(-uTexel.x, uTexel.y)).rgb;
        sum += 0.0625 * texture2D(uTex, vTex + vec2(uTexel.x, -uTexel.y)).rgb;
        vec3 hPass = iColor - sum;
        color = resultColor + uSharpen * hPass * 2.0;
    }
    if (uWhiten > 0.0) {
        vec3 colorEPM = color;
        color = clamp((colorEPM - vec3(levelBlack)) * levelRangeInv, 0.0, 1.0);
        vec3 texel = vec3(texture2D(uLookGray, vec2(color.r, 0.5)).r,
                          texture2D(uLookGray, vec2(color.g, 0.5)).g,
                          texture2D(uLookGray, vec2(color.b, 0.5)).b);
        texel = mix(color, texel, 0.5);
        texel = mix(colorEPM, texel, alpha);
        vec3 colorOrigin = lut4(uLookOrigin, texel);
        texel = mix(colorOrigin, color, alpha);
        color = clamp(lut4(uLookSkin, texel), 0.0, 1.0);
        color = mix(color, lut8(uLookCustom, color), uWhiten);
    }
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
"""

/** Цвет (ФЗ3): яркость, контраст, насыщенность, тепло; готовый набор gpupixel — таблицей 512×512. Свой. */
internal const val FS_COLOR = """
precision mediump float;
varying vec2 vTex;
uniform sampler2D uTex;
uniform sampler2D uLut;
uniform float uBright;
uniform float uContrast;
uniform float uSat;
uniform float uWarm;
uniform float uLutMix;
void main() {
    vec3 c = texture2D(uTex, vTex).rgb;
    c += uBright * 0.25;
    c = (c - 0.5) * (1.0 + uContrast * 0.5) + 0.5;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, 1.0 + uSat);
    c.r += uWarm * 0.08;
    c.b -= uWarm * 0.08;
    c = clamp(c, 0.0, 1.0);
    if (uLutMix > 0.0) {
        float blue = c.b * 63.0;
        vec2 q1; q1.y = floor(floor(blue) / 8.0); q1.x = floor(blue) - q1.y * 8.0;
        vec2 q2; q2.y = floor(ceil(blue) / 8.0); q2.x = ceil(blue) - q2.y * 8.0;
        vec2 p1 = vec2(q1.x / 8.0 + 0.5 / 512.0 + (1.0 / 8.0 - 1.0 / 512.0) * c.r, q1.y / 8.0 + 0.5 / 512.0 + (1.0 / 8.0 - 1.0 / 512.0) * c.g);
        vec2 p2 = vec2(q2.x / 8.0 + 0.5 / 512.0 + (1.0 / 8.0 - 1.0 / 512.0) * c.r, q2.y / 8.0 + 0.5 / 512.0 + (1.0 / 8.0 - 1.0 / 512.0) * c.g);
        vec3 t = mix(texture2D(uLut, p1).rgb, texture2D(uLut, p2).rgb, fract(blue));
        c = mix(c, t, uLutMix);
    }
    gl_FragColor = vec4(c, 1.0);
}
"""

/**
 * Тоньше лицо и больше глаза — gpupixel `FaceReshapeFilter`. Точек передаётся только 17 нужных (а не 106 × 2
 * чисел): у старых телефонов места под постоянные шейдера мало. Порядок — в `FrameStudio.RESHAPE_POINTS`.
 */
internal const val FS_RESHAPE = """
precision highp float;
varying vec2 vTex;
uniform sampler2D uTex;
uniform vec2 uP[17];
uniform float uAspect;
uniform float uThin;
uniform float uEyes;

vec2 enlargeEye(vec2 t, vec2 o, float radius, float delta) {
    float w = distance(vec2(t.x, t.y / uAspect), vec2(o.x, o.y / uAspect)) / radius;
    w = 1.0 - (1.0 - w * w) * delta;
    w = clamp(w, 0.0, 1.0);
    return o + (t - o) * w;
}

vec2 curveWarp(vec2 t, vec2 o, vec2 target, float delta) {
    vec2 direction = (target - o) * delta;
    float radius = distance(vec2(target.x, target.y / uAspect), vec2(o.x, o.y / uAspect));
    float ratio = distance(vec2(t.x, t.y / uAspect), vec2(o.x, o.y / uAspect)) / radius;
    ratio = clamp(1.0 - ratio, 0.0, 1.0);
    return t - direction * ratio;
}

void main() {
    vec2 p = vTex;
    if (uThin > 0.0) {
        p = curveWarp(p, uP[0], uP[9], uThin);
        p = curveWarp(p, uP[1], uP[9], uThin);
        p = curveWarp(p, uP[2], uP[10], uThin);
        p = curveWarp(p, uP[3], uP[10], uThin);
        p = curveWarp(p, uP[4], uP[11], uThin);
        p = curveWarp(p, uP[5], uP[11], uThin);
        p = curveWarp(p, uP[6], uP[12], uThin);
        p = curveWarp(p, uP[7], uP[12], uThin);
        p = curveWarp(p, uP[8], uP[12], uThin);
    }
    if (uEyes > 0.0) {
        float r1 = distance(vec2(uP[14].x, uP[14].y / uAspect), vec2(uP[13].x, uP[13].y / uAspect)) * 5.0;
        p = enlargeEye(p, uP[13], r1, uEyes);
        float r2 = distance(vec2(uP[16].x, uP[16].y / uAspect), vec2(uP[15].x, uP[15].y / uAspect)) * 5.0;
        p = enlargeEye(p, uP[15], r2, uEyes);
    }
    gl_FragColor = texture2D(uTex, p);
}
"""

/** Макияж по сетке лица — gpupixel `FaceMakeupFilter`: картинка помады или румян натянута на 111 точек. */
internal const val VS_MAKEUP = """
attribute vec2 aPos;
attribute vec2 aTex;
varying vec2 vTex;
varying vec2 vScreen;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = aTex;
    vScreen = aPos * 0.5 + 0.5;
}
"""

internal const val FS_MAKEUP = """
precision mediump float;
varying highp vec2 vTex;
varying highp vec2 vScreen;
uniform sampler2D uTex;
uniform sampler2D uMakeup;
uniform float uIntensity;
void main() {
    vec4 fg = texture2D(uMakeup, vTex) * uIntensity;
    if (fg.a <= 0.0) discard;
    vec4 bg = texture2D(uTex, vScreen);
    vec3 blend = clamp(fg.rgb * (1.0 / fg.a), 0.0, 1.0);
    vec3 color = bg.rgb * blend;
    gl_FragColor = vec4(bg.rgb * (1.0 - fg.a) + color * fg.a, 1.0);
}
"""
