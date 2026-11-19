package com.ulgentube.app

import com.ulgentube.app.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    // -------------------------------------------------------------------------
    // formatBytes testleri
    // -------------------------------------------------------------------------

    @Test
    fun formatBytes_returnsEmptyForNullZeroAndNegative() {
        assertEquals("", FormatUtils.formatBytes(null))
        assertEquals("", FormatUtils.formatBytes(0L))
        assertEquals("", FormatUtils.formatBytes(-512L))
    }

    @Test
    fun formatBytes_formatsBytesKbMbGb() {
        assertEquals("512 B", FormatUtils.formatBytes(512L))
        assertEquals("1 KB", FormatUtils.formatBytes(1024L))
        assertEquals("500 KB", FormatUtils.formatBytes(500L * 1024L))
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024L * 1024L))
        assertEquals("12.5 MB", FormatUtils.formatBytes((12.5 * 1024.0 * 1024.0).toLong()))
        assertEquals("1.00 GB", FormatUtils.formatBytes(1024L * 1024L * 1024L))
        assertEquals("2.50 GB", FormatUtils.formatBytes((2.5 * 1024.0 * 1024.0 * 1024.0).toLong()))
    }

    // -------------------------------------------------------------------------
    // formatDuration testleri
    // -------------------------------------------------------------------------

    @Test
    fun formatDuration_returnsEmptyForNullZeroAndNegative() {
        assertEquals("", FormatUtils.formatDuration(null))
        assertEquals("", FormatUtils.formatDuration(0L))
        assertEquals("", FormatUtils.formatDuration(-10L))
    }

    @Test
    fun formatDuration_formatsSecondsMinutesAndHours() {
        assertEquals("00:05", FormatUtils.formatDuration(5L))
        assertEquals("00:59", FormatUtils.formatDuration(59L))
        assertEquals("01:00", FormatUtils.formatDuration(60L))
        assertEquals("12:34", FormatUtils.formatDuration(12L * 60L + 34L))
        assertEquals("1:00:00", FormatUtils.formatDuration(3600L))
        assertEquals("2:05:09", FormatUtils.formatDuration(2L * 3600L + 5L * 60L + 9L))
    }
}
