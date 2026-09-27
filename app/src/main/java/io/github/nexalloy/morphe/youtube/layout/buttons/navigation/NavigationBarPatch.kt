package io.github.nexalloy.morphe.youtube.layout.buttons.navigation

import android.view.View
import android.widget.TextView
import app.morphe.extension.youtube.patches.NavigationBarPatch
import io.github.nexalloy.morphe.shared.misc.settings.preference.PreferenceScreenPreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import io.github.nexalloy.morphe.shared.misc.settings.preference.SwitchPreference
import io.github.nexalloy.morphe.youtube.insertLiteralOverride
import io.github.nexalloy.morphe.youtube.misc.navigation.InitializeBottomBarContainerFingerprint
import io.github.nexalloy.morphe.youtube.misc.navigation.NavigationBarHook
import io.github.nexalloy.morphe.youtube.misc.navigation.bottomBarContainerId
import io.github.nexalloy.morphe.youtube.misc.navigation.hookNavigationButtonCreated
import io.github.nexalloy.morphe.youtube.misc.playservice.VersionCheck
import io.github.nexalloy.morphe.youtube.misc.playservice.is_20_31_or_greater
import io.github.nexalloy.morphe.youtube.misc.settings.PreferenceScreen
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import org.luckypray.dexkit.wrap.DexMethod

val NavigationBar = patch(
    name = "Navigation bar",
    description = "Adds options to hide and change the bottom navigation bar (such as the Shorts button)" +
            " and the upper navigation toolbar.",
) {
    dependsOn(NavigationBarHook, VersionCheck)

    val navPreferences = mutableSetOf(
        SwitchPreference("morphe_hide_home_button"),
        SwitchPreference("morphe_hide_shorts_button"),
        SwitchPreference("morphe_hide_create_button"),
        SwitchPreference("morphe_hide_subscriptions_button"),
        SwitchPreference("morphe_hide_notifications_button"),
//        SwitchPreference("morphe_show_search_button"),         // TODO PivotBarRenderer proto
//        ListPreference("morphe_show_search_button_index"),     // TODO PivotBarRenderer proto
//        SwitchPreference("morphe_show_settings_button"),       // TODO PivotBarRenderer proto
//        ListPreference("morphe_show_settings_button_index"),   // TODO PivotBarRenderer proto
//        SwitchPreference("morphe_show_settings_button_type", summary = true),  // TODO PivotBarRenderer proto
        SwitchPreference("morphe_swap_create_with_notifications_button", summary = true),
        SwitchPreference("morphe_hide_navigation_bar"),
//        SwitchPreference("morphe_narrow_navigation_buttons", summary = true),  // TODO PivotBarChanged/PivotBarStyle METHOD_MID
        SwitchPreference("morphe_hide_navigation_button_labels"),
        SwitchPreference("morphe_navigation_bar_animations", summary = true),
        SwitchPreference("morphe_disable_translucent_navigation", summary = true)
    )

    if (is_20_31_or_greater) {
        navPreferences += SwitchPreference("morphe_disable_auto_hide_navigation_bar", summary = true)
    }

    PreferenceScreen.GENERAL.addPreferences(
        PreferenceScreenPreference(
            key = "morphe_navigation_buttons_screen",
            sorting = Sorting.UNSORTED,
            preferences = navPreferences
        )
    )

    // Swap create with notifications button.
    // TODO Morphe uses addOSNameHook(Endpoint.GUIDE, ...) which depends on clientContextHookPatch.
    // setExtensionIsPatchIncluded(NavigationBarPatch::class.java)

    // Alternative: scopedHook on AutoMotiveFeatureMethod.
    ::addCreateButtonViewFingerprint.hookMethod(scopedHook(::AutoMotiveFeatureMethod.member) {
        before { param ->
            param.result =
                NavigationBarPatch.swapCreateWithNotificationButton("") == "Android Automotive"
        }
    })

    // Hide navigation button labels.
    CreatePivotBarFingerprint.hookMethod(scopedHook(DexMethod("Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V").toMethod()) {
        before { param ->
            NavigationBarPatch.hideNavigationButtonLabels(param.thisObject as TextView)
        }
    })

    // Hook navigation button created, in order to hide them.
    hookNavigationButtonCreated.add { button, view ->
        NavigationBarPatch.navigationTabCreated(button, view)
    }

    // Hide the navigation bar and paint over the translucent system bars.
    //
    // NexAlloy v1.43.0 stopped forcing the `useTranslucentNavigation` and
    // `allowCollapsingToolbarLayout` feature flags off. Those flags also switch
    // the app out of edge-to-edge, which moves the whole window layout and
    // breaks every measurement against it, so upstream now hooks the bottom bar
    // container instead. `addBottomBarContainerHook` is a bytecode-insertion
    // helper, so the standalone runtime reaches the same view by hooking the
    // public `addOnLayoutChangeListener` API and matching the container id.
    InitializeBottomBarContainerFingerprint.hookMethod(
        scopedHook(
            DexMethod(
                "Landroid/view/View;->addOnLayoutChangeListener" +
                        "(Landroid/view/View\$OnLayoutChangeListener;)V"
            ).toMethod()
        ) {
            val containerId = bottomBarContainerId
            after {
                val container = it.thisObject as View
                if (container.id != containerId) return@after
                NavigationBarPatch.setNavigationBarOpaque(container)
                NavigationBarPatch.hideNavigationBar(container)
            }
        }
    )

    // Animated navigation tabs.
    insertLiteralOverride(45680008L, NavigationBarPatch::useAnimatedNavigationButtons)

    // TODO Narrow navigation buttons

    // disableAutoHidingNavigationBar

    if (is_20_31_or_greater) {
        listOf(
            AutoHideNavigationBarOnFeedScrollingFingerprint,
            AutoHideNavigationBarOnDismissMiniplayerFingerprint,
        ).forEach {
            it.hookMethod {
                before { param ->
                    if (NavigationBarPatch.disableAutoHidingNavigationBar()){
                        param.result = null
                    }
                }
            }
        }
    }

    // TODO upper navigation toolbar
}
