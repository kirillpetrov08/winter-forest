package com.ayaks.winterforest.presentation.gl

object Shaders {

    const val WORLD_VERTEX_SHADER = """
        uniform mat4 uMvpMatrix;
        uniform mat4 uModelMatrix;

        attribute vec3 aPosition;
        attribute vec3 aNormal;
        attribute vec2 aTexCoord;
        attribute vec4 aColor;

        varying vec3 vWorldPos;
        varying vec3 vNormal;
        varying vec2 vTexCoord;
        varying vec4 vColor;

        void main() {
            vec4 worldPos4 = uModelMatrix * vec4(aPosition, 1.0);
            vWorldPos = worldPos4.xyz;
            vNormal = normalize(mat3(uModelMatrix) * aNormal);
            vTexCoord = aTexCoord;
            vColor = aColor;
            gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
        }
    """

    const val WORLD_FRAGMENT_SHADER = """
        precision mediump float;

        uniform sampler2D uTexture;
        uniform vec3 uCameraPos;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uAmbientSky;
        uniform vec3 uPointLightPos;
        uniform vec3 uPointLightColor;
        uniform float uPointLightRadius;
        uniform vec3 uFogColor;
        uniform float uFogNear;
        uniform float uFogFar;
        uniform float uUnlit;

        varying vec3 vWorldPos;
        varying vec3 vNormal;
        varying vec2 vTexCoord;
        varying vec4 vColor;

        void main() {
            vec4 texel = texture2D(uTexture, vTexCoord) * vColor;
            if (texel.a < 0.05) {
                discard;
            }

            vec3 litColor;
            if (uUnlit > 0.5) {
                litColor = texel.rgb;
            } else {
                vec3 N = normalize(vNormal);
                float ndotl = max(dot(N, normalize(uSunDir)), 0.0);
                float hemi = 0.55 + 0.45 * N.y;
                vec3 ambient = uAmbientSky * hemi;
                vec3 directional = uSunColor * ndotl;

                vec3 toLight = uPointLightPos - vWorldPos;
                float distToLight = length(toLight);
                float atten = clamp(1.0 - distToLight / max(uPointLightRadius, 0.001), 0.0, 1.0);
                atten = atten * atten;
                float pDiff = 0.35 + 0.65 * max(dot(N, toLight / max(distToLight, 0.001)), 0.0);
                vec3 pointGlow = uPointLightColor * atten * pDiff;

                litColor = texel.rgb * (ambient + directional + pointGlow);
            }

            float camDist = length(vWorldPos - uCameraPos);
            float fogFactor = clamp((camDist - uFogNear) / max(uFogFar - uFogNear, 0.001), 0.0, 1.0);
            vec3 finalRgb = mix(litColor, uFogColor, fogFactor);
            gl_FragColor = vec4(finalRgb, texel.a);
        }
    """

    const val SKY_VERTEX_SHADER = """
        attribute vec2 aScreenQuad;
        varying vec2 vScreenUv;
        void main() {
            vScreenUv = aScreenQuad * 0.5 + 0.5;
            gl_Position = vec4(aScreenQuad, 0.999, 1.0);
        }
    """

    const val SKY_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec3 uSkyZenith;
        uniform vec3 uSkyHorizon;
        uniform vec2 uSunScreenUv;
        uniform vec3 uSunHaloColor;
        varying vec2 vScreenUv;

        void main() {
            float t = clamp(pow(vScreenUv.y, 0.72), 0.0, 1.0);
            vec3 sky = mix(uSkyHorizon, uSkyZenith, t);
            float d = distance(vScreenUv, uSunScreenUv);
            float disc = smoothstep(0.055, 0.015, d);
            float halo = exp(-d * 7.0) * 0.38;
            vec3 col = sky + uSunHaloColor * (disc * 0.85 + halo);
            gl_FragColor = vec4(col, 1.0);
        }
    """
}
