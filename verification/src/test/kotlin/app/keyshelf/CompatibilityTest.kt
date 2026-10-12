package app.keyshelf

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import app.keemobile.kotpass.database.modifiers.modifyGroup
import app.keemobile.kotpass.models.Group
import java.util.UUID

class CompatibilityTest {
    private val folder = File(System.getProperty("fixtureDir"))
    private val pass = "synthetic-master-123"
    @Test fun separateNameAndAliasRoundtrip() {
        val db = VaultCodec.decode(File(folder, "windows-alias.kdbx").readBytes(), pass)
        val row = VaultCodec.rows(db).single()
        assertEquals("Личный аккаунт", row.title)
        assertEquals(".p", row.alias)
        assertEquals(row, VaultCodec.search(listOf(row), "Google .p").single())
        val saved = VaultCodec.saveRow(db, row, row.group.uuid, row.title, row.login, "", row.kind,
            row.favorite, row.fields, alias = ".phone")
        File(folder, "android-alias.kdbx").writeBytes(VaultCodec.encode(saved))
        val updated = VaultCodec.rows(saved).single()
        assertEquals("Личный аккаунт", updated.title)
        assertEquals(".phone", updated.alias)
        assertTrue(VaultCodec.search(listOf(updated), ".p").isNotEmpty())
        val cleared = VaultCodec.saveRow(saved, updated, updated.group.uuid, updated.title, updated.login,
            "", updated.kind, updated.favorite, updated.fields, alias = "")
        assertTrue(VaultCodec.search(VaultCodec.rows(cleared), ".phone").isEmpty())
    }
    @Test fun windowsToAndroidToWindows() {
        val db = VaultCodec.decode(File(folder, "windows.kdbx").readBytes(), pass)
        val rows = VaultCodec.rows(db)
        val row = rows.first { it.kind == "account" }
        assertEquals("Дополнительное поле 1", row.fields.last().label)
        assertEquals("synthetic-extra", row.fields.last().value)
        assertTrue(row.favorite)
        assertEquals(0, VaultCodec.search(rows, "synthetic-password").size)
        assertEquals(1, VaultCodec.search(rows, "почта work-login").size)
        val modified = VaultCodec.saveRow(db, row, row.group.uuid, "Почта с телефона", row.login,
            row.entry.fields.notes!!.content, row.kind, row.favorite, row.fields.map {
                if (it.label == "Пароль") it.copy(value = "synthetic-mobile-password") else it
            }, alias = ".p")
        val bytes = VaultCodec.encode(modified)
        File(folder, "android-edited.kdbx").writeBytes(bytes)
        val decoded = VaultCodec.decode(bytes, pass)
        assertEquals(".p", VaultCodec.rows(decoded).first { it.kind == "account" }.alias)
        assertEquals("Почта с телефона", VaultCodec.search(VaultCodec.rows(decoded), ".p").single().title)
        val withAlias = VaultCodec.rows(decoded).first { it.kind == "account" }
        val fieldEdit = VaultCodec.saveRow(decoded, withAlias, withAlias.group.uuid, withAlias.title, withAlias.login,
            "", withAlias.kind, withAlias.favorite, withAlias.fields, preserveLayout = true)
        assertEquals(".p", VaultCodec.rows(fieldEdit).first { it.kind == "account" }.alias)
        assertEquals("synthetic-mobile-password", VaultCodec.rows(decoded).first { it.kind == "account" }.fields.first { it.label == "Пароль" }.value)
        assertThrows(Exception::class.java) { VaultCodec.decode(bytes, "wrong-password") }
        assertThrows(Exception::class.java) { VaultCodec.decode(bytes.copyOf(bytes.size / 2), pass) }
    }
    @Test fun mobileCreatedVault() {
        val db = VaultCodec.create(pass)
        val group = Group(UUID.randomUUID(), "Мобильный сервис")
        val withGroup = db.modifyGroup(db.content.group.uuid) { copy(groups = groups + group) }
        val saved = VaultCodec.saveRow(withGroup, null, group.uuid, "Мобильный аккаунт", "phone-login", "synthetic note", "account", false,
            listOf(VaultField("", "Пароль", "synthetic-new-password", true), VaultField("", "Секрет 2FA", "", true)))
        File(folder, "android-created.kdbx").writeBytes(VaultCodec.encode(saved))
    }
    @Test fun totpVectors() {
        assertEquals("94287082", Totp.code("otpauth://totp/test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8", 59))
        assertEquals("287082", Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59))
        assertThrows(IllegalArgumentException::class.java) { Totp.code("not-a-secret") }
    }
    @Test fun staleEditorCannotOverwriteNewRecord() {
        val db = VaultCodec.decode(File(folder, "windows.kdbx").readBytes(), pass)
        val row = VaultCodec.rows(db).first { it.kind == "account" }
        val changed = VaultCodec.saveRow(db, row, row.group.uuid, "Changed remotely", row.login, "", row.kind, false, row.fields)
        assertThrows(IllegalArgumentException::class.java) {
            VaultCodec.saveRow(changed, row, row.group.uuid, "Stale overwrite", row.login, "", row.kind, false, row.fields)
        }
    }
    @Test fun removingBaseAndRenumberingExtrasPreservesWindowsSchema() {
        val db = VaultCodec.decode(File(folder, "windows.kdbx").readBytes(), pass)
        val row = VaultCodec.rows(db).first { it.kind == "account" }
        val changed = VaultCodec.saveRow(db, row, row.group.uuid, row.title, row.login, "", row.kind, false,
            listOf(VaultField("", "Дополнительное поле 3", "synthetic-extra3", true)))
        val decoded = VaultCodec.rows(changed).first { it.kind == "account" }
        assertTrue(decoded.fields.any { it.label == "Пароль" && it.value.isEmpty() })
        assertTrue(decoded.fields.any { it.label == "Секрет 2FA" && it.value.isEmpty() })
        assertTrue(decoded.fields.any { it.label == "Дополнительное поле 1" && it.value == "synthetic-extra3" })
        File(folder, "android-deleted-fields.kdbx").writeBytes(VaultCodec.encode(changed))
    }
}
