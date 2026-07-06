package com.example.personeltracking2026kodau.core.mqtt

import android.content.Context

class MqttTopicManager(context: Context) {

    private val prefs =
        context.getSharedPreferences("mqtt_topics", Context.MODE_PRIVATE)

    companion object {
        private const val DATA_TOPIC = "kdu/radio/data"
        private const val SOS_TOPIC = "kdu/radio/sos"
        private const val TOPIC_VERSION = 1
    }

    fun save(config: MqttTopicConfig) {
        prefs.edit()
            .putString("personel_data", config.personelDataTopic.ifBlank { DATA_TOPIC })
            .putString("personel_sos", config.personelSosTopic.ifBlank { SOS_TOPIC })
            .putString("bodycam_data", config.bodycamDataTopic.ifBlank { DATA_TOPIC })
            .putString("bodycam_sos", config.bodycamSosTopic.ifBlank { SOS_TOPIC })
            .putInt("topic_version", TOPIC_VERSION)
            .apply()
    }

    fun load(): MqttTopicConfig {
        migrateTopics()
        return MqttTopicConfig(
            personelDataTopic = prefs.getString("personel_data", DATA_TOPIC)?.ifBlank { DATA_TOPIC } ?: DATA_TOPIC,
            personelSosTopic = prefs.getString("personel_sos", SOS_TOPIC)?.ifBlank { SOS_TOPIC } ?: SOS_TOPIC,
            bodycamDataTopic = prefs.getString("bodycam_data", DATA_TOPIC)?.ifBlank { DATA_TOPIC } ?: DATA_TOPIC,
            bodycamSosTopic = prefs.getString("bodycam_sos", SOS_TOPIC)?.ifBlank { SOS_TOPIC } ?: SOS_TOPIC
        )
    }

    private fun migrateTopics() {
        if (prefs.getInt("topic_version", 0) >= TOPIC_VERSION) return

        prefs.edit()
            .putString("personel_data", DATA_TOPIC)
            .putString("personel_sos", SOS_TOPIC)
            .putString("bodycam_data", DATA_TOPIC)
            .putString("bodycam_sos", SOS_TOPIC)
            .putInt("topic_version", TOPIC_VERSION)
            .apply()
    }
}
