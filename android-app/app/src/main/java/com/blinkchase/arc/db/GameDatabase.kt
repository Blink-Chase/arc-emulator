package com.blinkchase.arc.db

import android.content.Context
import androidx.room.*
import com.blinkchase.arc.GameFile
import com.blinkchase.arc.Platform
import com.blinkchase.arc.ControllerProfile
import com.blinkchase.arc.ControllerModel
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY name ASC")
    fun getAllGames(): Flow<List<GameFile>>

    @Query("SELECT * FROM games WHERE platform = :platform ORDER BY name ASC")
    fun getGamesByPlatform(platform: Platform): Flow<List<GameFile>>

    @Query("SELECT * FROM games WHERE path = :path LIMIT 1")
    suspend fun getGameByPath(path: String): GameFile?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGames(games: List<GameFile>)

    @Update
    suspend fun updateGame(game: GameFile)

    @Query("SELECT COUNT(*) FROM games")
    suspend fun getGameCount(): Int

    @Delete
    suspend fun deleteGame(game: GameFile)

    @Query("DELETE FROM games")
    suspend fun deleteAll()
}

@Dao
interface InputDao {
    @Query("SELECT * FROM controller_profiles WHERE deviceName = :name AND platform = :platform AND gamePath = :gamePath LIMIT 1")
    suspend fun getProfile(name: String, platform: String = "", gamePath: String = ""): ControllerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: ControllerProfile)

    @Query("SELECT * FROM controller_profiles")
    fun getAllProfiles(): Flow<List<ControllerProfile>>
}

class Converters {
    @TypeConverter
    fun fromPlatform(value: Platform): String = value.name

    @TypeConverter
    fun toPlatform(value: String): Platform = try {
        Platform.valueOf(value)
    } catch (e: Exception) {
        Platform.UNKNOWN
    }

    @TypeConverter
    fun fromControllerModel(value: ControllerModel): String = value.name

    @TypeConverter
    fun toControllerModel(value: String): ControllerModel = try {
        if (value == "NINTENDO") ControllerModel.N64 else ControllerModel.valueOf(value)
    } catch (e: Exception) {
        ControllerModel.GENERIC_ABXY
    }

    @TypeConverter
    fun fromIntMap(map: Map<Int, Int>): String {
        return map.entries.joinToString(";") { "${it.key}:${it.value}" }
    }

    @TypeConverter
    fun toIntMap(value: String): Map<Int, Int> {
        if (value.isBlank()) return emptyMap()
        return try {
            value.split(";").associate {
                val (k, v) = it.split(":")
                k.toInt() to v.toInt()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

@Database(entities = [GameFile::class, ControllerProfile::class], version = 6, exportSchema = false)
@TypeConverters(Converters::class)
abstract class GameDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun inputDao(): InputDao

    companion object {
        @Volatile
        private var INSTANCE: GameDatabase? = null

        fun getDatabase(context: Context): GameDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GameDatabase::class.java,
                    "arc_database"
                )
                .fallbackToDestructiveMigration() // Simple for dev version
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
