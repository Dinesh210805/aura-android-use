package com.aura.aura_ui.agentcore

import androidx.room.Room
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A heartbeat reports once into its chat and ends; a silent check waits for the next slot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JobRunnerTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AgentCoreDatabase::class.java)
        .allowMainThreadQueries().build()
    private var clock = 1_000_000L
    private val threads = ThreadStore(db.dao(), now = { clock })
    private val jobs = JobStore(db.dao(), now = { clock }, zone = { ZoneId.of("UTC") })
    private var answer = Schedule.SILENT
    private val runs = mutableListOf<String>()
    private val notified = mutableListOf<String>()
    private val runner = JobRunner(
        { id, text -> runs += text; flowOf(AgentEvent.TurnEnded(TurnEnd.ANSWERED, answer)) },
        threads, jobs, now = { clock },
    ) { _, text, _ -> notified += text }

    @After fun close() = db.close()

    @Test
    fun `a silent check reschedules and leaves no thread behind`() = runBlocking {
        val chat = threads.create().id
        val job = jobs.watch("My Swiggy order is out for delivery", 15, 6, chat)!!
        clock = job.nextRunMs
        runner.runDue()
        assertEquals(1, runs.size)
        assertTrue(runs.single().startsWith(Heartbeat.PREAMBLE))
        assertEquals(clock + 15 * 60_000L, jobs.list().single().nextRunMs)
        assertEquals(listOf(chat), db.dao().threads().first().map { it.id })
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `a met condition reports once into the chat and ends the watch`() = runBlocking {
        val chat = threads.create().id
        val job = jobs.watch("My Swiggy order is out for delivery", 15, 6, chat)!!
        answer = "Your Swiggy order is out for delivery."
        clock = job.nextRunMs
        runner.runDue()
        runner.runDue()
        assertEquals(1, runs.size)
        assertEquals(listOf(answer), notified)
        assertTrue(jobs.list().isEmpty())
        assertEquals(listOf(answer), db.dao().items(chat).first().filter { it.kind == ItemKind.ASSISTANT.name }.map { it.text })
    }

    @Test
    fun `an expired watch says so and is removed without a model call`() = runBlocking {
        val chat = threads.create().id
        jobs.watch("My parcel arrives", 15, 1, chat)
        clock += 2 * 3_600_000L
        runner.runDue()
        assertTrue(runs.isEmpty())
        assertTrue(jobs.list().isEmpty())
        assertEquals(ItemKind.NOTICE.name, db.dao().items(chat).first().last().kind)
    }

    @Test
    fun `a notification runs a matching watch at most once in two minutes`() = runBlocking {
        val chat = threads.create().id
        jobs.watch("My Swiggy order is out for delivery", 15, 6, chat)
        runner.poke("Swiggy: your order is being prepared")
        runner.poke("Swiggy: your order is being packed")
        runner.poke("WhatsApp: hello")
        assertEquals(1, runs.size)
        clock += JobRunner.POKE_GAP_MS
        runner.poke("Swiggy: picked up")
        assertEquals(2, runs.size)
    }
}
