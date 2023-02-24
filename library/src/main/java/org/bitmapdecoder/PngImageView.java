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

import android.content.Context;
import android.content.res.*;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * An implementation of ImageView, that attempts to decode its {@code app:src} attribute as indexed ShaderDrawable
 * with fallback to usual routine if that's not feasible.
 *
 * <p>
 * This class will not try to use indexed images under following circumstances:
 *
 * <ul>
 * <li>for SDK versions below 33</li>
 * <li>when attached to window without hardware acceleration</li>
 * <li>for 9-path drawables</li>
 * </ul>
 */
public class PngImageView extends ImageView {
    private static final String TAG = "pngs";

    private int pendingResId;
    private boolean swCanvasWarned;

    public PngImageView(@NonNull Context context) {
        super(context);
    }

    public PngImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PngImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        TypedArray ta = context.obtainStyledAttributes(attrs, new int[] { R.attr.src });
        try {
            int srcResId = ta.getResourceId(0, 0);
            if (srcResId == 0 && getDrawable() != null) {
                throw new IllegalStateException(PngSupport.ERROR_CODE_BAD_ATTRIBUTE);
            }

            setImageResource(srcResId);
        } finally {
            ta.recycle();
        }
    }

    @Override
    public void setImageResource(int resId) {
        if (isAttachedToWindow()) {
            resolveResource(resId);
        } else {
            this.pendingResId = resId;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        final int pendingResId = this.pendingResId;
        if (pendingResId != 0) {
            this.pendingResId = 0;
            resolveResource(pendingResId);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!swCanvasWarned && !canvas.isHardwareAccelerated()) {
            Log.d(TAG, PngSupport.ERROR_SOFTWARE_CANVAS);
            swCanvasWarned = true;
        }

        super.onDraw(canvas);
    }

    protected void resolveResource(int resId) {
        if (isInEditMode() || !isHardwareAccelerated()) {
            super.setImageResource(resId);
            return;
        }

        final Resources resources = getResources();
        if (resources == null) {
            return;
        }

        final TypedValue value = new TypedValue();
        resources.getValue(resId, value, true);
        if (value.string == null) {
            super.setImageResource(resId);
            return;
        }

        final String imageFile = value.string.toString();
        if (!isSupported(imageFile)) {
            super.setImageResource(resId);
            return;
        }

        final AssetManager am = resources.getAssets();

        try (AssetFileDescriptor stream = am.openNonAssetFd(value.assetCookie, imageFile)) {
            final ByteBuffer buffer = PngSupport.loadIndexedPng(stream);
            final Drawable decoded = PngSupport.getDrawable(buffer, 0);
            if (decoded != null) {
                super.setImageDrawable(decoded);
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, PngSupport.ERROR_CODE_DECODING_FAILED, e);
        }

        super.setImageResource(resId);
    }

    private static boolean isSupported(String filename) {
        return !filename.endsWith(".9.png");
    }

}
