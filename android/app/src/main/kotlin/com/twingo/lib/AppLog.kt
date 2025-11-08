package com.twingo.lib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

class AppLog(
    val level: LogLevel,
    val message: String,
    val time: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJsonString(jsonString: String): AppLog {
            val jsonObject = Json.parseToJsonElement(jsonString).jsonObject
            return AppLog(
                LogLevel.valueOf(jsonObject.getValue("level").jsonPrimitive.content),
                jsonObject.getValue("message").jsonPrimitive.content,
                jsonObject.getValue("time").jsonPrimitive.long
            )
        }
    }

    fun toJsonString(): String {
        return buildJsonObject {
            put("level", level.name)
            put("message", message)
            put("time", time)
        }.toString()
    }
}
