// VeilFrame NCNN Upscaler Engine
// Based on RealSR (Tencent ncnn) with:
// 1. Destructor null-pointer safety
// 2. Qualcomm Adreno 740 unaligned tile memcpy fix
// 3. True scanline-band streaming callback (eliminating full-image host allocations)
// 4. Return-code validation for model loading

#include "realsr.h"

#include <algorithm>
#include <vector>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_INFO, "VeilFrame.NativeSR", __VA_ARGS__)

#include "realsr_preproc.comp.hex.h"
#include "realsr_postproc.comp.hex.h"
#include "realsr_preproc_tta.comp.hex.h"
#include "realsr_postproc_tta.comp.hex.h"

RealSR::RealSR(int gpuid, bool _tta_mode, int num_threads)
{
    vkdev = (gpuid < 0 || gpuid >= ncnn::get_gpu_count()) ? 0 : ncnn::get_gpu_device(gpuid);
    net.opt.num_threads = num_threads > 0 ? num_threads : 1;

    realsr_preproc = 0;
    realsr_postproc = 0;
    bicubic_2x = 0;
    bicubic_3x = 0;
    bicubic_4x = 0;
    tta_mode = _tta_mode;
    scale = 4;
    tilesize = 160;
    prepadding = 10;
}

RealSR::~RealSR()
{
    delete realsr_preproc;
    realsr_preproc = nullptr;

    delete realsr_postproc;
    realsr_postproc = nullptr;

    if (bicubic_2x) {
        bicubic_2x->destroy_pipeline(net.opt);
        delete bicubic_2x;
        bicubic_2x = nullptr;
    }

    if (bicubic_3x) {
        bicubic_3x->destroy_pipeline(net.opt);
        delete bicubic_3x;
        bicubic_3x = nullptr;
    }

    if (bicubic_4x) {
        bicubic_4x->destroy_pipeline(net.opt);
        delete bicubic_4x;
        bicubic_4x = nullptr;
    }
}

int RealSR::load(const std::string& parampath, const std::string& modelpath)
{
    // Streaming currently binds ordinary 3-buffer shaders and performs one pass.
    // Reject TTA until its full transform/merge pipeline is implemented.
    if (tta_mode) {
        LOGD("TTA requested but unsupported by streaming engine; refusing model load");
        return -9;
    }

    // Repeated load() must release custom pipelines/layers before rebuilding them.
    delete realsr_preproc; realsr_preproc = nullptr;
    delete realsr_postproc; realsr_postproc = nullptr;
    if (bicubic_2x) { bicubic_2x->destroy_pipeline(net.opt); delete bicubic_2x; bicubic_2x = nullptr; }
    if (bicubic_3x) { bicubic_3x->destroy_pipeline(net.opt); delete bicubic_3x; bicubic_3x = nullptr; }
    if (bicubic_4x) { bicubic_4x->destroy_pipeline(net.opt); delete bicubic_4x; bicubic_4x = nullptr; }

    net.opt.use_vulkan_compute = (vkdev != nullptr);
    net.opt.use_fp16_packed = false;
    net.opt.use_fp16_storage = false;
    net.opt.use_fp16_arithmetic = false;
    net.opt.use_int8_storage = false;
    net.opt.use_int8_arithmetic = false;
    net.opt.use_winograd_convolution = false;
    net.opt.use_sgemm_convolution = true;

    if (vkdev) {
        net.set_vulkan_device(vkdev);
    }

    int ret = net.load_param(parampath.c_str());
    if (ret != 0) {
        fprintf(stderr, "RealSR: load_param failed: %d\n", ret);
        return -2;
    }

    ret = net.load_model(modelpath.c_str());
    if (ret != 0) {
        fprintf(stderr, "RealSR: load_model failed: %d\n", ret);
        return -3;
    }

    const auto& input_names = net.input_names();
    const auto& output_names = net.output_names();

    if (input_names.empty() || output_names.empty()) {
        fprintf(stderr, "RealSR: model lacks input or output names\n");
        return -4;
    }

    if (std::find(input_names.begin(), input_names.end(), net_input_name) == input_names.end()) {
        net_input_name = input_names[0];
    }
    if (std::find(output_names.begin(), output_names.end(), net_output_name) == output_names.end()) {
        net_output_name = output_names[0];
    }

    if (vkdev) {
        std::vector<ncnn::vk_specialization_type> specializations(1);
        specializations[0].i = 0; // Android/Linux ABI

        {
            static std::vector<uint32_t> spirv_by_mode[2];
            static ncnn::Mutex lock;
            std::vector<uint32_t>& spirv = spirv_by_mode[tta_mode ? 1 : 0];
            {
                ncnn::MutexLockGuard guard(lock);
                if (spirv.empty()) {
                    if (tta_mode)
                        compile_spirv_module(realsr_preproc_tta_comp_data, sizeof(realsr_preproc_tta_comp_data), net.opt, spirv);
                    else
                        compile_spirv_module(realsr_preproc_comp_data, sizeof(realsr_preproc_comp_data), net.opt, spirv);
                }
            }
            realsr_preproc = new ncnn::Pipeline(vkdev);
            realsr_preproc->set_optimal_local_size_xyz(8, 8, 3);
            int pret = realsr_preproc->create(spirv.data(), spirv.size() * 4, specializations);
            if (pret != 0) {
                delete realsr_preproc;
                realsr_preproc = nullptr;
                return -5;
            }
        }

        {
            static std::vector<uint32_t> spirv_by_mode[2];
            static ncnn::Mutex lock;
            std::vector<uint32_t>& spirv = spirv_by_mode[tta_mode ? 1 : 0];
            {
                ncnn::MutexLockGuard guard(lock);
                if (spirv.empty()) {
                    if (tta_mode)
                        compile_spirv_module(realsr_postproc_tta_comp_data, sizeof(realsr_postproc_tta_comp_data), net.opt, spirv);
                    else
                        compile_spirv_module(realsr_postproc_comp_data, sizeof(realsr_postproc_comp_data), net.opt, spirv);
                }
            }
            realsr_postproc = new ncnn::Pipeline(vkdev);
            realsr_postproc->set_optimal_local_size_xyz(8, 8, 3);
            int pret = realsr_postproc->create(spirv.data(), spirv.size() * 4, specializations);
            if (pret != 0) {
                delete realsr_postproc;
                realsr_postproc = nullptr;
                delete realsr_preproc;
                realsr_preproc = nullptr;
                return -6;
            }
        }
    }

    // Alpha channel interpolation layers
    ncnn::Layer*& interp_layer = (scale == 2) ? bicubic_2x : ((scale == 3) ? bicubic_3x : bicubic_4x);
    interp_layer = ncnn::create_layer("Interp");
    if (!interp_layer) {
        delete realsr_preproc;
        realsr_preproc = nullptr;
        delete realsr_postproc;
        realsr_postproc = nullptr;
        return -7;
    }
    interp_layer->vkdev = vkdev;
    ncnn::ParamDict pd;
    pd.set(0, 3); pd.set(1, (float)scale); pd.set(2, (float)scale);
    interp_layer->load_param(pd);
    int iret = interp_layer->create_pipeline(net.opt);
    if (iret != 0) {
        delete interp_layer;
        interp_layer = nullptr;
        delete realsr_preproc;
        realsr_preproc = nullptr;
        delete realsr_postproc;
        realsr_postproc = nullptr;
        return -8;
    }

    return 0;
}

namespace {
static int reflect_index(int index, int length) {
    if (length <= 1) return 0;
    const int period = 2 * (length - 1);
    int folded = index % period;
    if (folded < 0) folded += period;
    return folded < length ? folded : period - folded;
}

struct VulkanAllocatorGuard {
    ncnn::VulkanDevice* dev;
    ncnn::VkAllocator* blob;
    ncnn::VkAllocator* staging;
    VulkanAllocatorGuard(ncnn::VulkanDevice* d, ncnn::VkAllocator* b, ncnn::VkAllocator* s)
        : dev(d), blob(b), staging(s) {}
    ~VulkanAllocatorGuard() {
        if (dev) {
            if (blob) dev->reclaim_blob_allocator(blob);
            if (staging) dev->reclaim_staging_allocator(staging);
        }
    }
    VulkanAllocatorGuard(const VulkanAllocatorGuard&) = delete;
    VulkanAllocatorGuard& operator=(const VulkanAllocatorGuard&) = delete;
};
} // namespace

int RealSR::process_streaming(
    const unsigned char* pixeldata, int w, int h, int channels,
    RowCallback row_cb, ProgressCallback prog_cb, void* user_data
) const
{
    if (!vkdev) {
        return process_streaming_cpu(pixeldata, w, h, channels, row_cb, prog_cb, user_data);
    }

    const int TILE_SIZE_X = tilesize;
    const int TILE_SIZE_Y = tilesize;

    ncnn::VkAllocator* blob_vkallocator = vkdev->acquire_blob_allocator();
    ncnn::VkAllocator* staging_vkallocator = vkdev->acquire_staging_allocator();
    VulkanAllocatorGuard alloc_guard(vkdev, blob_vkallocator, staging_vkallocator);

    ncnn::Option opt = net.opt;
    opt.blob_vkallocator = blob_vkallocator;
    opt.workspace_vkallocator = blob_vkallocator;
    opt.staging_vkallocator = staging_vkallocator;

    const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;
    const int total_tiles = xtiles * ytiles;

    size_t in_out_tile_elemsize = opt.use_fp16_storage ? 2u : 4u;
    std::vector<unsigned char> band_buf((size_t)w * scale * TILE_SIZE_Y * scale * channels);

    for (int yi = 0; yi < ytiles; yi++) {
        const int in_tile_y0 = std::max(yi * TILE_SIZE_Y - prepadding, 0);
        const int in_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y + prepadding, h);
        const int safe_tile_h = in_tile_y1 - in_tile_y0;

        ncnn::Mat in;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage) {
            const bool is_standard_size = (safe_tile_h == TILE_SIZE_Y + 2 * prepadding);
            if (is_standard_size) {
                in = ncnn::Mat(w, safe_tile_h, (unsigned char*)pixeldata + in_tile_y0 * w * channels, (size_t)channels, 1);
            } else {
                in.create(w, safe_tile_h, (size_t)channels, 1);
                const unsigned char* src = (unsigned char*)pixeldata + in_tile_y0 * w * channels;
                memcpy(in.data, src, (size_t)w * safe_tile_h * channels);
            }
        } else {
            in = ncnn::Mat::from_pixels(
                pixeldata + in_tile_y0 * w * channels,
                channels == 4 ? ncnn::Mat::PIXEL_RGBA : ncnn::Mat::PIXEL_RGB,
                w, safe_tile_h
            );
        }

        ncnn::VkCompute cmd(vkdev);
        ncnn::VkMat in_gpu;
        cmd.record_clone(in, in_gpu, opt);
        if (in_gpu.empty()) {
            return -101;
        }
        {
            // Complete input upload before tile commands use in_gpu, including xtiles == 1.
            int submit_ret = cmd.submit_and_wait();
            if (submit_ret != 0) return submit_ret;
            int reset_ret = cmd.reset();
            if (reset_ret != 0) return reset_ret;
        }

        const int out_tile_y0 = std::max(yi * TILE_SIZE_Y, 0);
        const int out_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h);
        const int out_band_h = (out_tile_y1 - out_tile_y0) * scale;
        const int out_band_w = w * scale;

        ncnn::VkMat out_gpu;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage) {
            out_gpu.create(out_band_w, out_band_h, (size_t)channels, 1, blob_vkallocator);
        } else {
            out_gpu.create(out_band_w, out_band_h, channels, (size_t)4u, 1, blob_vkallocator);
        }
        if (out_gpu.empty()) {
            return -100;
        }

        for (int xi = 0; xi < xtiles; xi++) {
            const int tile_w_nopad = std::min((xi + 1) * TILE_SIZE_X, w) - xi * TILE_SIZE_X;
            const int tile_h_nopad = std::min((yi + 1) * TILE_SIZE_Y, h) - yi * TILE_SIZE_Y;

            ncnn::VkMat in_tile_gpu;
            ncnn::VkMat in_alpha_tile_gpu;

            int tile_x0 = xi * TILE_SIZE_X - prepadding;
            int tile_x1 = std::min((xi + 1) * TILE_SIZE_X, w) + prepadding;
            int tile_y0 = yi * TILE_SIZE_Y - prepadding;
            int tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h) + prepadding;

            in_tile_gpu.create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
            if (in_tile_gpu.empty()) return -101;
            if (channels == 4) {
                // Match RGB tile padding so bicubic alpha interpolation has
                // a valid neighborhood across tile boundaries and outer edges.
                in_alpha_tile_gpu.create(tile_x1 - tile_x0, tile_y1 - tile_y0, 1, in_out_tile_elemsize, 1, blob_vkallocator);
                if (in_alpha_tile_gpu.empty()) return -102;
            }

            std::vector<ncnn::VkMat> preproc_bindings(3);
            preproc_bindings[0] = in_gpu;
            preproc_bindings[1] = in_tile_gpu;
            preproc_bindings[2] = in_alpha_tile_gpu;

            std::vector<ncnn::vk_constant_type> preproc_constants(13);
            preproc_constants[0].i = in_gpu.w;
            preproc_constants[1].i = in_gpu.h;
            preproc_constants[2].i = in_gpu.cstep;
            preproc_constants[3].i = in_tile_gpu.w;
            preproc_constants[4].i = in_tile_gpu.h;
            preproc_constants[5].i = in_tile_gpu.cstep;
            preproc_constants[6].i = prepadding;
            preproc_constants[7].i = prepadding;
            preproc_constants[8].i = xi * TILE_SIZE_X;
            preproc_constants[9].i = std::min(yi * TILE_SIZE_Y, prepadding);
            preproc_constants[10].i = channels;
            preproc_constants[11].i = in_alpha_tile_gpu.w;
            preproc_constants[12].i = in_alpha_tile_gpu.h;

            ncnn::VkMat preproc_dispatcher;
            preproc_dispatcher.w = in_tile_gpu.w;
            preproc_dispatcher.h = in_tile_gpu.h;
            preproc_dispatcher.c = channels;

            cmd.record_pipeline(realsr_preproc, preproc_bindings, preproc_constants, preproc_dispatcher);

            // Neural network inference on tile
            ncnn::VkMat out_tile_gpu;
            {
                ncnn::Extractor ex = net.create_extractor();
                ex.set_blob_vkallocator(blob_vkallocator);
                ex.set_workspace_vkallocator(blob_vkallocator);
                ex.set_staging_vkallocator(staging_vkallocator);
                int in_ret = ex.input(net_input_name.c_str(), in_tile_gpu);
                if (in_ret != 0) return in_ret;
                int ext_ret = ex.extract(net_output_name.c_str(), out_tile_gpu, cmd);
                if (ext_ret != 0) return ext_ret;
            }

            ncnn::VkMat out_alpha_tile_gpu;
            if (channels == 4) {
                int fret = 0;
                if (scale == 2) {
                    fret = bicubic_2x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                } else if (scale == 3) {
                    fret = bicubic_3x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                } else {
                    fret = bicubic_4x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                }
                if (fret != 0) return fret;
            }

            // Postprocess tile into band out_gpu
            std::vector<ncnn::VkMat> postproc_bindings(3);
            postproc_bindings[0] = out_tile_gpu;
            postproc_bindings[1] = out_alpha_tile_gpu.empty() ? out_tile_gpu : out_alpha_tile_gpu;
            postproc_bindings[2] = out_gpu;

            std::vector<ncnn::vk_constant_type> postproc_constants(13);
            postproc_constants[0].i = out_tile_gpu.w;
            postproc_constants[1].i = out_tile_gpu.h;
            postproc_constants[2].i = out_tile_gpu.cstep;
            postproc_constants[3].i = out_gpu.w;
            postproc_constants[4].i = out_gpu.h;
            postproc_constants[5].i = out_gpu.cstep;
            postproc_constants[6].i = xi * TILE_SIZE_X * scale;
            postproc_constants[7].i = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
            postproc_constants[8].i = prepadding * scale;
            postproc_constants[9].i = prepadding * scale;
            postproc_constants[10].i = channels;
            postproc_constants[11].i = out_alpha_tile_gpu.w;
            postproc_constants[12].i = out_alpha_tile_gpu.h;

            ncnn::VkMat postproc_dispatcher;
            postproc_dispatcher.w = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
            postproc_dispatcher.h = out_gpu.h;
            postproc_dispatcher.c = channels;

            cmd.record_pipeline(realsr_postproc, postproc_bindings, postproc_constants, postproc_dispatcher);

            {
                // Complete GPU work before tile-local VkMat objects leave scope.
                int sret = cmd.submit_and_wait();
                if (sret != 0) return sret;
                int rret = cmd.reset();
                if (rret != 0) return rret;
            }
            if (xtiles > 1) {
#ifdef __ANDROID__
                // Preserve the documented SurfaceFlinger scheduling mitigation.
                usleep(15000);
#endif
            }

            if (prog_cb) {
                int current_tile = yi * xtiles + xi + 1;
                int percent = (current_tile * 100) / total_tiles;
                int pret = prog_cb(current_tile, total_tiles, percent, user_data);
                if (pret != 0) return pret;
            }
        }

        // Download completed horizontal band and stream row-by-row
        ncnn::Mat out;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage) {
            out = ncnn::Mat(out_gpu.w, out_gpu.h, band_buf.data(), (size_t)channels, 1, opt.blob_allocator);
            cmd.record_clone(out_gpu, out, opt);
            int sret = cmd.submit_and_wait();
            if (sret != 0) return sret;
        } else {
            cmd.record_clone(out_gpu, out, opt);
            int sret = cmd.submit_and_wait();
            if (sret != 0) return sret;
            out.to_pixels(band_buf.data(), channels == 4 ? ncnn::Mat::PIXEL_RGBA : ncnn::Mat::PIXEL_RGB);
        }

        for (int r = 0; r < out_band_h; r++) {
            int abs_row = out_tile_y0 * scale + r;
            const unsigned char* row_ptr = band_buf.data() + (size_t)r * out_band_w * channels;
            int cb_ret = row_cb(row_ptr, abs_row, out_band_w, channels, user_data);
            if (cb_ret != 0) {
                return cb_ret; // Non-zero indicates cancellation or consumer abort
            }
        }
#ifdef __ANDROID__
        usleep(10000); // 10ms yield between horizontal bands
#endif
    }

    return 0;
}

int RealSR::process_streaming_cpu(
    const unsigned char* pixeldata, int w, int h, int channels,
    RowCallback row_cb, ProgressCallback prog_cb, void* user_data
) const
{
    const int TILE_SIZE_X = tilesize;
    const int TILE_SIZE_Y = tilesize;

    const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;
    const int total_tiles = xtiles * ytiles;

    std::vector<unsigned char> band_buf((size_t)w * scale * TILE_SIZE_Y * scale * channels);

    for (int yi = 0; yi < ytiles; yi++) {
        const int in_tile_y0 = std::max(yi * TILE_SIZE_Y - prepadding, 0);
        const int in_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y + prepadding, h);
        const int out_tile_y0 = std::max(yi * TILE_SIZE_Y, 0);
        const int out_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h);
        const int out_band_h = (out_tile_y1 - out_tile_y0) * scale;
        const int out_band_w = w * scale;

        for (int xi = 0; xi < xtiles; xi++) {
            const int in_tile_x0 = std::max(xi * TILE_SIZE_X - prepadding, 0);
            const int in_tile_x1 = std::min((xi + 1) * TILE_SIZE_X + prepadding, w);
            const int in_tw = in_tile_x1 - in_tile_x0;
            const int in_th = in_tile_y1 - in_tile_y0;

            ncnn::Mat in_tile = ncnn::Mat::from_pixels_roi(
                pixeldata,
                channels == 4 ? ncnn::Mat::PIXEL_RGBA2RGB : ncnn::Mat::PIXEL_RGB,
                w, h,
                in_tile_x0, in_tile_y0,
                in_tw, in_th
            );

            // Scale to [0, 1]
            const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
            in_tile.substract_mean_normalize(0, norm_vals);

            ncnn::Extractor ex = net.create_extractor();
            int in_ret = ex.input(net_input_name.c_str(), in_tile);
            if (in_ret != 0) {
                return in_ret;
            }

            ncnn::Mat out_tile;
            int ex_ret = ex.extract(net_output_name.c_str(), out_tile);
            if (ex_ret != 0) {
                return ex_ret;
            }

            // Denormalize
            const float denorm_vals[3] = {255.f, 255.f, 255.f};
            out_tile.substract_mean_normalize(0, denorm_vals);

            // Crop prepadding borders
            const int crop_x0 = (xi * TILE_SIZE_X - in_tile_x0) * scale;
            const int crop_y0 = (yi * TILE_SIZE_Y - in_tile_y0) * scale;
            const int crop_w = (std::min((xi + 1) * TILE_SIZE_X, w) - xi * TILE_SIZE_X) * scale;
            const int crop_h = (std::min((yi + 1) * TILE_SIZE_Y, h) - yi * TILE_SIZE_Y) * scale;

            ncnn::Mat cropped;
            ncnn::copy_cut_border(out_tile, cropped, crop_y0, out_tile.h - crop_y0 - crop_h, crop_x0, out_tile.w - crop_x0 - crop_w);

            // Convert and paste into band_buf
            std::vector<unsigned char> tile_pixels((size_t)crop_w * crop_h * 3);
            cropped.to_pixels(tile_pixels.data(), ncnn::Mat::PIXEL_RGB);

            std::vector<float> alpha_cropped;
            if (channels == 4) {
                ncnn::Mat in_alpha(in_tw, in_th, 1);
                for (int y = 0; y < in_th; y++) {
                    const unsigned char* p = pixeldata + ((size_t)(in_tile_y0 + y) * w + in_tile_x0) * 4;
                    float* aptr = in_alpha.row(y);
                    for (int x = 0; x < in_tw; x++) {
                        aptr[x] = p[x * 4 + 3];
                    }
                }

                ncnn::Mat out_alpha;
                ncnn::resize_bicubic(in_alpha, out_alpha, in_tw * scale, in_th * scale);

                ncnn::Mat cropped_alpha;
                ncnn::copy_cut_border(out_alpha, cropped_alpha, crop_y0, out_alpha.h - crop_y0 - crop_h, crop_x0, out_alpha.w - crop_x0 - crop_w);

                alpha_cropped.resize((size_t)crop_w * crop_h);
                for (int y = 0; y < crop_h; y++) {
                    const float* aptr = cropped_alpha.row(y);
                    for (int x = 0; x < crop_w; x++) {
                        alpha_cropped[(size_t)y * crop_w + x] = aptr[x];
                    }
                }
            }

            const int dst_x_offset = xi * TILE_SIZE_X * scale;
            for (int r = 0; r < crop_h; r++) {
                unsigned char* dst_row = band_buf.data() + ((size_t)r * out_band_w + dst_x_offset) * channels;
                const unsigned char* src_row = tile_pixels.data() + (size_t)r * crop_w * 3;
                if (channels == 3) {
                    memcpy(dst_row, src_row, (size_t)crop_w * 3);
                } else {
                    const float* a_row = alpha_cropped.data() + (size_t)r * crop_w;
                    for (int c = 0; c < crop_w; c++) {
                        dst_row[c * 4] = src_row[c * 3];
                        dst_row[c * 4 + 1] = src_row[c * 3 + 1];
                        dst_row[c * 4 + 2] = src_row[c * 3 + 2];
                        float a = a_row[c];
                        dst_row[c * 4 + 3] = (unsigned char)std::max(0.0f, std::min(255.0f, a + 0.5f));
                    }
                }
            }

            if (prog_cb) {
                int current_tile = yi * xtiles + xi + 1;
                int percent = (current_tile * 100) / total_tiles;
                int pret = prog_cb(current_tile, total_tiles, percent, user_data);
                if (pret != 0) return pret;
            }
        }

        // Deliver band rows
        for (int r = 0; r < out_band_h; r++) {
            int abs_row = out_tile_y0 * scale + r;
            const unsigned char* row_ptr = band_buf.data() + (size_t)r * out_band_w * channels;
            int cb_ret = row_cb(row_ptr, abs_row, out_band_w, channels, user_data);
            if (cb_ret != 0) {
                return cb_ret;
            }
        }
    }

    return 0;
}

int RealSR::process_strip_streaming(
    int w, int h, int channels,
    StripCallback strip_cb, RowCallback row_cb, ProgressCallback prog_cb, void* user_data
) const
{
    if (!vkdev) {
        return process_strip_streaming_cpu(w, h, channels, strip_cb, row_cb, prog_cb, user_data);
    }

    const int TILE_SIZE_X = tilesize;
    const int TILE_SIZE_Y = tilesize;

    ncnn::VkAllocator* blob_vkallocator = vkdev->acquire_blob_allocator();
    ncnn::VkAllocator* staging_vkallocator = vkdev->acquire_staging_allocator();
    VulkanAllocatorGuard alloc_guard(vkdev, blob_vkallocator, staging_vkallocator);

    ncnn::Option opt = net.opt;
    opt.blob_vkallocator = blob_vkallocator;
    opt.workspace_vkallocator = blob_vkallocator;
    opt.staging_vkallocator = staging_vkallocator;

    const size_t in_out_tile_elemsize = opt.use_fp16_storage ? 2u : 4u;

    const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;
    const int total_tiles = xtiles * ytiles;

    const int max_in_band_h = TILE_SIZE_Y + 2 * prepadding;
    std::vector<unsigned char> strip_buf((size_t)w * max_in_band_h * channels);
    std::vector<unsigned char> band_buf((size_t)w * scale * TILE_SIZE_Y * scale * channels);

    for (int yi = 0; yi < ytiles; yi++) {
        const int in_tile_y0 = std::max(yi * TILE_SIZE_Y - prepadding, 0);
        const int in_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y + prepadding, h);
        const int in_band_h = in_tile_y1 - in_tile_y0;
        const int out_tile_y0 = std::max(yi * TILE_SIZE_Y, 0);
        const int out_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h);
        const int out_band_h = (out_tile_y1 - out_tile_y0) * scale;
        const int out_band_w = w * scale;

        // Fetch input strip [0, in_tile_y0, w, in_band_h] from consumer
        int sret = strip_cb(strip_buf.data(), in_tile_y0, in_band_h, user_data);
        if (sret != 0) {
            return sret;
        }

        if (strip_buf.size() >= 6) {
            LOGD("process_strip_streaming: yi=%d y0=%d h=%d c=%d scale=%d fp16_s=%d fp16_p=%d int8_s=%d strip_buf[0..5]=%d,%d,%d,%d,%d,%d",
                 yi, in_tile_y0, in_band_h, channels, scale,
                 (int)opt.use_fp16_storage, (int)opt.use_fp16_packed, (int)opt.use_int8_storage,
                 (int)strip_buf[0], (int)strip_buf[1], (int)strip_buf[2],
                 (int)strip_buf[3], (int)strip_buf[4], (int)strip_buf[5]);
        } else {
            LOGD("process_strip_streaming: strip buffer has only %zu bytes", strip_buf.size());
        }

        ncnn::VkMat out_gpu;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage) {
            out_gpu.create(out_band_w, out_band_h, (size_t)channels, 1, blob_vkallocator);
        } else {
            out_gpu.create(out_band_w, out_band_h, channels, (size_t)4u, 1, blob_vkallocator);
        }
        if (out_gpu.empty()) {
            return -100;
        }

        ncnn::VkCompute cmd(vkdev);

        // Upload each source band once. GPU preprocessing below creates all
        // reflected RGB/alpha tiles, avoiding a CPU ROI conversion and upload
        // for every horizontal tile.
        ncnn::Mat band_in = ncnn::Mat::from_pixels(
            strip_buf.data(),
            channels == 4 ? ncnn::Mat::PIXEL_RGBA : ncnn::Mat::PIXEL_RGB,
            w, in_band_h
        );
        ncnn::VkMat in_gpu;
        cmd.record_clone(band_in, in_gpu, opt);
        if (in_gpu.empty()) return -101;
        {
            int upload_ret = cmd.submit_and_wait();
            if (upload_ret != 0) return upload_ret;
            int reset_ret = cmd.reset();
            if (reset_ret != 0) return reset_ret;
        }

        for (int xi = 0; xi < xtiles; xi++) {
            const int tile_w_nopad = std::min((xi + 1) * TILE_SIZE_X, w) - xi * TILE_SIZE_X;
            const int tile_h_nopad = std::min((yi + 1) * TILE_SIZE_Y, h) - yi * TILE_SIZE_Y;
            const int tile_x0 = xi * TILE_SIZE_X - prepadding;
            const int tile_x1 = std::min((xi + 1) * TILE_SIZE_X, w) + prepadding;
            const int tile_y0 = yi * TILE_SIZE_Y - prepadding;
            const int tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h) + prepadding;

            const int crop_x0 = prepadding * scale;
            const int crop_y0 = prepadding * scale;
            const int crop_w = tile_w_nopad * scale;
            const int crop_h = tile_h_nopad * scale;

            ncnn::VkMat in_tile_gpu;
            in_tile_gpu.create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator);
            if (in_tile_gpu.empty()) return -101;

            ncnn::VkMat in_alpha_tile_gpu;
            if (channels == 4) {
                in_alpha_tile_gpu.create(tile_x1 - tile_x0, tile_y1 - tile_y0, 1, in_out_tile_elemsize, 1, blob_vkallocator);
                if (in_alpha_tile_gpu.empty()) return -102;
            }

            std::vector<ncnn::VkMat> preproc_bindings(3);
            preproc_bindings[0] = in_gpu;
            preproc_bindings[1] = in_tile_gpu;
            preproc_bindings[2] = in_alpha_tile_gpu;

            std::vector<ncnn::vk_constant_type> preproc_constants(13);
            preproc_constants[0].i = in_gpu.w;
            preproc_constants[1].i = in_gpu.h;
            preproc_constants[2].i = in_gpu.cstep;
            preproc_constants[3].i = in_tile_gpu.w;
            preproc_constants[4].i = in_tile_gpu.h;
            preproc_constants[5].i = in_tile_gpu.cstep;
            preproc_constants[6].i = prepadding;
            preproc_constants[7].i = prepadding;
            preproc_constants[8].i = xi * TILE_SIZE_X;
            preproc_constants[9].i = std::min(yi * TILE_SIZE_Y, prepadding);
            preproc_constants[10].i = channels;
            preproc_constants[11].i = in_alpha_tile_gpu.w;
            preproc_constants[12].i = in_alpha_tile_gpu.h;

            ncnn::VkMat preproc_dispatcher;
            preproc_dispatcher.w = in_tile_gpu.w;
            preproc_dispatcher.h = in_tile_gpu.h;
            preproc_dispatcher.c = channels;
            cmd.record_pipeline(realsr_preproc, preproc_bindings, preproc_constants, preproc_dispatcher);

            ncnn::VkMat out_alpha_tile_gpu;
            if (channels == 4) {
                int fret = 0;
                if (scale == 2) {
                    fret = bicubic_2x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                } else if (scale == 3) {
                    fret = bicubic_3x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                } else {
                    fret = bicubic_4x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                }
                if (fret != 0) return fret;
            }

            ncnn::VkMat out_tile_gpu;
            {
                ncnn::Extractor ex = net.create_extractor();
                ex.set_blob_vkallocator(blob_vkallocator);
                ex.set_workspace_vkallocator(blob_vkallocator);
                ex.set_staging_vkallocator(staging_vkallocator);
                int in_ret = ex.input(net_input_name.c_str(), in_tile_gpu);
                if (in_ret != 0) return in_ret;
                int ext_ret = ex.extract(net_output_name.c_str(), out_tile_gpu, cmd);
                if (ext_ret != 0) return ext_ret;
            }

            // Postprocess tile into band out_gpu
            std::vector<ncnn::VkMat> postproc_bindings(3);
            postproc_bindings[0] = out_tile_gpu;
            postproc_bindings[1] = out_alpha_tile_gpu.empty() ? out_tile_gpu : out_alpha_tile_gpu;
            postproc_bindings[2] = out_gpu;

            std::vector<ncnn::vk_constant_type> postproc_constants(13);
            postproc_constants[0].i = out_tile_gpu.w;
            postproc_constants[1].i = out_tile_gpu.h;
            postproc_constants[2].i = out_tile_gpu.cstep;
            postproc_constants[3].i = out_gpu.w;
            postproc_constants[4].i = out_gpu.h;
            postproc_constants[5].i = out_gpu.cstep;
            postproc_constants[6].i = xi * TILE_SIZE_X * scale;
            postproc_constants[7].i = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
            postproc_constants[8].i = crop_x0;
            postproc_constants[9].i = crop_y0;
            postproc_constants[10].i = channels;
            postproc_constants[11].i = out_alpha_tile_gpu.w;
            postproc_constants[12].i = out_alpha_tile_gpu.h;

            ncnn::VkMat postproc_dispatcher;
            postproc_dispatcher.w = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
            postproc_dispatcher.h = out_gpu.h;
            postproc_dispatcher.c = channels;

            cmd.record_pipeline(realsr_postproc, postproc_bindings, postproc_constants, postproc_dispatcher);

            {
                // Complete GPU work before tile-local VkMat objects leave scope.
                int submit_ret = cmd.submit_and_wait();
                if (submit_ret != 0) return submit_ret;
                int reset_ret = cmd.reset();
                if (reset_ret != 0) return reset_ret;
            }
            if (xtiles > 1) {
#ifdef __ANDROID__
                // Preserve the documented SurfaceFlinger scheduling mitigation.
                usleep(15000);
#endif
            }

            if (prog_cb) {
                int current_tile = yi * xtiles + xi + 1;
                int percent = (current_tile * 100) / total_tiles;
                int pret = prog_cb(current_tile, total_tiles, percent, user_data);
                if (pret != 0) return pret;
            }
        }

        // Download completed horizontal band and stream row-by-row
        ncnn::Mat out;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage) {
            out = ncnn::Mat(out_gpu.w, out_gpu.h, band_buf.data(), (size_t)channels, 1, opt.blob_allocator);
            cmd.record_clone(out_gpu, out, opt);
            int submit_ret = cmd.submit_and_wait();
            if (submit_ret != 0) {
                return submit_ret;
            }
        } else {
            cmd.record_clone(out_gpu, out, opt);
            int submit_ret = cmd.submit_and_wait();
            if (submit_ret != 0) {
                return submit_ret;
            }
            out.to_pixels(band_buf.data(), channels == 4 ? ncnn::Mat::PIXEL_RGBA : ncnn::Mat::PIXEL_RGB);
        }

        if (band_buf.size() >= 6) {
            LOGD("download: out_gpu w=%d h=%d c=%d cstep=%d. out dims=%d w=%d h=%d c=%d cstep=%d elemsize=%zu band_buf[0..5]=%d,%d,%d,%d,%d,%d",
                 out_gpu.w, out_gpu.h, out_gpu.c, (int)out_gpu.cstep,
                 out.dims, out.w, out.h, out.c, (int)out.cstep, out.elemsize,
                 (int)band_buf[0], (int)band_buf[1], (int)band_buf[2],
                 (int)band_buf[3], (int)band_buf[4], (int)band_buf[5]);
        } else {
            LOGD("download: band buffer has only %zu bytes", band_buf.size());
        }

        for (int r = 0; r < out_band_h; r++) {
            int abs_row = out_tile_y0 * scale + r;
            const unsigned char* row_ptr = band_buf.data() + (size_t)r * out_band_w * channels;
            int cb_ret = row_cb(row_ptr, abs_row, out_band_w, channels, user_data);
            if (cb_ret != 0) {
                return cb_ret;
            }
        }
#ifdef __ANDROID__
        usleep(10000); // 10ms yield between horizontal bands
#endif
    }

    return 0;
}

int RealSR::process_strip_streaming_cpu(
    int w, int h, int channels,
    StripCallback strip_cb, RowCallback row_cb, ProgressCallback prog_cb, void* user_data
) const
{
    const int TILE_SIZE_X = tilesize;
    const int TILE_SIZE_Y = tilesize;

    const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;
    const int total_tiles = xtiles * ytiles;

    const int max_in_band_h = TILE_SIZE_Y + 2 * prepadding;
    std::vector<unsigned char> strip_buf((size_t)w * max_in_band_h * channels);
    std::vector<unsigned char> band_buf((size_t)w * scale * TILE_SIZE_Y * scale * channels);

    for (int yi = 0; yi < ytiles; yi++) {
        const int in_tile_y0 = std::max(yi * TILE_SIZE_Y - prepadding, 0);
        const int in_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y + prepadding, h);
        const int in_band_h = in_tile_y1 - in_tile_y0;
        const int out_tile_y0 = std::max(yi * TILE_SIZE_Y, 0);
        const int out_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h);
        const int out_band_h = (out_tile_y1 - out_tile_y0) * scale;
        const int out_band_w = w * scale;

        int sret = strip_cb(strip_buf.data(), in_tile_y0, in_band_h, user_data);
        if (sret != 0) {
            return sret;
        }

        for (int xi = 0; xi < xtiles; xi++) {
            const int in_tile_x0 = std::max(xi * TILE_SIZE_X - prepadding, 0);
            const int in_tile_x1 = std::min((xi + 1) * TILE_SIZE_X + prepadding, w);
            const int in_tile_w = in_tile_x1 - in_tile_x0;

            ncnn::Mat in_tile = ncnn::Mat::from_pixels_roi(
                strip_buf.data(),
                channels == 4 ? ncnn::Mat::PIXEL_RGBA2RGB : ncnn::Mat::PIXEL_RGB,
                w, in_band_h,
                in_tile_x0, 0,
                in_tile_w, in_band_h
            );

            // Scale to [0, 1]
            const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
            in_tile.substract_mean_normalize(0, norm_vals);

            ncnn::Extractor ex = net.create_extractor();
            int in_ret = ex.input(net_input_name.c_str(), in_tile);
            if (in_ret != 0) {
                return in_ret;
            }

            ncnn::Mat out_tile;
            int ex_ret = ex.extract(net_output_name.c_str(), out_tile);
            if (ex_ret != 0) {
                return ex_ret;
            }

            // Denormalize
            const float denorm_vals[3] = {255.f, 255.f, 255.f};
            out_tile.substract_mean_normalize(0, denorm_vals);

            // Crop prepadding borders
            const int crop_x0 = (xi * TILE_SIZE_X - in_tile_x0) * scale;
            const int crop_y0 = (yi * TILE_SIZE_Y - in_tile_y0) * scale;
            const int crop_w = (std::min((xi + 1) * TILE_SIZE_X, w) - xi * TILE_SIZE_X) * scale;
            const int crop_h = (std::min((yi + 1) * TILE_SIZE_Y, h) - yi * TILE_SIZE_Y) * scale;

            ncnn::Mat cropped;
            ncnn::copy_cut_border(out_tile, cropped, crop_y0, out_tile.h - crop_y0 - crop_h, crop_x0, out_tile.w - crop_x0 - crop_w);

            // Convert and paste into band_buf
            std::vector<unsigned char> tile_pixels((size_t)crop_w * crop_h * 3);
            cropped.to_pixels(tile_pixels.data(), ncnn::Mat::PIXEL_RGB);

            std::vector<float> alpha_cropped;
            if (channels == 4) {
                ncnn::Mat in_alpha(in_tile_w, in_band_h, 1);
                for (int y = 0; y < in_band_h; y++) {
                    const unsigned char* p = strip_buf.data() + ((size_t)y * w + in_tile_x0) * 4;
                    float* aptr = in_alpha.row(y);
                    for (int x = 0; x < in_tile_w; x++) {
                        aptr[x] = p[x * 4 + 3];
                    }
                }

                ncnn::Mat out_alpha;
                ncnn::resize_bicubic(in_alpha, out_alpha, in_tile_w * scale, in_band_h * scale);

                ncnn::Mat cropped_alpha;
                ncnn::copy_cut_border(out_alpha, cropped_alpha, crop_y0, out_alpha.h - crop_y0 - crop_h, crop_x0, out_alpha.w - crop_x0 - crop_w);

                alpha_cropped.resize((size_t)crop_w * crop_h);
                for (int y = 0; y < crop_h; y++) {
                    const float* aptr = cropped_alpha.row(y);
                    for (int x = 0; x < crop_w; x++) {
                        alpha_cropped[(size_t)y * crop_w + x] = aptr[x];
                    }
                }
            }

            const int dst_x_offset = xi * TILE_SIZE_X * scale;
            for (int r = 0; r < crop_h; r++) {
                unsigned char* dst_row = band_buf.data() + ((size_t)r * out_band_w + dst_x_offset) * channels;
                const unsigned char* src_row = tile_pixels.data() + (size_t)r * crop_w * 3;
                if (channels == 3) {
                    memcpy(dst_row, src_row, (size_t)crop_w * 3);
                } else {
                    const float* a_row = alpha_cropped.data() + (size_t)r * crop_w;
                    for (int c = 0; c < crop_w; c++) {
                        dst_row[c * 4] = src_row[c * 3];
                        dst_row[c * 4 + 1] = src_row[c * 3 + 1];
                        dst_row[c * 4 + 2] = src_row[c * 3 + 2];
                        float a = a_row[c];
                        dst_row[c * 4 + 3] = (unsigned char)std::max(0.0f, std::min(255.0f, a + 0.5f));
                    }
                }
            }

            if (prog_cb) {
                int current_tile = yi * xtiles + xi + 1;
                int percent = (current_tile * 100) / total_tiles;
                int pret = prog_cb(current_tile, total_tiles, percent, user_data);
                if (pret != 0) return pret;
            }
        }

        // Deliver band rows
        for (int r = 0; r < out_band_h; r++) {
            int abs_row = out_tile_y0 * scale + r;
            const unsigned char* row_ptr = band_buf.data() + (size_t)r * out_band_w * channels;
            int cb_ret = row_cb(row_ptr, abs_row, out_band_w, channels, user_data);
            if (cb_ret != 0) {
                return cb_ret;
            }
        }
    }

    return 0;
}

int RealSR::process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const
{
    const unsigned char* pixeldata = (const unsigned char*)inimage.data;
    const int w = inimage.w;
    const int h = inimage.h;
    const int channels = inimage.elempack;

    return process_streaming(
        pixeldata, w, h, channels,
        [](const unsigned char* row_data, int row_idx, int row_w, int c, void* udata) -> int {
            ncnn::Mat* out = static_cast<ncnn::Mat*>(udata);
            memcpy((unsigned char*)out->data + (size_t)row_idx * row_w * c, row_data, (size_t)row_w * c);
            return 0;
        },
        nullptr,
        (void*)&outimage
    );
}

int RealSR::process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage) const
{
    const unsigned char* pixeldata = (const unsigned char*)inimage.data;
    const int w = inimage.w;
    const int h = inimage.h;
    const int channels = inimage.elempack;

    return process_streaming_cpu(
        pixeldata, w, h, channels,
        [](const unsigned char* row_data, int row_idx, int row_w, int c, void* udata) -> int {
            ncnn::Mat* out = static_cast<ncnn::Mat*>(udata);
            memcpy((unsigned char*)out->data + (size_t)row_idx * row_w * c, row_data, (size_t)row_w * c);
            return 0;
        },
        nullptr,
        (void*)&outimage
    );
}
