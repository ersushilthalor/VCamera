package com.example.camera.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EisPlusFrameDrainSchedulerTest {
    @Test
    fun interleavesMainAndUltraWideInsteadOfDrainingOneQueueFirst() {
        val plan = buildEisPlusRoundRobinDrainPlan(
            sources = listOf(PreviewStreamSource.MAIN, PreviewStreamSource.ULTRAWIDE),
            pendingCounts = mapOf(
                PreviewStreamSource.MAIN to 5,
                PreviewStreamSource.ULTRAWIDE to 2,
            ),
        )

        assertEquals(
            listOf(
                PreviewStreamSource.MAIN,
                PreviewStreamSource.ULTRAWIDE,
                PreviewStreamSource.MAIN,
                PreviewStreamSource.ULTRAWIDE,
                PreviewStreamSource.MAIN,
                PreviewStreamSource.MAIN,
                PreviewStreamSource.MAIN,
            ),
            plan,
        )
    }

    @Test
    fun ignoresEmptyAndNegativeQueueBudgets() {
        val plan = buildEisPlusRoundRobinDrainPlan(
            sources = listOf(PreviewStreamSource.MAIN, PreviewStreamSource.ULTRAWIDE),
            pendingCounts = mapOf(
                PreviewStreamSource.MAIN to -3,
                PreviewStreamSource.ULTRAWIDE to 0,
            ),
        )

        assertEquals(emptyList<PreviewStreamSource>(), plan)
    }

    @Test
    fun supportsOneAvailableSensor() {
        val plan = buildEisPlusRoundRobinDrainPlan(
            sources = listOf(PreviewStreamSource.MAIN, PreviewStreamSource.ULTRAWIDE),
            pendingCounts = mapOf(PreviewStreamSource.ULTRAWIDE to 2),
        )

        assertEquals(
            listOf(PreviewStreamSource.ULTRAWIDE, PreviewStreamSource.ULTRAWIDE),
            plan,
        )
    }
}
