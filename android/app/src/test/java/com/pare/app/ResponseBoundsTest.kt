package com.pare.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException

class ResponseBoundsTest {
    @Test fun slowTrickleCannotKeepAResponseOpenBeyondItsDeadline() {
        var now=0L
        val source=object:ByteArrayInputStream(ByteArray(32)) {
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {now+=60;return super.read(buffer,offset,1)}
        }
        assertThrows(InterruptedIOException::class.java) {readSyncResponse(source,100) {now}}
    }
    @Test fun cancelledReadDoesNotConsumeAnotherByte() {
        val source=ByteArrayInputStream(byteArrayOf(1))
        Thread.currentThread().interrupt()
        try {assertThrows(InterruptedIOException::class.java) {readSyncResponse(source,Long.MAX_VALUE)};assertEquals(1,source.available())}
        finally {Thread.interrupted()}
    }
    @Test fun oversizedResponseIsRejectedWithoutReturningPartialData() {
        assertThrows(IllegalArgumentException::class.java) {readSyncResponse(ByteArrayInputStream(ByteArray(5_242_881)),Long.MAX_VALUE)}
    }
}
