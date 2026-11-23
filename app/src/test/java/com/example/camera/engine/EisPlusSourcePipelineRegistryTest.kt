package com.example.camera.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EisPlusSourcePipelineRegistryTest {
    private class Pipeline(val name: String)

    @Test
    fun staleCleanupCannotDetachReplacementPipeline() {
        val registry = EisPlusSourcePipelineRegistry<Pipeline>()
        val oldPipeline = Pipeline("preview-generation-5")
        val replacement = Pipeline("standby-generation-6")

        registry.attach(PreviewStreamSource.ULTRAWIDE, oldPipeline)
        registry.attach(PreviewStreamSource.ULTRAWIDE, replacement)

        assertFalse(registry.detach(PreviewStreamSource.ULTRAWIDE, oldPipeline))
        assertSame(replacement, registry[PreviewStreamSource.ULTRAWIDE])
    }

    @Test
    fun currentOwnerCanDetachItsOwnPipeline() {
        val registry = EisPlusSourcePipelineRegistry<Pipeline>()
        val pipeline = Pipeline("active-ultrawide")

        registry.attach(PreviewStreamSource.ULTRAWIDE, pipeline)

        assertTrue(registry.detach(PreviewStreamSource.ULTRAWIDE, pipeline))
        assertTrue(registry[PreviewStreamSource.ULTRAWIDE] == null)
    }

    @Test
    fun mainAndUltraWidePipelinesRemainIndependentlyBound() {
        val registry = EisPlusSourcePipelineRegistry<Pipeline>()
        val main = Pipeline("main")
        val ultraWide = Pipeline("ultra-wide")

        registry.attach(PreviewStreamSource.MAIN, main)
        registry.attach(PreviewStreamSource.ULTRAWIDE, ultraWide)

        assertSame(main, registry[PreviewStreamSource.MAIN])
        assertSame(ultraWide, registry[PreviewStreamSource.ULTRAWIDE])
        assertTrue(registry.values.size == 2)
    }
}
