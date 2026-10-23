package com.example.data.repository

import com.example.data.focus.FocusGuardManager
import com.example.data.importer.BacklogImportExport
import com.example.data.importer.CurriculumIngestionReport
import com.example.data.importer.CurriculumScheduleIngestionUtility
import com.example.data.local.AppDatabase
import com.example.data.local.entities.ChapterEntity
import com.example.data.local.entities.DailyScheduleEntity
import com.example.data.local.entities.FocusSessionEntity
import com.example.data.local.entities.MasteryLevel
import com.example.data.local.entities.MistakeEntryEntity
import com.example.data.local.entities.RevisionItemEntity
import com.example.data.local.entities.TestRecordEntity
import com.example.data.local.entities.UserSettingsEntity
import com.example.data.local.entities.XpEventEntity
import com.example.data.progression.ProgressionManager
import com.example.data.revision.SpacedRevisionManager
import com.example.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class StudyRepository(private val db: AppDatabase) {

    val allChapters: Flow<List<ChapterEntity>> = db.chapterDao().getAllChapters()
    val weakChapters: Flow<List<ChapterEntity>> = db.chapterDao().getWeakChapters()
    val allSchedules: Flow<List<DailyScheduleEntity>> = db.dailyScheduleDao().getAllDays()
    val allTests: Flow<List<TestRecordEntity>> = db.testRecordDao().getAllTests()
    val completedTests: Flow<List<TestRecordEntity>> = db.testRecordDao().getCompletedTests()
    val allMistakes: Flow<List<MistakeEntryEntity>> = db.mistakeEntryDao().getAllMistakes()
    val pendingRevisions: Flow<List<RevisionItemEntity>> = db.revisionItemDao().getPendingRevisions()
    val allRevisions: Flow<List<RevisionItemEntity>> = db.revisionItemDao().getAllRevisions()
    val userSettings: Flow<UserSettingsEntity?> = db.userSettingsDao().getSettings()
    val activeFocusSession: Flow<FocusSessionEntity?> = db.focusSessionDao().getActiveSession()
    val allXpEvents: Flow<List<XpEventEntity>> = db.xpEventDao().getAllXpEvents()

    suspend fun initializeIfEmpty() = withContext(Dispatchers.IO) {
        val chapterCount = db.chapterDao().getChapterCount()
        val scheduleCount = db.dailyScheduleDao().getScheduleCount()
        if (chapterCount == 0) {
            val standardChapters = BacklogImportExport.getStandardJeeTemplate()
            CurriculumScheduleIngestionUtility.ingestIntoRoomDatabase(
                db = db,
                rawChapters = standardChapters,
                replaceExistingChapters = true
            )
        } else if (scheduleCount == 0) {
            val existingChapters = db.chapterDao().getAllChaptersSync()
            val mappedDays = CurriculumScheduleIngestionUtility.mapChaptersInto80DaySchedules(
                validatedChapters = existingChapters,
                existingSchedules = emptyList()
            )
            db.dailyScheduleDao().insertAll(mappedDays)
        } else {
            val existingDays = db.dailyScheduleDao().getAllDaysSync()
            val hasAssignedSchedules = existingDays.any { it.assignedChapterNames.isNotBlank() }
            if (!hasAssignedSchedules) {
                val existingChapters = db.chapterDao().getAllChaptersSync()
                if (existingChapters.isNotEmpty()) {
                    val mappedDays = CurriculumScheduleIngestionUtility.mapChaptersInto80DaySchedules(
                        validatedChapters = existingChapters,
                        existingSchedules = existingDays
                    )
                    db.dailyScheduleDao().insertAll(mappedDays)
                }
            }
        }

        val testCount = db.testRecordDao().getTestCount()
        if (testCount == 0) {
            val tests = BacklogImportExport.generateSampleSundayTests()
            db.testRecordDao().insertAll(tests)
        }

        val settings = db.userSettingsDao().getSettingsSync()
        if (settings == null) {
            db.userSettingsDao().insertOrUpdate(UserSettingsEntity())
        }

        // Restore active Focus Session across process death
        val persistedSession = db.focusSessionDao().getActiveSessionSync()
        FocusGuardManager.syncActiveSession(persistedSession)
    }

    /**
     * Awards XP idempotently using [eventKey] as primary key and enforcing the daily XP cap.
     * Never awards duplicate XP on repeated taps or imports.
     */
    suspend fun awardIdempotentXp(
        eventKey: String,
        category: String,
        description: String,
        requestedXp: Int,
        dateStr: String = DateUtils.todayDateString()
    ): Int = withContext(Dispatchers.IO) {
        val existing = db.xpEventDao().getEventByKey(eventKey)
        if (existing != null) return@withContext 0

        val earnedToday = db.xpEventDao().getXpEarnedOnDate(dateStr)
        val clampedXp = ProgressionManager.clampAwardToDailyCap(requestedXp, earnedToday)
        if (clampedXp <= 0) return@withContext 0

        val rowId = db.xpEventDao().insertXpEventIgnoreConflict(
            XpEventEntity(
                eventKey = eventKey,
                category = category,
                description = description,
                xpAmount = clampedXp,
                dateString = dateStr
            )
        )
        if (rowId != -1L) clampedXp else 0
    }

    suspend fun revokeXpByKey(eventKey: String) = withContext(Dispatchers.IO) {
        db.xpEventDao().deleteEventByKey(eventKey)
    }

    suspend fun seedOfficialTemplate(): CurriculumIngestionReport = withContext(Dispatchers.IO) {
        val chapters = BacklogImportExport.getStandardJeeTemplate()
        CurriculumScheduleIngestionUtility.ingestIntoRoomDatabase(
            db = db,
            rawChapters = chapters,
            replaceExistingChapters = true
        )
    }

    /**
     * Imports chapters from JSON or CSV text, validates against curriculum standards,
     * and maps them into the 80-day schedule database without awarding XP.
     */
    suspend fun importData(content: String, replaceExisting: Boolean = false): CurriculumIngestionReport = withContext(Dispatchers.IO) {
        val parsed = BacklogImportExport.parseChaptersFromJson(content)
        CurriculumScheduleIngestionUtility.ingestIntoRoomDatabase(
            db = db,
            rawChapters = parsed,
            replaceExistingChapters = replaceExisting
        )
    }

    /**
     * Imports chapters directly from a binary .xlsx spreadsheet, validates against curriculum standards,
     * and maps them into the 80-day schedule database.
     */
    suspend fun importXlsxBytes(xlsxBytes: ByteArray, replaceExisting: Boolean = false): CurriculumIngestionReport = withContext(Dispatchers.IO) {
        val parsed = CurriculumScheduleIngestionUtility.parseXlsxBytes(xlsxBytes)
        CurriculumScheduleIngestionUtility.ingestIntoRoomDatabase(
            db = db,
            rawChapters = parsed,
            replaceExistingChapters = replaceExisting
        )
    }

    /**
     * Re-maps the current backlog chapters into the 80-day schedule database so each day receives
     * an updated, interleaved Physics, Chemistry, and Mathematics study plan.
     */
    suspend fun syncScheduleFromCurrentBacklog(): CurriculumIngestionReport = withContext(Dispatchers.IO) {
        val currentChapters = db.chapterDao().getAllChaptersSync()
        val existingSchedules = db.dailyScheduleDao().getAllDaysSync()
        val report = CurriculumScheduleIngestionUtility.buildIngestionPlan(
            rawChapters = currentChapters,
            existingSchedules = existingSchedules
        )
        if (report.mappedSchedules.isNotEmpty()) {
            db.dailyScheduleDao().insertAll(report.mappedSchedules)
        }
        report
    }

    /**
     * Saves chapter task progress.
     * Note: Task completion (status = COMPLETED) is strictly separate from conceptual mastery (masteryLevel).
     */
    suspend fun saveChapter(chapter: ChapterEntity) = withContext(Dispatchers.IO) {
        val newStatus = when {
            !chapter.isAssessed -> "NOT_ASSESSED"
            chapter.completionPercentage >= 100 -> "COMPLETED"
            chapter.completionPercentage > 0 -> "IN_PROGRESS"
            else -> "NOT_STARTED"
        }
        val toSave = chapter.copy(
            status = newStatus,
            lastUpdated = System.currentTimeMillis()
        )
        if (toSave.id == 0L) {
            db.chapterDao().insertChapter(toSave)
        } else {
            db.chapterDao().updateChapter(toSave)
        }
    }

    suspend fun updateChapterMastery(chapter: ChapterEntity, mastery: MasteryLevel) = withContext(Dispatchers.IO) {
        val updated = chapter.copy(
            masteryLevel = mastery.dbKey,
            isWeak = mastery == MasteryLevel.NEEDS_REVISION || chapter.isWeak,
            lastUpdated = System.currentTimeMillis()
        )
        saveChapter(updated)
    }

    suspend fun deleteChapter(id: Long) = withContext(Dispatchers.IO) {
        db.chapterDao().deleteChapter(id)
    }

    suspend fun clearChapters() = withContext(Dispatchers.IO) {
        db.chapterDao().clearAllChapters()
    }

    suspend fun updateSchedule(schedule: DailyScheduleEntity) = withContext(Dispatchers.IO) {
        val isTargetMet = schedule.completedLectures >= schedule.targetLectures &&
                schedule.completedProblems >= schedule.targetProblems
        db.dailyScheduleDao().updateSchedule(schedule.copy(isCompleted = isTargetMet))
    }

    suspend fun recordStudySession(date: String, studyMinutes: Int, breakMinutes: Int) = withContext(Dispatchers.IO) {
        val current = db.dailyScheduleDao().getScheduleForDateSync(date)
        if (current != null) {
            val updated = current.copy(
                studyMinutes = current.studyMinutes + studyMinutes,
                breakMinutes = current.breakMinutes + breakMinutes
            )
            db.dailyScheduleDao().updateSchedule(updated)
        }
    }

    suspend fun rebalanceSchedule(fromDayNumber: Int) = withContext(Dispatchers.IO) {
        val currentChapters = db.chapterDao().getAllChaptersSync()
        val existingSchedules = db.dailyScheduleDao().getAllDaysSync()
        if (currentChapters.isNotEmpty()) {
            val remapped = CurriculumScheduleIngestionUtility.mapChaptersInto80DaySchedules(
                validatedChapters = currentChapters,
                existingSchedules = existingSchedules
            )
            for (day in remapped) {
                if (day.dayNumber >= fromDayNumber && !day.isCompleted) {
                    db.dailyScheduleDao().updateSchedule(day.copy(sleepHours = 7.5f))
                }
            }
        } else {
            for (day in fromDayNumber..80) {
                val schedule = db.dailyScheduleDao().getScheduleForDayNumber(day)
                if (schedule != null && !schedule.isCompleted) {
                    val targetLec = if (schedule.isSunday) 1 else 4
                    db.dailyScheduleDao().updateSchedule(
                        schedule.copy(
                            targetLectures = targetLec,
                            targetProblems = if (schedule.isSunday) 15 else 25,
                            sleepHours = 7.5f
                        )
                    )
                }
            }
        }
    }

    suspend fun autoEvaluateSundayTest(test: TestRecordEntity) = withContext(Dispatchers.IO) {
        val currentChapters = db.chapterDao().getAllChaptersSync()
        val evaluated = BacklogImportExport.autoGenerateMockScoreForTest(test, currentChapters)
        saveTestRecord(evaluated)
    }

    suspend fun autoEvaluateAllSundayTests() = withContext(Dispatchers.IO) {
        val currentChapters = db.chapterDao().getAllChaptersSync()
        val tests = BacklogImportExport.generateSampleSundayTests()
        db.testRecordDao().clearAllTests()
        val evaluatedList = tests.mapIndexed { idx, t ->
            if (idx < 6 || t.isAssessed) {
                if (t.isAssessed) t else BacklogImportExport.autoGenerateMockScoreForTest(t.copy(id = (idx + 1).toLong()), currentChapters)
            } else {
                BacklogImportExport.autoGenerateMockScoreForTest(t.copy(id = (idx + 1).toLong()), currentChapters)
            }
        }
        db.testRecordDao().insertAll(evaluatedList)
    }

    suspend fun saveTestRecord(test: TestRecordEntity) = withContext(Dispatchers.IO) {
        val id = if (test.id == 0L) {
            db.testRecordDao().insertTest(test)
        } else {
            db.testRecordDao().updateTest(test)
            test.id
        }

        if (test.isAssessed && test.weakTopics.isNotBlank()) {
            val weakTopicList = test.weakTopics.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val todayStr = DateUtils.todayDateString()
            for ((idx, topic) in weakTopicList.withIndex()) {
                val syntheticChapterId = id * 1000L + idx
                val existing = db.revisionItemDao().getActiveRevisionForChapter(syntheticChapterId)
                if (existing == null) {
                    db.revisionItemDao().insertRevision(
                        RevisionItemEntity(
                            chapterId = syntheticChapterId,
                            chapterName = topic.replaceFirstChar { it.uppercase() },
                            subject = "Test Review",
                            stage = 1,
                            dueDate = todayStr,
                            lastRevisedDate = todayStr,
                            isCompleted = false
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteTest(id: Long) = withContext(Dispatchers.IO) {
        db.testRecordDao().deleteTest(id)
    }

    suspend fun saveMistake(mistake: MistakeEntryEntity) = withContext(Dispatchers.IO) {
        if (mistake.id == 0L) {
            db.mistakeEntryDao().insertMistake(mistake)
        } else {
            db.mistakeEntryDao().updateMistake(mistake)
        }
    }

    suspend fun deleteMistake(id: Long) = withContext(Dispatchers.IO) {
        db.mistakeEntryDao().deleteMistake(id)
    }

    /**
     * Marks a chapter studied/revised today and creates or resets its Stage 1 spaced revision schedule
     * without creating duplicate active rows.
     */
    suspend fun markChapterStudiedAndScheduleRevision(
        chapter: ChapterEntity,
        todayStr: String = DateUtils.todayDateString()
    ): RevisionItemEntity = withContext(Dispatchers.IO) {
        val settings = db.userSettingsDao().getSettingsSync()
        val intervals = settings?.parsedRevisionIntervals ?: SpacedRevisionManager.DEFAULT_INTERVALS
        val existingActive = db.revisionItemDao().getActiveRevisionForChapter(chapter.id)

        val revisionItem = SpacedRevisionManager.createOrResetRevisionForChapter(
            chapter = chapter,
            existingActive = existingActive,
            intervals = intervals,
            todayDateStr = todayStr
        )

        val savedId = if (revisionItem.id == 0L) {
            db.revisionItemDao().insertRevision(revisionItem)
        } else {
            db.revisionItemDao().updateRevision(revisionItem)
            revisionItem.id
        }

        val updatedChapter = chapter.copy(
            lastStudiedDate = todayStr,
            revisionStage = 1,
            nextRevisionDate = revisionItem.dueDate,
            lastUpdated = System.currentTimeMillis()
        )
        saveChapter(updatedChapter)

        revisionItem.copy(id = savedId)
    }

    /**
     * Completes the current revision stage and advances to the next configured interval (e.g. 1 -> 3 -> 7 -> 21d),
     * awarding idempotent XP for the completed revision stage.
     */
    suspend fun completeRevision(item: RevisionItemEntity) = withContext(Dispatchers.IO) {
        val todayStr = DateUtils.todayDateString()
        val settings = db.userSettingsDao().getSettingsSync()
        val intervals = settings?.parsedRevisionIntervals ?: SpacedRevisionManager.DEFAULT_INTERVALS

        val advanced = SpacedRevisionManager.advanceStage(
            item = item,
            intervals = intervals,
            todayDateStr = todayStr
        )
        db.revisionItemDao().updateRevision(advanced)

        // Update linked chapter's revision metadata if present
        val linkedChapter = db.chapterDao().getChapterById(item.chapterId)
        if (linkedChapter != null) {
            saveChapter(
                linkedChapter.copy(
                    lastStudiedDate = todayStr,
                    revisionStage = if (advanced.isCompleted) intervals.size + 1 else advanced.stage,
                    nextRevisionDate = if (advanced.isCompleted) "" else advanced.effectiveDueDate
                )
            )
        }

        awardIdempotentXp(
            eventKey = ProgressionManager.revisionEventKey(item.id, item.stage, todayStr),
            category = "REVISION",
            description = "Completed Stage ${item.stage} revision: ${item.chapterName}",
            requestedXp = ProgressionManager.XP_REVISION_COMPLETED,
            dateStr = todayStr
        )
    }

    suspend fun snoozeRevision(item: RevisionItemEntity, days: Int) = withContext(Dispatchers.IO) {
        val snoozed = SpacedRevisionManager.snoozeRevision(item, days)
        db.revisionItemDao().updateRevision(snoozed)
        val linkedChapter = db.chapterDao().getChapterById(item.chapterId)
        if (linkedChapter != null) {
            saveChapter(linkedChapter.copy(nextRevisionDate = snoozed.effectiveDueDate))
        }
    }

    suspend fun rescheduleRevision(item: RevisionItemEntity, newDateStr: String) = withContext(Dispatchers.IO) {
        val rescheduled = SpacedRevisionManager.rescheduleRevision(item, newDateStr)
        db.revisionItemDao().updateRevision(rescheduled)
        val linkedChapter = db.chapterDao().getChapterById(item.chapterId)
        if (linkedChapter != null) {
            saveChapter(linkedChapter.copy(nextRevisionDate = rescheduled.effectiveDueDate))
        }
    }

    suspend fun deleteRevision(id: Long) = withContext(Dispatchers.IO) {
        db.revisionItemDao().deleteRevision(id)
    }

    suspend fun updateSettings(settings: UserSettingsEntity) = withContext(Dispatchers.IO) {
        db.userSettingsDao().insertOrUpdate(settings)
    }

    suspend fun startFocusSession(
        studyDurationMinutes: Int,
        permittedBreaks: Int,
        breakDurationMinutes: Int,
        blockedPackagesCsv: String,
        activeTaskName: String,
        extraSeconds: Int = 0
    ) = withContext(Dispatchers.IO) {
        val session = FocusGuardManager.startSession(
            studyDurationMinutes = studyDurationMinutes,
            permittedBreaks = permittedBreaks,
            breakDurationMinutes = breakDurationMinutes,
            blockedPackagesCsv = blockedPackagesCsv,
            activeTaskName = activeTaskName,
            extraSeconds = extraSeconds
        )
        db.focusSessionDao().insertOrUpdate(session)
    }

    suspend fun takeFocusBreak() = withContext(Dispatchers.IO) {
        val current = db.focusSessionDao().getActiveSessionSync() ?: return@withContext
        if (current.breaksRemaining > 0 && (current.sessionStatus == "RUNNING" || current.sessionStatus == "PAUSED")) {
            val updated = FocusGuardManager.takeBreak(current)
            db.focusSessionDao().insertOrUpdate(updated)
        }
    }

    suspend fun endFocusBreak() = withContext(Dispatchers.IO) {
        val current = db.focusSessionDao().getActiveSessionSync() ?: return@withContext
        val updated = FocusGuardManager.endBreak(current)
        db.focusSessionDao().insertOrUpdate(updated)
    }

    suspend fun pauseFocusSession() = withContext(Dispatchers.IO) {
        val current = db.focusSessionDao().getActiveSessionSync() ?: return@withContext
        val updated = FocusGuardManager.pauseSession(current)
        db.focusSessionDao().insertOrUpdate(updated)
    }

    suspend fun resumeFocusSession() = withContext(Dispatchers.IO) {
        val current = db.focusSessionDao().getActiveSessionSync() ?: return@withContext
        val updated = FocusGuardManager.resumeSession(current)
        db.focusSessionDao().insertOrUpdate(updated)
    }

    suspend fun cancelFocusSession() = withContext(Dispatchers.IO) {
        val current = db.focusSessionDao().getActiveSessionSync() ?: return@withContext
        val updated = FocusGuardManager.cancelSession(current)
        db.focusSessionDao().insertOrUpdate(updated)
    }

    /**
     * Completes an active Focus Session once and prevents duplicate completion rewards.
     */
    suspend fun completeFocusSession(): FocusSessionEntity? = withContext(Dispatchers.IO) {
        val current = db.focusSessionDao().getActiveSessionSync() ?: return@withContext null
        if (current.sessionStatus == "COMPLETED" || current.sessionStatus == "CANCELLED") {
            return@withContext null
        }
        val updated = FocusGuardManager.completeSession(current)
        db.focusSessionDao().insertOrUpdate(updated)

        val todayStr = DateUtils.todayDateString()
        recordStudySession(
            date = todayStr,
            studyMinutes = current.studyDurationMinutes,
            breakMinutes = (current.permittedBreaks - current.breaksRemaining).coerceAtLeast(0) * current.breakDurationMinutes
        )

        awardIdempotentXp(
            eventKey = ProgressionManager.focusSessionEventKey(current.sessionStartTimeMillis),
            category = "FOCUS",
            description = "Completed ${current.studyDurationMinutes}m Focus Session: ${current.activeTaskName}",
            requestedXp = ProgressionManager.XP_FOCUS_SESSION_COMPLETED,
            dateStr = todayStr
        )

        updated
    }
}
