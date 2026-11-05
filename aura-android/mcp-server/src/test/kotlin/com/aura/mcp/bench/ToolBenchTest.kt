package com.aura.mcp.bench

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Runs every [Scenarios] entry against the mock phone and writes `build/toolbench/report.html`.
 *
 * - Fails: only on status `fail`. A `gap` is a known break waiting on its planned change; a
 *   `closed` gap passes but is listed so its `gap` marker gets removed.
 */
class ToolBenchTest {

    @Test
    fun `tools survive the bench`() = runBlocking {
        val outcomes = Bench.run(Scenarios.all)
        val dir = File(System.getProperty("toolbench.out") ?: "build/toolbench")
        Bench.write(outcomes, dir)
        val summary = outcomes.groupingBy { it.status }.eachCount()
        println("toolbench: $summary -> ${File(dir, "report.html").absolutePath}")
        val failed = outcomes.filter { it.status == "fail" }
        if (failed.isNotEmpty()) {
            fail(
                failed.joinToString("\n") { o ->
                    "${o.scenario.id} ${o.scenario.title}: " +
                        (o.error ?: o.ctx?.checks?.filterNot { it.ok }?.joinToString { "${it.name} (${it.detail})" })
                },
            )
        }
    }
}
