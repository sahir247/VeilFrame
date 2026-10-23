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
    ex.extract("scores", scores);
    ex.extract("boxes", boxes);

    const float center_variance = 0.1f;
    const float size_variance = 0.2f;
    const int num_anchors = static_cast<int>(g_ultra_priors.size());

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

    // Pack into flat float array: [x1, y1, x2, y2, score, ...]
    const int stride = 5;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(picked.size() * stride));
    if (!result) return nullptr;

    std::vector<float> flat;
    flat.reserve(picked.size() * stride);
    for (const auto& b : picked) {
        flat.push_back(b.x1);
        flat.push_back(b.y1);
        flat.push_back(b.x2);
        flat.push_back(b.y2);
        flat.push_back(b.score);
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
        ex.extract("output", out);
    }

    std::vector<BBox> candidates;
    if (!out.empty() && out.dims >= 2) {
        const int num_anchors = out.h; // typically 8400
        const int total_features = out.w; // typically 4 + 80 = 84 or 4 + num_classes
        const int num_classes = total_features - 4;

        const float scale_x = static_cast<float>(orig_w) / target_size;
        const float scale_y = static_cast<float>(orig_h) / target_size;

        for (int i = 0; i < num_anchors; i++) {
            const float* row = out.row(i);
            float max_score = -1.0f;
            int best_cls = -1;

            for (int c = 0; c < num_classes; c++) {
                float sc = row[4 + c];
                if (sc > max_score) {
                    max_score = sc;
                    best_cls = c;
                }
            }

            if (max_score > scoreThreshold && best_cls >= 0) {
                float cx = row[0];
                float cy = row[1];
                float w = row[2];
                float h = row[3];

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
