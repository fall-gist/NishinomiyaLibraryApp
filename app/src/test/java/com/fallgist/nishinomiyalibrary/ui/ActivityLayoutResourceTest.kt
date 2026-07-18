package com.fallgist.nishinomiyalibrary.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** 実際のレイアウトXMLから、資格情報入力の状態保存を無効化していることを確認する。 */
class ActivityLayoutResourceTest {
    @Test
    fun credentialInputs_doNotSaveViewState() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory.newDocumentBuilder()
            .parse(activityLayoutFile())
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val attributesById = (0 until document.getElementsByTagName("EditText").length).associate { index ->
            val element = document.getElementsByTagName("EditText").item(index) as org.w3c.dom.Element
            element.getAttributeNS(androidNamespace, "id") to element.getAttributeNS(androidNamespace, "saveEnabled")
        }

        assertEquals("false", attributesById["@+id/member_card_input"])
        assertEquals("false", attributesById["@+id/member_password_input"])
    }

    private fun activityLayoutFile(): File = sequenceOf(
        File("app/src/main/res/layout/activity_main.xml"),
        File("src/main/res/layout/activity_main.xml"),
    ).firstOrNull(File::isFile) ?: error("activity_main.xml が見つかりません")
}
