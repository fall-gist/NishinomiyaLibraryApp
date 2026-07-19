package com.fallgist.nishinomiyalibrary.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.fallgist.nishinomiyalibrary.R
import com.fallgist.nishinomiyalibrary.ui.debug.DebugScreenController
import com.fallgist.nishinomiyalibrary.ui.debug.RegistrationAction
import com.fallgist.nishinomiyalibrary.ui.debug.RegistrationErrors
import com.fallgist.nishinomiyalibrary.ui.debug.RegistrationForm
import com.fallgist.nishinomiyalibrary.ui.di.MainActivityEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 標準Android Viewだけで構成する、最小限のランチャー画面。 */
open class MainActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: DebugScreenController
    private lateinit var nameInput: EditText
    private lateinit var colorInput: EditText
    private lateinit var cardInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var registerButton: Button
    private lateinit var syncButton: Button
    private lateinit var notificationButton: Button
    private lateinit var registrationStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var scheduleWarning: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var membersText: TextView
    private lateinit var loansText: TextView
    private lateinit var reservationsText: TextView
    private lateinit var shelvesText: TextView
    private lateinit var summariesText: TextView
    private lateinit var lastSyncText: TextView
    private lateinit var readingRecordsCountText: TextView
    private lateinit var readingRecordsSearchInput: EditText
    private lateinit var readingRecordsText: TextView

    private var registrationInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        super.onCreate(savedInstanceState)
        screenContentView()?.let(::setContentView) ?: setContentView(R.layout.activity_main)
        controller = resolveController()

        bindViews()
        bindActions()
        renderNotificationStatus()
        uiScope.launch {
            controller.state.collect { state ->
                syncButton.isEnabled = !state.isSyncInProgress
                syncStatus.text = state.syncMessage
                scheduleWarning.text = state.scheduleWarning.orEmpty()
                membersText.text = state.display.memberLines.toDisplayText(R.string.no_members)
                loansText.text = state.display.loanLines.toDisplayText(R.string.no_loans)
                reservationsText.text = state.display.reservationLines.toDisplayText(R.string.no_reservations)
                shelvesText.text = state.display.shelfLines.toDisplayText(R.string.no_shelves)
                summariesText.text = state.display.summaryLines.toDisplayText(R.string.no_summaries)
                lastSyncText.text = state.display.lastSyncLine
                readingRecordsCountText.text = formattedStringFor(
                    R.string.reading_records_count,
                    state.display.readingRecordCount,
                )
                readingRecordsText.text = state.display.readingRecordLines.toDisplayText(R.string.no_reading_records)
            }
        }
        uiScope.launch { controller.onScreenLaunched() }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) renderNotificationStatus()
    }

    private fun bindViews() {
        nameInput = findViewById(R.id.member_name_input)
        colorInput = findViewById(R.id.member_color_input)
        cardInput = findViewById(R.id.member_card_input)
        passwordInput = findViewById(R.id.member_password_input)
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        registerButton = findViewById(R.id.register_member_button)
        syncButton = findViewById(R.id.manual_sync_button)
        notificationButton = findViewById(R.id.notification_permission_button)
        registrationStatus = findViewById(R.id.registration_status_text)
        syncStatus = findViewById(R.id.sync_status_text)
        scheduleWarning = findViewById(R.id.schedule_warning_text)
        notificationStatus = findViewById(R.id.notification_status_text)
        membersText = findViewById(R.id.members_text)
        loansText = findViewById(R.id.loans_text)
        reservationsText = findViewById(R.id.reservations_text)
        shelvesText = findViewById(R.id.shelves_text)
        summariesText = findViewById(R.id.summaries_text)
        lastSyncText = findViewById(R.id.last_sync_text)
        readingRecordsCountText = findViewById(R.id.reading_records_count_text)
        readingRecordsSearchInput = findViewById(R.id.reading_records_search_input)
        readingRecordsText = findViewById(R.id.reading_records_text)
    }

    private fun bindActions() {
        registerButton.setOnClickListener {
            if (registrationInProgress) return@setOnClickListener
            registrationInProgress = true
            registerButton.isEnabled = false
            uiScope.launch {
                when (val action = controller.register(currentRegistrationForm())) {
                    RegistrationAction.Saved -> {
                        cardInput.text.clear()
                        passwordInput.text.clear()
                        registrationStatus.text = stringFor(R.string.member_registered)
                    }

                    is RegistrationAction.ValidationFailed -> {
                        showRegistrationErrors(action.errors)
                        registrationStatus.text = stringFor(R.string.registration_invalid)
                    }

                    RegistrationAction.Failed -> {
                        registrationStatus.text = stringFor(R.string.registration_failed)
                    }
                }
                registrationInProgress = false
                registerButton.isEnabled = true
            }
        }
        syncButton.setOnClickListener {
            if (!syncButton.isEnabled) return@setOnClickListener
            syncButton.isEnabled = false
            uiScope.launch { controller.requestManualSync() }
        }
        notificationButton.setOnClickListener { requestNotificationPermissionFromUserAction() }
        readingRecordsSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                controller.updateReadingRecordSearch(text?.toString().orEmpty())
            }

            override fun afterTextChanged(editable: Editable?) = Unit
        })
    }

    private fun currentRegistrationForm(): RegistrationForm = RegistrationForm(
        name = nameInput.text.toString(),
        colorHex = colorInput.text.toString(),
        cardNumber = cardInput.text.toString(),
        password = passwordInput.text.toString(),
    )

    private fun showRegistrationErrors(errors: RegistrationErrors) {
        nameInput.error = errors.name
        colorInput.error = errors.colorHex
        cardInput.error = errors.cardNumber
        passwordInput.error = errors.password
    }

    private fun requestNotificationPermissionFromUserAction() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationStatus.text = stringFor(R.string.notification_not_required)
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationStatus.text = stringFor(R.string.notification_granted)
            return
        }
        val preferences = getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(NOTIFICATION_PERMISSION_REQUESTED_KEY, false)) {
            notificationStatus.text = stringFor(R.string.notification_denied)
            return
        }
        preferences.edit().putBoolean(NOTIFICATION_PERMISSION_REQUESTED_KEY, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
    }

    private fun renderNotificationStatus() {
        notificationStatus.text = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> stringFor(R.string.notification_not_required)
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ->
                stringFor(R.string.notification_granted)
            else -> stringFor(R.string.notification_not_granted)
        }
    }

    private fun List<String>.toDisplayText(emptyTextResId: Int): String =
        if (isEmpty()) stringFor(emptyTextResId) else joinToString(separator = "\n\n")

    /** 本番ではXMLを使い、構造テストだけが最小Viewツリーを渡す。 */
    protected open fun screenContentView(): View? = null

    /** 本番の依存解決はApplication EntryPointに限定する。 */
    protected open fun resolveController(): DebugScreenController = EntryPointAccessors.fromApplication(
        applicationContext,
        MainActivityEntryPoint::class.java,
    ).debugScreenController()

    /** 構造テストでAndroidリソースを読まずに描画経路を検証するための境界。 */
    protected open fun stringFor(resourceId: Int): String = getString(resourceId)

    /** 構造テストでも件数表示の描画経路を通すための文字列境界。 */
    protected open fun formattedStringFor(resourceId: Int, value: Int): String = getString(resourceId, value)

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 7001
        const val NOTIFICATION_PREFERENCES = "notification_permission"
        const val NOTIFICATION_PERMISSION_REQUESTED_KEY = "notification_permission_requested"
    }
}
