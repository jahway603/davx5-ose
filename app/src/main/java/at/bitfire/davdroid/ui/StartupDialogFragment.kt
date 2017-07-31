/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.ui

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.app.DialogFragment
import android.support.v7.app.AlertDialog
import at.bitfire.davdroid.App
import at.bitfire.davdroid.BuildConfig
import at.bitfire.davdroid.Logger
import at.bitfire.davdroid.R
import at.bitfire.davdroid.model.ServiceDB
import at.bitfire.davdroid.model.Settings
import at.bitfire.davdroid.resource.LocalTaskList
import java.util.*
import java.util.logging.Level

class StartupDialogFragment: DialogFragment() {

    enum class Mode {
        BATTERY_OPTIMIZATIONS,
        DEVELOPMENT_VERSION,
        GOOGLE_PLAY_ACCOUNTS_REMOVED,
        OPENTASKS_NOT_INSTALLED
    }

    companion object {

        @JvmField val HINT_BATTERY_OPTIMIZATIONS = "hint_BatteryOptimizations"
        @JvmField val HINT_GOOGLE_PLAY_ACCOUNTS_REMOVED = "hint_GooglePlayAccountsRemoved"
        @JvmField val HINT_OPENTASKS_NOT_INSTALLED = "hint_OpenTasksNotInstalled"

        val ARGS_MODE = "mode"

        fun getStartupDialogs(context: Context): List<StartupDialogFragment> {
            val dialogs = LinkedList<StartupDialogFragment>()

            ServiceDB.OpenHelper(context).use { dbHelper ->
                val settings = Settings(dbHelper.readableDatabase)

                if (BuildConfig.VERSION_NAME.contains("-alpha") || BuildConfig.VERSION_NAME.contains("-beta") || BuildConfig.VERSION_NAME.contains("-rc"))
                    dialogs += StartupDialogFragment.instantiate(Mode.DEVELOPMENT_VERSION)
                else {
                    // store-specific information
                    if (BuildConfig.FLAVOR == App.FLAVOR_GOOGLE_PLAY) {
                        // Play store
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP &&         // only on Android <5
                            settings.getBoolean(HINT_GOOGLE_PLAY_ACCOUNTS_REMOVED, true))   // and only when "Don't show again" hasn't been clicked yet
                            dialogs += StartupDialogFragment.instantiate(Mode.GOOGLE_PLAY_ACCOUNTS_REMOVED)
                    }
                }

                // battery optimization white-listing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && settings.getBoolean(HINT_BATTERY_OPTIMIZATIONS, true)) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!powerManager.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID))
                        dialogs.add(StartupDialogFragment.instantiate(Mode.BATTERY_OPTIMIZATIONS))
                }

                // OpenTasks information
                if (!LocalTaskList.tasksProviderAvailable(context) && settings.getBoolean(HINT_OPENTASKS_NOT_INSTALLED, true))
                    dialogs.add(StartupDialogFragment.instantiate(Mode.OPENTASKS_NOT_INSTALLED))
            }

            return dialogs.reversed()
        }

        fun instantiate(mode: Mode): StartupDialogFragment {
            val frag = StartupDialogFragment()
            val args = Bundle(1)
            args.putString(ARGS_MODE, mode.name)
            frag.arguments = args
            return frag
        }

    }

    @SuppressLint("BatteryLife")
    @TargetApi(Build.VERSION_CODES.M)
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false

        val mode = Mode.valueOf(arguments.getString(ARGS_MODE))
        return when (mode) {
            Mode.BATTERY_OPTIMIZATIONS ->
                AlertDialog.Builder(activity)
                        .setIcon(R.drawable.ic_info_dark)
                        .setTitle(R.string.startup_battery_optimization)
                        .setMessage(R.string.startup_battery_optimization_message)
                        .setPositiveButton(android.R.string.ok, { _: DialogInterface, _: Int -> })
                        .setNeutralButton(R.string.startup_battery_optimization_disable, { _: DialogInterface, _: Int ->
                                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + BuildConfig.APPLICATION_ID))
                                if (intent.resolveActivity(context.packageManager) != null)
                                    context.startActivity(intent)
                            })
                        .setNegativeButton(R.string.startup_dont_show_again, { _: DialogInterface, _: Int ->
                            ServiceDB.OpenHelper(context).use { dbHelper ->
                                val settings = Settings(dbHelper.writableDatabase)
                                settings.putBoolean(HINT_BATTERY_OPTIMIZATIONS, false)
                            }
                        })
                        .create()

            Mode.DEVELOPMENT_VERSION ->
                AlertDialog.Builder(activity)
                        .setIcon(R.mipmap.ic_launcher)
                        .setTitle(R.string.startup_development_version)
                        .setMessage(getString(R.string.startup_development_version_message, getString(R.string.app_name)))
                        .setPositiveButton(android.R.string.ok, { _: DialogInterface, _: Int -> })
                        .setNeutralButton(R.string.startup_development_version_give_feedback, { _: DialogInterface, _: Int ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.startup_development_version_feedback_url))))
                        })
                        .create()

            Mode.GOOGLE_PLAY_ACCOUNTS_REMOVED -> {
                var icon: Drawable? = null
                try {
                    icon = context.packageManager.getApplicationIcon("com.android.vending").current
                } catch(e: PackageManager.NameNotFoundException) {
                    Logger.log.log(Level.WARNING, "Can't load Play Store icon", e)
                }
                return AlertDialog.Builder(activity)
                        .setIcon(icon)
                        .setTitle(R.string.startup_google_play_accounts_removed)
                        .setMessage(R.string.startup_google_play_accounts_removed_message)
                        .setPositiveButton(android.R.string.ok, { _: DialogInterface, _: Int -> })
                        .setNeutralButton(R.string.startup_google_play_accounts_removed_more_info, { _: DialogInterface, _: Int ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.navigation_drawer_faq_url)))
                            context.startActivity(intent)
                        })
                        .setNegativeButton(R.string.startup_dont_show_again, { _: DialogInterface, _: Int ->
                            ServiceDB.OpenHelper(context).use { dbHelper ->
                                val settings = Settings(dbHelper.writableDatabase)
                                settings.putBoolean(HINT_GOOGLE_PLAY_ACCOUNTS_REMOVED, false)
                            }
                        })
                        .create()
            }

            Mode.OPENTASKS_NOT_INSTALLED -> {
                val builder = StringBuilder(getString(R.string.startup_opentasks_not_installed_message))
                if (Build.VERSION.SDK_INT < 23)
                    builder.append("\n\n").append(getString(R.string.startup_opentasks_reinstall_davdroid))
                return AlertDialog.Builder(activity)
                        .setIcon(R.drawable.ic_alarm_on_dark)
                        .setTitle(R.string.startup_opentasks_not_installed)
                        .setMessage(builder.toString())
                        .setPositiveButton(android.R.string.ok, { _: DialogInterface, _: Int -> })
                        .setNeutralButton(R.string.startup_opentasks_not_installed_install, { _: DialogInterface, _: Int ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.dmfs.tasks"))
                            if (intent.resolveActivity(context.packageManager) != null)
                                context.startActivity(intent)
                            else
                                Logger.log.warning("No market app available, can't install OpenTasks")
                        })
                        .setNegativeButton(R.string.startup_dont_show_again, { _: DialogInterface, _: Int ->
                            ServiceDB.OpenHelper(context).use { dbHelper ->
                                val settings = Settings(dbHelper.writableDatabase)
                                settings.putBoolean(HINT_OPENTASKS_NOT_INSTALLED, false)
                            }
                        })
                        .create()
            }
        }
    }

}