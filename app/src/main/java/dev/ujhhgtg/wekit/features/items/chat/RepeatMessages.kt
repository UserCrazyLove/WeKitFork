package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.ExposurePlus1Icon
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.removeWxIdPrefix

@Suppress("DEPRECATION")
@Feature(name = "消息复读", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可复读一些简单的消息")
object RepeatMessages : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT, MessageType.QUOTE, MessageType.APP, MessageType.IMAGE
    )

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777008, "复读", ExposurePlus1Icon,
                shouldShow = { it.type in SUPPORTED_MSG_TYPES },
                onClick = { view, _, msgInfo ->
                    val context = view.context

                    when (msgInfo.type) {
                        MessageType.TEXT -> {
                            WeMessageApi.sendText(msgInfo.talker, msgInfo.actualContent)
                            showToast(context, "已发送")
                        }

                        MessageType.QUOTE -> {
                            val quoteMsg = msgInfo.toQuoteMessage() ?: return@MenuItem

                            var text = quoteMsg.title
                            if (msgInfo.isInGroupChat) {
                                text = text.removeWxIdPrefix()
                            }

                            WeMessageApi.sendText(msgInfo.talker, text)
                            showToast(context, "已发送")
                        }

                        MessageType.APP -> {
                            WeMessageApi.sendXmlAppMsg(msgInfo.talker, msgInfo.actualContent)
                            showToast(context, "已发送")
                        }

                        MessageType.IMAGE -> {
                            val md5 = WeServiceApi.getImageMd5FromMsgInfo(msgInfo)
                            WeMessageApi.sendImageByMd5(msgInfo.talker, md5, null)
                            showToast(view.context, "已发送")
                        }

                        else -> {}
                    }
                }
            )
        )
    }
}
