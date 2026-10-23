// Winter Forest — shader-accurate software rasterizer (offline render pipeline)
// Renders the exact GlMesh batches (pos3, normal3, uv2, color4) with the same
// WORLD_FRAGMENT_SHADER math (wrap-diffuse + hemisphere + 3 warm point lights +
// Blinn-Phong spec + rim + height/linear fog) to raw RGB.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

struct Vtx {
    float px, py, pz;
    float nx, ny, nz;
    float u, v;
    float r, g, b, a;
};

struct Batch {
    int32_t lit;          // 0 = lit textured, 1 = unlit textured
    int32_t texIndex;     // -1 = flat vertex color only
    float spec;
    uint32_t vcount;
    uint32_t icount;
    std::vector<Vtx> verts;
    std::vector<uint32_t> inds;
};

struct Tex {
    int size;
    std::vector<uint8_t> rgb; // size*size*3
};

static void die(const char* msg) {
    std::fprintf(stderr, "FATAL: %s\n", msg);
    std::exit(1);
}

template <typename T>
static T readScalar(std::ifstream& in) {
    T v{};
    in.read(reinterpret_cast<char*>(&v), sizeof(T));
    if (!in) die("truncated scene file");
    return v;
}

static float dot3(const float* a, const float* b) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }

static void normalize3(float* v) {
    float l = std::sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
    if (l < 1e-6f) { v[0] = 0; v[1] = 1; v[2] = 0; return; }
    v[0] /= l; v[1] /= l; v[2] /= l;
}

struct ClipV {
    float x, y, z, w; // clip space
    float a[11];      // nx,ny,nz,u,v,r,g,b,a  (9) + world pos is derived from model=identity => a[0..2] used for normal, plus wx,wy,wz packed below)
    float wx, wy, wz;
};

static ClipV lerpV(const ClipV& a, const ClipV& b, float t) {
    ClipV o;
    o.x = a.x + (b.x - a.x) * t;
    o.y = a.y + (b.y - a.y) * t;
    o.z = a.z + (b.z - a.z) * t;
    o.w = a.w + (b.w - a.w) * t;
    for (int i = 0; i < 11; i++) o.a[i] = a.a[i] + (b.a[i] - a.a[i]) * t;
    o.wx = a.wx + (b.wx - a.wx) * t;
    o.wy = a.wy + (b.wy - a.wy) * t;
    o.wz = a.wz + (b.wz - a.wz) * t;
    return o;
}

int main(int argc, char** argv) {
    if (argc < 4) die("usage: raster <scene.bin> <sky.rgb> <out.rgb>");
    const char* scenePath = argv[1];
    const char* skyPath = argv[2];
    const char* outPath = argv[3];

    std::ifstream in(scenePath, std::ios::binary);
    if (!in) die("cannot open scene");

    uint32_t magic = readScalar<uint32_t>(in);
    if (magic != 0x57465232) die("bad magic");
    int32_t W = readScalar<int32_t>(in);
    int32_t H = readScalar<int32_t>(in);

    float vp[16];
    in.read(reinterpret_cast<char*>(vp), sizeof(vp));

    // Lighting uniforms (match Kotlin layout)
    float sunDir[3], sunCol[3], ambSky[3], ptCol[3];
    float ptPos1[3], ptPos2[3], ptPos3[3];
    float ptRad1, ptRad2, ptRad3, fogNear, fogFar, fogCol[3];
    float camPos[3];
    in.read(reinterpret_cast<char*>(camPos), sizeof(camPos));
    in.read(reinterpret_cast<char*>(sunDir), sizeof(sunDir));
    in.read(reinterpret_cast<char*>(sunCol), sizeof(sunCol));
    in.read(reinterpret_cast<char*>(ambSky), sizeof(ambSky));
    in.read(reinterpret_cast<char*>(ptPos1), sizeof(ptPos1));
    in.read(reinterpret_cast<char*>(ptCol), sizeof(ptCol));
    in.read(reinterpret_cast<char*>(&ptRad1), sizeof(float));
    in.read(reinterpret_cast<char*>(ptPos2), sizeof(ptPos2));
    in.read(reinterpret_cast<char*>(&ptRad2), sizeof(float));
    in.read(reinterpret_cast<char*>(ptPos3), sizeof(ptPos3));
    in.read(reinterpret_cast<char*>(&ptRad3), sizeof(float));
    in.read(reinterpret_cast<char*>(fogCol), sizeof(fogCol));
    in.read(reinterpret_cast<char*>(&fogNear), sizeof(float));
    in.read(reinterpret_cast<char*>(&fogFar), sizeof(float));

    uint32_t nTex = readScalar<uint32_t>(in);
    std::vector<Tex> texs(nTex);
    for (uint32_t t = 0; t < nTex; t++) {
        texs[t].size = readScalar<int32_t>(in);
        texs[t].rgb.resize((size_t)texs[t].size * texs[t].size * 3);
        in.read(reinterpret_cast<char*>(texs[t].rgb.data()), texs[t].rgb.size());
    }

    uint32_t nBatch = readScalar<uint32_t>(in);
    std::vector<Batch> batches(nBatch);
    for (uint32_t b = 0; b < nBatch; b++) {
        Batch& B = batches[b];
        B.lit = readScalar<int32_t>(in);
        B.texIndex = readScalar<int32_t>(in);
        B.spec = readScalar<float>(in);
        B.vcount = readScalar<uint32_t>(in);
        B.icount = readScalar<uint32_t>(in);
        B.verts.resize(B.vcount);
        if (B.vcount) in.read(reinterpret_cast<char*>(B.verts.data()), sizeof(Vtx) * B.vcount);
        B.inds.resize(B.icount);
        if (B.icount) in.read(reinterpret_cast<char*>(B.inds.data()), sizeof(uint32_t) * B.icount);
    }
    in.close();

    // Buffers: RGB float backbuffer + depth
    std::vector<float> fb((size_t)W * H * 3);
    std::vector<float> depth((size_t)W * H, 1.0f);

    // Load sky background rendered by Python (same shader as SKY_FRAGMENT_SHADER)
    {
        std::ifstream sky(skyPath, std::ios::binary);
        if (!sky) die("cannot open sky.rgb");
        std::vector<uint8_t> srgb((size_t)W * H * 3);
        sky.read(reinterpret_cast<char*>(srgb.data()), srgb.size());
        for (size_t i = 0; i < srgb.size(); i++) fb[i] = srgb[i] / 255.0f;
    }

    auto sampleTex = [&](int ti, float u, float v, float filterR, float* out) {
        if (ti < 0 || ti >= (int)texs.size()) { out[0] = out[1] = out[2] = 1.0f; return; }
        const Tex& T = texs[ti];
        const int S = T.size;
        float fu = u - std::floor(u);
        float fv = v - std::floor(v);
        float fx = fu * S - 0.5f;
        float fy = fv * S - 0.5f;

        auto fetch = [&](int x, int y, float* c) {
            x = ((x % S) + S) % S;
            y = ((y % S) + S) % S;
            const uint8_t* p = &T.rgb[((size_t)y * S + x) * 3];
            c[0] = p[0] / 255.0f; c[1] = p[1] / 255.0f; c[2] = p[2] / 255.0f;
        };

        if (filterR <= 0.65f) {
            // Pure bilinear
            int x0 = (int)std::floor(fx), y0 = (int)std::floor(fy);
            float tx = fx - x0, ty = fy - y0;
            float c00[3], c10[3], c01[3], c11[3];
            fetch(x0, y0, c00); fetch(x0 + 1, y0, c10);
            fetch(x0, y0 + 1, c01); fetch(x0 + 1, y0 + 1, c11);
            for (int c = 0; c < 3; c++) {
                float a = c00[c] + (c10[c] - c00[c]) * tx;
                float b = c01[c] + (c11[c] - c01[c]) * tx;
                out[c] = a + (b - a) * ty;
            }
            return;
        }

        // Mip-like box average (minification): at most 5x5 taps spread over the footprint
        float r = filterR;
        if (r > 2.5f) r = 2.5f;
        int steps = 3;
        float stepSz = 2.0f * r / steps;
        float acc[3] = { 0, 0, 0 };
        int n = 0;
        for (int sy = 0; sy < steps; sy++) {
            for (int sx = 0; sx < steps; sx++) {
                float px = fx - r + (sx + 0.5f) * stepSz;
                float py = fy - r + (sy + 0.5f) * stepSz;
                int x0 = (int)std::floor(px), y0 = (int)std::floor(py);
                float tx = px - x0, ty = py - y0;
                float c00[3], c10[3], c01[3], c11[3];
                fetch(x0, y0, c00); fetch(x0 + 1, y0, c10);
                fetch(x0, y0 + 1, c01); fetch(x0 + 1, y0 + 1, c11);
                for (int c = 0; c < 3; c++) {
                    float a = c00[c] + (c10[c] - c00[c]) * tx;
                    float b = c01[c] + (c11[c] - c01[c]) * tx;
                    acc[c] += a + (b - a) * ty;
                }
                n++;
            }
        }
        out[0] = acc[0] / n; out[1] = acc[1] / n; out[2] = acc[2] / n;
    };

    auto warmPoint = [&](const float* lp, float radius, const float* N, const float* V, float specMask, const float* wpos, float* out) {
        out[0] = out[1] = out[2] = 0.0f;
        if (radius <= 0.05f) return;
        float td[3] = { lp[0] - wpos[0], lp[1] - wpos[1], lp[2] - wpos[2] };
        float dist = std::sqrt(td[0]*td[0] + td[1]*td[1] + td[2]*td[2]);
        if (dist >= radius) return;
        float L[3] = { td[0]/std::fmax(dist,0.001f), td[1]/std::fmax(dist,0.001f), td[2]/std::fmax(dist,0.001f) };
        float x = 1.0f - dist / radius; if (x < 0) x = 0; if (x > 1) x = 1;
        float atten = x * x * (0.65f + 0.35f * x);
        float ndl = dot3(N, L); if (ndl < 0) ndl = 0;
        float diff = 0.28f + 0.72f * ndl;
        float Hh[3] = { L[0]+V[0], L[1]+V[1], L[2]+V[2] };
        normalize3(Hh);
        float ndh = dot3(N, Hh); if (ndh < 0) ndh = 0;
        float spec = std::pow(ndh, 28.0f) * specMask * 0.45f;
        out[0] = ptCol[0] * atten * (diff + spec);
        out[1] = ptCol[1] * atten * (diff + spec);
        out[2] = ptCol[2] * atten * (diff + spec);
    };

    // Rasterize every triangle batch
    for (const Batch& B : batches) {
        if (B.icount < 3) continue;
        for (uint32_t ii = 0; ii + 2 < B.icount; ii += 3) {
            ClipV cv[3];
            bool valid = true;
            for (int k = 0; k < 3; k++) {
                const Vtx& s = B.verts[B.inds[ii + k]];
                float clipX = vp[0]*s.px + vp[4]*s.py + vp[8]*s.pz + vp[12];
                float clipY = vp[1]*s.px + vp[5]*s.py + vp[9]*s.pz + vp[13];
                float clipZ = vp[2]*s.px + vp[6]*s.py + vp[10]*s.pz + vp[14];
                float clipW = vp[3]*s.px + vp[7]*s.py + vp[11]*s.pz + vp[15];
                if (!(clipW == clipW)) { valid = false; break; }
                cv[k].x = clipX; cv[k].y = clipY; cv[k].z = clipZ; cv[k].w = clipW;
                cv[k].a[0] = s.nx; cv[k].a[1] = s.ny; cv[k].a[2] = s.nz;
                cv[k].a[3] = s.u;  cv[k].a[4] = s.v;
                cv[k].a[5] = s.r;  cv[k].a[6] = s.g; cv[k].a[7] = s.b; cv[k].a[8] = s.a;
                cv[k].a[9] = 0; cv[k].a[10] = 0;
                cv[k].wx = s.px; cv[k].wy = s.py; cv[k].wz = s.pz;
            }
            if (!valid) continue;

            // Near-plane clip against w >= NEAR_EPS (Sutherland-Hodgman on 3 verts)
            const float NEAR_EPS = 0.02f;
            ClipV poly[8];
            int pc = 0;
            for (int k = 0; k < 3; k++) {
                const ClipV& cur = cv[k];
                const ClipV& nxt = cv[(k + 1) % 3];
                bool curIn = cur.w >= NEAR_EPS;
                bool nxtIn = nxt.w >= NEAR_EPS;
                if (curIn) poly[pc++] = cur;
                if (curIn != nxtIn) {
                    float t = (NEAR_EPS - cur.w) / (nxt.w - cur.w);
                    poly[pc++] = lerpV(cur, nxt, t);
                }
            }
            if (pc < 3) continue;

            // Fan triangulation of clipped polygon + rasterize
            for (int f = 1; f + 1 < pc; f++) {
                const ClipV* tri[3] = { &poly[0], &poly[f], &poly[f + 1] };
                float sx[3], sy[3], iz[3], iw[3];
                float aa[3][12];
                float wxp[3], wyp[3], wzp[3];
                bool offscreen = true;
                for (int k = 0; k < 3; k++) {
                    const ClipV& P = *tri[k];
                    float invW = 1.0f / P.w;
                    float ndcX = P.x * invW, ndcY = P.y * invW, ndcZ = P.z * invW;
                    sx[k] = (ndcX * 0.5f + 0.5f) * W;
                    sy[k] = (1.0f - (ndcY * 0.5f + 0.5f)) * H;
                    iz[k] = ndcZ * 0.5f + 0.5f;
                    iw[k] = invW;
                    for (int q = 0; q < 9; q++) aa[k][q] = P.a[q] * invW; // premultiply by 1/w for perspective-correct interp
                    aa[k][9] = P.wx * invW; aa[k][10] = P.wy * invW; aa[k][11] = P.wz * invW;
                    wxp[k] = P.wx; wyp[k] = P.wy; wzp[k] = P.wz;
                    if (sx[k] > -4 && sx[k] < W + 4 && sy[k] > -4 && sy[k] < H + 4) offscreen = false;
                }
                if (offscreen) continue;

                float area = (sx[1]-sx[0])*(sy[2]-sy[0]) - (sx[2]-sx[0])*(sy[1]-sy[0]);
                if (std::fabs(area) < 1e-7f) continue;
                float invArea = 1.0f / area;

                // UV footprint per pixel (approximate screen-space gradient) for mip-like filtering
                float uRaw[3], vRaw[3];
                for (int k = 0; k < 3; k++) {
                    float iwv = iw[k] > 1e-9f ? 1.0f / iw[k] : 1.0f;
                    uRaw[k] = aa[k][3] * iwv;
                    vRaw[k] = aa[k][4] * iwv;
                }
                float duDx = (uRaw[0]*(sy[1]-sy[2]) + uRaw[1]*(sy[2]-sy[0]) + uRaw[2]*(sy[0]-sy[1])) * invArea;
                float duDy = (uRaw[0]*(sx[2]-sx[1]) + uRaw[1]*(sx[0]-sx[2]) + uRaw[2]*(sx[1]-sx[0])) * invArea;
                float dvDx = (vRaw[0]*(sy[1]-sy[2]) + vRaw[1]*(sy[2]-sy[0]) + vRaw[2]*(sy[0]-sy[1])) * invArea;
                float dvDy = (vRaw[0]*(sx[2]-sx[1]) + vRaw[1]*(sx[0]-sx[2]) + vRaw[2]*(sx[1]-sx[0])) * invArea;
                float duPx = std::sqrt(duDx*duDx + duDy*duDy);
                float dvPx = std::sqrt(dvDx*dvDx + dvDy*dvDy);
                float filterR = 0.5f * std::fmax(duPx, dvPx);
                if (B.texIndex >= 0 && B.texIndex < (int)texs.size()) {
                    filterR *= (float)texs[B.texIndex].size;
                }

                int minX = (int)std::floor(std::fmin(sx[0], std::fmin(sx[1], sx[2])));
                int maxX = (int)std::ceil(std::fmax(sx[0], std::fmax(sx[1], sx[2])));
                int minY = (int)std::floor(std::fmin(sy[0], std::fmin(sy[1], sy[2])));
                int maxY = (int)std::ceil(std::fmax(sy[0], std::fmax(sy[1], sy[2])));
                if (minX < 0) minX = 0; if (minY < 0) minY = 0;
                if (maxX >= W) maxX = W - 1; if (maxY >= H) maxY = H - 1;

                for (int py = minY; py <= maxY; py++) {
                    for (int px = minX; px <= maxX; px++) {
                        float cx = px + 0.5f, cy = py + 0.5f;
                        float w0 = ((sx[2]-sx[1])*(cy-sy[1]) - (sy[2]-sy[1])*(cx-sx[1])) * invArea;
                        float w1 = ((sx[0]-sx[2])*(cy-sy[2]) - (sy[0]-sy[2])*(cx-sx[2])) * invArea;
                        float w2 = 1.0f - w0 - w1;
                        if (w0 < 0 || w1 < 0 || w2 < 0) continue;

                        // Perspective-correct interpolation (attributes premultiplied by 1/w)
                        float iwSum = w0*iw[0] + w1*iw[1] + w2*iw[2];
                        if (iwSum <= 0) continue;
                        float zNdc = w0*iz[0] + w1*iz[1] + w2*iz[2];
                        size_t pix = (size_t)py * W + px;
                        if (zNdc >= depth[pix]) continue;

                        float corr = 1.0f / iwSum;
                        float N[3] = {
                            (w0*aa[0][0] + w1*aa[1][0] + w2*aa[2][0]) * corr,
                            (w0*aa[0][1] + w1*aa[1][1] + w2*aa[2][1]) * corr,
                            (w0*aa[0][2] + w1*aa[1][2] + w2*aa[2][2]) * corr
                        };
                        normalize3(N);
                        float u = (w0*aa[0][3] + w1*aa[1][3] + w2*aa[2][3]) * corr;
                        float v = (w0*aa[0][4] + w1*aa[1][4] + w2*aa[2][4]) * corr;
                        float cr = (w0*aa[0][5] + w1*aa[1][5] + w2*aa[2][5]) * corr;
                        float cg = (w0*aa[0][6] + w1*aa[1][6] + w2*aa[2][6]) * corr;
                        float cb = (w0*aa[0][7] + w1*aa[1][7] + w2*aa[2][7]) * corr;
                        float ca = (w0*aa[0][8] + w1*aa[1][8] + w2*aa[2][8]) * corr;
                        float wpx = (w0*aa[0][9] + w1*aa[1][9] + w2*aa[2][9]) * corr;
                        float wpy = (w0*aa[0][10] + w1*aa[1][10] + w2*aa[2][10]) * corr;
                        float wpz = (w0*aa[0][11] + w1*aa[1][11] + w2*aa[2][11]) * corr;

                        float tex[3] = { 1, 1, 1 };
                        sampleTex(B.texIndex, u, v, filterR, tex);
                        float texel[3] = { tex[0]*cr, tex[1]*cg, tex[2]*cb };
                        float alpha = ca;
                        if (alpha < 0.05f) continue;

                        float lit[3];
                        if (B.lit == 1) {
                            lit[0] = texel[0]; lit[1] = texel[1]; lit[2] = texel[2];
                        } else {
                            float wpos[3] = { wpx, wpy, wpz };
                            float Vv[3] = { camPos[0]-wpos[0], camPos[1]-wpos[1], camPos[2]-wpos[2] };
                            normalize3(Vv);
                            float Ls[3] = { sunDir[0], sunDir[1], sunDir[2] };
                            normalize3(Ls);

                            float hemi = 0.52f + 0.48f * N[1];
                            float negNy = -N[1]; if (negNy < 0) negNy = 0;
                            float amb[3] = {
                                ambSky[0]*hemi + ambSky[0]*0.76f*negNy*0.35f,
                                ambSky[1]*hemi + ambSky[1]*0.86f*negNy*0.35f,
                                ambSky[2]*hemi + ambSky[2]*1.04f*negNy*0.35f
                            };
                            float ndl = dot3(N, Ls);
                            float wrapN = (ndl + 0.22f) / 1.22f;
                            if (wrapN < 0) wrapN = 0; if (wrapN > 1) wrapN = 1;
                            float dirL[3] = { sunCol[0]*wrapN, sunCol[1]*wrapN, sunCol[2]*wrapN };

                            float Hh[3] = { Ls[0]+Vv[0], Ls[1]+Vv[1], Ls[2]+Vv[2] };
                            normalize3(Hh);
                            float ndh = dot3(N, Hh); if (ndh < 0) ndh = 0;
                            float texLuma = 0.299f*texel[0] + 0.587f*texel[1] + 0.114f*texel[2];
                            float specPower = B.spec * (0.45f + 0.75f*texLuma);
                            float specular = std::pow(ndh, 36.0f) * specPower;
                            float ndv = dot3(N, Vv); if (ndv > 1) ndv = 1; if (ndv < 0) ndv = 0;
                            float rim = std::pow(1.0f - ndv, 3.4f) * 0.18f;

                            float p1[3], p2[3], p3[3];
                            warmPoint(ptPos1, ptRad1, N, Vv, specPower, wpos, p1);
                            warmPoint(ptPos2, ptRad2, N, Vv, specPower, wpos, p2);
                            warmPoint(ptPos3, ptRad3, N, Vv, specPower, wpos, p3);

                            for (int c = 0; c < 3; c++) {
                                float lightSum = amb[c] + dirL[c] + p1[c] + p2[c] + p3[c];
                                float specRim = sunCol[c]*specular + ambSky[c]*rim;
                                lit[c] = texel[c] * lightSum + specRim;
                            }
                        }

                        float dx = wpx - camPos[0], dy = wpy - camPos[1], dz = wpz - camPos[2];
                        float camDist = std::sqrt(dx*dx + dy*dy + dz*dz);
                        float denom = fogFar - fogNear; if (denom < 0.001f) denom = 0.001f;
                        float linFog = (camDist - fogNear) / denom;
                        if (linFog < 0) linFog = 0; if (linFog > 1) linFog = 1;
                        float fogFactor = linFog * linFog * (3.0f - 2.0f * linFog);
                        if (fogFactor < 0) fogFactor = 0; if (fogFactor > 1) fogFactor = 1;

                        float outR = lit[0] + (fogCol[0] - lit[0]) * fogFactor;
                        float outG = lit[1] + (fogCol[1] - lit[1]) * fogFactor;
                        float outB = lit[2] + (fogCol[2] - lit[2]) * fogFactor;

                        // Alpha blend onto current framebuffer (matches GL_SRC_ALPHA/ONE_MINUS_SRC_ALPHA)
                        float* fp = &fb[pix * 3];
                        fp[0] = outR * alpha + fp[0] * (1.0f - alpha);
                        fp[1] = outG * alpha + fp[1] * (1.0f - alpha);
                        fp[2] = outB * alpha + fp[2] * (1.0f - alpha);

                        if (alpha > 0.995f) depth[pix] = zNdc;
                    }
                }
            }
        }
    }

    // Write final RGB8
    std::ofstream out(outPath, std::ios::binary);
    if (!out) die("cannot write output");
    std::vector<uint8_t> row((size_t)W * 3);
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            for (int c = 0; c < 3; c++) {
                float v = fb[((size_t)y * W + x) * 3 + c];
                if (v < 0) v = 0; if (v > 1) v = 1;
                row[x * 3 + c] = (uint8_t)(v * 255.0f + 0.5f);
            }
        }
        out.write(reinterpret_cast<char*>(row.data()), row.size());
    }
    out.close();
    std::printf("Rendered %dx%d, %u batches OK\n", W, H, nBatch);
    return 0;
}
