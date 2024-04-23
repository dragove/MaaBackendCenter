package plus.maa.backend.service.level

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.web.reactive.function.client.WebClient
import plus.maa.backend.common.extensions.awaitString
import plus.maa.backend.repository.entity.gamedata.ArkActivity
import plus.maa.backend.repository.entity.gamedata.ArkCharacter
import plus.maa.backend.repository.entity.gamedata.ArkCrisisV2Info
import plus.maa.backend.repository.entity.gamedata.ArkStage
import plus.maa.backend.repository.entity.gamedata.ArkTower
import plus.maa.backend.repository.entity.gamedata.ArkZone
import java.util.Locale

data class ArkGameDataHolder(
    val stageMap: Map<String, ArkStage>,
    val zoneMap: Map<String, ArkZone>,
    val zoneActivityMap: Map<String, ArkActivity>,
    val arkCharacterMap: Map<String, ArkCharacter>,
    val arkTowerMap: Map<String, ArkTower>,
    var arkCrisisV2InfoMap: Map<String, ArkCrisisV2Info>,
) {
    private val levelStageMap = stageMap.values.let { stages ->
        val mLevelToStageMap = mutableMapOf<String, ArkStage>()
        stages.forEach { stage -> stage.levelId?.let { mLevelToStageMap[it.lowercase(Locale.getDefault())] = stage } }
        mLevelToStageMap.toMap()
    }

    fun findStage(levelId: String, code: String, stageId: String): ArkStage? {
        val stage = levelStageMap[levelId.lowercase(Locale.getDefault())]
        if (stage != null && stage.code.equals(code, ignoreCase = true)) {
            return stage
        }
        return stageMap[stageId]
    }

    fun findZone(levelId: String, code: String, stageId: String): ArkZone? {
        val stage = findStage(levelId, code, stageId)
        if (stage == null) {
            log.error { "[DATA]stage不存在:$stageId, Level: $levelId" }
            return null
        }
        val zone = zoneMap[stage.zoneId]
        if (zone == null) {
            log.error { "[DATA]zone不存在:${stage.zoneId}, Level: $levelId" }
        }
        return zone
    }

    fun findTower(zoneId: String) = arkTowerMap[zoneId]

    fun findCharacter(characterId: String): ArkCharacter? {
        val ids = characterId.split("_")
        return arkCharacterMap[ids[ids.size - 1]]
    }

    fun findActivityByZoneId(zoneId: String) = zoneActivityMap[zoneId]

    /**
     * 通过 stageId 或者 seasonId 提取危机合约信息
     *
     * @param id stageId 或者 seasonId
     * @return 危机合约信息，包含合约名、开始时间、结束时间等
     */
    fun findCrisisV2InfoById(id: String?) = findCrisisV2InfoByKeyInfo(ArkLevelUtil.getKeyInfoById(id))

    /**
     * 通过地图系列的唯一标识提取危机合约信息
     *
     * @param keyInfo 地图系列的唯一标识
     * @return 危机合约信息，包含合约名、开始时间、结束时间等
     */
    fun findCrisisV2InfoByKeyInfo(keyInfo: String) = arkCrisisV2InfoMap[keyInfo]

    suspend fun updateCrisisV2Info() = apply { arkCrisisV2InfoMap = fetchCrisisV2Info() }

    companion object {
        private const val ARK_RESOURCE_BASE = "https://raw.githubusercontent.com/yuanyan3060/ArknightsGameResource/main/gamedata/excel"
        private const val ARK_STAGE = "$ARK_RESOURCE_BASE/stage_table.json"
        private const val ARK_ZONE = "$ARK_RESOURCE_BASE/zone_table.json"
        private const val ARK_ACTIVITY = "$ARK_RESOURCE_BASE/activity_table.json"
        private const val ARK_CHARACTER = "$ARK_RESOURCE_BASE/character_table.json"
        private const val ARK_TOWER = "$ARK_RESOURCE_BASE/climb_tower_table.json"
        private const val ARK_CRISIS_V2 = "$ARK_RESOURCE_BASE/crisis_v2_table.json"
        private val log = KotlinLogging.logger {}
        private lateinit var webClient: WebClient
        private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        suspend fun fetch(webClient: WebClient): ArkGameDataHolder {
            this.webClient = webClient
            return coroutineScope {
                val dStageMap = async { fetchStages() }
                val dZoneMap = async { fetchZones() }
                val dZoneToActivity = async { fetchActivities() }
                val dCharacterMap = async { fetchCharacters() }
                val dTowerMap = async { fetchTowers() }
                val dKeyInfoToCrisisV2Info = async { fetchCrisisV2Info() }

                ArkGameDataHolder(
                    stageMap = dStageMap.await(),
                    zoneMap = dZoneMap.await(),
                    zoneActivityMap = dZoneToActivity.await(),
                    arkCharacterMap = dCharacterMap.await(),
                    arkTowerMap = dTowerMap.await(),
                    arkCrisisV2InfoMap = dKeyInfoToCrisisV2Info.await(),
                )
            }
        }

        private suspend fun fetchStages() = fetchMapResource<ArkStageTable, ArkStage>("stages", ARK_STAGE) { it.stages }
        private data class ArkStageTable(val stages: Map<String, ArkStage>)

        private suspend fun fetchCharacters() = fetchMapResource<Map<String, ArkCharacter>, ArkCharacter>("characters", ARK_CHARACTER) {
            val tmp = mutableMapOf<String, ArkCharacter>()
            it.forEach { (id, c) ->
                val ids = id.split("_")
                if (ids.size != 3) return@forEach
                c.id = id
                tmp[ids[2]] = c
            }
            tmp
        }

        private suspend fun fetchZones() = fetchMapResource<ArkZoneTable, ArkZone>("zones", ARK_ZONE) { it.zones }
        private data class ArkZoneTable(val zones: Map<String, ArkZone>)

        private suspend fun fetchActivities() = fetchMapResource<ArkActivityTable, ArkActivity>("activities", ARK_ACTIVITY) {
            val actMap = it.basicInfo
            val ret = mutableMapOf<String, ArkActivity>()
            it.zoneToActivity.forEach { (zoneId, actId) ->
                actMap[actId]?.let { act -> ret[zoneId] = act }
            }
            ret
        }

        private data class ArkActivityTable(val zoneToActivity: Map<String, String>, val basicInfo: Map<String, ArkActivity>)

        private suspend fun fetchTowers() = fetchMapResource<ArkTowerTable, ArkTower>("towers", ARK_TOWER) { it.towers }
        private data class ArkTowerTable(val towers: Map<String, ArkTower>)

        private suspend fun fetchCrisisV2Info() = fetchMapResource<CrisisV2Table, ArkCrisisV2Info>("crisis v2 info", ARK_CRISIS_V2) {
            it.seasonInfoDataMap.mapKeys { entry -> ArkLevelUtil.getKeyInfoById(entry.key) }
        }

        private data class CrisisV2Table(val seasonInfoDataMap: Map<String, ArkCrisisV2Info>)

        private suspend inline fun <reified T, S> fetchMapResource(
            label: String,
            uri: String,
            crossinline extract: (T) -> Map<String, S>,
        ): Map<String, S> = try {
            val text = webClient.get().uri(uri).retrieve().awaitString()
            val v = mapper.readValue<T>(text)
            extract(v).apply { log.info { "[DATA]获取 $label 数据成功，共 ${this.size} 条" } }
        } catch (e: Exception) {
            throw Exception("[DATA]获取 $label 数据失败", e)
        }
    }
}
