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
        precision highp float;

        uniform sampler2D uTexture;
        uniform vec3 uCameraPos;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uAmbientSky;
        uniform vec3 uPointLightPos;
        uniform vec3 uPointLightColor;
        uniform float uPointLightRadius;
        uniform vec3 uPointLightPos2;
        uniform float uPointLightRadius2;
        uniform vec3 uPointLightPos3;
        uniform float uPointLightRadius3;
        uniform vec3 uFogColor;
        uniform float uFogNear;
        uniform float uFogFar;
        uniform float uUnlit;
        uniform float uSpecularStrength;

        varying vec3 vWorldPos;
        varying vec3 vNormal;
        varying vec2 vTexCoord;
        varying vec4 vColor;

        vec3 evalWarmPointLight(vec3 lightPos, float radius, vec3 N, vec3 V, float specMask) {
            if (radius <= 0.05) return vec3(0.0);
            vec3 toLight = lightPos - vWorldPos;
            float dist = length(toLight);
            if (dist >= radius) return vec3(0.0);
            vec3 L = toLight / max(dist, 0.001);
            float x = clamp(1.0 - dist / radius, 0.0, 1.0);
            float atten = x * x * (0.65 + 0.35 * x);
            float diff = 0.28 + 0.72 * max(dot(N, L), 0.0);
            vec3 H = normalize(L + V);
            float spec = pow(max(dot(N, H), 0.0), 28.0) * specMask * 0.45;
            return uPointLightColor * atten * (diff + spec);
        }

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
                vec3 V = normalize(uCameraPos - vWorldPos);
                vec3 Lsun = normalize(uSunDir);

                // Мягкое полусферическое освещение неба + холодный рефлекс от снежного покрова снизу
                float hemi = 0.52 + 0.48 * N.y;
                vec3 snowBounce = uAmbientSky * vec3(0.76, 0.86, 1.04) * max(-N.y, 0.0) * 0.35;
                vec3 ambient = uAmbientSky * hemi + snowBounce;

                // Мягкий wrap-diffuse для объёмных сугробов и хвои
                float wrapNdotL = clamp((dot(N, Lsun) + 0.22) / 1.22, 0.0, 1.0);
                vec3 directional = uSunColor * wrapNdotL;

                // Зеркальный блик на льду, металле и кристаллах наста (Blinn-Phong + Fresnel)
                vec3 H = normalize(Lsun + V);
                float texLuma = dot(texel.rgb, vec3(0.299, 0.587, 0.114));
                float specPower = uSpecularStrength * (0.45 + 0.75 * texLuma);
                float specular = pow(max(dot(N, H), 0.0), 36.0) * specPower;
                float rim = pow(1.0 - max(dot(N, V), 0.0), 3.4) * 0.18;
                vec3 specRim = uSunColor * specular + uAmbientSky * rim;

                // До 3 одновременных тёплых источников света (костёр базы + окна/полевые костры + факел игрока)
                vec3 pointGlow =
                    evalWarmPointLight(uPointLightPos, uPointLightRadius, N, V, specPower) +
                    evalWarmPointLight(uPointLightPos2, uPointLightRadius2, N, V, specPower) +
                    evalWarmPointLight(uPointLightPos3, uPointLightRadius3, N, V, specPower);

                litColor = texel.rgb * (ambient + directional + pointGlow) + specRim;
            }

            // Экспоненциально-линейная воздушная перспектива с высотной дымкой
            float camDist = length(vWorldPos - uCameraPos);
            float linFog = clamp((camDist - uFogNear) / max(uFogFar - uFogNear, 0.001), 0.0, 1.0);
            float fogFactor = clamp(linFog * linFog * (3.0 - 2.0 * linFog), 0.0, 1.0);
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
        precision highp float;
        uniform vec3 uSkyZenith;
        uniform vec3 uSkyHorizon;
        uniform vec3 uFogColor;
        uniform vec2 uSunScreenUv;
        uniform vec3 uSunHaloColor;
        uniform float uCamYaw;
        uniform float uCamPitch;
        uniform float uAspect;
        uniform float uTime;
        uniform float uAuroraStrength;
        varying vec2 vScreenUv;

        float hash21(vec2 p) {
            p = fract(p * vec2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float noise2D(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(
                mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
                mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x),
                u.y
            );
        }

        float fbm2(vec2 p) {
            float v = 0.0;
            float a = 0.55;
            for (int i = 0; i < 4; i++) {
                v += a * noise2D(p);
                p = p * 2.13 + vec2(17.7, 9.2);
                a *= 0.52;
            }
            return v;
        }

        float noise1D(float x) {
            float i = floor(x);
            float f = fract(x);
            float u = f * f * (3.0 - 2.0 * f);
            return mix(hash21(vec2(i, 1.7)), hash21(vec2(i + 1.0, 1.7)), u);
        }

        float mountainRidge(float az, float freq, float seedShift) {
            float h = 0.0;
            h += 0.52 * abs(noise1D(az * freq + seedShift) * 2.0 - 1.0);
            h += 0.28 * abs(noise1D(az * freq * 2.13 + seedShift * 1.7) * 2.0 - 1.0);
            h += 0.14 * abs(noise1D(az * freq * 4.41 + seedShift * 2.3) * 2.0 - 1.0);
            return h;
        }

        void main() {
            // Истинный 3D-луч взгляда для панорамного неба 360°
            vec2 ndc = vScreenUv * 2.0 - 1.0;
            float tanHalfFov = 0.60086;
            vec3 rayCam = normalize(vec3(ndc.x * uAspect * tanHalfFov, ndc.y * tanHalfFov, -1.0));

            float cp = cos(uCamPitch);
            float sp = sin(uCamPitch);
            vec3 rayPitch = vec3(rayCam.x, rayCam.y * cp - rayCam.z * sp, rayCam.y * sp + rayCam.z * cp);

            float cy = cos(uCamYaw);
            float sy = sin(uCamYaw);
            vec3 dir = normalize(vec3(rayPitch.x * cy - rayPitch.z * sy, rayPitch.y, rayPitch.x * sy + rayPitch.z * cy));

            float elev = clamp(dir.y, -0.35, 1.0);
            float az = atan(dir.z, dir.x);

            // 1. Глубокий вертикальный градиент: зенит -> холодный горизонт
            float skyGrad = clamp(pow(max(elev, 0.0), 0.58), 0.0, 1.0);
            vec3 col = mix(uSkyHorizon, uSkyZenith, skyGrad);

            // 2. Тёплое зарево в стороне солнца (нормированный азимут к солнцу)
            float sunAz = atan(uSunScreenUv.y - 0.5, uSunScreenUv.x - 0.5);
            float azAlign = max(0.0, cos(az - sunAz));
            float duskBand = pow(azAlign, 3.0) * exp(-abs(elev) * 4.2);
            col += uSunHaloColor * duskBand * 0.28;

            // 3. Звёздное небо: мерцание, разный размер, только выше горизонта
            if (uAuroraStrength > 0.02 && elev > 0.015) {
                vec2 starCell = floor(vec2(az * 60.0, elev * 110.0));
                float starRnd = hash21(starCell);
                if (starRnd > 0.978) {
                    float mag = hash21(starCell + 4.7);
                    float twinkle = 0.55 + 0.45 * sin(uTime * (2.2 + mag * 3.0) + starRnd * 40.0);
                    vec2 starUv = fract(vec2(az * 60.0, elev * 110.0)) - 0.5;
                    float starShape = smoothstep(0.34, 0.02, length(starUv));
                    col += vec3(0.82, 0.90, 1.0) * starShape * twinkle * (0.35 + 0.65 * mag) *
                        uAuroraStrength * smoothstep(0.015, 0.22, elev);
                }

                // 4. Два живых занавеса северного сияния
                float wave1 = sin(az * 3.0 + uTime * 0.28 + sin(az * 7.0 - uTime * 0.15) * 0.8);
                float wave2 = sin(az * 2.2 - uTime * 0.20 + sin(az * 5.0 + uTime * 0.12) * 1.1);
                float curtain1 = exp(-abs(elev - (0.30 + 0.09 * wave1)) * 7.5);
                float curtain2 = exp(-abs(elev - (0.46 + 0.11 * wave2)) * 9.5);
                float streaks = 0.55 + 0.45 * sin(az * 26.0 + uTime * 0.9);
                float streaks2 = 0.55 + 0.45 * sin(az * 18.0 - uTime * 0.7 + 1.3);
                vec3 auroraA = mix(vec3(0.10, 0.88, 0.58), vec3(0.42, 0.28, 0.92), clamp(elev * 1.4, 0.0, 1.0));
                vec3 auroraB = mix(vec3(0.16, 0.72, 0.66), vec3(0.30, 0.42, 0.95), clamp(elev * 1.1, 0.0, 1.0));
                col += auroraA * curtain1 * streaks * uAuroraStrength * 0.52;
                col += auroraB * curtain2 * streaks2 * uAuroraStrength * 0.30;
            }

            // 5. Два слоя облаков: высокие перистые + низкие плотные, с ветровым дрейфом
            if (elev > 0.02) {
                vec2 cloudUv = vec2(az * 2.1 + uTime * 0.012, elev * 3.4);
                float highCloud = fbm2(cloudUv * 1.7 + vec2(uTime * 0.016, 0.0));
                float lowCloud = fbm2(cloudUv * 3.1 + vec2(-uTime * 0.010, 2.3));
                float cloudMask = smoothstep(0.52, 0.88, highCloud * 0.62 + lowCloud * 0.38) * exp(-elev * 2.4);
                vec3 cloudLit = mix(uSkyHorizon * 1.06, uSunHaloColor, 0.42);
                col = mix(col, cloudLit, cloudMask * 0.42);
                float wisp = smoothstep(0.60, 0.95, lowCloud) * exp(-elev * 1.8) * 0.16;
                col = mix(col, mix(uSkyHorizon, uSunHaloColor, 0.25), wisp);
            }

            // 6. Солнечный / лунный диск: диск с затемнением края и мягкое гало
            float dScreen = distance(vScreenUv, uSunScreenUv);
            float disc = smoothstep(0.042, 0.014, dScreen);
            float limb = 1.0 - smoothstep(0.0, 0.042, dScreen) * 0.25;
            float halo = exp(-dScreen * 5.6) * 0.36 + exp(-dScreen * 1.7) * 0.10;
            col += uSunHaloColor * (disc * limb * 0.92 + halo);

            // 7. Три эшелона заснеженных горных хребтов 360°
            float farPeak = 0.150 * mountainRidge(az, 2.6, 4.2) + 0.012;
            float midPeak = 0.118 * mountainRidge(az, 4.1, 8.9) + 0.002;
            float nearPeak = 0.086 * mountainRidge(az, 6.2, 11.7) - 0.006;

            if (elev < farPeak) {
                float snowLine = smoothstep(farPeak * 0.42, farPeak, elev);
                vec3 farMtn = mix(uFogColor * 0.70, mix(uFogColor, vec3(0.92, 0.96, 1.0), 0.55), snowLine);
                col = mix(col, farMtn, 0.80);
            }
            if (elev < midPeak) {
                float snowLine = smoothstep(midPeak * 0.40, midPeak, elev);
                vec3 midMtn = mix(uFogColor * 0.62, mix(uFogColor, vec3(0.93, 0.97, 1.0), 0.50), snowLine);
                col = mix(col, midMtn, 0.86);
            }
            if (elev < nearPeak) {
                float snowLine2 = smoothstep(nearPeak * 0.36, nearPeak, elev);
                vec3 nearMtn = mix(uFogColor * 0.52, mix(uFogColor, vec3(0.95, 0.98, 1.0), 0.44), snowLine2);
                col = mix(col, nearMtn, 0.93);
            }

            // 8. Плавная дымка горизонта
            float horizonHaze = smoothstep(0.065, -0.05, elev);
            col = mix(col, uFogColor, horizonHaze * 0.94);

            // 9. Дизеринг: убирает полосатость градиента на 8-битных экранах
            float dither = (hash21(vScreenUv * vec2(1913.0, 1171.0) + uTime) - 0.5) * 0.012;
            col += dither;

            gl_FragColor = vec4(col, 1.0);
        }
    """
}
