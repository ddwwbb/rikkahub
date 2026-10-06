package me.rerere.asr

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DuplexEventQueueTest {
    @Test fun `queued events survive absence of collector`() = runBlocking {
        val queue = DuplexEventQueue(4)
        val expected = listOf(DuplexASREvent.Started("a"), DuplexASREvent.Ended("a"), DuplexASREvent.Final("a", "one"))
        expected.forEach { assertTrue(queue.offer(it)) }
        queue.close()
        assertEquals(expected, queue.events.toList())
    }

    @Test fun `overflow preserves queued events then emits explicit failure`() = runBlocking {
        val queue = DuplexEventQueue(2)
        assertTrue(queue.offer(DuplexASREvent.Final("a", "one")))
        assertTrue(queue.offer(DuplexASREvent.Final("b", "two")))
        assertFalse(queue.offer(DuplexASREvent.Final("c", "three")))
        queue.fail("overflow")
        assertEquals(listOf(DuplexASREvent.Final("a", "one"), DuplexASREvent.Final("b", "two"),
            DuplexASREvent.Failed("overflow")), queue.events.toList())
    }
}
