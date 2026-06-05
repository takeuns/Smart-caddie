package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Dao
interface GolfCourseDao {
    @Query("SELECT * FROM golf_courses ORDER BY id ASC")
    fun getAllCourses(): Flow<List<GolfCourse>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: GolfCourse): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<GolfCourse>)
}

@Dao
interface GolfHoleDao {
    @Query("SELECT * FROM golf_holes WHERE courseId = :courseId ORDER BY holeNumber ASC")
    fun getHolesForCourse(courseId: Long): Flow<List<GolfHole>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHole(hole: GolfHole)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHoles(holes: List<GolfHole>)
}

@Dao
interface ClubDao {
    @Query("SELECT * FROM clubs ORDER BY averageCarryDistance DESC")
    fun getAllClubs(): Flow<List<Club>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClub(club: Club)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClubs(clubs: List<Club>)

    @Query("DELETE FROM clubs WHERE id = :id")
    suspend fun deleteClubById(id: Long)
}

@Dao
interface SwingLogDao {
    @Query("SELECT * FROM swing_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<SwingLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SwingLog)

    @Query("DELETE FROM swing_logs WHERE id = :id")
    suspend fun deleteLogById(id: Long)

    @Query("DELETE FROM swing_logs")
    suspend fun clearLogs()
}

@Database(entities = [GolfCourse::class, GolfHole::class, Club::class, SwingLog::class], version = 1, exportSchema = false)
abstract class CaddyDatabase : RoomDatabase() {
    abstract fun golfCourseDao(): GolfCourseDao
    abstract fun golfHoleDao(): GolfHoleDao
    abstract fun clubDao(): ClubDao
    abstract fun swingLogDao(): SwingLogDao

    companion object {
        @Volatile
        private var INSTANCE: CaddyDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): CaddyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CaddyDatabase::class.java,
                    "smart_caddy_db"
                )
                .addCallback(CaddyDatabaseCallback(scope))
                .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class CaddyDatabaseCallback(
        private val scope: CoroutineScope
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                scope.launch(Dispatchers.IO) {
                    populateDefaultData(database)
                }
            }
        }

        private suspend fun populateDefaultData(db: CaddyDatabase) {
            // Seed default clubs
            val defaultClubs = listOf(
                Club(name = "드라이버 (1W)", clubType = "WOOD", averageCarryDistance = 220f, description = "티샷용 주요 우드 클럽"),
                Club(name = "3번 우드 (3W)", clubType = "WOOD", averageCarryDistance = 195f, description = "긴 페어웨이나 도그렉 홀 티샷용"),
                Club(name = "4번 유틸 (4H)", clubType = "HYBRID", averageCarryDistance = 175f, description = "치기 편하고 안정적인 롱 유틸리티"),
                Club(name = "5번 아이언", clubType = "IRON", averageCarryDistance = 160f, description = "중장거리 공략용 미들과 롱 사이 아이언"),
                Club(name = "6번 아이언", clubType = "IRON", averageCarryDistance = 150f, description = "중거리용 아이언"),
                Club(name = "7번 아이언", clubType = "IRON", averageCarryDistance = 140f, description = "조작이 편해 세컨샷에 가장 많이 쓰는 표준 아이언"),
                Club(name = "8번 아이언", clubType = "IRON", averageCarryDistance = 130f, description = "짧은 미들 어프로치용 아이언"),
                Club(name = "9번 아이언", clubType = "IRON", averageCarryDistance = 120f, description = "경사지와 정확한 터치를 요하는 어프로치"),
                Club(name = "피칭 웨지 (PW)", clubType = "WEDGE", averageCarryDistance = 105f, description = "그린 핀 직접 공략용 풀스윙 미들 웨지"),
                Club(name = "샌드 웨지 (SW)", clubType = "WEDGE", averageCarryDistance = 80f, description = "벙커 탈출 및 높은 로브샷 전용 웨지"),
                Club(name = "퍼터 (PT)", clubType = "PUTTER", averageCarryDistance = 20f, description = "그린 전용 최종 스윙 도구")
            )
            db.clubDao().insertClubs(defaultClubs)

            // Seed default courses
            val pebbleBeachId = db.golfCourseDao().insertCourse(
                GolfCourse(name = "페블비치 골프 링크스", location = "미국 캘리포니아", totalHoles = 18)
            )
            val jackNicklausId = db.golfCourseDao().insertCourse(
                GolfCourse(name = "잭 니클라우스 GC 코리아", location = "대한민국 인천 송도", totalHoles = 18)
            )
            val southSpringsId = db.golfCourseDao().insertCourse(
                GolfCourse(name = "사우스스프링스 CC", location = "대한민국 경기도 이천", totalHoles = 18)
            )

            // Seed holes for Pebble Beach
            val pebbleBeachHoles = listOf(
                GolfHole(
                    courseId = pebbleBeachId,
                    holeNumber = 1,
                    par = 4,
                    distanceYards = 380,
                    teeLatitude = 36.5685, teeLongitude = -121.9482,
                    greenLatitude = 36.5658, greenLongitude = -121.9472,
                    bunkerLatitude = 36.5663, bunkerLongitude = -121.9474,
                    hazardLatitude = 36.5670, hazardLongitude = -121.9477,
                    tip = "첫 홀은 완만한 미들홀입니다. 슬라이스 바람이 자주 불어 우측 클리크 해저드나 벙커를 피하려면 페어웨이 약간 왼쪽을 공략하시는 것이 바람직합니다."
                ),
                GolfHole(
                    courseId = pebbleBeachId,
                    holeNumber = 2,
                    par = 5,
                    distanceYards = 502,
                    teeLatitude = 36.5658, teeLongitude = -121.9472,
                    greenLatitude = 36.5620, greenLongitude = -121.9458,
                    bunkerLatitude = 36.5635, bunkerLongitude = -121.9463,
                    hazardLatitude = null, hazardLongitude = null,
                    tip = "투온이 가능한 비교적 짧은 롱홀입니다. 세컨 샷 시 페어웨이 군데군데 위치한 크로스 벙커들을 주의하셔서 레이업 하거나 그린 전방 넓은 구역으로 직접 보내세요."
                ),
                GolfHole(
                    courseId = pebbleBeachId,
                    holeNumber = 3,
                    par = 4,
                    distanceYards = 397,
                    teeLatitude = 36.5620, teeLongitude = -121.9458,
                    greenLatitude = 36.5598, greenLongitude = -121.9475,
                    bunkerLatitude = 36.5604, bunkerLongitude = -121.9471,
                    hazardLatitude = null, hazardLongitude = null,
                    tip = "급격하게 왼쪽으로 꺾이는 도그렉 홀입니다. 페어웨이가 좁으므로 드라이버 대신 3번 우드나 하이브리드로 잘라 치는 티샷 전략이 안전하고 영리합니다."
                ),
                GolfHole(
                    courseId = pebbleBeachId,
                    holeNumber = 4,
                    par = 4,
                    distanceYards = 327,
                    teeLatitude = 36.5598, teeLongitude = -121.9475,
                    greenLatitude = 36.5574, greenLongitude = -121.9465,
                    bunkerLatitude = 36.5580, bunkerLongitude = -121.9468,
                    hazardLatitude = 36.5576, hazardLongitude = -121.9460,
                    tip = "대단히 멋진 짧은 파4 홀이지만 우측 전체가 가파른 모래 절벽과 바다 해저드입니다. 티샷은 무조건 페어웨이 중앙 서쪽 언덕 라인을 타게 안전하게 유지하십시오."
                ),
                GolfHole(
                    courseId = pebbleBeachId,
                    holeNumber = 5,
                    par = 3,
                    distanceYards = 192,
                    teeLatitude = 36.5574, teeLongitude = -121.9465,
                    greenLatitude = 36.5558, greenLongitude = -121.9450,
                    bunkerLatitude = 36.5559, bunkerLongitude = -121.9453,
                    hazardLatitude = null, hazardLongitude = null,
                    tip = "바다를 향해 길게 뻗은 시각적으로 까다로운 파3홀입니다. 늘 강력한 맞바람이 부는 구역이므로 클럽을 한 클럽 넉넉하게 잡는 슬기로운 선택이 어프로치 성공 비법입니다."
                )
            )
            db.golfHoleDao().insertHoles(pebbleBeachHoles)

            // Seed holes for Jack Nicklaus Korea
            val jackNicklausHoles = listOf(
                GolfHole(
                    courseId = jackNicklausId,
                    holeNumber = 1,
                    par = 4,
                    distanceYards = 370,
                    teeLatitude = 37.3789, teeLongitude = 126.6345,
                    greenLatitude = 37.3812, greenLongitude = 126.6360,
                    bunkerLatitude = 37.3802, bunkerLongitude = 126.6352,
                    hazardLatitude = null, hazardLongitude = null,
                    tip = "매우 위협적인 우측 워터 해저드가 있습니다. 티샷 시 벙커 좌측 사이공간으로 부드러운 하이드로 구질을 그리며 스윙하기 딱 좋습니다."
                ),
                GolfHole(
                    courseId = jackNicklausId,
                    holeNumber = 2,
                    par = 4,
                    distanceYards = 410,
                    teeLatitude = 37.3812, teeLongitude = 126.6360,
                    greenLatitude = 37.3838, greenLongitude = 126.6378,
                    bunkerLatitude = 37.3824, bunkerLongitude = 126.6370,
                    hazardLatitude = 37.3830, hazardLongitude = 126.6385,
                    tip = "이 코스에서 최고로 명망 높은 난도의 핸디캡 1번 파4 홀입니다. 그린으로 갈수록 오르막이 심해 투온을 노리기보다 어프로치로 붙여 파(Par)를 수비하는 세이브 전략이 주효합니다."
                ),
                GolfHole(
                    courseId = jackNicklausId,
                    holeNumber = 3,
                    par = 5,
                    distanceYards = 540,
                    teeLatitude = 37.3838, teeLongitude = 126.6378,
                    greenLatitude = 37.3860, greenLongitude = 126.6415,
                    bunkerLatitude = 37.3848, bunkerLongitude = 126.6398,
                    hazardLatitude = null, hazardLongitude = null,
                    tip = "구불구불 길게 파도치는 언듈레이션의 롱 파5 홀입니다. 쓰리온을 기치로 각 샷의 낙하 지점을 페어웨이 기복이 편평한 우측 공간 위주로 가져가는 영리함이 버디 기회를 살릴 수 있습니다."
                )
            )
            db.golfHoleDao().insertHoles(jackNicklausHoles)

            // Seed holes for South Springs CC
            val southSpringsHoles = listOf(
                GolfHole(
                    courseId = southSpringsId,
                    holeNumber = 1,
                    par = 4,
                    distanceYards = 350,
                    teeLatitude = 37.1950, teeLongitude = 127.3910,
                    greenLatitude = 37.1970, greenLongitude = 127.3930,
                    bunkerLatitude = 37.1960, bunkerLongitude = 127.3920,
                    hazardLatitude = null, hazardLongitude = null,
                    tip = "비교적 페어웨이가 곧게 뻗어 편하게 드라이버 스몰 런업을 시전할 수 있습니다. 페어웨이 중지 잔디의 탄력을 이용해 중앙 뒤 벙커 전방에 드롭시키는 걸 목적으로 하세요."
                ),
                GolfHole(
                    courseId = southSpringsId,
                    holeNumber = 2,
                    par = 3,
                    distanceYards = 155,
                    teeLatitude = 37.1970, teeLongitude = 127.3930,
                    greenLatitude = 37.1980, greenLongitude = 127.3945,
                    bunkerLatitude = 37.1978, bunkerLongitude = 127.3942,
                    hazardLatitude = 37.1975, hazardLongitude = 127.3940,
                    tip = "그린 바로 우측 앞단에 워터해저드와 거대한 비치벙커(Beach Bunker)가 도사립니다. 한 템포 과감한 핀 좌우 쏠림을 방어해 우물 안 샷처럼 그린 중앙 안전 스팟을 찍으셔야 오비 위기를 탈피합니다."
                )
            )
            db.golfHoleDao().insertHoles(southSpringsHoles)

            // Seed raw logs so User has dynamic visual content on first boot!
            val historicalLogs = listOf(
                SwingLog(clubName = "드라이버 (1W)", swingSpeedMph = 101f, ballSpeedMph = 146.5f, smashFactor = 1.45f, launchAngleDegrees = 13.8f, spinRateRpm = 2550f, carryDistanceYards = 227f, resultDirection = "Straight", notes = "굉장히 균형감 있는 티샷. 템포가 잘 유지됨."),
                SwingLog(clubName = "7번 아이언", swingSpeedMph = 83f, ballSpeedMph = 112.5f, smashFactor = 1.35f, launchAngleDegrees = 19.2f, spinRateRpm = 5800f, carryDistanceYards = 144f, resultDirection = "Slice", notes = "피니시에서 손목 힘을 과하게 써서 약간 우측 밀림 편차."),
                SwingLog(clubName = "5번 아이언", swingSpeedMph = 87f, ballSpeedMph = 118.0f, smashFactor = 1.36f, launchAngleDegrees = 17.5f, spinRateRpm = 4900f, carryDistanceYards = 162f, resultDirection = "Straight", notes = "정확한 임팩트로 최적의 런 배정."),
                SwingLog(clubName = "드라이버 (1W)", swingSpeedMph = 103f, ballSpeedMph = 144.2f, smashFactor = 1.40f, launchAngleDegrees = 14.1f, spinRateRpm = 2800f, carryDistanceYards = 219f, resultDirection = "Slice", notes = "헤드 스피드는 증가했으나 궤도가 아웃-인을 그리며 슬라이스."),
                SwingLog(clubName = "샌드 웨지 (SW)", swingSpeedMph = 71f, ballSpeedMph = 85.2f, smashFactor = 1.20f, launchAngleDegrees = 28.5f, spinRateRpm = 8200f, carryDistanceYards = 78f, resultDirection = "Straight", notes = "그린 앞 벙커 세이브 탈출 성공. 스핀 제어 양호.")
            )
            historicalLogs.forEach { db.swingLogDao().insertLog(it) }
        }
    }
}
