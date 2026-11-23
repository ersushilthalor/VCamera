package com.example.camera.engine

/**
 * EIS+ preview owns one physical Camera2 output at a time. On a shared logical
 * camera, changing only the relay source cannot switch to a sensor that has no
 * configured output; the active session must be recreated for that physical lens.
 */
internal fun requiresPhysicalLensReconfigurationForEisPlusPreview(
    isVideoPreview: Boolean,
    previewRelayActive: Boolean,
    isRecording: Boolean,
    sameCameraDevice: Boolean,
    switchingMainAndUltraWide: Boolean,
): Boolean =
    isVideoPreview &&
        previewRelayActive &&
        !isRecording &&
        sameCameraDevice &&
        switchingMainAndUltraWide
