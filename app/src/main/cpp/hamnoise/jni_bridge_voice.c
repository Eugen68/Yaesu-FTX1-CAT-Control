#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include "denoise_audio.h"
#include "voice_model_weights.h"

/*
 * JNI Bridge for HamNoise Voice Denoiser (SSB/voice, model "cw_denoise_gru_mask_bs"
 * cu front-end band-split pe 17 benzi - k_voice_reduction_model).
 * Foloseste acelasi motor generic (denoise_audio.c/denoise_gru.c) ca varianta CW din
 * jni_bridge.c - doar ponderile difera (k_voice_reduction_model vs k_denoise_model).
 * Matches Kotlin class com.example.yaesucatcontroladv.deepcw.denoise.VoiceDenoiserNative
 */

JNIEXPORT jlong JNICALL
Java_com_example_yaesucatcontroladv_deepcw_denoise_VoiceDenoiserNative_nativeCreate(JNIEnv *env, jclass clazz) {
    denoise_stream_t *stream = (denoise_stream_t *)malloc(sizeof(denoise_stream_t));
    if (!stream) return 0;

    if (denoise_stream_init(stream) != 0) {
        free(stream);
        return 0;
    }

    if (denoise_model_validate(&k_voice_reduction_model) != 0) {
        free(stream);
        return 0;
    }

    return (jlong)(intptr_t)stream;
}

JNIEXPORT void JNICALL
Java_com_example_yaesucatcontroladv_deepcw_denoise_VoiceDenoiserNative_nativeReset(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    denoise_stream_t *stream = (denoise_stream_t *)(intptr_t)handle;
    denoise_stream_reset(stream);
}

JNIEXPORT jint JNICALL
Java_com_example_yaesucatcontroladv_deepcw_denoise_VoiceDenoiserNative_nativeProcessHop(
    JNIEnv *env, jclass clazz, jlong handle, jfloatArray input, jfloatArray output
) {
    if (handle == 0) return -1;
    denoise_stream_t *stream = (denoise_stream_t *)(intptr_t)handle;

    float in_hop[DENOISE_HOP_LENGTH];
    float out_hop[DENOISE_HOP_LENGTH];

    (*env)->GetFloatArrayRegion(env, input, 0, DENOISE_HOP_LENGTH, in_hop);

    bool produced = false;
    int status = denoise_stream_process_hop(stream, &k_voice_reduction_model, in_hop, out_hop, &produced);
    if (status != 0) return -1;

    (*env)->SetFloatArrayRegion(env, output, 0, DENOISE_HOP_LENGTH, out_hop);

    return produced ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_example_yaesucatcontroladv_deepcw_denoise_VoiceDenoiserNative_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle != 0) {
        free((void *)(intptr_t)handle);
    }
}

JNIEXPORT jint JNICALL
Java_com_example_yaesucatcontroladv_deepcw_denoise_VoiceDenoiserNative_nativeHopLength(JNIEnv *env, jclass clazz) {
    return DENOISE_HOP_LENGTH;
}

JNIEXPORT jint JNICALL
Java_com_example_yaesucatcontroladv_deepcw_denoise_VoiceDenoiserNative_nativeSampleRate(JNIEnv *env, jclass clazz) {
    return DENOISE_SAMPLE_RATE;
}
