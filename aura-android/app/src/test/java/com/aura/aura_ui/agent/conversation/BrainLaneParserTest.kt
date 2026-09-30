package com.aura.aura_ui.agent.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainLaneParserTest {

    // ── The ordinary case ──

    @Test fun `an ANSWER line becomes speech with no action`() {
        val r = BrainLaneParser.parse("ANSWER: Paris.")
        assertEquals("Paris.", r.spoken)
        assertEquals(BrainLaneAction.None, r.action)
    }

    @Test fun `a PHONE line carries the task and still speaks`() {
        val r = BrainLaneParser.parse(
            """
            ANSWER: On it.
            PHONE: open Swiggy and order a filter coffee
            """.trimIndent(),
        )
        assertEquals("On it.", r.spoken)
        assertEquals(BrainLaneAction.Phone("open Swiggy and order a filter coffee"), r.action)
    }

    // ── Steering ──

    @Test fun `a STEER line without GOAL amends rather than retargets`() {
        val r = BrainLaneParser.parse("STEER: also make it a large one")
        assertEquals(BrainLaneAction.Steer("also make it a large one", newGoal = null), r.action)
    }

    @Test fun `a GOAL line attaches to the STEER as a retarget`() {
        val r = BrainLaneParser.parse(
            """
            ANSWER: Switching to food B.
            STEER: stop searching for food A; search for food B and re-check the screen first
            GOAL: find food B
            """.trimIndent(),
        )
        val steer = r.action as BrainLaneAction.Steer
        assertEquals("find food B", steer.newGoal)
        assertTrue(steer.directive.startsWith("stop searching for food A"))
    }

    @Test fun `a GOAL line with no STEER does not invent an action`() {
        val r = BrainLaneParser.parse("ANSWER: ok\nGOAL: something")
        assertEquals(BrainLaneAction.None, r.action)
    }

    @Test fun `ABORT, PAUSE and RESUME parse`() {
        assertEquals(BrainLaneAction.Abort, BrainLaneParser.parse("ABORT: stop now").action)
        assertEquals(BrainLaneAction.Pause, BrainLaneParser.parse("PAUSE:").action)
        assertEquals(BrainLaneAction.Resume, BrainLaneParser.parse("RESUME:").action)
    }

    // ── Side effects ──

    @Test fun `MEMORY lines are collected with their type`() {
        val r = BrainLaneParser.parse(
            """
            ANSWER: Noted.
            MEMORY: USER | prefers filter coffee
            MEMORY: STYLE | mixes Tamil and English
            """.trimIndent(),
        )
        assertEquals(2, r.memories.size)
        assertEquals(MemoryWrite("USER", "prefers filter coffee"), r.memories[0])
        assertEquals("STYLE", r.memories[1].type)
    }

    @Test fun `a MEMORY line with no type still stores the fact`() {
        val r = BrainLaneParser.parse("MEMORY: has a sister in Coimbatore")
        assertEquals("", r.memories.single().type)
        assertEquals("has a sister in Coimbatore", r.memories.single().text)
    }

    @Test fun `REMIND lines parse an absolute time`() {
        val r = BrainLaneParser.parse("ANSWER: Set.\nREMIND: 1765432100000 | call the dentist")
        assertEquals(ReminderWrite(1_765_432_100_000L, "call the dentist"), r.reminders.single())
    }

    /**
     * A guessed reminder time is worse than no reminder: the user believes it is set and it
     * fires at the wrong moment, or never.
     */
    @Test fun `a REMIND line with an unparseable time is dropped, not guessed`() {
        val r = BrainLaneParser.parse("ANSWER: ok\nREMIND: tomorrow evening | call the dentist")
        assertTrue(r.reminders.isEmpty())
        assertEquals("ok", r.spoken)
    }

    @Test fun `a REMIND line with no separator is dropped`() {
        assertTrue(BrainLaneParser.parse("REMIND: 1765432100000").reminders.isEmpty())
    }

    // ── Degrading gracefully: the whole reason for line prefixes ──

    @Test fun `a reply with no prefixes at all is still spoken`() {
        val r = BrainLaneParser.parse("It's about two hours by train.")
        assertEquals("It's about two hours by train.", r.spoken)
        assertEquals(BrainLaneAction.None, r.action)
    }

    @Test fun `unprefixed continuation lines join the spoken answer`() {
        val r = BrainLaneParser.parse("ANSWER: Paris.\nIt's the capital of France.")
        assertEquals("Paris.\nIt's the capital of France.", r.spoken)
    }

    @Test fun `empty and null replies produce nothing rather than throwing`() {
        assertEquals("", BrainLaneParser.parse(null).spoken)
        assertEquals("", BrainLaneParser.parse("   ").spoken)
        assertEquals(BrainLaneAction.None, BrainLaneParser.parse(null).action)
    }

    @Test fun `prefixes are recognised regardless of case`() {
        assertEquals(BrainLaneAction.Abort, BrainLaneParser.parse("abort: stop").action)
    }

    /**
     * A prefix only counts at the start of a line. Otherwise AURA saying the word "answer" mid
     * sentence would silently truncate its own reply.
     */
    @Test fun `a prefix word inside a sentence is not treated as a prefix`() {
        val r = BrainLaneParser.parse("ANSWER: the ANSWER: is forty two")
        assertEquals("the ANSWER: is forty two", r.spoken)
    }

    /**
     * PHONE and STEER together are contradictory — start something new vs change something
     * running. First-wins keeps behaviour stable instead of depending on the model's line order.
     */
    @Test fun `the first action wins when a reply contradicts itself`() {
        val r = BrainLaneParser.parse("PHONE: do a thing\nSTEER: change the other thing")
        assertEquals(BrainLaneAction.Phone("do a thing"), r.action)
    }

    @Test fun `an action line with an empty payload is ignored rather than acted on`() {
        assertEquals(BrainLaneAction.None, BrainLaneParser.parse("ANSWER: hi\nPHONE:").action)
        assertEquals(BrainLaneAction.None, BrainLaneParser.parse("ANSWER: hi\nSTEER:  ").action)
    }
}
