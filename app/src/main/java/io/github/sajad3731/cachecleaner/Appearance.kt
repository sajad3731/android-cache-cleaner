package io.github.sajad3731.cachecleaner

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/** Light, dark or system theme, pure black for OLED, and wallpaper colours. */
object Appearance {
    fun init(app: Application) {
        applyNightMode(Prefs(app))
        DynamicColors.applyToActivitiesIfAvailable(
            app,
            DynamicColorsOptions.Builder().setPrecondition { activity, _ -> Prefs(activity).dynamicColor }.build(),
        )
    }

    fun applyNightMode(prefs: Prefs) {
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.themeMode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    /** Settings that need an activity to be recreated when they change. */
    fun key(prefs: Prefs): String = "${prefs.pureBlack}/${prefs.dynamicColor}"

    fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * A themed context for views outside an activity (the cleaning overlay), following the same
     * theme choice, pure black and wallpaper colours as the app.
     */
    fun themedContext(context: Context): Context {
        val prefs = Prefs(context)
        val config = Configuration(context.resources.configuration)
        val nightBits = when (prefs.themeMode) {
            ThemeMode.SYSTEM -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
        var themed: Context = ContextThemeWrapper(context.createConfigurationContext(config), R.style.AppTheme)
        if (prefs.dynamicColor) themed = DynamicColors.wrapContextIfAvailable(themed)
        if (prefs.pureBlack && isNight(themed)) themed.theme.applyStyle(R.style.ThemeOverlay_CacheCleaner_PureBlack, true)
        return themed
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Appearance.init(this)
        ReminderJobService.ensureScheduled(this)
    }
}

/** Applies pure black and recreates itself when an appearance setting changed elsewhere. */
abstract class ThemedActivity : AppCompatActivity() {
    private var appliedKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        appliedKey = Appearance.key(prefs)
        // After super: AppCompat has resolved night mode and dynamic colours by now, and views
        // are not inflated yet, so this overlay still wins over both.
        if (prefs.pureBlack && Appearance.isNight(this)) {
            theme.applyStyle(R.style.ThemeOverlay_CacheCleaner_PureBlack, true)
            window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Appearance.key(Prefs(this)) != appliedKey) recreate()
    }
}
