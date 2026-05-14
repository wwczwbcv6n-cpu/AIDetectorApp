package com.myapplication.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
