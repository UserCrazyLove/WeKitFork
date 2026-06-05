package dev.ujhhgtg.wekit.hooks.api.ui

import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.widget.BaseAdapter
import android.widget.ImageView
import androidx.collection.mutableIntObjectMapOf
import androidx.core.util.size
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.isSubclassOf
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BBool
import dev.ujhhgtg.wekit.utils.reflection.BInt
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/首页菜单服务", description = "提供向首页右上角菜单添加菜单项的能力")
object WeHomeScreenPopupMenuApi : ApiHookItem(), IResolvesDex {

    interface IMenuItemsProvider {
        fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val onClick: () -> Unit
    ) {
        val fakeResId get() = id + text.hashCode()
    }

    private val providers = CopyOnWriteArrayList<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        providers.remove(provider)
    }

    private val TAG = This.Class.simpleName

    private val fakeResIdToResMap = mutableIntObjectMapOf<Drawable>()

    private val methodAddItem by dexMethod()
    private val methodHandleItemClick by dexMethod()
    private val classMenuItemData by dexClass()
    private val classMenuItemWrapper by dexClass()

    override fun onEnable() {
        // WeChat 8.0.70 moved this to com.tencent.mm.ui.HomeUI
        methodAddItem.hookAfter {
            var thisObj = thisObject

            if (thisObj.javaClass.simpleName == "HomeUI") {
                thisObj = thisObj.asResolver()
                    .firstField { type = methodHandleItemClick.method.declaringClass }
                    .get()!!
            }

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.asResolver()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val baseAdapter = thisObj.asResolver()
                .firstField {
                    type { it isSubclassOf BaseAdapter::class }
                }
                .get()!! as BaseAdapter

            baseAdapter.asResolver().firstMethod {
                name = "getView"
            }.apply {
                var unhook: XC_MethodHook.Unhook? = null

                hookBefore {
                    unhook = ImageView::class.asResolver().firstMethod {
                        name = "setImageResource"
                    }.hookBefore {
                        val fakeResId = args[0] as Int
                        val imageView = thisObject as ImageView
                        imageView.setImageDrawable(fakeResIdToResMap[fakeResId] ?: return@hookBefore)
                        result = null
                    }
                }

                hookAfter {
                    unhook!!.unhook()
                }
            }

            for (provider in providers) {
                try {
                    for (item in provider.getMenuItems(this)) {
                        fakeResIdToResMap[item.fakeResId] = item.drawable

                        val itemData = classMenuItemData.clazz.createInstance(
                            item.id,
                            item.text,
                            "",
                            item.fakeResId,
                            0
                        )
                        val itemWrapper =
                            classMenuItemWrapper.clazz.createInstance(itemData)
                        items.put(items.size, itemWrapper)
                    }
                } catch (ex: Exception) {
                    WeLogger.e(
                        TAG,
                        "provider ${provider.javaClass.name} threw while providing menu items",
                        ex
                    )
                }
            }
            baseAdapter.notifyDataSetChanged()
        }

        methodHandleItemClick.hookBefore {
            val thisObj = thisObject

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.asResolver()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val position = args[2] as Int
            val itemWrapper = items.get(position)
            val itemData = itemWrapper.asResolver()
                .firstField { type = classMenuItemData.clazz }.get()!!
            val id = itemData.asResolver()
                .field { type = Int::class }[1].get()!! as Int

            for (provider in providers) {
                for (item in provider.getMenuItems(this)) {
                    if (item.id == id) {
                        try {
                            item.onClick()
                            return@hookBefore
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while handling click event",
                                ex
                            )
                        }
                    }
                }
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodAddItem.find(dexKit) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                usingEqStrings(
                    "MicroMsg.PlusSubMenuHelper",
                    "dyna plus config is null, we use default one"
                )
            }
        }

        methodHandleItemClick.find(dexKit) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
            }
        }

        classMenuItemData.find(dexKit) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                addFieldForType(BString)
                addFieldForType(BInt)
                addFieldForType(BInt)
                addFieldForType(BInt)
                addFieldForType(BString)
                fieldCount(5)
                methods {
                    add {
                        usingEqStrings("")
                    }
                }
            }
        }

        classMenuItemWrapper.find(dexKit) {
            searchPackages("com.tencent.mm.ui")
            matcher {
                addFieldForType(BBool)
                addFieldForType(classMenuItemData.clazz)
            }
        }
    }
}
