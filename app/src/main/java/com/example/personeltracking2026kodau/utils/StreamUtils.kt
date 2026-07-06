package com.example.personeltracking2026kodau.utils

object StreamUtils {

    fun getRtmpUrl(serial: String): String {
        return "rtmp://76.13.20.253:11935/personel/$serial"
    }
}
