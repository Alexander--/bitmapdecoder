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

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PaintDrawable;
import android.util.Log;
import android.util.StateSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import static org.bitmapdecoder.PngSupport.makeStateSpec;

/**
 * A rudimentary Drawable, that implements bare minimum of features to render a {@link  PaletteShader}.
 * <br/>
 * Supports single-color tinting and most of the basic Drawable optimizations (getOpacity, getOutline).
 * <br/>
 * You can accomplish similar results with {@link PaintDrawable} and {@link Paint#setShader}.
 */
public class ShaderDrawable extends Drawable {
    private static final String TAG = "pngs";

    static final int OPAQUE_MASK = 0xF0000000;

    protected State state;
    private boolean mutated;

    public ShaderDrawable(@NonNull Shader shader, int width, int height, boolean opaque) {
        this(new Paint(), width, height, opaque);

        state.paint.setShader(shader);
    }

    public ShaderDrawable(@NonNull Paint paint, int width, int height, boolean opaque) {
        this(new State(paint, width, height, opaque));
    }

    protected ShaderDrawable(Paint paint, int width, int height, PngDecoder.DecodingResult result) {
        this(new State(paint, width, height, result));
    }

    protected ShaderDrawable(@NonNull State state) {
        this.state = state;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!canvas.isHardwareAccelerated()) {
            return;
        }

        Rect bounds = getBounds();

        float dstWidth = bounds.width();
        float dstHeight = bounds.height();
        float srcWidth = getIntrinsicWidth();
        float srcHeight = getIntrinsicHeight();
        float imageScale = state.isTiled() ? 1.0f : Math.max(dstWidth / srcWidth, dstHeight / srcHeight);
        float finalScale = imageScale * state.getScale();

        canvas.save();
        canvas.clipRect(bounds);
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(finalScale, finalScale);
        canvas.drawPaint(state.paint);
        canvas.restore();
    }

    @NonNull
    @Override
    public ConstantState getConstantState() {
        return state;
    }

    @Override
    public int getIntrinsicWidth() {
        return state.width;
    }

    @Override
    public int getIntrinsicHeight() {
        return state.height;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        super.getOutline(outline);

        if (state.isOpaque()) {
            outline.setAlpha(1.0f);
        }
    }

    @Override
    public int getOpacity() {
        return state.isOpaque() ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }

    @Override
    public ColorFilter getColorFilter() {
        return state.paint.getColorFilter();
    }

    @Override
    public int getAlpha() {
        return state.paint.getAlpha();
    }

    @Override
    public void setAlpha(int alpha) {
        if (alpha == state.paint.getAlpha()) {
            return;
        }
        state.paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (Objects.equals(colorFilter, state.paint.getColorFilter())) {
            return;
        }
        state.paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        if (tint == null) {
            clearTint();
            return;
        }

        if (tint.isStateful()) {
            Log.e(TAG, PngSupport.ERROR_CODE_STATEFUL_TINT_LIST);
            return;
        }

        final int color = tint.getColorForState(StateSet.WILD_CARD, 0);
        if (color == 0) {
            return;
        }

        setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    protected void clearTint() {
        setColorFilter(null);
    }

    @NonNull
    @Override
    public Drawable mutate() {
        if (!mutated) {
            this.state = state.copy();
            mutated = true;
        }

        return this;
    }

    public void clearMutated() {
        mutated = false;
    }

    protected static class State extends Drawable.ConstantState {
        protected final Paint paint;
        protected final int width, height, flags;

        protected State(@NonNull Paint paint, int width, int height, int flags) {
            this.paint = paint;
            this.width = width;
            this.height = height;
            this.flags = flags;

            paint.setFilterBitmap(false);
            paint.setDither(false);
        }

        protected State(@NonNull Paint paint, int width, int height, boolean opaque) {
            this(paint, width, height, makeStateSpec(opaque));
        }

        protected State(@NonNull Paint paint, int width, int height, PngDecoder.DecodingResult result) {
            this(paint, width, height, result.isOpaque());
        }

        boolean isOpaque() {
            return (flags & OPAQUE_MASK) != 0 && paint.getAlpha() == 255;
        }

        @NonNull
        @Override
        public Drawable newDrawable() {
            return new ShaderDrawable(this);
        }

        protected State copy() {
            return new State(new Paint(paint), width, height, flags);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }

        protected boolean isTiled() {
            return false;
        }

        protected float getScale() {
            return 1.0f;
        }
    }
}
