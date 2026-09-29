package app.snipnet.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Domain models mirroring the types section of docs/api.md. Field names are the camelCase JSON names of the
// contract; timestamps stay ISO-8601 strings because the client only displays and echoes them. Media times are
// integer milliseconds and image coordinates are normalized to [0, 1].

@Serializable
data class User(
    val id: String,
    val email: String,
    val trainingConsent: Boolean,
    val createdAt: String,
)

/** Region of interest in normalized frame coordinates, origin top-left. */
@Serializable
data class Roi(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

@Serializable
data class Point(
    val x: Double,
    val y: Double,
)

@Serializable
data class Court(
    val roi: Roi,
    val netPoint: Point,
)

@Serializable
enum class VideoStatus {
    @SerialName("created")
    CREATED,

    @SerialName("uploaded")
    UPLOADED,

    @SerialName("analyzing")
    ANALYZING,

    @SerialName("analyzed")
    ANALYZED,

    @SerialName("failed")
    FAILED,
}

@Serializable
enum class JobStatus {
    @SerialName("queued")
    QUEUED,

    @SerialName("running")
    RUNNING,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class Job(
    val id: String,
    val videoId: String,
    val status: JobStatus,
    /** Progress from 0 to 1. */
    val progress: Double,
    val modelVersion: String?,
    val error: String?,
    val attempts: Int,
    val createdAt: String,
    val startedAt: String?,
    val finishedAt: String?,
)

@Serializable
data class Video(
    val id: String,
    val filename: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val proxySizeBytes: Long,
    val status: VideoStatus,
    val court: Court?,
    val createdAt: String,
    val updatedAt: String,
    val latestJob: Job?,
)

@Serializable
enum class SegmentLabel {
    @SerialName("rally")
    RALLY,
}

@Serializable
data class Segment(
    val startMs: Long,
    val endMs: Long,
    val label: SegmentLabel = SegmentLabel.RALLY,
    val confidence: Double? = null,
)

/** Rally probability per sample (0 to 1), sampled at [hz] samples per second. */
@Serializable
data class ScoreCurve(
    val hz: Double,
    val values: List<Double>,
)

@Serializable
enum class EditOpKind {
    @SerialName("trim")
    TRIM,

    @SerialName("split")
    SPLIT,

    @SerialName("merge")
    MERGE,

    @SerialName("delete")
    DELETE,

    @SerialName("add")
    ADD,

    @SerialName("toggle")
    TOGGLE,

    @SerialName("move")
    MOVE,
}

@Serializable
data class EditOp(
    val op: EditOpKind,
    /** Client epoch milliseconds of the action. */
    val atMs: Long,
    val before: List<Segment>,
    val after: List<Segment>,
)

@Serializable
enum class SegmentSetKind {
    @SerialName("prediction")
    PREDICTION,

    @SerialName("user")
    USER,
}

@Serializable
data class SegmentSet(
    val id: String,
    val videoId: String,
    val kind: SegmentSetKind,
    val parentSetId: String?,
    val jobId: String?,
    val modelVersion: String?,
    val segments: List<Segment>,
    val scores: ScoreCurve?,
    val editLog: List<EditOp>?,
    val isFinal: Boolean,
    val createdAt: String,
)

/** Response of `POST /v1/auth/register` and `POST /v1/auth/login`. */
@Serializable
data class AuthResponse(
    val token: String,
    val user: User,
)

/** Error envelope returned with every non-2xx response. */
@Serializable
data class ApiErrorBody(
    val error: ApiErrorDetail,
)

/** Wire form of an error; the client surfaces it as the sealed `app.snipnet.shared.api.ApiError`. */
@Serializable
data class ApiErrorDetail(
    val code: String,
    val message: String,
)

/** Presigned upload target returned by `POST /v1/videos`. */
@Serializable
data class UploadTarget(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val expiresAt: String,
)

/** Response of `POST /v1/videos`. */
@Serializable
data class CreateVideoResponse(
    val video: Video,
    val upload: UploadTarget,
)

/** Request body of `POST /v1/videos`. */
@Serializable
data class CreateVideoRequest(
    val filename: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val proxySizeBytes: Long,
)

/** One line of the admin training export (`GET /v1/admin/training-export`). */
@Serializable
data class TrainingExportItem(
    val video: Video,
    val proxyUrl: String,
    val prediction: SegmentSet,
    val final: SegmentSet,
)
