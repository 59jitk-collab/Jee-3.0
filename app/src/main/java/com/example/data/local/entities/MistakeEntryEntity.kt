package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mistake_entries")
data class MistakeEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subject: String,
    val chapterName: String,
    val testName: String = "Sunday Test Analysis", // Associated Sunday Test or DPP session
    val questionSnippet: String,
    val errorType: String, // "Concept Gap", "Calculation Error", "Time Pressure", "Misread Question", "Formula Forgot", "Trap Option"
    val conceptMissed: String = "", // Specific concept/theorem missed
    val correctTakeaway: String = "", // Solution takeaway
    val lessonLearned: String = "", // Actionable lesson learned for next Sunday test
    val solutionNotes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val reviewCount: Int = 0,
    val isResolved: Boolean = false
)
