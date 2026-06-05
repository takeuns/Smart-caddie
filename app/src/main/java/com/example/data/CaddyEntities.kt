package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "golf_courses")
data class GolfCourse(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val location: String,
    val totalHoles: Int
)

@Entity(tableName = "golf_holes")
data class GolfHole(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    val holeNumber: Int,
    val par: Int,
    val distanceYards: Int,
    // coordinates for map simulation and actual GPS calculations
    val teeLatitude: Double,
    val teeLongitude: Double,
    val greenLatitude: Double,
    val greenLongitude: Double,
    val hazardLatitude: Double?,
    val hazardLongitude: Double?,
    val bunkerLatitude: Double?,
    val bunkerLongitude: Double?,
    val tip: String
)

@Entity(tableName = "clubs")
data class Club(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,         // e.g., "Driver", "7-Iron", "Sand Wedge", "Putter"
    val clubType: String,     // e.g., "WOOD", "HYBRID", "IRON", "WEDGE", "PUTTER"
    val averageCarryDistance: Float, // distance in yards
    val description: String
)

@Entity(tableName = "swing_logs")
data class SwingLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val clubName: String,
    val swingSpeedMph: Float,
    val ballSpeedMph: Float,
    val smashFactor: Float,
    val launchAngleDegrees: Float,
    val spinRateRpm: Float,
    val carryDistanceYards: Float,
    val resultDirection: String, // e.g., "Straight", "Slice", "Hook", "Push", "Pull"
    val notes: String = ""
)
