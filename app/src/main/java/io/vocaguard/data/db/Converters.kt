package io.vocaguard.data.db

import androidx.room.TypeConverter

class Converters {
    /** Stores a List<String> of enum names as a comma-separated string. */
    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(",")
}
