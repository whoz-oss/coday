package io.biznet.agentos.tools.service

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.context.i18n.LocaleContextHolder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


internal class DateTimeTools {
    @Tool(description = "Get the current date and time in the user's timezone")
    fun getCurrentDateTime(): String = LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString()

    @Tool(description = "Set a user alarm for the given time")
    fun setAlarm(@ToolParam(description = "Time in ISO-8601 format") time: String) {
        val alarmTime = LocalDateTime.parse(time, DateTimeFormatter.ISO_DATE_TIME)
        println("Alarm set for " + alarmTime)
    }
}