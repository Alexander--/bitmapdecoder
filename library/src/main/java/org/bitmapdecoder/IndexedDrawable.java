/*
 * Copyright 2023 Alexander Rvachev.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bitmapdecoder;

import android.content.res.*;
import android.graphics.*;
import android.graphics.Bitmap.Config;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.*;
import androidx.annotation.*;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.nio.ByteBuffer;

import static android.util.TypedValue.TYPE_ATTRIBUTE;
import static android.util.TypedValue.TYPE_NULL;

/**
 * A specialized Drawable, that stores 8-bit PNG images much more efficiently than BitmapDrawable.
 *
 * <p>For each 8-bit PNG image with indexed color create an XML file like this in your {@code res/drawable/} directory:
 *
 * <pre>
 *
 * &lt;drawable
 *   class="org.bitmapdecoder.IndexedDrawable"
 *   android:src="@drawable/indexed_png_image"
 *   android:tint="?attr/icon_color"
 * /&gt;</pre>
 *
 * <p>"Indexed" stands for indexed color: PNG images with palette (color type 0 in PNG specification) internally
 * store each pixel as 1 byte, referring to index in array with 256 colors. Android builtin PNG decoders discard the
 * palette and convert such images to true color (ARGB_8888) Bitmap, using 4 times as much memory for each pixel.
 *
 * <p>IndexedDrawable uses {@link PaletteShader} and 2 Bitmaps: ARGB palette and ALPHA_8 pixel Bitmap to keep original
 * data from PNG format, for total of 1 byte per pixel + 1024 bytes of fixed overhead for palette. This means,
 * that IndexedDrawable takes roughly 1/2 of memory used by identical BitmapDrawable with {@code HARDWARE} Config
 * and 1/4 of memory when compared to {@code ARGB_8888} BitmapDrawable.
 *
 * <p>IndexedDrawable supports tiling, tinting, density scaling and configuration-aware caching.
 *
 * <p>Unlike BitmapDrawable, this class has hardcoded gravity behavior: it always positions itself at the top left
 * of container and, depending on tiling setting, either repeats/mirrors in both directions, or scales up to fill all
 * available space without changing ratio of sides.
 *
 * <p>This class does not support mipmaps and always performs nearest neighbor filtering (may cause pixelation!)
 * regardless of Paint settings.
 *
 * <p>Note, that despite it's name this class is able to handle non-indexed and non-PNG images just fine. However, it
 * will fall back to using {@link BitmapFactory} and {@link BitmapShader} for such images, resulting in no memory gains
 * compared to BitmapDrawable. Only 8-bit colormap (color type 0) and 8/16-bit greyscale (color type 4) PNGs will be
 * decoded to the more efficient representation.
 *
 * @see <a href="http://www.libpng.org/pub/png/spec/1.2/PNG-Chunks.html">PNG specification</a>
 */
public class IndexedDrawable extends ShaderDrawable {
    private static final State EMPTY_STATE = new State(new Paint(), -1, -1, false);

    private static final String TAG = "pngs";

    private static final int MASK_USAGE_THRESHOLD = 48 * 48;

    private static final int DISABLE_TILING = 0xffffffff;

    public IndexedDrawable(@NonNull Resources resources, @DrawableRes int resourceId) {
        this();

        try {
            decode(resources, PngSupport.loadValue(resources, resourceId));
        } catch (IOException ioe) {
            throw new IllegalArgumentException(ioe);
        }
    }

    public IndexedDrawable(@NonNull ByteBuffer buffer) {
        this();

        if (!decode(buffer)) {
            throw new IllegalArgumentException(PngSupport.ERROR_CODE_DECODING_FAILED);
        }
    }

    public IndexedDrawable() {
        super(EMPTY_STATE);
    }

    protected IndexedDrawable(State state) {
        super(state);
    }

    @Override
    public boolean canApplyTheme() {
        return state.canApplyTheme();
    }

    @Override
    public void applyTheme(@NonNull Resources.Theme theme) {
        TypedValue tv = new TypedValue();
        if (theme.resolveAttribute(getTintResId(), tv, true)) {
            ColorStateList tintList = PngSupport.toColor(theme, tv);
            if (tintList != null) {
                int newConfiguration = state.getChangingConfigurations() | tv.changingConfigurations;

                state = new IndexedDrawableState(state, tintList, tv.resourceId, newConfiguration,
                        state.getScale(), state.isTiled());

                applyTint(StateSet.NOTHING, getState(), tintList, true);
            }
        }

        super.applyTheme(theme);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet set, Resources.Theme theme) throws IOException, XmlPullParserException {
        super.inflate(r, parser, set, theme);

        final TypedArray typedArray;
        if (theme == null) {
            typedArray = r.obtainAttributes(set, R.styleable.IndexedDrawable);
        } else {
            typedArray = theme.obtainStyledAttributes(set, R.styleable.IndexedDrawable, 0, 0);
        }

        try {
            final boolean tiled;
            final int tileMode;

            final int tileModeAttr = typedArray.getInt(R.styleable.IndexedDrawable_android_tileMode, DISABLE_TILING);
            if (tileModeAttr == DISABLE_TILING) {
                tileMode = 0;
                tiled = false;
            } else {
                tileMode = tileModeAttr;
                tiled = true;
            }

            final int resourceId = typedArray.getResourceId(R.styleable.IndexedDrawable_android_src, 0);
            if (resourceId != 0) {
                final DisplayMetrics displayMetrics = r.getDisplayMetrics();
                final TypedValue tv = new TypedValue();
                r.getValueForDensity(resourceId, displayMetrics.densityDpi, tv, true);

                final boolean decoded = PngSupport.isPreview(typedArray)
                        ? decodePreview(r, tv, tileMode)
                        : decode(r, tv, tileMode);

                if (decoded) {
                    float scale = applyDensity(r.getDisplayMetrics(), tv.density);

                    int tint;
                    int resType;

                    if (typedArray.getValue(R.styleable.IndexedDrawable_android_tint, tv)) {
                        tint = tv.data;
                        resType = tv.type;
                    } else {
                        tint = 0;
                        resType = 0;
                    }

                    ColorStateList tintList;

                    switch (resType) {
                        case TYPE_ATTRIBUTE:
                        case TYPE_NULL:
                            tintList = null;
                            break;
                        default:
                            tintList = PngSupport.toColor(r, theme, tv);
                    }

                    int attributeConfigurations = typedArray.getChangingConfigurations();

                    if (tintList != null || resType == TYPE_ATTRIBUTE || scale != 1.0 || attributeConfigurations != 0 || tiled) {
                        state = new IndexedDrawableState(state, tintList, tint, attributeConfigurations, scale, tiled);

                        if (tintList != null) {
                            applyTint(StateSet.NOTHING, getState(), tintList, true);
                        }
                    }

                    return;
                }
            }

            throw new XmlPullParserException(PngSupport.ERROR_CODE_DECODING_FAILED);
        } finally {
            typedArray.recycle();
        }
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        this.state = new IndexedDrawableState(state, tint, 0, state.getChangingConfigurations(),
                state.getScale(), state.isTiled());

        applyTint(getState(), getState(), tint, true);
    }

    @Override
    public boolean isStateful() {
        final ColorStateList tint = getTint();

        return tint != null && tint.isStateful();
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | state.getChangingConfigurations();
    }

    @Override
    public boolean setState(int[] stateSet) {
        final int[] oldState = getState();

        boolean changed = super.setState(stateSet);

        final ColorStateList stateList = getTint();
        if (stateList != null) {
            changed = applyTint(oldState, stateSet, stateList, false);
        }

        return changed;
    }

    private boolean applyTint(int[] oldState, int[] currentState, ColorStateList stateList, boolean force) {
        if (stateList == null) {
            setColorFilter(null);
            return true;
        }

        final int defaultColor = stateList.getDefaultColor();
        final int currentColor = stateList.getColorForState(oldState, defaultColor);
        final int newColor = stateList.getColorForState(currentState, defaultColor);

        if (force || newColor != currentColor) {
            setColorFilter(new PorterDuffColorFilter(newColor, PorterDuff.Mode.SRC_IN));
            return true;
        }

        return false;
    }

    private float applyDensity(DisplayMetrics metrics, int sDensity) {
        int tDensity = metrics.densityDpi;

        if (sDensity == 0) {
            sDensity = DisplayMetrics.DENSITY_MEDIUM;
        }

        if (tDensity == sDensity) return 1.0f;

        if (sDensity == TypedValue.DENSITY_NONE || tDensity == TypedValue.DENSITY_NONE) {
            return 1.0f;
        }

        int w = state.width;
        int h = state.height;

        state = new State(state.paint, scale(w, sDensity, tDensity), scale(h, sDensity, tDensity), state.opaque);

        return state.width / (float) w;
    }

    private static int scale(int size, int sourceDensity, int targetDensity) {
        return ((size * targetDensity) + (sourceDensity >> 1)) / sourceDensity;
    }

    private ColorStateList getTint() {
        if (!(state instanceof IndexedDrawableState)) {
            return null;
        }

        return ((IndexedDrawableState) state).tint;
    }

    private int getTintResId() {
        if (!(state instanceof IndexedDrawableState)) {
            return 0;
        }

        return ((IndexedDrawableState) state).tintResId;
    }

    private void decode(Resources r, TypedValue tv) throws IOException {
        if (!decode(r, tv, 0)) {
            throw new IOException(PngSupport.ERROR_CODE_DECODING_FAILED);
        }

        float scale = applyDensity(r.getDisplayMetrics(), tv.density);
        if (scale != 1.0) {
            state = new IndexedDrawableState(state, getTint(), getTintResId(), state.getChangingConfigurations(),
                    scale, state.isTiled());
        }
    }

    private boolean decode(Resources r, TypedValue tv, int tileMode) throws IOException {
        final AssetManager am = r.getAssets();

        try (AssetFileDescriptor stream = am.openNonAssetFd(tv.assetCookie, tv.string.toString())) {
            final ByteBuffer buffer = PngSupport.loadIndexedPng(stream);
            final PngDecoder.PngHeaderInfo headerInfo = PngDecoder.getImageInfo(buffer);
            if (headerInfo != null && headerInfo.isPaletteOrGreyscale() && decode(buffer, headerInfo, tileMode)) {
                return true;
            }
            return decodeFallback(r, tv, tileMode);
        }
    }

    private boolean decode(ByteBuffer buffer) {
        final PngDecoder.PngHeaderInfo headerInfo = PngDecoder.getImageInfo(buffer);
        if (headerInfo == null) {
            return false;
        }
        return decode(buffer, headerInfo, 0);
    }

    private boolean decode(ByteBuffer buffer, PngDecoder.PngHeaderInfo headerInfo, int tileMode) {
        final Bitmap imageBitmap = Bitmap.createBitmap(headerInfo.width, headerInfo.height, Config.ALPHA_8);
        final int decoderFlags = getFlags(headerInfo);
        final PngDecoder.DecodingResult result = PngDecoder.decodeIndexed(buffer, imageBitmap, decoderFlags);
        final Paint paint = result == null ? null : PngSupport.createPaint(result, imageBitmap, decoderFlags | tileMode);
        if (paint != null) {
            state = new State(paint, headerInfo.width, headerInfo.height, result.isOpaque());
            return true;
        }
        // fall back to ARGB_8888 Bitmap
        imageBitmap.recycle();
        return false;
    }

    private static int getFlags(PngDecoder.PngHeaderInfo image) {
        if (image.width * image.height <= MASK_USAGE_THRESHOLD) {
            return PngDecoder.OPTION_DECODE_AS_MASK;
        } else {
            return PngDecoder.DEFAULT_DECODER_FLAGS;
        }
    }

    private static Shader.TileMode toTileMode(int tileMode) {
        return Shader.TileMode.values()[tileMode];
    }

    // Android Studio Layout Preview can decode Bitmaps only by absolute file paths
    private boolean decodePreview(Resources r, TypedValue tv, int tileMode) {
        final BitmapDrawable bitmapDrawable = new BitmapDrawable(r, tv.string.toString());
        final Bitmap bitmap = bitmapDrawable.getBitmap();
        if (bitmap == null) {
            return false;
        }
        setShaderFallback(bitmap, tileMode);
        return true;
    }

    private boolean decodeFallback(Resources r, TypedValue tv, int tileMode) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        final Bitmap bitmap = BitmapFactory.decodeResource(r, tv.resourceId, options);
        if (bitmap == null) {
            return false;
        }
        setShaderFallback(bitmap, tileMode);
        return true;
    }

    private void setShaderFallback(Bitmap bitmap, int tileMode) {
        final Paint fallback = new Paint();
        final Shader.TileMode tiled = toTileMode(tileMode);
        fallback.setShader(new BitmapShader(bitmap, tiled, tiled));
        state = new State(fallback, bitmap.getWidth(), bitmap.getHeight(), !bitmap.hasAlpha());
    }

    private static class IndexedDrawableState extends State {
        protected final ColorStateList tint;
        protected final int tintResId;

        private final int configurations;
        private final float scale;
        private final boolean tiled;

        private IndexedDrawableState(@NonNull Paint paint,
                                     @NonNull State state,
                                     ColorStateList tint,
                                     int tintResId,
                                     int configurations,
                                     float scale,
                                     boolean tiled) {
            super(paint, state.width, state.height, state.opaque);

            this.tint = tint;
            this.tintResId = tintResId;
            this.scale = scale;
            this.tiled = tiled;
            this.configurations = configurations | getConfigurations(tint);
        }

        private IndexedDrawableState(@NonNull State state,
                                     ColorStateList tint,
                                     int tintResId,
                                     int configurations,
                                     float scale,
                                     boolean tiled) {
            this(state.paint, state, tint, tintResId, configurations, scale, tiled);
        }

        @NonNull
        @Override
        public IndexedDrawable newDrawable() {
            return new IndexedDrawable(this);
        }

        @Override
        public boolean canApplyTheme() {
            return tintResId != 0 && tint == null;
        }

        @Override
        protected State copy() {
            return new IndexedDrawableState(new Paint(paint), this, tint, tintResId, configurations, scale, tiled);
        }

        @Override
        public int getChangingConfigurations() {
            return configurations;
        }

        @Override
        protected boolean isTiled() {
            return tiled;
        }

        @Override
        protected float getScale() {
            return scale;
        }

        private static int getConfigurations(ColorStateList colorStateList) {
            if (Build.VERSION.SDK_INT >= 23 && colorStateList != null) {
                return colorStateList.getChangingConfigurations();
            }

            return 0;
        }
    }
}