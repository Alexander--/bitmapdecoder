#define __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__ 1

#include <stdio.h>
#include <stdint.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <android/hardware_buffer_jni.h>
#include <android/bitmap.h>
#include <android/trace.h>
#include "jni.h"

#define WUFFS_CONFIG__MODULES
#define WUFFS_CONFIG__MODULE__ADLER32
#define WUFFS_CONFIG__MODULE__AUX__BASE
#define WUFFS_CONFIG__MODULE__AUX__IMAGE
#define WUFFS_CONFIG__MODULE__BASE
#define WUFFS_CONFIG__MODULE__CRC32
#define WUFFS_CONFIG__MODULE__DEFLATE
#define WUFFS_CONFIG__MODULE__GIF
#define WUFFS_CONFIG__MODULE__LZW
#define WUFFS_CONFIG__MODULE__PNG
#define WUFFS_CONFIG__MODULE__ZLIB

#define WUFFS_IMPLEMENTATION
#define WUFFS_CONFIG__STATIC_FUNCTIONS

#include "wuffs-unsupported-snapshot.c"

#define LOG_TAG "pngs"

#define LOG(str, ...) (__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, str, __VA_ARGS__))

#define FLAG_OPAQUE 0x4

// copied from wuffs internals
static inline uint32_t abgr_nonpremul_to_argb_premul(uint32_t argb_nonpremul) {
  uint32_t a = 0xFF & (argb_nonpremul >> 24);
  uint32_t a16 = a * (0x101 * 0x101);

  uint32_t r = 0xFF & (argb_nonpremul >> 16);
  r = ((r * a16) / 0xFFFF) >> 8;
  uint32_t g = 0xFF & (argb_nonpremul >> 8);
  g = ((g * a16) / 0xFFFF) >> 8;
  uint32_t b = 0xFF & (argb_nonpremul >> 0);
  b = ((b * a16) / 0xFFFF) >> 8;

  return (a << 24) | (b << 16) | (g << 8) | (r << 0);
}

static void copyPalette(const uint8_t *restrict dest, const uint8_t *restrict src, size_t size) {
    for (int i = 0; i < size; i += 4) {
        const uint32_t* srcColor = (uint32_t*) (src + i);
        uint32_t* dstColor = (uint32_t*) (dest + i);
        *dstColor = abgr_nonpremul_to_argb_premul(*srcColor);
    }
}

JNIEXPORT jint JNICALL Java_org_bitmapdecoder_PngDecoder_decode(
        JNIEnv* env,
        jclass type,
        jobject buffer,
        jobject out_image,
        jbyteArray out_palette,
        jint options
) {
    const void* const mapped = (*env)->GetDirectBufferAddress(env, buffer);
    const jlong size = (*env)->GetDirectBufferCapacity(env, buffer);

    wuffs_png__decoder decoder;
    wuffs_base__status i_status = wuffs_png__decoder__initialize(&decoder, sizeof decoder, WUFFS_VERSION, 0);
    if (!wuffs_base__status__is_ok(&i_status)) {
        printf("Failed to initialize PNG decoder: %s\n", wuffs_base__status__message(&i_status));
        return -1;
    }

    wuffs_base__io_buffer src = {
        .data = {
            .ptr = mapped,
            .len = size
        },
        .meta = {
            .wi = size,
            .ri = 0,
            .pos = 0,
            .closed = true
        }
    };

    wuffs_base__image_config imageconfig;
    wuffs_base__status dic_status = wuffs_png__decoder__decode_image_config(&decoder, &imageconfig, &src);
    if (!wuffs_base__status__is_ok(&dic_status)) {
        printf("Failed to get config: %s\n", wuffs_base__status__message(&dic_status));
        return 0;
    }

    if (!wuffs_base__image_config__is_valid(&imageconfig)) {
        printf("Invalid image configuration\n");
        return 0;
    }

    const uint32_t img_width = wuffs_base__pixel_config__width(&imageconfig.pixcfg);
    const uint32_t img_height = wuffs_base__pixel_config__height(&imageconfig.pixcfg);

    wuffs_base__slice_u8 workbuff, dstbuff;

    void* separate_buffer = NULL;

    void* dst_buffer = NULL;
    uint64_t dst_len;

    if (out_palette == NULL) {
        // decode as RGBA
        //LOG("%s\n", "Decoding indexed as RGBA");

        wuffs_base__pixel_config__set(
             &imageconfig.pixcfg, WUFFS_BASE__PIXEL_FORMAT__RGBA_NONPREMUL,
             WUFFS_BASE__PIXEL_SUBSAMPLING__NONE, img_width, img_height);

        dst_len = img_width * img_height * 4;

        goto allocate_buffer; //!!!

        AndroidBitmap_lockPixels(env, out_image, &dst_buffer);
    } else {
        //LOG("%s\n", "Decoding indexed to ALPHA8 with palette");

        // decode as indexed
keep_palette:
        dst_len = img_width * img_height + 256 * 4;
allocate_buffer:
        separate_buffer = calloc(dst_len, 1);
        dst_buffer = separate_buffer;
    }

    if (!dst_buffer) {
        LOG("%s\n", "Could not allocate result buffer");
        return 0;
    }

    dstbuff = wuffs_base__make_slice_u8(dst_buffer, dst_len);

    wuffs_base__pixel_buffer pb;
    wuffs_base__status newbuffer_status = wuffs_base__pixel_buffer__set_from_slice(&pb, &imageconfig.pixcfg, dstbuff);

    if (!wuffs_base__status__is_ok(&newbuffer_status)) {
        LOG("Failed to initialize pixel buffer: %s\n", wuffs_base__status__message(&newbuffer_status));
        return 0;
    }

    uint64_t workbuf_len_max_incl = wuffs_png__decoder__workbuf_len(&decoder).max_incl;
    workbuff = wuffs_base__malloc_slice_u8(malloc, workbuf_len_max_incl);
    if (!workbuff.ptr) {
        LOG("%s\n", "Could not allocate work buffer");
        return 0;
    }

    ATrace_beginSection("decode_frame");

    wuffs_base__status framestatus = wuffs_png__decoder__decode_frame(&decoder, &pb, &src, WUFFS_BASE__PIXEL_BLEND__SRC, workbuff, NULL);
    if (!wuffs_base__status__is_ok(&framestatus)) {
        LOG("Failed to decode 8-bit image: %s\n", wuffs_base__status__message(&framestatus));
        return 0;
    }

    ATrace_endSection();

    free(workbuff.ptr);

    jint result = 1;

    if (out_palette != NULL) {
        const uint8_t* palette = wuffs_base__pixel_buffer__palette(&pb).ptr;

        void *palette_mem = (*env)->GetPrimitiveArrayCritical(env, out_palette, NULL);

        copyPalette(palette_mem, palette, 256 * 4);

        (*env)->ReleasePrimitiveArrayCritical(env, out_palette, palette_mem, 0);
    }

    if (separate_buffer == NULL) {
        // we have decoded the image directly into Bitmap, unlock it
        AndroidBitmap_unlockPixels(env, out_image);
    } else {
        // copy the image to output Bitmap and release the storage
        uint8_t* fullimage = wuffs_base__pixel_buffer__plane(&pb, 0).ptr;

        void *buffer_mem;
        AndroidBitmap_lockPixels(env, out_image, &buffer_mem);
        memcpy(buffer_mem, fullimage, img_width * img_height);
        AndroidBitmap_unlockPixels(env, out_image);

        free(dst_buffer);
    }

    return result;
}
