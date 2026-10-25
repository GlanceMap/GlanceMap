package com.glancemap.glancemapwearos.presentation.features.download

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException

private const val EXTRACTION_JOB_ID = 42211
private const val EXTRACTION_TOKEN = "extractionToken"
private val extractionJobs = OamExtractionJobCoordinator()

internal suspend fun runOamExtraction(
    context: Context,
    work: suspend () -> String,
): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return work()
    val scheduler = context.getSystemService(JobScheduler::class.java)
    try {
        return extractionJobs.execute(
            schedule = { token ->
                val job =
                    JobInfo
                        .Builder(EXTRACTION_JOB_ID, ComponentName(context, OamExtractionJobService::class.java))
                        .setExpedited(true)
                        .setExtras(PersistableBundle().apply { putString(EXTRACTION_TOKEN, token) })
                        .build()
                val accepted = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
                DebugTelemetry.log("OamDownload", "event=extraction_job_scheduled accepted=$accepted")
                accepted
            },
            cancel = { scheduler.cancel(EXTRACTION_JOB_ID) },
            work = work,
        )
    } catch (error: OamExtractionJobUnavailableException) {
        throw IOException(context.getString(R.string.download_please_try_again), error)
    }
}

/** Runs local ZIP work while JobScheduler owns the execution wake lock. No network constraints. */
class OamExtractionJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeParams: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val token = params.extras.getString(EXTRACTION_TOKEN) ?: return false
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !params.isExpeditedJob) {
            extractionJobs.stop(token)
            DebugTelemetry.log("OamDownload", "event=extraction_job_rejected reason=not_expedited")
            false
        } else {
            val started =
                extractionJobs.start(token, scope) {
                    mainHandler.post {
                        if (activeParams === params) {
                            activeParams = null
                            DebugTelemetry.log("OamDownload", "event=extraction_job_finished")
                            jobFinished(params, false)
                        }
                    }
                }
            if (started) activeParams = params
            DebugTelemetry.log("OamDownload", "event=extraction_job_started expedited=true owned=$started")
            started
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val token = params.extras.getString(EXTRACTION_TOKEN)
        if (activeParams?.extras?.getString(EXTRACTION_TOKEN) == token) activeParams = null
        token?.let(extractionJobs::stop)
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.stopReason else -1
        DebugTelemetry.log("OamDownload", "event=extraction_job_stopped reason=$reason")
        return false
    }

    override fun onDestroy() {
        activeParams?.extras?.getString(EXTRACTION_TOKEN)?.let(extractionJobs::stop)
        activeParams = null
        scope.cancel()
        super.onDestroy()
    }
}
