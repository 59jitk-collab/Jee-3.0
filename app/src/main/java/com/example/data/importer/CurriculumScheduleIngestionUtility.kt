package com.example.data.importer

import com.example.data.local.AppDatabase
import com.example.data.local.entities.ChapterEntity
import com.example.data.local.entities.DailyScheduleEntity
import com.example.data.local.entities.MasteryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class CurriculumStandardSpec(
    val canonicalSubject: String,
    val canonicalName: String,
    val keywords: List<String>,
    val defaultLectures: Int?,
    val defaultDpp: Int?,
    val defaultPyq: Int?,
    val defaultDiby: Int? = if (defaultLectures != null) 20 else null,
    val defaultWorksheets: Int? = if (defaultLectures != null) 2 else null
)

data class CurriculumIngestionReport(
    val validatedChapters: List<ChapterEntity>,
    val mappedSchedules: List<DailyScheduleEntity>,
    val curriculumMatchedCount: Int,
    val customChaptersCount: Int,
    val totalRemainingLectures: Int,
    val totalRemainingProblems: Int,
    val assignedStudyDaysCount: Int,
    val warnings: List<String>,
    val summaryMessage: String
) {
    val scheduledDaysUpdated: Int get() = assignedStudyDaysCount
    val totalLecturesScheduled: Int get() = totalRemainingLectures
}

/**
 * Room-based data ingestion utility that:
 * 1. Parses raw chapter data from .xlsx spreadsheets (OpenXML ZIP), CSV, or JSON.
 * 2. Validates and normalizes chapters against JEE Main & Advanced curriculum standards.
 * 3. Maps the validated chapter backlog into the 80-day schedule database (DailyScheduleEntity)
 *    so each day receives an interleaved Physics, Chemistry, and Mathematics study plan.
 */
object CurriculumScheduleIngestionUtility {

    private val curriculumStandards: List<CurriculumStandardSpec> by lazy {
        BacklogImportExport.getStandardJeeTemplate().map { template ->
            val cleanBase = template.chapterName
                .replace("(Organic)", "", ignoreCase = true)
                .trim()
            val words = cleanBase.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 && it !in setOf("and", "the", "for") }
            CurriculumStandardSpec(
                canonicalSubject = template.subject,
                canonicalName = template.chapterName,
                keywords = words,
                defaultLectures = template.totalLectures,
                defaultDpp = template.dppTotal,
                defaultPyq = template.pyqTotal,
                defaultDiby = template.dibyTotal,
                defaultWorksheets = template.worksheetsTotal
            )
        }
    }

    /**
     * Normalizes subject aliases (e.g. "Phy", "Organic Chemistry", "Maths") to standard JEE subjects.
     */
    fun normalizeSubject(rawSubject: String?, chapterHint: String = ""): String {
        val s = (rawSubject ?: "").trim().lowercase()
        val hint = chapterHint.trim().lowercase()
        return when {
            s.startsWith("phy") || s.contains("mechanics") || s.contains("optics") || s.contains("electrostatics") -> "Physics"
            s.startsWith("chem") || s.contains("organic") || s.contains("inorganic") || s.contains("physical") -> "Chemistry"
            s.startsWith("math") || s.contains("calculus") || s.contains("algebra") || s.contains("coordinate") -> "Mathematics"
            hint.contains("goc") || hint.contains("hydrocarbon") || hint.contains("mole") ||
                    hint.contains("atomic") || hint.contains("bonding") || hint.contains("equilibrium") ||
                    hint.contains("electrochemistry") || hint.contains("kinetics") || hint.contains("coordination") -> "Chemistry"
            hint.contains("integral") || hint.contains("integration") || hint.contains("matrix") ||
                    hint.contains("matrices") || hint.contains("conic") || hint.contains("quadratic") ||
                    hint.contains("binomial") || hint.contains("probability") || hint.contains("complex") -> "Mathematics"
            else -> "Physics"
        }
    }

    /**
     * Matches a raw chapter name against the JEE curriculum standards registry.
     */
    fun findCurriculumMatch(subject: String, rawChapterName: String): CurriculumStandardSpec? {
        val normalizedSub = normalizeSubject(subject, rawChapterName)
        val cleanInput = rawChapterName.trim().lowercase()
        if (cleanInput.isEmpty()) return null

        val subjectCandidates = curriculumStandards.filter {
            it.canonicalSubject.equals(normalizedSub, ignoreCase = true)
        }

        // 1. Exact or substring match
        subjectCandidates.firstOrNull { spec ->
            val specClean = spec.canonicalName.replace("(Organic)", "", ignoreCase = true).trim().lowercase()
            specClean == cleanInput || specClean.contains(cleanInput) || cleanInput.contains(specClean)
        }?.let { return it }

        // 2. Keyword overlap match
        return subjectCandidates.maxByOrNull { spec ->
            spec.keywords.count { kw -> cleanInput.contains(kw) }
        }?.takeIf { best ->
            best.keywords.count { kw -> cleanInput.contains(kw) } >= 1
        }
    }

    /**
     * Validates a list of raw chapters against JEE curriculum standards, clamping invalid numbers
     * and filling missing curriculum metadata while preserving unassessed Organic Chemistry chapters.
     */
    fun validateAgainstCurriculumStandards(
        rawChapters: List<ChapterEntity>,
        warningsOut: MutableList<String> = mutableListOf()
    ): Pair<List<ChapterEntity>, Int> {
        val validated = mutableListOf<ChapterEntity>()
        val seenKeys = mutableSetOf<String>()
        var matchedCurriculumCount = 0

        rawChapters.forEachIndexed { idx, raw ->
            val rawName = raw.chapterName.trim()
            if (rawName.isBlank()) {
                warningsOut.add("Row ${idx + 1}: Skipped blank chapter name.")
                return@forEachIndexed
            }

            val normSubject = normalizeSubject(raw.subject, rawName)
            val curriculumMatch = findCurriculumMatch(normSubject, rawName)
            if (curriculumMatch != null) {
                matchedCurriculumCount++
            } else {
                warningsOut.add("Custom chapter '$rawName' ($normSubject) accepted with curriculum bounds validation.")
            }

            val finalSubject = curriculumMatch?.canonicalSubject ?: normSubject
            val dedupKey = "${finalSubject.lowercase()}::${rawName.lowercase()}"
            if (!seenKeys.add(dedupKey)) {
                warningsOut.add("Duplicate chapter '$rawName' ($finalSubject) merged.")
                return@forEachIndexed
            }

            val isOrganicUnassessed = rawName.contains("organic", ignoreCase = true) ||
                    curriculumMatch?.defaultLectures == null && raw.totalLectures == null

            val resolvedTotalLectures: Int? = when {
                raw.totalLectures != null && raw.totalLectures > 0 -> raw.totalLectures.coerceIn(1, 35)
                isOrganicUnassessed -> null
                curriculumMatch?.defaultLectures != null -> curriculumMatch.defaultLectures
                else -> 8
            }

            val resolvedCompletedLectures = if (resolvedTotalLectures != null) {
                raw.completedLectures.coerceIn(0, resolvedTotalLectures)
            } else {
                0
            }

            val resolvedDppTotal = when {
                raw.dppTotal != null && raw.dppTotal >= 0 -> raw.dppTotal.coerceIn(0, 50)
                resolvedTotalLectures == null -> null
                curriculumMatch?.defaultDpp != null -> curriculumMatch.defaultDpp
                else -> 6
            }
            val resolvedDppDone = if (resolvedDppTotal != null) {
                (raw.dppDone ?: 0).coerceIn(0, resolvedDppTotal)
            } else null

            val resolvedPyqTotal = when {
                raw.pyqTotal != null && raw.pyqTotal >= 0 -> raw.pyqTotal.coerceIn(0, 250)
                resolvedTotalLectures == null -> null
                curriculumMatch?.defaultPyq != null -> curriculumMatch.defaultPyq
                else -> 45
            }
            val resolvedPyqDone = if (resolvedPyqTotal != null) {
                (raw.pyqDone ?: 0).coerceIn(0, resolvedPyqTotal)
            } else null

            val resolvedDibyTotal = when {
                raw.dibyTotal != null && raw.dibyTotal >= 0 -> raw.dibyTotal.coerceIn(0, 100)
                resolvedTotalLectures == null -> null
                curriculumMatch?.defaultDiby != null -> curriculumMatch.defaultDiby
                else -> 20
            }
            val resolvedDibyDone = if (resolvedDibyTotal != null) {
                (raw.dibyDone ?: 0).coerceIn(0, resolvedDibyTotal)
            } else null

            val resolvedWorksheetsTotal = when {
                raw.worksheetsTotal != null && raw.worksheetsTotal >= 0 -> raw.worksheetsTotal.coerceIn(0, 20)
                resolvedTotalLectures == null -> null
                curriculumMatch?.defaultWorksheets != null -> curriculumMatch.defaultWorksheets
                else -> 2
            }
            val resolvedWorksheetsDone = if (resolvedWorksheetsTotal != null) {
                (raw.worksheetsDone ?: 0).coerceIn(0, resolvedWorksheetsTotal)
            } else null

            val resolvedStatus = when {
                resolvedTotalLectures == null -> "NOT_ASSESSED"
                resolvedCompletedLectures >= resolvedTotalLectures -> "COMPLETED"
                resolvedCompletedLectures > 0 || (resolvedDppDone ?: 0) > 0 || (resolvedPyqDone ?: 0) > 0 -> "IN_PROGRESS"
                else -> "NOT_STARTED"
            }

            val resolvedMastery = MasteryLevel.fromKey(raw.masteryLevel).dbKey

            validated.add(
                raw.copy(
                    subject = finalSubject,
                    chapterName = rawName,
                    orderIndex = validated.size + 1,
                    totalLectures = resolvedTotalLectures,
                    completedLectures = resolvedCompletedLectures,
                    dppTotal = resolvedDppTotal,
                    dppDone = resolvedDppDone,
                    pyqTotal = resolvedPyqTotal,
                    pyqDone = resolvedPyqDone,
                    dibyTotal = resolvedDibyTotal,
                    dibyDone = resolvedDibyDone,
                    classProblemsTotal = if (resolvedTotalLectures != null) (raw.classProblemsTotal ?: 30) else null,
                    classProblemsDone = if (resolvedTotalLectures != null) (raw.classProblemsDone ?: 0) else null,
                    worksheetsTotal = resolvedWorksheetsTotal,
                    worksheetsDone = resolvedWorksheetsDone,
                    status = resolvedStatus,
                    masteryLevel = resolvedMastery
                )
            )
        }

        return Pair(validated, matchedCurriculumCount)
    }

    /**
     * Maps validated chapters into the 80-day schedule database so every day in the 80-day plan
     * receives a balanced, interleaved Physics + Chemistry + Mathematics chapter schedule.
     */
    fun mapChaptersInto80DaySchedules(
        validatedChapters: List<ChapterEntity>,
        existingSchedules: List<DailyScheduleEntity> = emptyList()
    ): List<DailyScheduleEntity> {
        val baseSchedules = if (existingSchedules.size >= 80) {
            existingSchedules.sortedBy { it.dayNumber }
        } else {
            BacklogImportExport.generate80DaySchedules()
        }

        if (validatedChapters.isEmpty()) return baseSchedules

        data class LectureUnit(
            val subject: String,
            val chapterName: String,
            val lectureNumber: Int,
            val totalLectures: Int,
            val problemsPerLecture: Int
        )

        fun buildSubjectQueue(subject: String): ArrayDeque<LectureUnit> {
            val queue = ArrayDeque<LectureUnit>()
            val subChapters = validatedChapters.filter { it.subject.equals(subject, ignoreCase = true) }
            for (ch in subChapters) {
                val totalLecs = ch.totalLectures
                if (totalLecs != null && totalLecs > 0) {
                    val remainingLecs = (totalLecs - ch.completedLectures).coerceAtLeast(0)
                    val remDpp = ((ch.dppTotal ?: 0) - (ch.dppDone ?: 0)).coerceAtLeast(0) * 5
                    val remPyq = ((ch.pyqTotal ?: 0) - (ch.pyqDone ?: 0)).coerceAtLeast(0)
                    val probsPerLec = if (remainingLecs > 0) {
                        ((remDpp + remPyq) / remainingLecs).coerceIn(6, 20)
                    } else 8
                    for (lecNum in (ch.completedLectures + 1)..totalLecs) {
                        queue.addLast(
                            LectureUnit(
                                subject = ch.subject,
                                chapterName = ch.chapterName,
                                lectureNumber = lecNum,
                                totalLectures = totalLecs,
                                problemsPerLecture = probsPerLec
                            )
                        )
                    }
                } else {
                    // Unassessed chapter (e.g., Organic Chemistry): allocate 2 structured review slots
                    for (slot in 1..2) {
                        queue.addLast(
                            LectureUnit(
                                subject = ch.subject,
                                chapterName = ch.chapterName,
                                lectureNumber = slot,
                                totalLectures = 2,
                                problemsPerLecture = 8
                            )
                        )
                    }
                }
            }
            return queue
        }

        val physQueue = buildSubjectQueue("Physics")
        val chemQueue = buildSubjectQueue("Chemistry")
        val mathQueue = buildSubjectQueue("Mathematics")
        val queues = listOf(physQueue, chemQueue, mathQueue)

        val totalRemainingUnits = physQueue.size + chemQueue.size + mathQueue.size
        val nonSundayCount = baseSchedules.count { !it.isSunday }.coerceAtLeast(1)
        val recommendedDailyLectures = ((totalRemainingUnits + nonSundayCount - 1) / nonSundayCount).coerceIn(3, 6)

        var rotationIdx = 0
        val currentWeekChapters = linkedSetOf<String>()

        // Also prepare a fallback revision cycle once primary backlog lectures finish in later days
        val allChapterLabels = validatedChapters.map { "${it.subject}: ${it.chapterName}" }
        var revisionCycleIdx = 0

        return baseSchedules.map { daySchedule ->
            if (daySchedule.isSunday) {
                val weekReviewSummary = if (currentWeekChapters.isNotEmpty()) {
                    currentWeekChapters.take(4).joinToString(", ")
                } else {
                    allChapterLabels.take(3).joinToString(", ")
                }
                currentWeekChapters.clear()
                daySchedule.copy(
                    targetLectures = 1,
                    targetProblems = 20,
                    assignedChapterNames = "Sunday Mock Test & Audit, Weekly Revision ($weekReviewSummary)",
                    notes = "3-Hour Sunday Mock Test + Mistake Notebook Audit for: $weekReviewSummary"
                )
            } else {
                val pickedUnits = mutableListOf<LectureUnit>()
                var attempts = 0
                while (pickedUnits.size < recommendedDailyLectures &&
                    (physQueue.isNotEmpty() || chemQueue.isNotEmpty() || mathQueue.isNotEmpty()) &&
                    attempts < 18
                ) {
                    val q = queues[rotationIdx % queues.size]
                    rotationIdx++
                    attempts++
                    if (q.isNotEmpty()) {
                        pickedUnits.add(q.removeFirst())
                    }
                }

                if (pickedUnits.isNotEmpty()) {
                    // Group consecutive lectures of the same chapter on the same day for clean display
                    val grouped = pickedUnits.groupBy { "${it.subject}: ${it.chapterName}" }
                    val assignedLabels = grouped.map { (chKey, units) ->
                        currentWeekChapters.add(chKey)
                        val firstLec = units.first().lectureNumber
                        val lastLec = units.last().lectureNumber
                        if (firstLec == lastLec) {
                            "$chKey (L$firstLec)"
                        } else {
                            "$chKey (L$firstLec-L$lastLec)"
                        }
                    }
                    val computedTargetProblems = pickedUnits.sumOf { it.problemsPerLecture }.coerceIn(20, 60)
                    daySchedule.copy(
                        targetLectures = pickedUnits.size.coerceAtLeast(1),
                        targetProblems = computedTargetProblems,
                        assignedChapterNames = assignedLabels.joinToString(", "),
                        notes = "Day ${daySchedule.dayNumber} Plan: ${assignedLabels.joinToString(" • ")}"
                    )
                } else {
                    // All primary backlog lectures allocated -> High-Yield PYQ & Mock Revision Sprint
                    val revChapters = if (allChapterLabels.isNotEmpty()) {
                        val c1 = allChapterLabels[revisionCycleIdx % allChapterLabels.size]
                        val c2 = allChapterLabels[(revisionCycleIdx + 1) % allChapterLabels.size]
                        revisionCycleIdx += 2
                        listOf(c1, c2).distinct()
                    } else {
                        listOf("Physics & Math Full-Syllabus PYQ Sprint")
                    }
                    daySchedule.copy(
                        targetLectures = 3,
                        targetProblems = 35,
                        assignedChapterNames = revChapters.joinToString(", ") { "$it (PYQ Sprint)" },
                        notes = "Final Sprint Revision & PYQ Drill: ${revChapters.joinToString(" • ")}"
                    )
                }
            }
        }
    }

    /**
     * Parses an .xlsx spreadsheet stream (OpenXML ZIP archive) directly into raw ChapterEntity records.
     * Supports multi-sheet workbooks (e.g., Physics, Chemistry, Mathematics tabs or a unified Backlog sheet).
     */
    fun parseXlsxStream(inputStream: InputStream): List<ChapterEntity> {
        return parseXlsxBytes(inputStream.readBytes())
    }

    fun parseXlsxBytes(bytes: ByteArray): List<ChapterEntity> {
        if (bytes.isEmpty()) return emptyList()

        // Check if user selected a plain text CSV/JSON file with .xlsx extension or via generic picker
        if (bytes.size >= 2 && (bytes[0].toInt() != 0x50 || bytes[1].toInt() != 0x4B)) {
            val rawText = bytes.toString(Charsets.UTF_8)
            return BacklogImportExport.parseChaptersFromJson(rawText)
        }

        val sharedStrings = mutableListOf<String>()
        val sheetNamesByFile = mutableMapOf<String, String>()
        val worksheetXmlByFile = linkedMapOf<String, String>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    when {
                        name.equals("xl/sharedStrings.xml", ignoreCase = true) -> {
                            val xml = zis.readBytes().toString(Charsets.UTF_8)
                            sharedStrings.addAll(parseSharedStringsXml(xml))
                        }
                        name.equals("xl/workbook.xml", ignoreCase = true) -> {
                            val xml = zis.readBytes().toString(Charsets.UTF_8)
                            sheetNamesByFile.putAll(parseWorkbookSheetNames(xml))
                        }
                        name.startsWith("xl/worksheets/sheet", ignoreCase = true) && name.endsWith(".xml", ignoreCase = true) -> {
                            val xml = zis.readBytes().toString(Charsets.UTF_8)
                            val shortKey = name.substringAfterLast('/').removeSuffix(".xml").lowercase()
                            worksheetXmlByFile[shortKey] = xml
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val extractedChapters = mutableListOf<ChapterEntity>()
        var orderIdx = 1

        for ((sheetKey, sheetXml) in worksheetXmlByFile) {
            val sheetTitle = sheetNamesByFile[sheetKey] ?: sheetKey
            val rows = parseWorksheetRows(sheetXml, sharedStrings)
            if (rows.isEmpty()) continue

            var subjectCol = -1
            var chapterCol = -1
            var totalLecCol = -1
            var compLecCol = -1
            var dppCol = -1
            var pyqCol = -1
            var dibyCol = -1
            var worksheetCol = -1
            var statusCol = -1
            var masteryCol = -1
            var headerRowIndex = -1

            // Detect header row in the first 10 rows
            for (rIdx in 0 until minOf(10, rows.size)) {
                val row = rows[rIdx]
                val joined = row.joinToString(" ").lowercase()
                if (joined.contains("chapter") || joined.contains("topic") || joined.contains("subject") || joined.contains("lecture")) {
                    headerRowIndex = rIdx
                    row.forEachIndexed { cIdx, cellText ->
                        val h = cellText.trim().lowercase()
                        when {
                            h.contains("subject") -> subjectCol = cIdx
                            (h.contains("chapter") || h.contains("topic") || h == "name") && chapterCol == -1 -> chapterCol = cIdx
                            (h.contains("total") && h.contains("lec")) || h == "lectures" || h == "totallectures" || h == "lec" -> totalLecCol = cIdx
                            (h.contains("comp") || h.contains("done")) && (h.contains("lec") || compLecCol == -1) -> compLecCol = cIdx
                            h.contains("dpp") -> dppCol = cIdx
                            h.contains("pyq") -> pyqCol = cIdx
                            h.contains("diby") -> dibyCol = cIdx
                            h.contains("worksheet") -> worksheetCol = cIdx
                            h.contains("status") -> statusCol = cIdx
                            h.contains("mastery") -> masteryCol = cIdx
                        }
                    }
                    break
                }
            }

            val startRow = if (headerRowIndex >= 0) headerRowIndex + 1 else 0
            val sheetImpliedSubject = when {
                sheetTitle.contains("phys", ignoreCase = true) -> "Physics"
                sheetTitle.contains("chem", ignoreCase = true) -> "Chemistry"
                sheetTitle.contains("math", ignoreCase = true) -> "Mathematics"
                else -> null
            }

            for (rIdx in startRow until rows.size) {
                val row = rows[rIdx]
                if (row.all { it.isBlank() }) continue

                val rawSubject: String
                val rawChapterName: String
                val rawTotalLecs: Int?
                val rawCompLecs: Int
                val rawDpp: Int?
                val rawPyq: Int?
                val rawDiby: Int?
                val rawWorksheets: Int?
                val rawStatus: String?
                val rawMastery: String?

                if (chapterCol >= 0) {
                    rawChapterName = row.getOrNull(chapterCol)?.trim().orEmpty()
                    rawSubject = if (subjectCol >= 0) {
                        row.getOrNull(subjectCol)?.trim().orEmpty().ifBlank { sheetImpliedSubject ?: "Physics" }
                    } else {
                        sheetImpliedSubject ?: normalizeSubject(null, rawChapterName)
                    }
                    rawTotalLecs = if (totalLecCol >= 0) row.getOrNull(totalLecCol)?.trim()?.toDoubleOrNull()?.toInt() else null
                    rawCompLecs = if (compLecCol >= 0) row.getOrNull(compLecCol)?.trim()?.toDoubleOrNull()?.toInt() ?: 0 else 0
                    rawDpp = if (dppCol >= 0) row.getOrNull(dppCol)?.trim()?.toDoubleOrNull()?.toInt() else null
                    rawPyq = if (pyqCol >= 0) row.getOrNull(pyqCol)?.trim()?.toDoubleOrNull()?.toInt() else null
                    rawDiby = if (dibyCol >= 0) row.getOrNull(dibyCol)?.trim()?.toDoubleOrNull()?.toInt() else null
                    rawWorksheets = if (worksheetCol >= 0) row.getOrNull(worksheetCol)?.trim()?.toDoubleOrNull()?.toInt() else null
                    rawStatus = if (statusCol >= 0) row.getOrNull(statusCol)?.trim() else null
                    rawMastery = if (masteryCol >= 0) row.getOrNull(masteryCol)?.trim() else null
                } else {
                    // Positional fallback: either [Subject, Chapter, TotalLec, DoneLec, DPP, PYQ]
                    // or [Chapter, TotalLec, DoneLec, DPP, PYQ] when sheet is named Physics/Chemistry/Mathematics
                    val firstCell = row.getOrNull(0)?.trim().orEmpty()
                    val secondCell = row.getOrNull(1)?.trim().orEmpty()
                    val firstLooksLikeSubject = firstCell.equals("Physics", ignoreCase = true) ||
                            firstCell.equals("Chemistry", ignoreCase = true) ||
                            firstCell.equals("Mathematics", ignoreCase = true) ||
                            firstCell.equals("Maths", ignoreCase = true)

                    if (firstLooksLikeSubject && secondCell.isNotBlank()) {
                        rawSubject = firstCell
                        rawChapterName = secondCell
                        rawTotalLecs = row.getOrNull(2)?.trim()?.toDoubleOrNull()?.toInt()
                        rawCompLecs = row.getOrNull(3)?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                        rawDpp = row.getOrNull(4)?.trim()?.toDoubleOrNull()?.toInt()
                        rawPyq = row.getOrNull(5)?.trim()?.toDoubleOrNull()?.toInt()
                        rawDiby = row.getOrNull(6)?.trim()?.toDoubleOrNull()?.toInt()
                        rawWorksheets = null
                        rawStatus = null
                        rawMastery = null
                    } else if (sheetImpliedSubject != null && firstCell.isNotBlank()) {
                        rawSubject = sheetImpliedSubject
                        rawChapterName = firstCell
                        rawTotalLecs = secondCell.toDoubleOrNull()?.toInt()
                        rawCompLecs = row.getOrNull(2)?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                        rawDpp = row.getOrNull(3)?.trim()?.toDoubleOrNull()?.toInt()
                        rawPyq = row.getOrNull(4)?.trim()?.toDoubleOrNull()?.toInt()
                        rawDiby = null
                        rawWorksheets = null
                        rawStatus = null
                        rawMastery = null
                    } else {
                        continue
                    }
                }

                if (rawChapterName.isBlank() ||
                    rawChapterName.equals("Chapter", ignoreCase = true) ||
                    rawChapterName.equals("ChapterName", ignoreCase = true) ||
                    rawChapterName.equals("Total", ignoreCase = true)
                ) {
                    continue
                }

                extractedChapters.add(
                    ChapterEntity(
                        subject = normalizeSubject(rawSubject, rawChapterName),
                        chapterName = rawChapterName,
                        orderIndex = orderIdx++,
                        totalLectures = rawTotalLecs,
                        completedLectures = rawCompLecs,
                        notesDone = false,
                        dibyTotal = rawDiby,
                        dibyDone = if (rawDiby != null) 0 else null,
                        classProblemsTotal = if (rawTotalLecs != null) 30 else null,
                        classProblemsDone = if (rawTotalLecs != null) 0 else null,
                        dppTotal = rawDpp,
                        dppDone = if (rawDpp != null) 0 else null,
                        pyqTotal = rawPyq,
                        pyqDone = if (rawPyq != null) 0 else null,
                        worksheetsTotal = rawWorksheets,
                        worksheetsDone = if (rawWorksheets != null) 0 else null,
                        status = rawStatus ?: if (rawTotalLecs == null) "NOT_ASSESSED" else "NOT_STARTED",
                        masteryLevel = rawMastery ?: "NOT_ASSESSED"
                    )
                )
            }
        }

        return extractedChapters
    }

    /**
     * Generates a valid OpenXML .xlsx spreadsheet binary (ByteArray) from chapters,
     * allowing full round-trip .xlsx export/import and curriculum schedule ingestion testing.
     */
    fun generateXlsxWorkbookBytes(chapters: List<ChapterEntity>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun writeEntry(path: String, content: String) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            writeEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>""".trimIndent()
            )

            writeEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheets>
                    <sheet name="JEE_Backlog_Tracker" sheetId="1" r:id="rId1" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/>
                  </sheets>
                </workbook>""".trimIndent()
            )

            val rowsXml = StringBuilder()
            // Header row
            rowsXml.append(
                """<row r="1">
                    <c r="A1" t="inlineStr"><is><t>Subject</t></is></c>
                    <c r="B1" t="inlineStr"><is><t>ChapterName</t></is></c>
                    <c r="C1" t="inlineStr"><is><t>TotalLectures</t></is></c>
                    <c r="D1" t="inlineStr"><is><t>CompletedLectures</t></is></c>
                    <c r="E1" t="inlineStr"><is><t>DPP</t></is></c>
                    <c r="F1" t="inlineStr"><is><t>PYQ</t></is></c>
                </row>"""
            )

            chapters.forEachIndexed { idx, ch ->
                val r = idx + 2
                val safeSub = escapeXml(ch.subject)
                val safeName = escapeXml(ch.chapterName)
                rowsXml.append("<row r=\"$r\">")
                rowsXml.append("<c r=\"A$r\" t=\"inlineStr\"><is><t>$safeSub</t></is></c>")
                rowsXml.append("<c r=\"B$r\" t=\"inlineStr\"><is><t>$safeName</t></is></c>")
                if (ch.totalLectures != null) {
                    rowsXml.append("<c r=\"C$r\"><v>${ch.totalLectures}</v></c>")
                }
                rowsXml.append("<c r=\"D$r\"><v>${ch.completedLectures}</v></c>")
                if (ch.dppTotal != null) {
                    rowsXml.append("<c r=\"E$r\"><v>${ch.dppTotal}</v></c>")
                }
                if (ch.pyqTotal != null) {
                    rowsXml.append("<c r=\"F$r\"><v>${ch.pyqTotal}</v></c>")
                }
                rowsXml.append("</row>")
            }

            writeEntry(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>$rowsXml</sheetData>
                </worksheet>""".trimIndent()
            )
        }
        return out.toByteArray()
    }

    /**
     * Full pipeline: Validates raw chapters against JEE curriculum standards, maps them into
     * the 80-day schedule, and returns a complete [CurriculumIngestionReport].
     */
    fun buildIngestionPlan(
        rawChapters: List<ChapterEntity>,
        existingSchedules: List<DailyScheduleEntity> = emptyList()
    ): CurriculumIngestionReport {
        val warnings = mutableListOf<String>()
        val (validated, matchedCount) = validateAgainstCurriculumStandards(rawChapters, warnings)
        val mappedSchedules = mapChaptersInto80DaySchedules(validated, existingSchedules)

        val totalRemainingLecs = validated.sumOf { ch ->
            val total = ch.totalLectures ?: 2
            (total - ch.completedLectures).coerceAtLeast(0)
        }
        val totalRemainingProbs = validated.sumOf { ch ->
            val dppRem = ((ch.dppTotal ?: 0) - (ch.dppDone ?: 0)).coerceAtLeast(0) * 5
            val pyqRem = ((ch.pyqTotal ?: 0) - (ch.pyqDone ?: 0)).coerceAtLeast(0)
            dppRem + pyqRem
        }
        val assignedDays = mappedSchedules.count { it.assignedChapterNames.isNotBlank() }
        val customCount = (validated.size - matchedCount).coerceAtLeast(0)

        val summary = "Validated ${validated.size} chapters ($matchedCount JEE curriculum standard, $customCount custom) • " +
                "Auto-mapped $totalRemainingLecs remaining lectures across $assignedDays days in the 80-Day Schedule."

        return CurriculumIngestionReport(
            validatedChapters = validated,
            mappedSchedules = mappedSchedules,
            curriculumMatchedCount = matchedCount,
            customChaptersCount = customCount,
            totalRemainingLectures = totalRemainingLecs,
            totalRemainingProblems = totalRemainingProbs,
            assignedStudyDaysCount = assignedDays,
            warnings = warnings,
            summaryMessage = summary
        )
    }

    /**
     * Persists the validated chapters and the generated 80-day daily schedules into the Room database.
     */
    suspend fun ingestIntoRoomDatabase(
        db: AppDatabase,
        rawChapters: List<ChapterEntity>,
        replaceExistingChapters: Boolean = true
    ): CurriculumIngestionReport = withContext(Dispatchers.IO) {
        val existingSchedules = db.dailyScheduleDao().getAllDaysSync()
        val report = buildIngestionPlan(rawChapters, existingSchedules)

        if (report.validatedChapters.isNotEmpty()) {
            if (replaceExistingChapters) {
                db.chapterDao().clearAllChapters()
            }
            db.chapterDao().insertAll(report.validatedChapters)
            db.dailyScheduleDao().insertAll(report.mappedSchedules)
        }
        report
    }

    private fun parseSharedStringsXml(xml: String): List<String> {
        val result = mutableListOf<String>()
        val siRegex = Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
        val tRegex = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        for (siMatch in siRegex.findAll(xml)) {
            val inner = siMatch.groupValues[1]
            val sb = StringBuilder()
            for (tMatch in tRegex.findAll(inner)) {
                sb.append(unescapeXml(tMatch.groupValues[1]))
            }
            result.add(sb.toString())
        }
        return result
    }

    private fun parseWorkbookSheetNames(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val sheetRegex = Regex("<sheet\\b[^>]*name=\"([^\"]+)\"[^>]*/>")
        var idx = 1
        for (m in sheetRegex.findAll(xml)) {
            map["sheet$idx"] = unescapeXml(m.groupValues[1])
            idx++
        }
        return map
    }

    private fun parseWorksheetRows(xml: String, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val rowRegex = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellRegex = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
        val refAttrRegex = Regex("\\br=\"([A-Za-z]+)\\d+\"")
        val typeAttrRegex = Regex("\\bt=\"([^\"]+)\"")
        val vTagRegex = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        val tTagRegex = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)

        for (rowMatch in rowRegex.findAll(xml)) {
            val rowInner = rowMatch.groupValues[1]
            val cellsByCol = sortedMapOf<Int, String>()
            var fallbackCol = 0

            for (cellMatch in cellRegex.findAll(rowInner)) {
                val attrs = cellMatch.groupValues[1]
                val cellInner = cellMatch.groupValues[2]
                val colLetters = refAttrRegex.find(attrs)?.groupValues?.get(1)
                val colIndex = if (colLetters != null) excelColumnToIndex(colLetters) else fallbackCol
                fallbackCol = colIndex + 1

                val cellType = typeAttrRegex.find(attrs)?.groupValues?.get(1) ?: ""
                val value = when (cellType) {
                    "s" -> {
                        val sIdx = vTagRegex.find(cellInner)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                        if (sIdx != null && sIdx in sharedStrings.indices) sharedStrings[sIdx] else ""
                    }
                    "inlineStr" -> {
                        tTagRegex.findAll(cellInner).joinToString("") { unescapeXml(it.groupValues[1]) }
                    }
                    else -> {
                        val vText = vTagRegex.find(cellInner)?.groupValues?.get(1)
                            ?: tTagRegex.find(cellInner)?.groupValues?.get(1)
                            ?: ""
                        unescapeXml(vText.trim())
                    }
                }
                cellsByCol[colIndex] = value
            }

            if (cellsByCol.isNotEmpty()) {
                val maxCol = cellsByCol.keys.maxOrNull() ?: 0
                val rowList = (0..maxCol).map { cellsByCol[it] ?: "" }
                rows.add(rowList)
            }
        }
        return rows
    }

    private fun excelColumnToIndex(colLetters: String): Int {
        var result = 0
        for (ch in colLetters.uppercase()) {
            if (ch in 'A'..'Z') {
                result = result * 26 + (ch - 'A' + 1)
            }
        }
        return (result - 1).coerceAtLeast(0)
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun unescapeXml(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}
