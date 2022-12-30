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

/**
 * A rudimentary Drawable, that implements bare minimum of features to render a {@link  PaletteShader}.
 * <br/>
 * Supports single-color tinting and most of the basic Drawable optimizations (getOpacity, getOutline).
 * <br/>
 * You can accomplish similar results with {@link PaintDrawable} and {@link Paint#setShader}.
 */
public class ShaderDrawable extends Drawable {
    private static final String TAG = "pngs";

    protected State state;
    private boolean mutated;

    public ShaderDrawable(@NonNull Shader shader, int width, int height, boolean opaque) {
        this(new Paint(), width, height, opaque);

        state.paint.setShader(shader);
    }

    public ShaderDrawable(@NonNull Paint paint, int width, int height, boolean opaque) {
        this(new State(paint, width, height, opaque));
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
        if (dstWidth == srcWidth && dstHeight == srcHeight) {
            canvas.drawRect(bounds, state.paint);
        } else {
            canvas.save();
            canvas.scale(dstWidth / srcWidth, dstHeight / srcHeight);
            canvas.drawRect(bounds, state.paint);
            canvas.restore();
        }
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

        if (state.opaque) {
            outline.setAlpha(getAlpha() / 255.0f);
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
            setColorFilter(null);
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

    @NonNull
    @Override
    public Drawable mutate() {
        if (!mutated) {
            this.state = state.copy();
            mutated = true;
        }

        return this;
    }

    protected static class State extends Drawable.ConstantState {
        protected final Paint paint;
        protected final int width, height;
        protected final boolean opaque;

        protected State(@NonNull Paint paint, int width, int height, boolean opaque) {
            this.paint = paint;
            this.width = width;
            this.height = height;
            this.opaque = opaque;

            paint.setFilterBitmap(false);
            paint.setDither(false);
        }

        State copy() {
            return new State(new Paint(paint), width, height, opaque);
        }

        boolean isOpaque() {
            return opaque && paint.getAlpha() == 255;
        }

        @NonNull
        @Override
        public Drawable newDrawable() {
            return new ShaderDrawable(this);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }
}
