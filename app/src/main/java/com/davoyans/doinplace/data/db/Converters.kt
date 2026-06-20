package com.davoyans.doinplace.data.db

import androidx.room.TypeConverter
import com.davoyans.doinplace.data.model.ContactStatus
import com.davoyans.doinplace.data.model.PlaceMode
import com.davoyans.doinplace.data.model.PlaceType
import com.davoyans.doinplace.data.model.RecurrenceType
import com.davoyans.doinplace.data.model.TaskEventType
import com.davoyans.doinplace.data.model.TaskPriority
import com.davoyans.doinplace.data.model.TaskStatus
import com.davoyans.doinplace.data.model.TaskType

class Converters {
    @TypeConverter fun fromTaskStatus(v: TaskStatus) = v.name
    @TypeConverter fun toTaskStatus(v: String) = TaskStatus.valueOf(v)

    @TypeConverter fun fromContactStatus(v: ContactStatus) = v.name
    @TypeConverter fun toContactStatus(v: String) = runCatching { ContactStatus.valueOf(v) }.getOrDefault(ContactStatus.PENDING)

    @TypeConverter fun fromPlaceType(v: PlaceType) = v.name
    @TypeConverter fun toPlaceType(v: String) = PlaceType.valueOf(v)

    @TypeConverter fun fromEventType(v: TaskEventType) = v.name
    @TypeConverter fun toEventType(v: String) = TaskEventType.valueOf(v)

    @TypeConverter fun fromPriority(v: TaskPriority) = v.name
    @TypeConverter fun toPriority(v: String) = when (v) {
        "HIGH"                 -> TaskPriority.URGENT
        "NORMAL", "LOW", "EASY" -> TaskPriority.NO_RUSH
        else -> runCatching { TaskPriority.valueOf(v) }.getOrDefault(TaskPriority.NO_RUSH)
    }

    @TypeConverter fun fromPlaceMode(v: PlaceMode) = v.name
    @TypeConverter fun toPlaceMode(v: String) = runCatching { PlaceMode.valueOf(v) }.getOrDefault(PlaceMode.EXACT)

    @TypeConverter fun fromTaskType(v: TaskType) = v.name
    @TypeConverter fun toTaskType(v: String) = runCatching { TaskType.valueOf(v) }.getOrDefault(TaskType.SIMPLE)

    @TypeConverter fun fromRecurrenceType(v: RecurrenceType) = v.name
    @TypeConverter fun toRecurrenceType(v: String) = runCatching { RecurrenceType.valueOf(v) }.getOrDefault(RecurrenceType.NONE)
}
