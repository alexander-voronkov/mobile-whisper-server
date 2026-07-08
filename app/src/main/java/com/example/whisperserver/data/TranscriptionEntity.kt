package com.example.whisperserver.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.whisperserver.service.TranscriptionRecord

/**
 * Room row for a persisted transcription request. Mirrors [TranscriptionRecord]
 * one-to-one; kept as a separate type so the domain model stays free of Room
 * annotations. The record's monotonic [id] is the primary key (it's also the
 * audio clip's file name, so it's unique across sessions once seeded from the
 * persisted max).
 */
@Entity(tableName = "transcriptions")
data class TranscriptionEntity(
    @PrimaryKey val id: Long,
    val timestampMillis: Long,
    val remoteAddress: String,
    val success: Boolean,
    val httpStatus: Int,
    val modelId: String,
    val audioBytes: Long,
    val audioDurationMillis: Long,
    val queueWaitMillis: Long,
    val processingMillis: Long,
    val text: String,
    val errorMessage: String?,
    val audioFileName: String?,
    val detectedLanguage: String,
)

fun TranscriptionEntity.toRecord(): TranscriptionRecord = TranscriptionRecord(
    id = id,
    timestampMillis = timestampMillis,
    remoteAddress = remoteAddress,
    success = success,
    httpStatus = httpStatus,
    modelId = modelId,
    audioBytes = audioBytes,
    audioDurationMillis = audioDurationMillis,
    queueWaitMillis = queueWaitMillis,
    processingMillis = processingMillis,
    text = text,
    errorMessage = errorMessage,
    audioFileName = audioFileName,
    detectedLanguage = detectedLanguage,
)

fun TranscriptionRecord.toEntity(): TranscriptionEntity = TranscriptionEntity(
    id = id,
    timestampMillis = timestampMillis,
    remoteAddress = remoteAddress,
    success = success,
    httpStatus = httpStatus,
    modelId = modelId,
    audioBytes = audioBytes,
    audioDurationMillis = audioDurationMillis,
    queueWaitMillis = queueWaitMillis,
    processingMillis = processingMillis,
    text = text,
    errorMessage = errorMessage,
    audioFileName = audioFileName,
    detectedLanguage = detectedLanguage,
)
