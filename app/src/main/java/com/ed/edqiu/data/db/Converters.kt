package com.ed.edqiu.data.db

import androidx.room.TypeConverter
import com.ed.edqiu.data.model.LinkStatus

/** Room 类型转换器：枚举 <-> 字符串。 */
class Converters {

    @TypeConverter
    fun fromStatus(status: LinkStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): LinkStatus =
        runCatching { LinkStatus.valueOf(value) }.getOrDefault(LinkStatus.PENDING)
}
