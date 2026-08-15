package com.blinkchase.arc.db

import android.content.Context
import androidx.room.*
import com.blinkchase.arc.GameFile
import com.blinkchase.arc.Platform
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

    @Delete
    suspend fun deleteGame(game: GameFile)

    @Query("DELETE FROM games")
    suspend fun deleteAll()
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
}

@Database(entities = [GameFile::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class GameDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao

    companion object {
        @Volatile
        private var INSTANCE: GameDatabase? = null

        fun getDatabase(context: Context): GameDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GameDatabase::class.java,
                    "arc_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
