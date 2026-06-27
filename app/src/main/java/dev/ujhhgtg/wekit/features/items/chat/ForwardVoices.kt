package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.utils.ForwardIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(
    name = "语音转发",
    categories = ["聊天"],
    description = "在语音消息菜单添加转发按钮, 支持选择好友或群聊后重新发送语音"
)
object ForwardVoices : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = This.Class.simpleName

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777010,
                "转发",
                ForwardIcon,
                shouldShow = { msgInfo -> msgInfo.typeCode == MessageType.VOICE.code }
            ) { view, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val contacts = runCatching {
                        WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                    }.onFailure {
                        WeLogger.e(TAG, "failed to load contacts for voice forward", it)
                    }.getOrDefault(emptyList())

                    if (contacts.isEmpty()) {
                        showToast(view.context, "联系人列表为空或读取失败")
                        return@launch
                    }

                    CoroutineScope(Dispatchers.Main).launch {
                        showComposeDialog(view.context) {
                            ContactsSelector(
                                title = "选择转发对象",
                                contacts = contacts,
                                initialSelectedWxIds = emptySet(),
                                onDismiss = onDismiss,
                                onConfirm = { selectedWxIds ->
                                    if (selectedWxIds.isEmpty()) {
                                        showToast("请选择至少一个联系人")
                                        return@ContactsSelector
                                    }

                                    onDismiss()
                                    forwardVoice(msgInfo, selectedWxIds)
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    private fun forwardVoice(msgInfo: MessageInfo, targetWxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            val voicePath = runCatching { resolveVoicePath(msgInfo) }
                .onFailure { WeLogger.e(TAG, "failed to resolve voice path", it) }
                .getOrNull()

            WeLogger.d(TAG, "forwardVoice: resolved voicePath=$voicePath")

            if (voicePath.isNullOrBlank()) {
                showToastSuspend("语音文件路径解析失败")
                return@launch
            }

            val durationMs = resolveDurationMs(msgInfo, voicePath)
            WeLogger.d(TAG, "forwardVoice: durationMs=$durationMs, targetWxIds=$targetWxIds")

            var successCount = 0

            for (wxId in targetWxIds) {
                WeLogger.d(TAG, "forwardVoice: sending to $wxId, path=$voicePath, duration=$durationMs")
                val sent = WeMessageApi.sendVoice(wxId, voicePath, durationMs)
                WeLogger.d(TAG, "forwardVoice: sendVoice result for $wxId = $sent")
                if (sent) {
                    successCount++
                } else {
                    WeLogger.e(TAG, "failed to forward voice to $wxId")
                }
            }

            val failedCount = targetWxIds.size - successCount
            val message = if (failedCount == 0) {
                "已转发 $successCount 条语音"
            } else {
                "语音转发完成: 成功 $successCount, 失败 $failedCount"
            }
            WeLogger.d(TAG, "forwardVoice: final message=$message")
            showToastSuspend(message)
        }
    }

    private fun resolveVoicePath(msgInfo: MessageInfo): String {
        val fileNames = mutableListOf<String>().apply {
            addIfNotBlank(queryVoiceFileNameFromDb(msgInfo))
            addIfNotBlank(msgInfo.imagePath)
        }.distinct()

        WeLogger.d(
            TAG,
            "resolving voice path: msgId=${msgInfo.id}, svrId=${msgInfo.serverId}, " +
                    "talker=${msgInfo.talker}, imgPath=${msgInfo.imagePath}, candidates=$fileNames"
        )

        for (fileName in fileNames) {
            if (fileName.startsWith("/") || fileName.contains("://")) {
                if (WeMessageApi.voiceFileExists(fileName)) return fileName
                WeLogger.w(TAG, "voice candidate path does not exist: $fileName")
                continue
            }

            val path = runCatching { WeMessageApi.getVoiceFullPath(fileName) }
                .onFailure { WeLogger.w(TAG, "failed to resolve voice path for fileName=$fileName", it) }
                .getOrNull()

            if (!path.isNullOrBlank()) {
                if (WeMessageApi.voiceFileExists(path)) {
                    WeLogger.d(TAG, "resolved voice path: fileName=$fileName, path=$path")
                    return path
                }
                WeLogger.w(TAG, "resolved voice path but file does not exist: fileName=$fileName, path=$path")
            }
        }

        return ""
    }

    private fun MutableList<String>.addIfNotBlank(value: String?) {
        if (!value.isNullOrBlank()) add(value)
    }

    private fun queryVoiceFileNameFromDb(msgInfo: MessageInfo): String? {
        val safeTalker = msgInfo.talker.replace("'", "''")
        val safeImagePath = msgInfo.imagePath.replace("'", "''")
        val sqlCandidates = mutableListOf<String>().apply {
            if (msgInfo.id > 0) {
                add("SELECT FileName FROM voiceinfo WHERE MsgLocalId=${msgInfo.id} AND MsgTalker='$safeTalker' LIMIT 1")
                add("SELECT FileName FROM voiceinfo WHERE MsgLocalId=${msgInfo.id} LIMIT 1")
            }
            if (msgInfo.serverId > 0) {
                add("SELECT FileName FROM voiceinfo WHERE MsgId=${msgInfo.serverId} AND MsgTalker='$safeTalker' LIMIT 1")
                add("SELECT FileName FROM voiceinfo WHERE MsgId=${msgInfo.serverId} LIMIT 1")
            }
            if (safeImagePath.isNotBlank()) {
                add("SELECT FileName FROM voiceinfo WHERE FileName='$safeImagePath' LIMIT 1")
            }
        }

        for (sql in sqlCandidates) {
            runCatching {
                WeDatabaseApi.rawQuery(sql).use { cursor ->
                    if (!cursor.moveToFirst()) return@runCatching null
                    val fileName = cursor.getString(0)
                    if (!fileName.isNullOrBlank()) {
                        WeLogger.d(TAG, "resolved voice FileName from voiceinfo: $fileName, sql=$sql")
                        return fileName
                    }
                }
            }.onFailure {
                WeLogger.w(TAG, "failed to query voice FileName with sql: $sql", it)
            }
        }

        WeLogger.w(
            TAG,
            "voiceinfo FileName not found: msgId=${msgInfo.id}, svrId=${msgInfo.serverId}, " +
                    "talker=${msgInfo.talker}, imgPath=${msgInfo.imagePath}"
        )
        return null
    }

    private fun resolveDurationMs(msgInfo: MessageInfo, voicePath: String): Int {
        return queryDurationFromFile(voicePath)
            ?: parseDurationFromContent(msgInfo.content)
            ?: queryDurationFromVoiceInfo(msgInfo)
            ?: FALLBACK_DURATION_MS
    }

    private fun queryDurationFromFile(voicePath: String): Int? {
        return runCatching {
            normalizeDurationMs(AudioUtils.getDurationMs(voicePath).toInt())
        }.onFailure {
            WeLogger.w(TAG, "failed to read voice duration from file: $voicePath", it)
        }.getOrNull()
    }

    private fun parseDurationFromContent(content: String): Int? {
        val match = Regex("""(?:voicelength|voiceLength|duration|length)=["']?(\d+)""")
            .find(content)
            ?: return null

        val duration = match.groupValues[1].toIntOrNull() ?: return null
        return normalizeDurationMs(duration)
    }

    private fun queryDurationFromVoiceInfo(msgInfo: MessageInfo): Int? {
        val safeImagePath = msgInfo.imagePath.replace("'", "''")
        val candidates = listOf(
            "SELECT * FROM voiceinfo WHERE FileName='$safeImagePath' LIMIT 1",
            "SELECT * FROM voiceinfo WHERE MsgLocalId=${msgInfo.id} LIMIT 1",
            "SELECT * FROM voiceinfo WHERE MsgId=${msgInfo.serverId} LIMIT 1"
        )

        for (sql in candidates) {
            runCatching {
                WeDatabaseApi.rawQuery(sql).use { cursor ->
                    if (!cursor.moveToFirst()) return@runCatching null

                    for (index in 0 until cursor.columnCount) {
                        val columnName = cursor.getColumnName(index)
                        if (!columnName.contains("length", ignoreCase = true) &&
                            !columnName.contains("duration", ignoreCase = true)
                        ) {
                            continue
                        }

                        val value = cursor.getLong(index).toInt()
                        val duration = normalizeDurationMs(value)
                        if (duration != null) return duration
                    }
                }
            }.onFailure {
                WeLogger.w(TAG, "failed to query voice duration with sql: $sql", it)
            }
        }

        return null
    }

    private fun normalizeDurationMs(value: Int): Int? {
        if (value <= 0) return null
        val millis = if (value <= 60) value * 1000 else value
        return millis.coerceIn(1, 60_000)
    }

    private const val FALLBACK_DURATION_MS = 1_000
}
