package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local;

import kotlin.jvm.functions.Function1;

/**
 * JNI callback bridge for sherpa-onnx streaming TTS.
 *
 * The libsherpa-onnx-jni.so expects a callback object with method signature
 * {@code invoke([F)Ljava/lang/Integer;} (returns boxed java.lang.Integer).
 *
 * Kotlin lambdas of type {@code (FloatArray) -> Int} compile to
 * {@code invoke([F)I} (primitive int return), causing NoSuchMethodError
 * and SIGABRT crash.
 *
 * This Java helper provides the exact binary signature the JNI expects,
 * by auto-boxing the primitive int to Integer at the Java bytecode level.
 */
public abstract class SherpaCallbackBridge implements Function1<float[], Integer> {

    @Override
    public final Integer invoke(float[] samples) {
        // Java auto-boxes onSamples()'s int return to Integer,
        // producing invoke([F)Ljava/lang/Integer; in bytecode.
        return onSamples(samples);
    }

    /**
     * Implement in Kotlin subclass. Must return 1 to continue synthesis,
     * or 0 to stop early.
     */
    protected abstract int onSamples(float[] samples);
}
