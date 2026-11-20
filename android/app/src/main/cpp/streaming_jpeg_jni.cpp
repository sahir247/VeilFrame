#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <csetjmp>
#include <cstdint>
#include <jpeglib.h>

namespace {
struct Error {
    jpeg_error_mgr base;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
};
void fail(j_common_ptr c) {
    auto* e = reinterpret_cast<Error*>(c->err);
    (*c->err->format_message)(c, e->message);
    longjmp(e->jump, 1);
}
void message(j_common_ptr c, int level) { if (level < 0) fail(c); }
void setup(Error& e) {
    jpeg_std_error(&e.base);
    e.base.error_exit = fail;
    e.base.emit_message = message;
}
void io(JNIEnv* env, const char* reason) {
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) env->ThrowNew(cls, reason);
}
struct Encoder {
    jpeg_compress_struct jpeg;
    Error error;
    FILE* file;
    bool created;
    bool finished;
    int width;
    unsigned char* row;
};
struct Decoder {
    jpeg_decompress_struct jpeg;
    Error error;
    FILE* file;
    bool created;
    bool finished;
    unsigned char* row;
};
void freeEncoder(Encoder* p) {
    if (!p) return;
    if (p->created) jpeg_destroy_compress(&p->jpeg);
    if (p->file) std::fclose(p->file);
    std::free(p->row);
    std::free(p);
}
void freeDecoder(Decoder* p) {
    if (!p) return;
    if (p->created) jpeg_destroy_decompress(&p->jpeg);
    if (p->file) std::fclose(p->file);
    std::free(p->row);
    std::free(p);
}
template<typename T> T* handle(jlong value) {
    return reinterpret_cast<T*>(static_cast<uintptr_t>(value));
}
template<typename T> jlong token(T* value) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(value));
}
bool geometry(jint w, jint h) {
    return w > 0 && h > 0 && w <= 32768 && h <= 32768 &&
           static_cast<int64_t>(w) * h <= 128000000;
}
FILE* openPath(JNIEnv* env, jstring path, const char* mode) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return nullptr;
    FILE* f = std::fopen(chars, mode);
    env->ReleaseStringUTFChars(path, chars);
    return f;
}
}

#define JNI_METHOD(name) Java_com_veilframe_app_cv_segmentation_rembg_StreamingJpeg_##name

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(openEncoder)(
    JNIEnv* env, jobject, jstring path, jint w, jint h, jint quality) {
    if (!geometry(w,h) || quality < 1 || quality > 100) { io(env,"Invalid JPEG geometry/quality"); return 0; }
    auto* p = static_cast<Encoder*>(std::calloc(1, sizeof(Encoder)));
    if (!p) { io(env,"JPEG allocation failed"); return 0; }
    p->width = w;
    p->file = openPath(env,path,"wb");
    p->row = static_cast<unsigned char*>(std::malloc(static_cast<size_t>(w)*3));
    if (!p->file || !p->row) { freeEncoder(p); if (!env->ExceptionCheck()) io(env,"Cannot open JPEG stage"); return 0; }
    setup(p->error);
    p->jpeg.err = &p->error.base;
    if (setjmp(p->error.jump)) { io(env,p->error.message); freeEncoder(p); return 0; }
    p->created = true;
    jpeg_create_compress(&p->jpeg);
    jpeg_stdio_dest(&p->jpeg,p->file);
    p->jpeg.image_width = static_cast<JDIMENSION>(w);
    p->jpeg.image_height = static_cast<JDIMENSION>(h);
    p->jpeg.input_components = 3;
    p->jpeg.in_color_space = JCS_RGB;
    jpeg_set_defaults(&p->jpeg);
    jpeg_set_quality(&p->jpeg,quality,TRUE);
    // Sequential baseline, 4:4:4, no image-sized coefficient passes.
    p->jpeg.optimize_coding = FALSE;
    p->jpeg.arith_code = FALSE;
    for (int i=0; i<p->jpeg.num_components; ++i) {
        p->jpeg.comp_info[i].h_samp_factor = 1;
        p->jpeg.comp_info[i].v_samp_factor = 1;
    }
    jpeg_start_compress(&p->jpeg,TRUE);
    return token(p);
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(writeRow)(
    JNIEnv* env,jobject,jlong value,jbyteArray rgb) {
    auto* p=handle<Encoder>(value);
    if (!p || p->finished || p->jpeg.next_scanline >= p->jpeg.image_height ||
        !rgb || env->GetArrayLength(rgb)!=p->width*3) { io(env,"Invalid JPEG scanline"); return; }
    env->GetByteArrayRegion(rgb,0,p->width*3,reinterpret_cast<jbyte*>(p->row));
    if (env->ExceptionCheck()) return;
    if (setjmp(p->error.jump)) { io(env,p->error.message); return; }
    JSAMPROW row=p->row;
    if (jpeg_write_scanlines(&p->jpeg,&row,1)!=1) io(env,"JPEG scanline not consumed");
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(finishEncoder)(
    JNIEnv* env,jobject,jlong value) {
    auto* p=handle<Encoder>(value);
    if (!p || p->finished || p->jpeg.next_scanline!=p->jpeg.image_height) { io(env,"Incomplete JPEG encoding"); return; }
    if (setjmp(p->error.jump)) { io(env,p->error.message); return; }
    jpeg_finish_compress(&p->jpeg);
    p->finished=true;
    const int flushStatus=std::fflush(p->file);
    const int errorStatus=std::ferror(p->file);
    const int closeStatus=std::fclose(p->file);
    p->file=nullptr;
    if (flushStatus || errorStatus || closeStatus) io(env,"JPEG stage write failed");
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(closeEncoder)(JNIEnv*,jobject,jlong value) {
    freeEncoder(handle<Encoder>(value));
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(openVerifier)(
    JNIEnv* env,jobject,jstring path,jint w,jint h) {
    if (!geometry(w,h)) { io(env,"Invalid JPEG verification geometry"); return 0; }
    auto* p=static_cast<Decoder*>(std::calloc(1,sizeof(Decoder)));
    if (!p) { io(env,"JPEG verifier allocation failed"); return 0; }
    p->file=openPath(env,path,"rb");
    p->row=static_cast<unsigned char*>(std::malloc(static_cast<size_t>(w)*3));
    if(!p->file || !p->row) { freeDecoder(p); if(!env->ExceptionCheck()) io(env,"Cannot read JPEG stage"); return 0; }
    setup(p->error);
    p->jpeg.err=&p->error.base;
    if(setjmp(p->error.jump)) { io(env,p->error.message); freeDecoder(p); return 0; }
    p->created=true;
    jpeg_create_decompress(&p->jpeg);
    jpeg_stdio_src(&p->jpeg,p->file);
    if (jpeg_read_header(&p->jpeg,TRUE)!=JPEG_HEADER_OK ||
        p->jpeg.image_width!=static_cast<JDIMENSION>(w) ||
        p->jpeg.image_height!=static_cast<JDIMENSION>(h) ||
        p->jpeg.data_precision!=8 || p->jpeg.num_components!=3 ||
        p->jpeg.progressive_mode || p->jpeg.arith_code || jpeg_has_multiple_scans(&p->jpeg)) {
        io(env,"Unexpected JPEG header or non-sequential coding"); freeDecoder(p); return 0;
    }
    p->jpeg.out_color_space=JCS_RGB;
    if(!jpeg_start_decompress(&p->jpeg) || p->jpeg.output_components!=3 ||
       p->jpeg.output_width!=static_cast<JDIMENSION>(w) ||
       p->jpeg.output_height!=static_cast<JDIMENSION>(h)) {
        io(env,"Invalid JPEG decoder output"); freeDecoder(p); return 0;
    }
    return token(p);
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(verifyRows)(
    JNIEnv* env,jobject,jlong value,jint rows) {
    auto* p=handle<Decoder>(value);
    if (!p || p->finished || rows<1 || rows>64 ||
        static_cast<JDIMENSION>(rows)>p->jpeg.output_height-p->jpeg.output_scanline) {
        io(env,"Invalid JPEG verification request"); return;
    }
    if(setjmp(p->error.jump)) { io(env,p->error.message); return; }
    for(int i=0;i<rows;i++) {
        JSAMPROW row=p->row;
        if(jpeg_read_scanlines(&p->jpeg,&row,1)!=1) { io(env,"Truncated JPEG row"); return; }
    }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(finishVerifier)(
    JNIEnv* env,jobject,jlong value) {
    auto* p=handle<Decoder>(value);
    if(!p || p->finished || p->jpeg.output_scanline!=p->jpeg.output_height) { io(env,"Incomplete JPEG verification"); return; }
    if(setjmp(p->error.jump)) { io(env,p->error.message); return; }
    if(!jpeg_finish_decompress(&p->jpeg) || p->jpeg.src->bytes_in_buffer!=0 ||
       std::fgetc(p->file)!=EOF || std::ferror(p->file)) {
        io(env,"JPEG stream has trailing data or failed to terminate"); return;
    }
    p->finished=true;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(closeVerifier)(JNIEnv*,jobject,jlong value) {
    freeDecoder(handle<Decoder>(value));
}
