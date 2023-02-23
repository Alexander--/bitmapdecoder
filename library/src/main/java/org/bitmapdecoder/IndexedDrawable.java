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
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.StateSet;
import android.util.TypedValue;
import androidx.annotation.*;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A subclass of {@link ShaderDrawable}, that can be used in XML
 */
public class IndexedDrawable extends ShaderDrawable {
    private static final State EMPTY_STATE = new State(new Paint(), -1, -1, false);

    private static final String TAG = "pngs";

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
        if (!(state instanceof IndexedDrawableState)) {
            return false;
        }
        IndexedDrawableState state = (IndexedDrawableState) this.state;
        return state.tintResId != 0 && state.tint == null;
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

            TypedValue tv = typedArray.peekValue(R.styleable.IndexedDrawable_android_src);
            if (tv != null) {
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
                        case TypedValue.TYPE_ATTRIBUTE:
                        case TypedValue.TYPE_NULL:
                            tintList = null;
                            break;
                        default:
                            tintList = typedArray.getColorStateList(R.styleable.IndexedDrawable_android_tint);
                    }

                    int attributeConfigurations = typedArray.getChangingConfigurations();

                    if (tint != 0 || resType == TypedValue.TYPE_ATTRIBUTE || attributeConfigurations != 0 || tiled) {
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
        final int decoderFlags = PngDecoder.DEFAULT_DECODER_FLAGS;
        final PngDecoder.DecodingResult result = PngDecoder.decodeIndexed(buffer, imageBitmap, decoderFlags);
        final Paint paint = result == null ? null : PngSupport.createPaint(result, imageBitmap, tileMode);
        if (paint != null) {
            state = new State(paint, headerInfo.width, headerInfo.height, result.isOpaque());
            return true;
        }
        // fall back to ARGB_8888 Bitmap
        imageBitmap.recycle();
        return false;
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
        final Bitmap bitmap = BitmapFactory.decodeResource(r, tv.resourceId);
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

        @NonNull
        @Override
        public Drawable newDrawable(@Nullable Resources res, @Nullable Resources.Theme theme) {
            ColorStateList tint = this.tint;
            if (Build.VERSION.SDK_INT >= 23 && res != null && theme != null && tintResId != 0) {
                tint = res.getColorStateList(tintResId, theme);
            }
            if (tint == this.tint) {
                return new IndexedDrawable(this);
            }
            State s = new IndexedDrawableState(this, tint, tintResId, configurations, scale, tiled);
            return new IndexedDrawable(s);
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