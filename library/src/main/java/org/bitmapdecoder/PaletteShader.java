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

import android.annotation.TargetApi;
import android.graphics.BitmapShader;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import androidx.annotation.NonNull;

/**
 * Simple shader for rendering images with 8-bit indexed color.
 */
@TargetApi(33)
public class PaletteShader extends RuntimeShader {
    /**
     * Create a shader without initializing it with image data (you must call {@link #setBitmap} to do so).
     */
    public PaletteShader() {
        super("uniform shader t;" +
                "uniform shader p;" +
                "vec4 main(vec2 c){" +
                "return p.eval(vec2(t.eval(c).a*255,0));" +
                "}");
    }

    public PaletteShader(@NonNull Shader palette, @NonNull BitmapShader storageTexture) {
        this();

        setBitmap(palette, storageTexture);
    }

    /**
     * Initialize a shader with image and palette.
     * <p/>
     * This method allows reusing existing {@link PaletteShader} object and the associated
     * {@code SkRuntimeEffect} (such reuse might or might not be beneficial for performance).
     *
     * @param palette single-row colormap, assumed to contain up to 256 pixels
     * @param storageTexture ALPHA_8 allocation (value of each pixel is index of color in palette)
     */
    public void setBitmap(@NonNull Shader palette, @NonNull BitmapShader storageTexture) {
        setInputShader("p", palette);
        setInputBuffer("t", storageTexture);
    }
}
