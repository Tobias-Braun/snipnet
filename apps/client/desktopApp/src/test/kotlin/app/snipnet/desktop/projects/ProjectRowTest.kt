package app.snipnet.desktop.projects

import app.snipnet.shared.model.Job
import app.snipnet.shared.model.JobStatus
import app.snipnet.shared.model.Video
import app.snipnet.shared.model.VideoStatus
import app.snipnet.shared.store.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectRowTest {
    private val proxy: Path = Files.createTempFile("snipnet-proxy", ".mp4")

    private fun project(
        proxyPath: String? = proxy.toString(),
        remote: String? = "v1",
    ) = Project("p1", "/videos/match.mp4", proxyPath, remote, null, null, 0, 0)

    private fun video(status: VideoStatus) =
        Video("v1", "match.mp4", 1000, 854, 480, 15.0, 10, status, null, "", "", null)

    private fun status(
        project: Project = project(),
        video: Video? = null,
        task: TaskState? = null,
        checked: Boolean = true,
    ) = ProjectRow.derive(project, video, task, checked)

    @Test
    fun aRunningTaskWinsOverTheServerStatus() {
        val row = status(video = video(VideoStatus.UPLOADED), task = TaskState(Stage.UPLOADING, 0.4))
        assertEquals(ProjectStatus.UPLOADING, row.status)
        assertEquals(0.4, row.progress)
    }

    @Test
    fun aFailedTaskShowsItsErrorAndIsNotCancellable() {
        val row = status(task = TaskState(Stage.PROXY, error = "ffmpeg died"))
        assertEquals(ProjectStatus.FAILED, row.status)
        assertEquals("ffmpeg died", row.error)
        assertEquals(false, row.cancellable)
    }

    @Test
    fun serverStatusMapsToTheBadges() {
        assertEquals(ProjectStatus.READY, status(video = video(VideoStatus.UPLOADED)).status)
        assertEquals(ProjectStatus.ANALYZING, status(video = video(VideoStatus.ANALYZING)).status)
        assertEquals(ProjectStatus.ANALYZED, status(video = video(VideoStatus.ANALYZED)).status)
        assertEquals(ProjectStatus.FAILED, status(video = video(VideoStatus.FAILED)).status)
        assertEquals(ProjectStatus.FAILED, status(video = video(VideoStatus.CREATED)).status)
    }

    private fun analyzingVideo(jobStatus: JobStatus) =
        video(VideoStatus.ANALYZING).copy(
            latestJob = Job("j1", "v1", jobStatus, 0.0, null, null, 1, "", null, null),
        )

    @Test
    fun onlyARunningJobMakesTheRowUnremovable() {
        assertEquals(false, status(video = analyzingVideo(JobStatus.RUNNING)).removable)
        assertEquals(true, status(video = analyzingVideo(JobStatus.QUEUED)).removable)
        assertEquals(true, status(video = video(VideoStatus.UPLOADED)).removable)
        assertEquals(false, status(task = TaskState(Stage.ANALYZING, 0.5, jobStatus = JobStatus.RUNNING)).removable)
        assertEquals(true, status(task = TaskState(Stage.ANALYZING, 0.0, jobStatus = JobStatus.QUEUED)).removable)
        assertEquals(true, status(task = TaskState(Stage.ANALYZING, 0.0)).removable)
    }

    @Test
    fun anInterruptedImportShowsAsFailedFromTheLocalFiles() {
        assertEquals("The import was interrupted.", status(project(proxyPath = null)).error)
        assertEquals("The upload was interrupted.", status(project(remote = null)).error)
    }

    @Test
    fun missingServerDataIsUnknownUntilTheListWasFetched() {
        assertEquals(ProjectStatus.UNKNOWN, status(checked = false).status)
        assertNotNull(status(checked = true).error)
        assertNull(status(checked = false).error)
    }

    @Test
    fun onlyTheSupportedContainersAreAccepted() {
        val dir = Files.createTempDirectory("snipnet-ext")
        for (name in listOf("a.mp4", "b.MOV", "c.mkv", "d.m4v")) {
            assertNull(SupportedVideo.problem(Files.write(dir.resolve(name), ByteArray(1))), name)
        }
        assertNotNull(SupportedVideo.problem(Files.write(dir.resolve("e.avi"), ByteArray(1))))
    }
}
