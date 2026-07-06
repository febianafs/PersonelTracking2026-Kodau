package com.example.personeltracking2026kodau.core.mqtt.queue

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mqtt_queue")
data class MqttMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val topic: String,
    val payload: String,
    val timestamp: Long
)