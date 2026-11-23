package com.example.camera.engine

/**
 * Thread-safe per-lens EIS+ pipeline ownership for a relay.
 * A delayed detach can only remove the pipeline instance that still owns the source.
 */
internal class EisPlusSourcePipelineRegistry<T : Any> {
    private val lock = Any()
    private val pipelines = LinkedHashMap<PreviewStreamSource, T>()

    operator fun get(source: PreviewStreamSource): T? = synchronized(lock) {
        pipelines[source]
    }

    val values: Collection<T>
        get() = synchronized(lock) { pipelines.values.toList() }

    val entries: Set<Map.Entry<PreviewStreamSource, T>>
        get() = synchronized(lock) { pipelines.toMap().entries }

    fun attach(source: PreviewStreamSource, pipeline: T): T? = synchronized(lock) {
        pipelines.put(source, pipeline)
    }

    fun detach(source: PreviewStreamSource, expectedPipeline: T): Boolean = synchronized(lock) {
        if (pipelines[source] !== expectedPipeline) {
            false
        } else {
            pipelines.remove(source)
            true
        }
    }
}
