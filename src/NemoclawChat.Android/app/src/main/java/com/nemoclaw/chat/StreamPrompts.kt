package com.nemoclaw.chat


internal fun streamHermesNativeInstructions(mode: String): String {
    return ""
}

internal fun isVideoRequest(prompt: String): Boolean {
    val text = prompt.lowercase()
    return listOf("video", "filmato", "clip", "montaggio", "remotion", "youtube", "mp4", "render").any { text.contains(it) }
}

internal fun hermesHubVideoInstructions(settings: AppSettings): String {
    val path = settings.videoLibraryPath.ifBlank { "HERMES_VIDEO_LIBRARY_PATH" }
    return """
        System prompt video Hermes Hub:
        - La sezione Video e' un watched-folder feed, non dipende dalla chat.
        - La cartella ufficiale sul PC Hermes e': $path
        - Quando l'utente chiede creazione, download, montaggio, rendering o preparazione di un video, salva sempre il file video finale in quella cartella.
        - Ogni file video comune (.mp4/.m4v/.mov/.mkv/.webm/.avi/.wmv/.flv/.mpg/.mpeg/.ts/.m2ts/.3gp/.ogv) messo in quella cartella apparira' automaticamente nella sezione Video tramite /v1/video/library.
        - Per massima compatibilita crea preferibilmente MP4 H.264 + AAC + yuv420p + faststart; il gateway puo' anche servire playback compat con ?format=mp4.
        - Se mostri anche il video in chat, pubblicalo tramite proxy /v1/media/... come visual_blocks media_file; non scrivere file:// o path locali come risposta finale.
        - Se non riesci a generare/renderizzare il video, spiega il blocco concreto e non fingere di averlo mandato.
    """.trimIndent()
}

