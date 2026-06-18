package com.nan.player

data class VideoItem(
    val title: String,
    val description: String,
    val url: String,
    val duration: String
) {
    val isHttp: Boolean
        get() = url.startsWith("http://") || url.startsWith("https://")

    val isDownloadable: Boolean
        get() = isHttp
}
