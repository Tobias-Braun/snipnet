package app.snipnet.desktop.projects

import app.snipnet.desktop.upload.ProxyUploader
import app.snipnet.desktop.video.ProxyTranscoder
import app.snipnet.desktop.video.VideoEngine
import app.snipnet.desktop.video.VideoInfo
import app.snipnet.shared.api.ApiError
import app.snipnet.shared.api.SnipnetApi
import app.snipnet.shared.model.CreateVideoRequest
import app.snipnet.shared.model.Job
import app.snipnet.shared.model.JobStatus
import app.snipnet.shared.model.UploadTarget
import app.snipnet.shared.model.Video
import app.snipnet.shared.model.VideoStatus
import app.snipnet.shared.store.Project
import app.snipnet.shared.store.ProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.abs

/** The inference job ended in `failed`; the message is the job's own error text. */
private class AnalysisFailedException(
    message: String,
) : Exception(message)

/**
 * Drives every project through import (probe, proxy transcode), upload and analysis, independent of which screen is
 * showing, and publishes the resulting list as [rows]. It lives as long as the app: leaving the projects screen must
 * not abort a running transcode or upload.
 *
 * Every step is a cancellable task per project. A failure is kept as an error on the task so the row offers a retry;
 * [retry] re-derives what is still missing (proxy, upload, analysis) from the stores instead of replaying state, so
 * it also recovers an import that was interrupted by closing the app.
 *
 * Server deletes are bound to the account that started them: the signed-in user's id is captured when a delete is
 * queued or launched and passed to the store explicitly, and the request is skipped once [currentUserId] no longer
 * returns it, because the API would send it with the new account's token. A skipped delete stays on the pending list
 * of its own account and goes out when that account signs in again.
 *
 * @param currentUserId the id of the signed-in user, or null when nobody is; read on every check.
 * @param onAnalyzed called on [dispatcher] when an analysis started with `openWhenDone` succeeded, to open the editor.
 * @param pollIntervalMs first job poll delay, also used again whenever the job's progress moved.
 * @param maxPollIntervalMs upper bound of the backoff applied while the job's progress stands still.
 */
class ImportPipeline(
    private val api: SnipnetApi,
    private val store: ProjectStore,
    private val videoEngine: VideoEngine,
    private val transcoder: ProxyTranscoder,
    private val uploader: ProxyUploader,
    private val proxyDir: Path,
    private val currentUserId: () -> String?,
    private val onAnalyzed: (Project) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val pollIntervalMs: Long = 2_000,
    private val maxPollIntervalMs: Long = 10_000,
    private val now: () -> Instant = Instant::now,
) {
    private var scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Guards the maps below: progress callbacks arrive from ffmpeg and upload threads. */
    private val lock = Any()
    private val tasks = mutableMapOf<String, TaskState>()
    private val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val targets = mutableMapOf<String, UploadTarget>()

    /**
     * Every launched task until it has really ended. Unlike [jobs] this keeps a task that [remove] or [reset] already
     * unregistered, whose coroutine may still be winding down, so [awaitTasksEnded] can wait for it.
     */
    private val liveTasks = mutableSetOf<kotlinx.coroutines.Job>()
    private var videos = mapOf<String, Video>()
    private var serverChecked = false

    /** Remote video ids with a delete running right now, so [remove] and [refresh] never delete the same one twice. */
    private val deletingRemote = mutableSetOf<String>()

    /**
     * Analyses requested while the import was still running, mapped to their `openWhenDone` flag. The import task
     * picks them up once its upload succeeded (or [finishTask] does, see there); failure, cancellation, [remove] and
     * [reset] drop them.
     */
    private val pendingAnalyses = mutableMapOf<String, Boolean>()

    private val mutableRows = MutableStateFlow<List<ProjectRow>>(emptyList())

    /** The projects list with badges, newest opened first. */
    val rows: StateFlow<List<ProjectRow>> = mutableRows.asStateFlow()

    init {
        publish()
    }

    /**
     * Registers the files as projects and starts their import. Returns one message per rejected file (unsupported
     * extension, not a file) so the UI can tell the user; accepted files are unaffected by rejected ones.
     */
    fun import(paths: List<Path>): List<String> {
        val rejected = mutableListOf<String>()
        for (path in paths) {
            val problem = SupportedVideo.problem(path)
            if (problem != null) {
                rejected += problem
                continue
            }
            val project = store.create(originalPath = path.toAbsolutePath().toString())
            launchTask(project.id, TaskState(Stage.PROXY, 0.0)) { importUploadAndAnalyze(project.id) }
        }
        publish()
        return rejected
    }

    /**
     * Fetches the server's video list and resumes polling analyses that were running when the app was closed.
     *
     * The local rows are published first: the store lists only the signed-in user's projects, and the account may have
     * changed since the last publish (logout publishes an empty list). Without this, a user whose first refresh after
     * login fails would see no projects at all instead of their local data.
     */
    suspend fun refresh() {
        publish()
        val list = api.listVideos()
        synchronized(lock) {
            videos = list.associateBy { it.id }
            serverChecked = true
        }
        publish()
        retryPendingDeletes(list)
        for (project in store.list()) {
            val video = project.remoteVideoId?.let { videos[it] } ?: continue
            if (video.status == VideoStatus.ANALYZING) {
                val job = video.latestJob
                launchTask(project.id, TaskState(Stage.ANALYZING, job?.progress, jobStatus = job?.status)) {
                    analyze(project, openWhenDone = false)
                }
            }
        }
    }

    /**
     * Starts the analysis of the uploaded video of the local project [projectId]: saves the local court to the server
     * if it is not there yet, creates the job and polls it until it ends. The court selection screen calls this after
     * the user confirmed the court.
     *
     * While the import (transcode, upload) of the project is still running the request is not dropped: it is queued
     * and the import task starts the analysis right after the upload succeeded. A request during a running analysis
     * is ignored, as that analysis is what was asked for.
     */
    fun startAnalysis(
        projectId: String,
        openWhenDone: Boolean = true,
    ) {
        val project = store.get(projectId) ?: return
        val busy =
            synchronized(lock) {
                val active = jobs[projectId]?.isActive == true
                if (active && tasks[projectId]?.stage != Stage.ANALYZING) pendingAnalyses[projectId] = openWhenDone
                active
            }
        if (busy) return
        launchTask(project.id, TaskState(Stage.ANALYZING, 0.0)) { analyze(project, openWhenDone) }
    }

    /** Repeats whatever step of [projectId] failed, or finishes an import that was interrupted. */
    fun retry(projectId: String) {
        val project = store.get(projectId) ?: return
        val remoteId = project.remoteVideoId
        val task = synchronized(lock) { tasks[projectId] }
        val video = remoteId?.let { synchronized(lock) { videos[it] } }
        val analysisFailed = task?.stage == Stage.ANALYZING || (task == null && video?.status == VideoStatus.FAILED)
        if (analysisFailed && remoteId != null) {
            startAnalysis(projectId)
        } else {
            launchTask(projectId, TaskState(Stage.PROXY, 0.0)) { importUploadAndAnalyze(projectId) }
        }
    }

    /** Stops the running step of [projectId]; the project stays and shows as failed with a retry. */
    fun cancel(projectId: String) {
        synchronized(lock) { jobs[projectId] }?.cancel()
    }

    /**
     * Deletes the project locally, its proxy file, and (best effort) the server's video. The original is kept.
     *
     * The server refuses to delete a video with a running job (409), while a job that is only queued is dropped along
     * with the video. Once the local project is gone its remote id is gone too, so a video removed while its job runs
     * would stay on the server for good. Such a project is therefore not removed: this returns false and the project
     * stays until the job left the running state. A 409 that still arrives, because the local view of the job was
     * stale or a worker claimed the queued job at that moment, is retried in the background.
     *
     * @return whether the project was removed.
     */
    fun remove(projectId: String): Boolean {
        val project = store.get(projectId) ?: return true
        val busy = mutableRows.value.firstOrNull { it.project.id == projectId }?.removable == false
        if (busy) return false
        synchronized(lock) {
            jobs.remove(projectId)?.cancel()
            tasks.remove(projectId)
            pendingAnalyses.remove(projectId)
            targets.remove(projectId)
        }
        project.proxyPath?.let { runCatching { Files.deleteIfExists(Path.of(it)) } }
        val remoteId = project.remoteVideoId
        val userId = currentUserId()
        if (remoteId == null) {
            store.delete(projectId)
        } else {
            // Deleted and queued atomically: the local row that knew the id is gone afterwards, so the pending entry
            // is the only thing that lets a later run (or app start) finish the delete if this attempt does not.
            store.deleteAndQueueRemoteDelete(projectId, remoteId, userId)
        }
        publish()
        if (remoteId != null && userId != null) launchRemoteDelete(remoteId, userId)
        return true
    }

    /** Starts [deleteRemoteWithRetry] for [remoteId] of [userId] unless a delete of that video is already running. */
    private fun launchRemoteDelete(
        remoteId: String,
        userId: String,
    ) {
        val started = synchronized(lock) { deletingRemote.add(remoteId) }
        if (!started) return
        scope.launch {
            try {
                deleteRemoteWithRetry(remoteId, userId)
            } finally {
                synchronized(lock) { deletingRemote.remove(remoteId) }
            }
        }
    }

    /**
     * Deletes the server video and takes it off the persisted pending list once the server confirmed it (204) or
     * reported it as gone (404). While a job still holds the video (409) the delete is repeated a bounded number of
     * times; any other failure (server or network down) or running out of attempts leaves the entry for the next
     * [refresh]. Nothing is sent once the signed-in account is not [userId] any more.
     */
    private suspend fun deleteRemoteWithRetry(
        remoteId: String,
        userId: String,
    ) {
        var attempt = 0
        while (true) {
            if (currentUserId() != userId) return
            try {
                api.deleteVideo(remoteId)
                store.removePendingVideoDelete(remoteId, userId)
                return
            } catch (e: ApiError.NotFound) {
                store.removePendingVideoDelete(remoteId, userId)
                return
            } catch (e: ApiError.Conflict) {
                if (++attempt >= MAX_DELETE_ATTEMPTS) return
                delay(maxPollIntervalMs)
            } catch (e: ApiError) {
                return
            }
        }
    }

    /**
     * Retries the persisted deletes, except for videos whose latest job is still running in [list]: the server would
     * answer 409 again, so those wait for a later refresh. A queued job does not hold the delete back, the server
     * drops it with the video.
     */
    private fun retryPendingDeletes(list: List<Video>) {
        val byId = list.associateBy { it.id }
        val userId = currentUserId() ?: return
        for (remoteId in store.pendingVideoDeletes()) {
            val job = byId[remoteId]?.latestJob
            if (job != null && job.status == JobStatus.RUNNING) continue
            launchRemoteDelete(remoteId, userId)
        }
    }

    /**
     * Stops everything and forgets the in-memory state, used on logout. The republished list is empty because the
     * store only lists the signed-in user's projects and nobody is signed in any more.
     */
    fun reset() {
        scope.cancel()
        synchronized(lock) {
            jobs.clear()
            tasks.clear()
            pendingAnalyses.clear()
            targets.clear()
            videos = emptyMap()
            serverChecked = false
        }
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        publish()
    }

    /**
     * Suspends until every task launched so far has ended, including ones that [remove] already unregistered. Tests use
     * it to assert that nothing happens after a removal instead of guessing a grace period.
     */
    internal suspend fun awaitTasksEnded() {
        synchronized(lock) { liveTasks.toList() }.forEach { it.join() }
    }

    /**
     * Runs [work] as the task of [projectId] unless one is already running. Success clears the task, so the row falls
     * back to the server's status; failure and cancellation keep it with an error message, which is what enables the
     * retry button.
     */
    private fun launchTask(
        projectId: String,
        initial: TaskState,
        work: suspend () -> Unit,
    ) {
        val job =
            synchronized(lock) {
                if (jobs[projectId]?.isActive == true) return
                tasks[projectId] = initial
                scope.launch(start = CoroutineStart.LAZY) { runTask(projectId, work) }.also {
                    jobs[projectId] = it
                    liveTasks += it
                    it.invokeOnCompletion { _ -> synchronized(lock) { liveTasks.remove(it) } }
                }
            }
        publish()
        job.start()
    }

    private suspend fun runTask(
        projectId: String,
        work: suspend () -> Unit,
    ) {
        val self = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        try {
            work()
            finishTask(projectId, self, error = null)
        } catch (e: CancellationException) {
            finishTask(projectId, self, error = "Cancelled.")
            throw e
        } catch (e: Exception) {
            finishTask(projectId, self, error = describe(e))
        }
    }

    /**
     * Ignores a task that was replaced or removed meanwhile ([reset], [remove]) so it cannot resurrect stale state.
     *
     * A queued analysis is always taken out here. Usually [importUploadAndAnalyze] consumed it already, but a
     * [startAnalysis] from another thread can still queue one after that check and before this task is unregistered;
     * after a success that request is started now instead of being lost, after a failure it is dropped.
     */
    private fun finishTask(
        projectId: String,
        job: kotlinx.coroutines.Job?,
        error: String?,
    ) {
        val queued =
            synchronized(lock) {
                if (jobs[projectId] !== job) return
                jobs.remove(projectId)
                val queued = pendingAnalyses.remove(projectId)
                val stage = tasks[projectId]?.stage ?: Stage.PROXY
                if (error == null) tasks.remove(projectId) else tasks[projectId] = TaskState(stage, error = error)
                queued.takeIf { error == null }
            }
        publish()
        if (queued != null) startAnalysis(projectId, queued)
    }

    private fun setProgress(
        projectId: String,
        stage: Stage,
        progress: Double?,
        jobStatus: JobStatus? = null,
    ) {
        val changed =
            synchronized(lock) {
                if (jobs[projectId] == null) return
                val previous = tasks[projectId]
                val significant =
                    previous == null ||
                        previous.stage != stage ||
                        previous.jobStatus != jobStatus ||
                        previous.progress == null ||
                        progress == null ||
                        abs(progress - previous.progress) >= MIN_PROGRESS_STEP ||
                        progress >= 1.0
                if (significant) tasks[projectId] = TaskState(stage, progress, jobStatus = jobStatus)
                significant
            }
        if (changed) publish()
    }

    private fun publish() {
        val rows =
            synchronized(lock) {
                store.list().map {
                    ProjectRow.derive(
                        it,
                        it.remoteVideoId?.let(videos::get),
                        tasks[it.id],
                        serverChecked,
                    )
                }
            }
        mutableRows.value = rows
    }

    private fun remember(video: Video) {
        synchronized(lock) { videos = videos + (video.id to video) }
        publish()
    }

    /** Runs the import and, if an analysis was requested meanwhile (see [startAnalysis]), continues with it. */
    private suspend fun importUploadAndAnalyze(projectId: String) {
        importAndUpload(projectId)
        val openWhenDone = synchronized(lock) { pendingAnalyses.remove(projectId) } ?: return
        // Re-read: the upload stored the remote video id, and the court may have been saved meanwhile.
        val project = store.get(projectId) ?: return
        setProgress(projectId, Stage.ANALYZING, 0.0)
        analyze(project, openWhenDone)
    }

    private suspend fun importAndUpload(projectId: String) {
        val project = store.get(projectId) ?: return
        val original = Path.of(project.originalPath)
        if (!Files.isRegularFile(original)) throw IOException("The original file is missing: $original")
        setProgress(projectId, Stage.PROXY, 0.0)
        val info = videoEngine.probe(original)
        val proxy = ensureProxy(project, original, info)
        setProgress(projectId, Stage.UPLOADING, 0.0)
        uploadProxy(project, original, info, proxy)
    }

    private suspend fun ensureProxy(
        project: Project,
        original: Path,
        info: VideoInfo,
    ): Path {
        val existing = project.proxyPath?.let(Path::of)
        if (existing != null && Files.isRegularFile(existing)) return existing
        val target = proxyDir.resolve("${project.id}.mp4")
        transcoder.transcode(original, target, info.durationMs) { setProgress(project.id, Stage.PROXY, it) }
        store.setProxyPath(project.id, target.toString())
        publish()
        return target
    }

    /**
     * Registers the proxy with the server, uploads it and then sends a court that exists only locally. A video that
     * already left `created` needs at most that court. An unfinished one is only continued while its presigned URL is
     * still known and valid; otherwise it is deleted and created anew, because the contract has no endpoint to fetch a
     * fresh URL for an existing video.
     */
    private suspend fun uploadProxy(
        project: Project,
        original: Path,
        info: VideoInfo,
        proxy: Path,
    ) {
        val size = Files.size(proxy)
        val existing = project.remoteVideoId?.let { fetchVideo(it) }
        if (existing != null && existing.status != VideoStatus.CREATED) {
            remember(existing)
            // A retry after a failed court upload lands here, because the proxy itself is already on the server.
            sendLocalCourt(project.id, existing)
            return
        }
        val known = synchronized(lock) { targets[project.id] }
        val reusable = known != null && existing != null && existing.proxySizeBytes == size && stillValid(known)
        val videoId: String
        val target: UploadTarget
        if (reusable) {
            videoId = existing!!.id
            target = known!!
        } else {
            if (existing != null) discard(existing.id)
            val request =
                CreateVideoRequest(
                    original.fileName.toString(),
                    info.durationMs,
                    info.width,
                    info.height,
                    info.frameRate,
                    size,
                )
            // Uncancellable so that a cancelled task still receives the created video's id and can clean it up.
            val userId = currentUserId()
            val created = withContext(NonCancellable) { api.createVideo(request) }
            abandonIfRemoved(project.id, created.video.id, userId)
            store.setRemoteVideoId(project.id, created.video.id)
            synchronized(lock) { targets[project.id] = created.upload }
            remember(created.video)
            videoId = created.video.id
            target = created.upload
        }
        uploader.upload(target, proxy) { setProgress(project.id, Stage.UPLOADING, it) }
        val completed = api.uploadComplete(videoId)
        remember(completed)
        synchronized(lock) { targets.remove(project.id) }
        sendLocalCourt(project.id, completed)
    }

    /**
     * Sends a court that was marked before the video was registered. The court screen only calls the server when the
     * project already has a remote video id, so a court saved while the proxy was still transcoding or uploading
     * exists only locally; the project is re-read because the court may have been saved during the upload.
     */
    private suspend fun sendLocalCourt(
        projectId: String,
        video: Video,
    ) {
        if (video.court != null) return
        val court = store.get(projectId)?.court ?: return
        remember(api.putCourt(video.id, court))
    }

    /**
     * A [remove] that ran while `createVideo` was in flight cannot stop a POST that still succeeds: the new video id
     * would go to a deleted row and the server would keep a `created` video nobody deletes. The project is therefore
     * checked once the video exists; when it is gone the video is deleted right away (uncancellably, as the task is
     * already cancelled) and the task ends as cancelled.
     *
     * [userId] is the account that was signed in when the video was created. If another account is signed in now the
     * DELETE is not sent (it would carry the wrong token); the video is only queued under [userId], so it is deleted
     * when that account signs in again.
     */
    private suspend fun abandonIfRemoved(
        projectId: String,
        videoId: String,
        userId: String?,
    ) {
        if (store.get(projectId) != null) return
        withContext(NonCancellable) {
            if (userId != null && currentUserId() == userId) {
                try {
                    discard(videoId)
                } catch (e: ApiError) {
                    // Unreachable server or a job holds the video: kept on the pending list for the next refresh.
                    store.addPendingVideoDelete(videoId, userId)
                }
            } else {
                store.addPendingVideoDelete(videoId, userId)
            }
        }
        throw CancellationException("The project was removed during the upload.")
    }

    private fun stillValid(target: UploadTarget): Boolean =
        runCatching {
            Instant
                .parse(
                    target.expiresAt,
                ).isAfter(now().plusSeconds(URL_SAFETY_MARGIN_S))
        }.getOrDefault(false)

    private suspend fun fetchVideo(id: String): Video? =
        try {
            api.getVideo(id)
        } catch (e: ApiError.NotFound) {
            null
        }

    private suspend fun discard(id: String) {
        try {
            api.deleteVideo(id)
        } catch (e: ApiError.NotFound) {
            // Already gone, which is what was wanted.
        }
    }

    private suspend fun analyze(
        project: Project,
        openWhenDone: Boolean,
    ) {
        val remoteId = checkNotNull(project.remoteVideoId) { "The video has not been uploaded yet." }
        val video = api.getVideo(remoteId)
        val running =
            video.latestJob?.takeIf {
                video.status == VideoStatus.ANALYZING &&
                    ProjectRow.isActive(
                        it.status,
                    )
            }
        val job = running ?: createJob(project, video)
        pollUntilDone(project.id, job)
        remember(api.getVideo(remoteId))
        if (openWhenDone) onAnalyzed(project)
    }

    private suspend fun createJob(
        project: Project,
        video: Video,
    ): Job {
        if (video.court == null) {
            val local = project.court ?: throw IllegalStateException("Select the court before analyzing.")
            remember(api.putCourt(video.id, local))
        }
        return try {
            api.analyze(video.id)
        } catch (e: ApiError.Conflict) {
            // A job started elsewhere (or by a previous run) is already queued or running: follow that one.
            api.getVideo(video.id).latestJob?.takeIf { ProjectRow.isActive(it.status) } ?: throw e
        }
    }

    /**
     * Polls the job every [pollIntervalMs]; while its progress stands still the interval grows by half each time up to
     * [maxPollIntervalMs], and it snaps back as soon as progress moves. Temporary server or network trouble is
     * retried a few times before the analysis is reported as failed.
     */
    private suspend fun pollUntilDone(
        projectId: String,
        first: Job,
    ): Job {
        var job = first
        var interval = pollIntervalMs
        var failures = 0
        while (ProjectRow.isActive(job.status)) {
            setProgress(projectId, Stage.ANALYZING, job.progress, job.status)
            delay(interval)
            val next =
                try {
                    api.getJob(job.id).also { failures = 0 }
                } catch (e: ApiError) {
                    if (!isTransient(e) || ++failures >= MAX_POLL_FAILURES) throw e
                    null
                }
            interval =
                nextPollInterval(
                    interval,
                    pollIntervalMs,
                    maxPollIntervalMs,
                    progressMoved =
                        next != null && next.progress > job.progress,
                )
            if (next != null) job = next
        }
        if (job.status == JobStatus.FAILED) {
            throw AnalysisFailedException(job.error ?: "The analysis failed.")
        }
        return job
    }

    private fun isTransient(e: ApiError) = e is ApiError.Network || e is ApiError.Server || e is ApiError.RateLimited

    private fun describe(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()

    companion object {
        private const val MIN_PROGRESS_STEP = 0.01
        private const val URL_SAFETY_MARGIN_S = 60L
        private const val MAX_POLL_FAILURES = 5
        private const val MAX_DELETE_ATTEMPTS = 30

        /**
         * The wait before the next job poll: back to [base] whenever progress moved, otherwise one and a half times
         * [current], capped at [max], so a stalled or queued job is polled less and less often.
         */
        fun nextPollInterval(
            current: Long,
            base: Long,
            max: Long,
            progressMoved: Boolean,
        ): Long = if (progressMoved) base else (current * 3 / 2).coerceIn(base, max)
    }
}
