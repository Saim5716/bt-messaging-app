package com.example

import com.example.transport.ble.BleMeshFramer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshProtocolTest {

    @Test
    fun `ble framer splits and reassembles multi-chunk payload out of order`() {
        val framer = BleMeshFramer()
        val payload = ByteArray(1500) { idx -> (idx % 251).toByte() }
        val fragments = framer.fragment(payload, mtuPayloadCapacity = 240)
        assertEquals(7, fragments.size)

        // Feed fragments in reverse order to verify sequence reassembly
        var reassembled: ByteArray? = null
        for (fragment in fragments.reversed()) {
            reassembled = framer.acceptFragment("AA:BB:CC:DD:EE:FF", fragment)
        }
        assertArrayEquals(payload, reassembled)
    }

    @Test
    fun `ble framer rejects malformed short fragments`() {
        val framer = BleMeshFramer()
        assertNull(framer.acceptFragment("AA:BB:CC:DD:EE:FF", byteArrayOf(1, 2, 3)))
    }
}
