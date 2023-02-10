package org.bitmapdecoder;

import android.graphics.Bitmap;
import android.os.Trace;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An interface for accessing decoder, internally used by the library
 * (currently implemented as small JNI wrapper, based on Wuffs Project).
 * <p/>
 * This class is made public in spirit of providing useful access to library internals,
 * but it is probably not what most people will want to use.
 *
 * @see PngSupport
 */
public final class PngDecoder {
    private PngDecoder() {}

    private static final AtomicBoolean loaded = new AtomicBoolean();
    static {
        load();
    }

    public static final int OPTION_DECODE_AS_MASK = 0b0001;

    public static final int FLAG_IS_INDEXED    = 0b00100;
    public static final int FLAG_IS_GREYSCALE  = 0b01000;
    public static final int FLAGS_8BIT = FLAG_IS_INDEXED | FLAG_IS_GREYSCALE;

    private static final int SUCCESS_MASK           = 0b0001;
    private static final int FLAG_CONVERTED_TO_MASK = 0b0010;
    private static final int FLAG_CONVERTED_TO_GREY = 0b0100;
    private static final int FLAG_OPAQUE            = 0b1000;

    private static final long PNG_SIGNATURE_LONG = -8552249625308161526L;
    private static final int PNG_COLOR_GREYSCALE = 0;
    private static final int PNG_COLOR_INDEXED = 3;

    /**
     * Load native libraries, required by decoder.
     * <p>
     * This is normally done automatically, but you can use this method to manually trigger loading of
     * libraries for benchmarking, to avoid any potential delays on the main thread etc.
     */
    public static void load() {
        if (loaded.compareAndSet(false, true)) {
            System.loadLibrary("pngs");
        }
    }

    /**
     * @param image buffer with image data
     *
     * @return some information about image or {@code null} if supplied buffer does not contain PNG image
     */
    public static @Nullable PngHeaderInfo getImageInfo(@NonNull ByteBuffer image) {
        Trace.beginSection("peek");
        try {
            image.mark();

            final long signature = image.getLong();
            if (signature != PNG_SIGNATURE_LONG) {
                return null;
            }

            image.getLong();

            final int width = image.getInt();
            final int height = image.getInt();
            final int colorType = (image.getInt() & 0x00ff0000) >>> 16;

            final int flags;

            switch (colorType) {
                case PNG_COLOR_INDEXED:
                    flags = FLAG_IS_INDEXED;
                    break;
                case PNG_COLOR_GREYSCALE:
                    flags = FLAG_IS_GREYSCALE;
                    break;
                default:
                    flags = 0;
            }

            return new PngHeaderInfo(width, height, flags);
        } finally {
            image.reset();

            Trace.endSection();
        }
    }

    /**
     * @param image buffer with image data
     * @param output Bitmap object that will be populated with decoded image contents
     *
     * @return {@link DecodingResult}, describing outcome of decoding or null in case of failure
     */
    public static @Nullable DecodingResult decodeIndexed(@NonNull ByteBuffer image, @NonNull Bitmap output, int options) {
        if (output.getConfig() != Bitmap.Config.ALPHA_8) {
            throw new IllegalArgumentException();
        }

        final byte[] palette = new byte[256 * 4];

        final int returnCode;

        Trace.beginSection("decode");
        try {
            returnCode = decode(image, output, palette, options);
            if ((returnCode & SUCCESS_MASK) == 0) {
                return null;
            }
        } finally {
            Trace.endSection();
        }


        output.setPremultiplied(false);

        final ByteBuffer wrapped = ByteBuffer.wrap(palette);
        final int count = getPaletteSize(wrapped);
        wrapped.limit(count * 4);

        return new DecodingResult(output, wrapped, returnCode);
    }

    public static final class DecodingResult {
        public final Bitmap bitmap;
        public final ByteBuffer palette;
        private final int flags;

        DecodingResult(Bitmap bitmap, ByteBuffer palette, int flags) {
            this.bitmap = bitmap;
            this.palette = palette;
            this.flags = flags;
        }

        public boolean decodedAsMask() {
            return (flags & FLAG_CONVERTED_TO_MASK) != 0;
        }

        public boolean decodedAsGreyscale() {
            return (flags & FLAG_CONVERTED_TO_GREY) != 0;
        }

        public boolean isOpaque() {
            return (flags & FLAG_OPAQUE) != 0;
        }
    }

    public static final class PngHeaderInfo {
        public final int width, height, flags;

        public PngHeaderInfo(int width, int height, int flags) {
            this.width = width;
            this.height = height;
            this.flags = flags;
        }

        public boolean isIndexed() {
            return (flags & PngDecoder.FLAG_IS_INDEXED) != 0;
        }

        public boolean isGreyscale() {
            return (flags & PngDecoder.FLAG_IS_GREYSCALE) != 0;
        }

        boolean isPaletteOrGreyscale() {
            return (flags & PngDecoder.FLAGS_8BIT) != 0;
        }
    }

    private static int getPaletteSize(ByteBuffer palette) {
        final IntBuffer ib = palette.asIntBuffer();

        if (ib.get(255) != 0) {
            return 256;
        }

        boolean hadZero = false;
        int i;
        for (i = 255; i > 0; --i) {
            if (ib.get(i) == 0) {
                if (hadZero) break;
                hadZero = true;
            }
        }
        return i;
    }

    private static native int decode(ByteBuffer buffer, Bitmap imageBitmap, byte[] palette, int options);
}
