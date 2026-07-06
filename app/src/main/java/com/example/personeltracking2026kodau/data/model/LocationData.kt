package com.example.personeltracking2026kodau.data.model

data class LocationData(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val source: String,
    val timestamp: Long = System.currentTimeMillis()
)
