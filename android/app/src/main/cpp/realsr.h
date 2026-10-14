// VeilFrame NCNN Upscaler Engine
// Based on RealSR (Tencent ncnn) with:
// 1. Destructor null-pointer safety
// 2. Qualcomm Adreno 740 unaligned tile memcpy fix
// 3. True scanline-band streaming callback (eliminating full-image host allocations)
// 4. Return-code validation for model loading

#ifndef VEILFRAME_REALSR_H
#define VEILFRAME_REALSR_H

#include <string>
#include <vector>

// ncnn headers
#include "ncnn/net.h"
#include "ncnn/gpu.h"
#include "ncnn/layer.h"

// Callback type for streaming row delivery:
// (row_data, row_index, row_width, channels, user_data)
// Return 0 to continue, non-zero to cancel/abort.
typedef int (*RowCallback)(const unsigned char* row_data, int row_index, int row_width, int channels, void* user_data);

// Callback type for progress updates:
// (current_tile, total_tiles, percent, user_data)
typedef void (*ProgressCallback)(int current_tile, int total_tiles, int percent, void* user_data);

class RealSR
{
public:
    RealSR(int gpuid, bool tta_mode = false, int num_threads = 1);
    ~RealSR();

    int load(const std::string& parampath, const std::string& modelpath);

    // Standard in-memory processing (allocates output image in host RAM)
    int process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const;
    int process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage) const;

    // True dual-ended streaming processing: outputs each horizontal row to callback
    // as bands complete, completely eliminating full-image host allocations.
    int process_streaming(
        const unsigned char* pixeldata, int w, int h, int channels,
        RowCallback row_cb, ProgressCallback prog_cb, void* user_data
    ) const;

    int process_streaming_cpu(
        const unsigned char* pixeldata, int w, int h, int channels,
        RowCallback row_cb, ProgressCallback prog_cb, void* user_data
    ) const;

public:
    // realsr parameters
    int scale;
    int tilesize;
    int prepadding;
    std::string net_input_name = "data";
    std::string net_output_name = "output";

private:
    ncnn::VulkanDevice* vkdev;
    ncnn::Net net;
    ncnn::Pipeline* realsr_preproc;
    ncnn::Pipeline* realsr_postproc;
    ncnn::Layer* bicubic_2x;
    ncnn::Layer* bicubic_3x;
    ncnn::Layer* bicubic_4x;
    bool tta_mode;
};

#endif // VEILFRAME_REALSR_H
