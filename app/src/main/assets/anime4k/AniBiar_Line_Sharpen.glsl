//!DESC AniBiar-Line-Sharpen
//!HOOK MAIN
//!BIND HOOKED
#define ANI_SHARPEN_STRENGTH 10.0
#define ANI_SHARPEN_OVERSHOOT 0.45
float ani_luma(vec3 rgb) { return dot(rgb, vec3(0.2126, 0.7152, 0.0722)); }
float ani_tap(float x, float y) { return ani_luma(HOOKED_texOff(vec2(x, y)).rgb); }
vec4 hook() {
    vec4 center = HOOKED_texOff(vec2(0.0, 0.0));
    float c = ani_luma(center.rgb);
    float n = ani_tap(0.0, -1.0);
    float s = ani_tap(0.0, 1.0);
    float w = ani_tap(-1.0, 0.0);
    float e = ani_tap(1.0, 0.0);
    float nw = ani_tap(-1.0, -1.0);
    float ne = ani_tap(1.0, -1.0);
    float sw = ani_tap(-1.0, 1.0);
    float se = ani_tap(1.0, 1.0);
    float n2 = ani_tap(0.0, -2.0);
    float s2 = ani_tap(0.0, 2.0);
    float w2 = ani_tap(-2.0, 0.0);
    float e2 = ani_tap(2.0, 0.0);
    float blur = (36.0 * c + 24.0 * (n + s + w + e) + 16.0 * (nw + ne + sw + se) + 6.0 * (n2 + s2 + w2 + e2)) / 220.0;
    float lo = min(c, min(min(min(n, s), min(w, e)), min(min(nw, ne), min(sw, se))));
    float hi = max(c, max(max(max(n, s), max(w, e)), max(max(nw, ne), max(sw, se))));
    float range = hi - lo;
    float sharpened = clamp(c + ANI_SHARPEN_STRENGTH * (c - blur), lo - ANI_SHARPEN_OVERSHOOT * range, hi + ANI_SHARPEN_OVERSHOOT * range);
    return vec4(clamp(center.rgb + (sharpened - c), 0.0, 1.0), center.a);
}
