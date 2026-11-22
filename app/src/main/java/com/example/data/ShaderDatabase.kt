package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.BuiltInTexture
import com.example.model.ClockTimeUnit
import com.example.model.ShaderMode
import com.example.model.ShaderProject
import com.example.model.ShaderVariable
import com.example.model.TextureFilterMode
import com.example.model.TextureWrapMode
import com.example.model.UniformType
import com.example.model.ValueMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "shader_projects")
data class ShaderProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String,
    val useCustomVertex: Boolean,
    val combinedSource: String,
    val vertexSource: String,
    val fragmentSource: String,
    val variablesJson: String,
    val meshSubdivisions: Int,
    val updatedAt: Long,
)

@Dao
interface ShaderDao {
    @Query("SELECT * FROM shader_projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ShaderProjectEntity>>

    @Query("SELECT * FROM shader_projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ShaderProjectEntity?

    @Query("SELECT COUNT(*) FROM shader_projects")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ShaderProjectEntity)

    @Query("DELETE FROM shader_projects WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [ShaderProjectEntity::class], version = 1, exportSchema = false)
abstract class ShaderDatabase : RoomDatabase() {
    abstract fun shaderDao(): ShaderDao

    companion object {
        @Volatile
        private var INSTANCE: ShaderDatabase? = null

        fun getInstance(context: Context): ShaderDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShaderDatabase::class.java,
                    "shaderveil.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

object ShaderVariableCodec {
    fun encodeList(variables: List<ShaderVariable>): String {
        val arr = JSONArray()
        for (v in variables) {
            val obj = JSONObject().apply {
                put("id", v.id)
                put("name", v.name)
                put("isAutoNamed", v.isAutoNamed)
                put("type", v.type.name)
                put("autoDeclare", v.autoDeclare)
                put("valueMode", v.valueMode.name)
                put("constantValue", v.constantValue.toDouble())
                put("minValue", v.minValue.toDouble())
                put("maxValue", v.maxValue.toDouble())
                put("stepValue", v.stepValue.toDouble())
                put("timeSpeed", v.timeSpeed.toDouble())
                put("timeOffset", v.timeOffset.toDouble())
                put("timeLoop", v.timeLoop.toDouble())
                put("clockUnit", v.clockUnit.name)
                put("clockScale", v.clockScale.toDouble())
                put("clockOffset", v.clockOffset.toDouble())
                put("waveFrequency", v.waveFrequency.toDouble())
                put("waveAmplitude", v.waveAmplitude.toDouble())
                put("wavePhase", v.wavePhase.toDouble())
                put("waveBias", v.waveBias.toDouble())
                put("textureUri", v.textureUri ?: JSONObject.NULL)
                put("builtInTexture", v.builtInTexture.name)
                put("filterMode", v.filterMode.name)
                put("wrapMode", v.wrapMode.name)
                put("checked", v.checked)
                put("checkboxAsFloat", v.checkboxAsFloat)
                put("defineEnabled", v.defineEnabled)
                put("defineValue", v.defineValue)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    fun decodeList(json: String): List<ShaderVariable> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { idx ->
                val obj = arr.getJSONObject(idx)
                ShaderVariable(
                    id = obj.optString("id"),
                    name = obj.optString("name", "u_var_$idx"),
                    isAutoNamed = obj.optBoolean("isAutoNamed", true),
                    type = enumOrDefault(obj.optString("type"), UniformType.NONE),
                    autoDeclare = obj.optBoolean("autoDeclare", true),
                    valueMode = enumOrDefault(obj.optString("valueMode"), ValueMode.CONSTANT),
                    constantValue = obj.optDouble("constantValue", 0.5).toFloat(),
                    minValue = obj.optDouble("minValue", 0.0).toFloat(),
                    maxValue = obj.optDouble("maxValue", 1.0).toFloat(),
                    stepValue = obj.optDouble("stepValue", 0.01).toFloat(),
                    timeSpeed = obj.optDouble("timeSpeed", 1.0).toFloat(),
                    timeOffset = obj.optDouble("timeOffset", 0.0).toFloat(),
                    timeLoop = obj.optDouble("timeLoop", 0.0).toFloat(),
                    clockUnit = enumOrDefault(obj.optString("clockUnit"), ClockTimeUnit.SECONDS_OF_DAY),
                    clockScale = obj.optDouble("clockScale", 1.0).toFloat(),
                    clockOffset = obj.optDouble("clockOffset", 0.0).toFloat(),
                    waveFrequency = obj.optDouble("waveFrequency", 1.0).toFloat(),
                    waveAmplitude = obj.optDouble("waveAmplitude", 0.5).toFloat(),
                    wavePhase = obj.optDouble("wavePhase", 0.0).toFloat(),
                    waveBias = obj.optDouble("waveBias", 0.5).toFloat(),
                    textureUri = if (obj.isNull("textureUri")) null else obj.optString("textureUri", "").ifBlank { null },
                    builtInTexture = enumOrDefault(obj.optString("builtInTexture"), BuiltInTexture.CHECKERBOARD),
                    filterMode = enumOrDefault(obj.optString("filterMode"), TextureFilterMode.LINEAR),
                    wrapMode = enumOrDefault(obj.optString("wrapMode"), TextureWrapMode.REPEAT),
                    checked = obj.optBoolean("checked", false),
                    checkboxAsFloat = obj.optBoolean("checkboxAsFloat", false),
                    defineEnabled = obj.optBoolean("defineEnabled", true),
                    defineValue = obj.optString("defineValue", "1"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T {
        return runCatching { enumValueOf<T>(name) }.getOrDefault(default)
    }
}

fun ShaderProjectEntity.toDomain(): ShaderProject = ShaderProject(
    id = id,
    title = title,
    mode = runCatching { ShaderMode.valueOf(mode) }.getOrDefault(ShaderMode.COMBINED),
    useCustomVertex = useCustomVertex,
    combinedSource = combinedSource,
    vertexSource = vertexSource,
    fragmentSource = fragmentSource,
    variables = ShaderVariableCodec.decodeList(variablesJson),
    meshSubdivisions = meshSubdivisions,
    updatedAt = updatedAt,
)

fun ShaderProject.toEntity(): ShaderProjectEntity = ShaderProjectEntity(
    id = id,
    title = title,
    mode = mode.name,
    useCustomVertex = useCustomVertex,
    combinedSource = combinedSource,
    vertexSource = vertexSource,
    fragmentSource = fragmentSource,
    variablesJson = ShaderVariableCodec.encodeList(variables),
    meshSubdivisions = meshSubdivisions,
    updatedAt = updatedAt,
)

class ShaderRepository(private val dao: ShaderDao) {
    val allProjects: Flow<List<ShaderProject>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun count(): Int = dao.count()

    suspend fun getById(id: String): ShaderProject? = dao.getById(id)?.toDomain()

    suspend fun saveProject(project: ShaderProject) {
        dao.upsert(project.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun deleteProject(id: String) {
        dao.deleteById(id)
    }
}
