package com.example.data

import kotlinx.coroutines.flow.Flow

class CaddyRepository(private val database: CaddyDatabase) {
    val allCourses: Flow<List<GolfCourse>> = database.golfCourseDao().getAllCourses()

    fun getHolesForCourse(courseId: Long): Flow<List<GolfHole>> = 
        database.golfHoleDao().getHolesForCourse(courseId)

    val allClubs: Flow<List<Club>> = database.clubDao().getAllClubs()

    val allLogs: Flow<List<SwingLog>> = database.swingLogDao().getAllLogs()

    suspend fun insertCourse(course: GolfCourse) = 
        database.golfCourseDao().insertCourse(course)

    suspend fun insertHole(hole: GolfHole) = 
        database.golfHoleDao().insertHole(hole)

    suspend fun insertClub(club: Club) = 
        database.clubDao().insertClub(club)

    suspend fun deleteClub(id: Long) = 
        database.clubDao().deleteClubById(id)

    suspend fun insertLog(log: SwingLog) = 
        database.swingLogDao().insertLog(log)

    suspend fun deleteLog(id: Long) = 
        database.swingLogDao().deleteLogById(id)

    suspend fun clearLogs() = 
        database.swingLogDao().clearLogs()
}
