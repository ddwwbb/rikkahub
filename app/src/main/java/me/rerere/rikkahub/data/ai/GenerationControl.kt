package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Voice interruption may cancel a model request, but never an executing tool batch. */
class GenerationControl {
    enum class Phase { GENERATING, EXECUTING_TOOLS, FINISHED }

    private var job: Job? = null
    private var phase = Phase.GENERATING
    private var interrupted = false

    @Synchronized
    fun attach(job: Job) {
        this.job = job
        if (interrupted) job.cancel(VoiceGenerationSuperseded())
    }

    @Synchronized
    fun interrupt() {
        interrupted = true
        if (phase == Phase.GENERATING) job?.cancel(VoiceGenerationSuperseded())
    }

    @Synchronized
    fun beginTools(): Boolean {
        if (interrupted) return false
        phase = Phase.EXECUTING_TOOLS
        return true
    }

    /** Called only after the collector has recorded the actual tool results. */
    @Synchronized
    fun finishTools(): Boolean {
        phase = Phase.GENERATING
        return !interrupted
    }

    @Synchronized
    fun isInterrupted(): Boolean = interrupted

    @Synchronized
    fun finish() {
        phase = Phase.FINISHED
        job = null
    }
}

class VoiceGenerationSuperseded : CancellationException("Voice reply superseded")
