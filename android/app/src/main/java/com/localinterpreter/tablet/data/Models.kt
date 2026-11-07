package com.localinterpreter.tablet.data

data class Course(
    val id: Long,
    val name: String,
    val kind: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Session(
    val id: Long,
    val courseId: Long?,
    val startedAt: Long,
    val endedAt: Long?,
    val durationS: Double,
    val audioSource: String,
    val sourceLang: String,
    val targetLang: String,
    val title: String,
    val summary: String,
    val status: String,
)

data class Segment(
    val id: Long,
    val sessionId: Long,
    val speakerId: Long?,
    val t0: Double,
    val t1: Double,
    val srcLang: String,
    val srcText: String,
    val tgtText: String,
    val audioPath: String?,
    val lowConfidence: Boolean,
)

data class Speaker(
    val id: Long,
    val sessionId: Long,
    val label: String,
    val color: String,
)

data class Question(
    val id: Long,
    val sessionId: Long,
    val segmentId: Long?,
    val question: String,
    val questionTgt: String,
    val answer: String,
    val answerAlt: String,
    val source: String,
    val createdAt: Long,
)

data class SummaryItem(
    val id: Long,
    val sessionId: Long,
    val content: String,
    val createdAt: Long,
)

data class Material(
    val id: Long,
    val courseId: Long?,
    val name: String,
    val path: String,
    val createdAt: Long,
)

data class MaterialChunk(
    val id: Long,
    val materialId: Long,
    val courseId: Long?,
    val page: Int,
    val text: String,
)

data class GlossaryItem(
    val id: Long,
    val courseId: Long?,
    val term: String,
    val translation: String,
)

data class Bookmark(
    val id: Long,
    val sessionId: Long,
    val segmentId: Long,
    val createdAt: Long,
)

data class Quiz(
    val id: Long,
    val sessionId: Long,
    val content: String,
    val createdAt: Long,
)

data class CourseHit(
    val materialName: String,
    val page: Int,
    val text: String,
    val score: Double,
)

data class SegmentHit(
    val segment: Segment,
    val sessionTitle: String,
    val sessionStartedAt: Long,
)
