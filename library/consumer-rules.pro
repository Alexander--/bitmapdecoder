#
#  Consumer rules for applications using bitmapdecoder library
#
-keepnames,allowoptimization public class org.bitmapdecoder.PngDecoder
-keepclassmembernames class org.bitmapdecoder.* {
    native <methods>;
}