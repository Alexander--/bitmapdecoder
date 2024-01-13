// Copyright 2023 Alexander Rvachev
// Licensed under Apache License, Version 2.0
// Refer to the LICENSE file included.

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
#define WUFFS_CONFIG__MODULE__BASE__CORE
#define WUFFS_CONFIG__MODULE__BASE__PIXCONV
#define WUFFS_CONFIG__MODULE__CRC32
#define WUFFS_CONFIG__MODULE__DEFLATE
#define WUFFS_CONFIG__MODULE__PNG
#define WUFFS_CONFIG__MODULE__ZLIB

#define WUFFS_IMPLEMENTATION
#define WUFFS_CONFIG__STATIC_FUNCTIONS

#include "wuffs-unsupported-snapshot.c"

#define LOG_TAG "pngs"

#define LOG(str, ...) (__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, str, __VA_ARGS__))

#define OPTION_DECODE_AS_MASK 0x4
#define OPTION_EXTRACT_MASK 0x8

#define FLAG_U8_MASK 0x2
#define FLAG_GREY 0x4
#define FLAG_OPAQUE 0x8

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

static int copyPalette(const uint8_t *restrict dest, const uint8_t *restrict src, size_t size) {
    int is_opaque = 1;

    for (int i = 0; i < size; i += 4) {
        const uint32_t* srcColor = (uint32_t*) (src + i);
        uint32_t* dstColor = (uint32_t*) (dest + i);

        const uint32_t abgr = *srcColor;
        const uint32_t alpha = 0xFF & (abgr >> 24);
        if (alpha != 0xFF) is_opaque = 0;

        *dstColor = abgr_nonpremul_to_argb_premul(abgr);
    }

    return is_opaque;
}

static void extract_mask(uint8_t *restrict dest, uint8_t *restrict src, uint32_t *restrict palette, size_t size) {
    for (int i = 0; i < size; i++) {
        uint8_t value = src[i];
        uint32_t color = palette[value];
        uint8_t alpha = (uint8_t) ((color >> 24) & 0xFF);
        dest[i] = alpha;
    }
}

static int convert_to_mask(uint8_t *restrict dest, uint8_t *restrict src, uint32_t *restrict palette, size_t size) {
    uint32_t hue = 0;

    for (int i = 0; i < size; i++) {
        uint8_t value = src[i];
        uint32_t color = palette[value];
        uint8_t alpha = (uint8_t) ((color >> 24) & 0xFF);
        dest[i] = alpha;

        if (alpha != 0) {
            if (hue != 0 && (hue & 0x00FFFFFF) != (color & 0x00FFFFFF)) return 0;
            hue = color;
        }
    }

    return 1;
}

JNIEXPORT jint JNICALL Java_org_bitmapdecoder_PngDecoder_decode(
        JNIEnv* env,
        jclass type,
        jobject buffer,
        jobject out_image,
        jbyteArray out_palette,
        jint position,
        jint limit,
        jint options
) {
    uint8_t* mapped = (*env)->GetDirectBufferAddress(env, buffer);
    if (mapped == NULL) {
        return 0;
    }

    mapped += position;

    const jlong size = limit - position;

    wuffs_png__decoder decoder;
    wuffs_base__status i_status = wuffs_png__decoder__initialize(&decoder, sizeof decoder, WUFFS_VERSION, 0);
    if (!wuffs_base__status__is_ok(&i_status)) {
        LOG("%s\n", wuffs_base__status__message(&i_status));
        return 0;
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
        LOG("%s\n", wuffs_base__status__message(&dic_status));
        return 0;
    }

    if (!wuffs_base__image_config__is_valid(&imageconfig)) {
        LOG("%s\n", "Invalid configuration");
        return 0;
    }

    const uint32_t img_width = wuffs_base__pixel_config__width(&imageconfig.pixcfg);
    const uint32_t img_height = wuffs_base__pixel_config__height(&imageconfig.pixcfg);

    AndroidBitmapInfo bitmap_info;
    bitmap_info.width = 0;
    bitmap_info.height = 0;

    AndroidBitmap_getInfo(env, out_image, &bitmap_info);

    if (img_width * img_height > bitmap_info.width * bitmap_info.height) {
        LOG("Bitmap is %d x %d, needed %d x %d\n", bitmap_info.width, bitmap_info.height, img_width, img_height);
        return 0;
    }

    wuffs_base__slice_u8 workbuff, dstbuff;

    void* separate_buffer = NULL;

    void* dst_buffer = NULL;
    uint64_t dst_len;

    wuffs_base__pixel_format source_format = wuffs_base__pixel_config__pixel_format(&imageconfig.pixcfg);
    if (source_format.repr == WUFFS_BASE__PIXEL_FORMAT__Y ||
        source_format.repr == WUFFS_BASE__PIXEL_FORMAT__Y_16LE ||
        source_format.repr == WUFFS_BASE__PIXEL_FORMAT__Y_16BE) {

        wuffs_base__pixel_config__set(
            &imageconfig.pixcfg, WUFFS_BASE__PIXEL_FORMAT__Y,
            WUFFS_BASE__PIXEL_SUBSAMPLING__NONE, img_width, img_height);

        dst_len = img_width * img_height;

        AndroidBitmap_lockPixels(env, out_image, &dst_buffer);
    } else if (out_palette == NULL) {
        return 0;
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
        LOG("%s\n", wuffs_base__status__message(&newbuffer_status));
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
        LOG("Decoding failed: %s\n", wuffs_base__status__message(&framestatus));
        return 0;
    }

    ATrace_endSection();

    free(workbuff.ptr);

    jint result = 1;

    uint32_t single = 0;
    int is_opaque = 0;

    if (wuffs_base__pixel_config__pixel_format(&imageconfig.pixcfg).repr == WUFFS_BASE__PIXEL_FORMAT__Y) {
        is_opaque = 1;
        result |= FLAG_GREY;
        result |= FLAG_OPAQUE;
    } else if (out_palette != NULL) {
        const uint8_t* palette = wuffs_base__pixel_buffer__palette(&pb).ptr;

        void *palette_mem = (*env)->GetPrimitiveArrayCritical(env, out_palette, NULL);

        is_opaque = copyPalette(palette_mem, palette, 256 * 4);

        (*env)->ReleasePrimitiveArrayCritical(env, out_palette, palette_mem, 0);

        if (is_opaque) {
            result |= FLAG_OPAQUE;
        }
    }

    if (separate_buffer == NULL) {
        // we have decoded the image directly into Bitmap, unlock it
        AndroidBitmap_unlockPixels(env, out_image);
    } else {
        // copy the image to output Bitmap and release the storage
        uint8_t* fullimage = wuffs_base__pixel_buffer__plane(&pb, 0).ptr;

        void *buffer_mem;
        AndroidBitmap_lockPixels(env, out_image, &buffer_mem);

        uint32_t* palette = (uint32_t*) wuffs_base__pixel_buffer__palette(&pb).ptr;

        // if we have an image where all visible palette entries have the same color
        // (different only be alpha value); this allows us to convert it to alpha mask!
        // furthermore, if we know that the image is to be tinted, we can convert to mask
        // regardless of palette!
        if ((options & OPTION_EXTRACT_MASK) != 0) {
            LOG("%s\n", "Forced mask conversion!!");
            extract_mask(buffer_mem, fullimage, palette, img_width * img_height);
            result |= FLAG_U8_MASK;
        } else if (!is_opaque && (options & OPTION_DECODE_AS_MASK) != 0 && convert_to_mask(buffer_mem, fullimage, palette, img_width * img_height)) {
            result |= FLAG_U8_MASK;
        } else {
            memcpy(buffer_mem, fullimage, img_width * img_height);
        }

        AndroidBitmap_unlockPixels(env, out_image);
        free(dst_buffer);
    }

    return result;
}
