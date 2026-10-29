#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/asset_manager_jni.h>
#include "net.h"
#include <cmath>
#include <vector>
#include <algorithm>
#include <mutex>

#define TAG "VeilFrame.PiiNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define clip(x, y) (x < 0 ? 0 : (x > y ? y : x))

struct BBox {
    float x1;
    float y1;
    float x2;
    float y2;
    float score;
    int class_id;
};

static std::mutex g_pii_mutex;
static ncnn::Net* g_ultraface_net = nullptr;
static ncnn::Net* g_yolov8_net = nullptr;

static const int ULTRA_IN_W = 320;
static const int ULTRA_IN_H = 240;
static std::vector<std::vector<float>> g_ultra_priors;

static void generate_ultra_priors() {
    g_ultra_priors.clear();
    const std::vector<float> strides = {8.0f, 16.0f, 32.0f, 64.0f};
    const std::vector<std::vector<float>> min_boxes = {
        {10.0f, 16.0f, 24.0f},
        {32.0f, 48.0f},
        {64.0f, 96.0f},
        {128.0f, 192.0f, 256.0f}
    };

    std::vector<std::vector<float>> featuremap_size;
    for (float stride : strides) {
        featuremap_size.push_back({std::ceil(ULTRA_IN_W / stride), std::ceil(ULTRA_IN_H / stride)});
    }

    for (int index = 0; index < 4; index++) {
        float scale_w = ULTRA_IN_W / strides[index];
        float scale_h = ULTRA_IN_H / strides[index];
        int fm_w = static_cast<int>(featuremap_size[index][0]);
        int fm_h = static_cast<int>(featuremap_size[index][1]);

        for (int j = 0; j < fm_h; j++) {
            for (int i = 0; i < fm_w; i++) {
                float x_center = (i + 0.5f) / scale_w;
                float y_center = (j + 0.5f) / scale_h;

                for (float k : min_boxes[index]) {
                    float w = k / ULTRA_IN_W;
                    float h = k / ULTRA_IN_H;
                    g_ultra_priors.push_back({
                        clip(x_center, 1.0f),
                        clip(y_center, 1.0f),
                        clip(w, 1.0f),
                        clip(h, 1.0f)
                    });
                }
            }
        }
    }
}

static inline float iou_calc(const BBox& a, const BBox& b) {
    float x1 = std::max(a.x1, b.x1);
    float y1 = std::max(a.y1, b.y1);
    float x2 = std::min(a.x2, b.x2);
    float y2 = std::min(a.y2, b.y2);
    float w = std::max(0.0f, x2 - x1);
    float h = std::max(0.0f, y2 - y1);
    float inter = w * h;
    float a_area = (a.x2 - a.x1) * (a.y2 - a.y1);
    float b_area = (b.x2 - b.x1) * (b.y2 - b.y1);
    float union_area = a_area + b_area - inter;
    return union_area > 0.0f ? inter / union_area : 0.0f;
}

static void nms_filter(std::vector<BBox>& boxes, float iou_threshold, std::vector<BBox>& picked) {
    std::sort(boxes.begin(), boxes.end(), [](const BBox& a, const BBox& b) {
        return a.score > b.score;
    });

    std::vector<bool> suppressed(boxes.size(), false);
    for (size_t i = 0; i < boxes.size(); i++) {
        if (suppressed[i]) continue;
        picked.push_back(boxes[i]);
        for (size_t j = i + 1; j < boxes.size(); j++) {
            if (suppressed[j]) continue;
            if (boxes[i].class_id == boxes[j].class_id && iou_calc(boxes[i], boxes[j]) > iou_threshold) {
                suppressed[j] = true;
            }
        }
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_veilframe_app_privacy_ncnn_PiiNcnnNative_nativeInit(
    JNIEnv* env, jclass clazz,
    jobject assetManager
) {
    std::lock_guard<std::mutex> lock(g_pii_mutex);

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("AssetManager is null in PII init");
        return JNI_FALSE;
    }

    if (g_ultra_priors.empty()) {
        generate_ultra_priors();
    }

    // Load UltraFace RFB-320
    if (g_ultraface_net == nullptr) {
        g_ultraface_net = new ncnn::Net();
        g_ultraface_net->opt.lightmode = true;
        g_ultraface_net->opt.num_threads = 4;
        int r0 = g_ultraface_net->load_param(mgr, "models/pii/RFB-320.param");
        int r1 = g_ultraface_net->load_model(mgr, "models/pii/RFB-320.bin");
        if (r0 != 0 || r1 != 0) {
            LOGE("Failed to load UltraFace: %d, %d", r0, r1);
            delete g_ultraface_net;
            g_ultraface_net = nullptr;
        } else {
            LOGI("UltraFace RFB-320 loaded successfully");
        }
    }

    // Load YOLOv8n
    if (g_yolov8_net == nullptr) {
        g_yolov8_net = new ncnn::Net();
        g_yolov8_net->opt.lightmode = true;
        g_yolov8_net->opt.num_threads = 4;
        int r0 = g_yolov8_net->load_param(mgr, "models/pii/yolov8n.ncnn.param");
        int r1 = g_yolov8_net->load_model(mgr, "models/pii/yolov8n.ncnn.bin");
        if (r0 != 0 || r1 != 0) {
            LOGE("Failed to load YOLOv8n: %d, %d", r0, r1);
            delete g_yolov8_net;
            g_yolov8_net = nullptr;
        } else {
            LOGI("YOLOv8n-NCNN loaded successfully");
        }
    }

    return (g_ultraface_net != nullptr || g_yolov8_net != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_veilframe_app_privacy_ncnn_PiiNcnnNative_nativeDetectFaces(
    JNIEnv* env, jclass clazz,
    jobject srcBitmap,
    jfloat scoreThreshold,
    jfloat iouThreshold
) {
    std::lock_guard<std::mutex> lock(g_pii_mutex);
    if (!g_ultraface_net) {
        LOGE("UltraFace net not loaded");
        return nullptr;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, srcBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    const int orig_w = info.width;
    const int orig_h = info.height;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        reinterpret_cast<const unsigned char*>(pixels),
        ncnn::Mat::PIXEL_RGBA2RGB,
        orig_w, orig_h,
        static_cast<int>(info.stride),
        ULTRA_IN_W, ULTRA_IN_H
    );
    AndroidBitmap_unlockPixels(env, srcBitmap);

    const float mean_vals[3] = {127.0f, 127.0f, 127.0f};
    const float norm_vals[3] = {1.0f / 128.0f, 1.0f / 128.0f, 1.0f / 128.0f};
    in.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = g_ultraface_net->create_extractor();
    ex.set_light_mode(true);
    ex.input("input", in);

    ncnn::Mat scores, boxes;
    int ret_s = ex.extract("scores", scores);
    int ret_b = ex.extract("boxes", boxes);
    if (ret_s != 0 || ret_b != 0 || scores.empty() || boxes.empty()) {
        LOGE("UltraFace extraction failed (ret_s=%d, ret_b=%d, scores_empty=%d, boxes_empty=%d)",
             ret_s, ret_b, scores.empty(), boxes.empty());
        return nullptr;
    }

    const float center_variance = 0.1f;
    const float size_variance = 0.2f;
    const int num_anchors = static_cast<int>(g_ultra_priors.size());

    if (scores.c < 1 || boxes.c < 1 ||
        scores.channel(0).total() < static_cast<size_t>(num_anchors * 2) ||
        boxes.channel(0).total() < static_cast<size_t>(num_anchors * 4)) {
        LOGE("UltraFace tensor shape invalid: scores_c=%d, boxes_c=%d, num_anchors=%d",
             scores.c, boxes.c, num_anchors);
        return nullptr;
    }

    std::vector<BBox> raw_boxes;
    for (int i = 0; i < num_anchors; i++) {
        float score = scores.channel(0)[i * 2 + 1];
        if (score > scoreThreshold) {
            float x_center = boxes.channel(0)[i * 4] * center_variance * g_ultra_priors[i][2] + g_ultra_priors[i][0];
            float y_center = boxes.channel(0)[i * 4 + 1] * center_variance * g_ultra_priors[i][3] + g_ultra_priors[i][1];
            float w = std::exp(boxes.channel(0)[i * 4 + 2] * size_variance) * g_ultra_priors[i][2];
            float h = std::exp(boxes.channel(0)[i * 4 + 3] * size_variance) * g_ultra_priors[i][3];

            float x1 = clip(x_center - w / 2.0f, 1.0f) * orig_w;
            float y1 = clip(y_center - h / 2.0f, 1.0f) * orig_h;
            float x2 = clip(x_center + w / 2.0f, 1.0f) * orig_w;
            float y2 = clip(y_center + h / 2.0f, 1.0f) * orig_h;

            if (x2 > x1 && y2 > y1) {
                raw_boxes.push_back({x1, y1, x2, y2, score, 0});
            }
        }
    }

    std::vector<BBox> picked;
    nms_filter(raw_boxes, iouThreshold, picked);

    // Stride 6: [class_id (0=FACE), score, x1, y1, x2, y2]
    const int stride = 6;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(picked.size() * stride));
    if (!result) return nullptr;

    std::vector<float> flat;
    flat.reserve(picked.size() * stride);
    for (const auto& b : picked) {
        flat.push_back(0.0f); // PiiClass.FACE (class 0)
        flat.push_back(b.score);
        flat.push_back(b.x1);
        flat.push_back(b.y1);
        flat.push_back(b.x2);
        flat.push_back(b.y2);
    }

    env->SetFloatArrayRegion(result, 0, flat.size(), flat.data());
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_veilframe_app_privacy_ncnn_PiiNcnnNative_nativeDetectPii(
    JNIEnv* env, jclass clazz,
    jobject srcBitmap,
    jfloat scoreThreshold,
    jfloat iouThreshold
) {
    std::lock_guard<std::mutex> lock(g_pii_mutex);
    if (!g_yolov8_net) {
        LOGE("YOLOv8 net not loaded");
        return nullptr;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, srcBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    const int orig_w = info.width;
    const int orig_h = info.height;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;

    const int target_size = 640;
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        reinterpret_cast<const unsigned char*>(pixels),
        ncnn::Mat::PIXEL_RGBA2RGB,
        orig_w, orig_h,
        static_cast<int>(info.stride),
        target_size, target_size
    );
    AndroidBitmap_unlockPixels(env, srcBitmap);

    const float norm_vals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    in.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = g_yolov8_net->create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in);

    ncnn::Mat out;
    int ret = ex.extract("out0", out);
    if (ret != 0) {
        // Fallback blob names for alternate pnnx exports
        ret = ex.extract("output", out);
    }
    if (ret != 0 || out.empty() || out.dims < 2) {
        LOGE("YOLOv8 extraction failed or output tensor invalid (ret=%d, empty=%d, dims=%d)",
             ret, out.empty(), out.dims);
        return nullptr;
    }

    std::vector<BBox> candidates;
    if (!out.empty() && out.dims >= 2) {
        int num_anchors = 8400;
        int total_features = 84;
        bool transposed = false;

        if (out.h == 8400) {
            num_anchors = out.h;
            total_features = out.w;
            transposed = false;
        } else if (out.w == 8400) {
            num_anchors = out.w;
            total_features = out.h;
            transposed = true;
        } else {
            num_anchors = out.h;
            total_features = out.w;
            transposed = false;
        }

        const float scale_x = static_cast<float>(orig_w) / target_size;
        const float scale_y = static_cast<float>(orig_h) / target_size;

        auto get_feat = [&](int anchor_idx, int feat_idx) -> float {
            if (transposed) {
                return out.row(feat_idx)[anchor_idx];
            } else {
                return out.row(anchor_idx)[feat_idx];
            }
        };

        if (total_features == 144) {
            // YOLOv8 with DFL (4 * 16 = 64) and 80 COCO classes
            const int num_classes = 80;
            for (int i = 0; i < num_anchors; i++) {
                float max_score = -1.0f;
                int best_cls = -1;

                for (int c = 0; c < num_classes; c++) {
                    float logit = get_feat(i, 64 + c);
                    float sc = 1.0f / (1.0f + std::exp(-logit));
                    if (sc > max_score) {
                        max_score = sc;
                        best_cls = c;
                    }
                }

                if (max_score > scoreThreshold && best_cls >= 0) {
                    float anchor_cx = 0.0f;
                    float anchor_cy = 0.0f;
                    float stride_val = 8.0f;

                    if (i < 6400) {
                        stride_val = 8.0f;
                        int gx = i % 80;
                        int gy = i / 80;
                        anchor_cx = (gx + 0.5f) * stride_val;
                        anchor_cy = (gy + 0.5f) * stride_val;
                    } else if (i < 8000) {
                        stride_val = 16.0f;
                        int idx = i - 6400;
                        int gx = idx % 40;
                        int gy = idx / 40;
                        anchor_cx = (gx + 0.5f) * stride_val;
                        anchor_cy = (gy + 0.5f) * stride_val;
                    } else {
                        stride_val = 32.0f;
                        int idx = i - 8000;
                        int gx = idx % 20;
                        int gy = idx / 20;
                        anchor_cx = (gx + 0.5f) * stride_val;
                        anchor_cy = (gy + 0.5f) * stride_val;
                    }

                    // Decode DFL for 4 directions: left (0), top (1), right (2), bottom (3)
                    float dist[4];
                    for (int k = 0; k < 4; k++) {
                        float max_val = get_feat(i, k * 16);
                        for (int j = 1; j < 16; j++) {
                            float v = get_feat(i, k * 16 + j);
                            if (v > max_val) max_val = v;
                        }
                        float exp_vals[16];
                        float sum_exp = 0.0f;
                        for (int j = 0; j < 16; j++) {
                            exp_vals[j] = std::exp(get_feat(i, k * 16 + j) - max_val);
                            sum_exp += exp_vals[j];
                        }
                        float d = 0.0f;
                        if (sum_exp > 0.0f) {
                            for (int j = 0; j < 16; j++) {
                                d += j * (exp_vals[j] / sum_exp);
                            }
                        }
                        dist[k] = d;
                    }

                    float left = (anchor_cx - dist[0] * stride_val) * scale_x;
                    float top = (anchor_cy - dist[1] * stride_val) * scale_y;
                    float right = (anchor_cx + dist[2] * stride_val) * scale_x;
                    float bottom = (anchor_cy + dist[3] * stride_val) * scale_y;

                    candidates.push_back({
                        std::max(0.0f, left),
                        std::max(0.0f, top),
                        std::min(static_cast<float>(orig_w), right),
                        std::min(static_cast<float>(orig_h), bottom),
                        max_score,
                        best_cls
                    });
                }
            }
        } else {
            // Standard end-to-end format (cx, cy, w, h + class probabilities)
            const int num_classes = total_features - 4;
            for (int i = 0; i < num_anchors; i++) {
                float max_score = -1.0f;
                int best_cls = -1;

                for (int c = 0; c < num_classes; c++) {
                    float sc = get_feat(i, 4 + c);
                    if (sc > max_score) {
                        max_score = sc;
                        best_cls = c;
                    }
                }

                if (max_score > scoreThreshold && best_cls >= 0) {
                    float cx = get_feat(i, 0);
                    float cy = get_feat(i, 1);
                    float w = get_feat(i, 2);
                    float h = get_feat(i, 3);

                    float left = (cx - w / 2.0f) * scale_x;
                    float top = (cy - h / 2.0f) * scale_y;
                    float right = (cx + w / 2.0f) * scale_x;
                    float bottom = (cy + h / 2.0f) * scale_y;

                    candidates.push_back({
                        std::max(0.0f, left),
                        std::max(0.0f, top),
                        std::min(static_cast<float>(orig_w), right),
                        std::min(static_cast<float>(orig_h), bottom),
                        max_score,
                        best_cls
                    });
                }
            }
        }
    }

    std::vector<BBox> picked;
    nms_filter(candidates, iouThreshold, picked);

    // Stride 6: [class_id, score, x1, y1, x2, y2]
    const int stride = 6;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(picked.size() * stride));
    if (!result) return nullptr;

    std::vector<float> flat;
    flat.reserve(picked.size() * stride);
    for (const auto& b : picked) {
        flat.push_back(static_cast<float>(b.class_id));
        flat.push_back(b.score);
        flat.push_back(b.x1);
        flat.push_back(b.y1);
        flat.push_back(b.x2);
        flat.push_back(b.y2);
    }

    env->SetFloatArrayRegion(result, 0, flat.size(), flat.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_veilframe_app_privacy_ncnn_PiiNcnnNative_nativeRelease(
    JNIEnv* env, jclass clazz
) {
    std::lock_guard<std::mutex> lock(g_pii_mutex);
    if (g_ultraface_net) {
        delete g_ultraface_net;
        g_ultraface_net = nullptr;
    }
    if (g_yolov8_net) {
        delete g_yolov8_net;
        g_yolov8_net = nullptr;
    }
    LOGI("PII NCNN nets released");
}

} // extern "C"
