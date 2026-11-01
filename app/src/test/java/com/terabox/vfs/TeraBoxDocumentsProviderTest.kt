package com.terabox.vfs

import com.terabox.vfs.data.TeraBoxDatabaseHelper
import com.terabox.vfs.provider.TeraBoxDocumentsProvider
import org.junit.Assert.*
import org.junit.Test

class TeraBoxDocumentsProviderTest {

    @Test
    fun testDocIdBuildingAndParsing() {
        val accountId = "acc_user_123_test"
        val fsId = 9876543210123L

        val docId = TeraBoxDocumentsProvider.buildDocId(accountId, fsId)
        assertEquals("doc:acc_user_123_test:9876543210123", docId)

        val (parsedAccount, parsedFsId) = TeraBoxDocumentsProvider.parseDocId(docId)
        assertEquals(accountId, parsedAccount)
        assertEquals(fsId, parsedFsId)
    }

    @Test
    fun testRootDocIdParsing() {
        val accountId = "terabox_personal"
        val rootDocId = TeraBoxDocumentsProvider.buildDocId(accountId, 0L)
        assertEquals("doc:terabox_personal:0", rootDocId)

        val (parsedAccount, parsedFsId) = TeraBoxDocumentsProvider.parseDocId(rootDocId)
        assertEquals(accountId, parsedAccount)
        assertEquals(0L, parsedFsId)
    }

    @Test
    fun testLegacyDocIdParsingFallback() {
        val legacyDocId = "doc_myaccount_12345"
        val (parsedAccount, parsedFsId) = TeraBoxDocumentsProvider.parseDocId(legacyDocId)
        assertEquals("myaccount", parsedAccount)
        assertEquals(12345L, parsedFsId)
    }

    @Test
    fun testParentPathNormalization() {
        assertEquals("/", TeraBoxDatabaseHelper.normalizeParentPath("/"))
        assertEquals("/", TeraBoxDatabaseHelper.normalizeParentPath(""))
        assertEquals("/Games", TeraBoxDatabaseHelper.normalizeParentPath("/Games"))
        assertEquals("/Games", TeraBoxDatabaseHelper.normalizeParentPath("/Games/"))
        assertEquals("/Games/Emulators", TeraBoxDatabaseHelper.normalizeParentPath("/Games/Emulators/"))
        assertEquals("/Games/Emulators", TeraBoxDatabaseHelper.normalizeParentPath("Games/Emulators"))
    }
}
