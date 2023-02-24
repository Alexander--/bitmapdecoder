/**
 * <h2>Advanced PNG decoder for Android</h2>
 *
 * This package contains everything necessary for decoding indexed PNG images to the most optimal in-memory format.
 *
 * <p>Most users of this library will want {@link org.bitmapdecoder.IndexedDrawable}. It can be created from
 * Kotlin/Java code or used in XML. This class has the most features and closely resembles BitmapDrawable
 * and its API.
 *
 * <p>Some of more advanced use-cases, such as extending this library and/or dynamically decoding PNG images in image
 * loader may be better served by {@link org.bitmapdecoder.ShaderDrawable}, which is a light-weight class, internally
 * used by IndexedDrawable. Use {@link org.bitmapdecoder.PngSupport} to create {@link android.graphics.Paint} from
 * a PNG resource file and pass it to constructor of ShaderDrawable.
 *
 * <p>{@link org.bitmapdecoder.PngImageView} is an alternative to {@link android.widget.ImageView}, that automatically
 * tries to decode provided drawable as ShaderDrawable. Use it if for some reason you are not comfortable with
 * IndexedDrawable (for example, because you don't like to create an XML file for each PNG image in your application).
 * A notable advantage over using IndexedDrawable is that PngImageView postpones decoding of it's image until it
 * is able to determine if it is attached to Activity without hardware-acceleration  — IndexedDrawable just renders
 * nothing when that happens, while PngImageView falls back to decoding image as BitmapDrawable.
 *
 * <p>{@link org.bitmapdecoder.PaletteShader} is a corresponding alternative to {@link android.graphics.BitmapShader}.
 *
 * <p>If you want to directly decode a PNG file to ALPHA_8 Bitmap and ARGB_8888 palette, compatible with PaletteShader,
 * use {@link org.bitmapdecoder.PngDecoder}.
 */
package org.bitmapdecoder;