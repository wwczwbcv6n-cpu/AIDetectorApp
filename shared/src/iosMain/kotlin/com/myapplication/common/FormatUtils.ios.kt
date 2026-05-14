package com.myapplication.common

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun formatTimestamp(epochMillis: Long): String {
    val formatter = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd HH:mm:ss"
    }
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    return formatter.stringFromDate(date)
}
