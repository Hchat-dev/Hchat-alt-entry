package h.Hchat.hooks.items.linkparse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkImageClientTest {
    @Test fun distinguishesJpegAndWebpSignaturesWithoutConversion() {
        assertEquals(
            WorkImageFormat.JPEG,
            WorkImageFormatSniffer.detect(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))
        )
        assertEquals(
            WorkImageFormat.WEBP,
            WorkImageFormatSniffer.detect("RIFF1234WEBPVP8 ".toByteArray(Charsets.US_ASCII))
        )
        assertNull(WorkImageFormatSniffer.detect("RIFF1234WAVE".toByteArray(Charsets.US_ASCII)))
        assertNull(WorkImageFormatSniffer.detect(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
    }

    @Test fun keepsAnimatedGifAndPngBytesVerbatim() {
        assertEquals(
            WorkImageFormat.GIF,
            WorkImageFormatSniffer.detect("GIF89a\u0001\u0000".toByteArray(Charsets.US_ASCII))
        )
        assertEquals(
            WorkImageFormat.GIF,
            WorkImageFormatSniffer.detect("GIF87a\u0001\u0000".toByteArray(Charsets.US_ASCII))
        )
        assertEquals(
            WorkImageFormat.PNG,
            WorkImageFormatSniffer.detect(
                byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a)
            )
        )
        // 只截到 "GIF" 三个字节时不能当作有效动图，避免把半截文件当图发出去。
        assertNull(WorkImageFormatSniffer.detect("GIF".toByteArray(Charsets.US_ASCII)))
    }
}
