package com.fallgist.nishinomiyalibrary.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.fallgist.nishinomiyalibrary.R
import com.fallgist.nishinomiyalibrary.domain.model.Loan
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.model.Reservation
import com.fallgist.nishinomiyalibrary.domain.model.ReadingInfo
import com.fallgist.nishinomiyalibrary.domain.model.ReadingRecord
import com.fallgist.nishinomiyalibrary.domain.model.ShelfItem
import com.fallgist.nishinomiyalibrary.domain.model.UserSummary
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import com.fallgist.nishinomiyalibrary.domain.repository.ReadingRecordRepository
import com.fallgist.nishinomiyalibrary.domain.repository.StatusRepository
import com.fallgist.nishinomiyalibrary.domain.repository.SyncLog
import com.fallgist.nishinomiyalibrary.domain.repository.SyncResult
import com.fallgist.nishinomiyalibrary.domain.repository.SyncTrigger
import com.fallgist.nishinomiyalibrary.ui.debug.DebugScreenController
import com.fallgist.nishinomiyalibrary.ui.debug.SyncScheduleStarter
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 実ApplicationのWorkManager初期化を必要としない構造テスト。
 * Controllerの実装テストは別途純Kotlinテストで網羅する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MainActivityTest {
    @Test
    fun launch_hasRequiredViewsPasswordInputAndSecureFlag() {
        val activity = Robolectric.buildActivity(StructuralMainActivity::class.java).setup().get()

        assertNotNull(activity.findViewById<EditText>(R.id.member_name_input))
        assertNotNull(activity.findViewById<EditText>(R.id.member_color_input))
        assertNotNull(activity.findViewById<EditText>(R.id.member_card_input))
        val password = activity.findViewById<EditText>(R.id.member_password_input)
        assertNotNull(activity.findViewById<Button>(R.id.register_member_button))
        assertNotNull(activity.findViewById<Button>(R.id.manual_sync_button))
        assertNotNull(activity.findViewById<Button>(R.id.notification_permission_button))
        assertNotNull(activity.findViewById<EditText>(R.id.reading_records_search_input))
        assertNotNull(activity.findViewById<TextView>(R.id.reading_records_count_text))
        assertNotNull(activity.findViewById<TextView>(R.id.reading_records_text))
        assertTrue(password.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun invalidForm_staysInInputsAndShowsValidationError() {
        val activity = Robolectric.buildActivity(StructuralMainActivity::class.java).setup().get()
        val card = randomCardNumber()
        val password = randomSecret()
        activity.findViewById<EditText>(R.id.member_name_input).setText("")
        activity.findViewById<EditText>(R.id.member_color_input).setText("invalid")
        activity.findViewById<EditText>(R.id.member_card_input).setText(card)
        activity.findViewById<EditText>(R.id.member_password_input).setText(password)

        activity.findViewById<Button>(R.id.register_member_button).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(card, activity.findViewById<EditText>(R.id.member_card_input).text.toString())
        assertEquals(password, activity.findViewById<EditText>(R.id.member_password_input).text.toString())
        assertNotNull(activity.findViewById<EditText>(R.id.member_name_input).error)
        assertNotNull(activity.findViewById<EditText>(R.id.member_color_input).error)
    }

    private fun randomCardNumber(): String = UUID.randomUUID().toString().filter(Char::isDigit).take(12)

    private fun randomSecret(): String = "secret-${UUID.randomUUID()}"
}

class StructuralMainActivity : MainActivity() {
    private val structuralController by lazy {
        DebugScreenController(
            familyRepository = EmptyFamilyRepository,
            statusRepository = EmptyStatusRepository,
            readingRecordRepository = EmptyReadingRecordRepository,
            scheduleStarter = EmptyScheduleStarter,
        )
    }

    override fun screenContentView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addEditText(R.id.member_name_input)
        addEditText(R.id.member_color_input)
        addEditText(R.id.member_card_input)
        addEditText(R.id.member_password_input)
        addButton(R.id.register_member_button)
        addText(R.id.registration_status_text)
        addButton(R.id.manual_sync_button)
        addText(R.id.sync_status_text)
        addText(R.id.schedule_warning_text)
        addButton(R.id.notification_permission_button)
        addText(R.id.notification_status_text)
        addText(R.id.members_text)
        addText(R.id.loans_text)
        addText(R.id.reservations_text)
        addText(R.id.shelves_text)
        addText(R.id.summaries_text)
        addText(R.id.last_sync_text)
        addText(R.id.reading_records_count_text)
        addEditText(R.id.reading_records_search_input)
        addText(R.id.reading_records_text)
    }

    override fun resolveController(): DebugScreenController = structuralController

    override fun stringFor(resourceId: Int): String = "構造テスト用"

    override fun formattedStringFor(resourceId: Int, value: Int): String = "構造テスト用"

    private fun LinearLayout.addEditText(id: Int) {
        addView(EditText(context).apply { this.id = id })
    }

    private fun LinearLayout.addButton(id: Int) {
        addView(Button(context).apply { this.id = id })
    }

    private fun LinearLayout.addText(id: Int) {
        addView(TextView(context).apply { this.id = id })
    }
}

private object EmptyFamilyRepository : FamilyRepository {
    override fun members(): Flow<List<Member>> = flowOf(emptyList())

    override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) = Unit

    override suspend fun updateMember(member: Member, newPassword: String?) = Unit

    override suspend fun removeMember(memberId: Long) = Unit
}

private object EmptyStatusRepository : StatusRepository {
    override fun loans(): Flow<List<Loan>> = flowOf(emptyList())

    override fun reservations(): Flow<List<Reservation>> = flowOf(emptyList())

    override fun shelf(memberId: Long): Flow<List<ShelfItem>> = flowOf(emptyList())

    override fun summaries(): Flow<List<UserSummary>> = flowOf(emptyList())

    override fun lastSync(): Flow<SyncLog?> = flowOf(null)

    override suspend fun syncAll(trigger: SyncTrigger): SyncResult = SyncResult.Completed(0, 0)
}

private object EmptyReadingRecordRepository : ReadingRecordRepository {
    override fun records(memberId: Long?): Flow<List<ReadingRecord>> = flowOf(emptyList())

    override fun search(query: String, memberId: Long?): Flow<List<ReadingRecord>> = flowOf(emptyList())

    override fun hasRead(tilcod: String): Flow<List<ReadingInfo>> = flowOf(emptyList())
}

private object EmptyScheduleStarter : SyncScheduleStarter {
    override suspend fun scheduleFromSettings() = Unit
}
