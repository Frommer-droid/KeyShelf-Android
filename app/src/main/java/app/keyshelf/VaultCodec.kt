package app.keyshelf

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.models.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

const val META = "SecretVault.Metadata.v1"
const val PREFIX = "SecretVault.Field."
data class VaultField(val key: String, val label: String, val value: String, val secret: Boolean)
data class VaultRow(val entry: Entry, val group: Group, val meta: JSONObject, val fields: List<VaultField>, val root: Boolean = false) {
    val id get() = entry.uuid.toString()
    val title get() = entry.fields.title?.content.orEmpty()
    val alias get() = meta.optString("alias")
    val service get() = if (root && group.name.equals("Root", true)) "Без сервиса" else group.name
    val login get() = entry.fields.userName?.content.orEmpty()
    val kind get() = meta.optString("kind", "legacy")
    val favorite get() = meta.optBoolean("favorite")
    val displayFields: List<VaultField> get() {
        val layout = meta.optJSONArray("field_layout")?.let { order ->
            (0 until order.length()).map { order.getString(it) }
        }
        val visible = if (layout == null) fields else layout.mapNotNull { name -> fields.find { it.label == name } }
        if (login.isEmpty()) return visible
        if (kind == "account" || kind == "legacy") {
            val user = VaultField("UserName", "Логин", login, false)
            return if (layout == null) listOf(user) + visible else layout.mapNotNull { name ->
                if (name == "Логин") user else visible.find { it.label == name }
            }
        }
        // Older mobile versions allowed UserName on non-account records.
        // Expose it even when field_layout omitted it; keep the stored key intact.
        val labels = visible.map { it.label }.toSet()
        val label = if (kind == "other") (1..3).map { "Поле $it" }.firstOrNull { it !in labels }
            else null
        var extra = 1
        while ("Дополнительное поле $extra" in labels) extra++
        return visible + VaultField("UserName", label ?: "Дополнительное поле $extra", login, false)
    }
}

object VaultCodec {
    fun serviceName(group: Group, rootId: UUID) =
        if (group.uuid == rootId && group.name.equals("Root", true)) "Без сервиса" else group.name
    fun credentials(password: String) = Credentials.from(EncryptedValue.fromString(password))
    fun decode(bytes: ByteArray, password: String): KeePassDatabase {
        require(bytes.size <= 32 * 1024 * 1024) { "Хранилище больше 32 МиБ" }
        return bytes.inputStream().use { KeePassDatabase.decode(it, credentials(password)) }.also {
            require(it is KeePassDatabase.Ver4x) { "Нужен KDBX4" }
            rows(it) // Validate KeyShelf metadata before exposing or replacing a file.
        }
    }
    fun encode(database: KeePassDatabase): ByteArray = ByteArrayOutputStream().use {
        database.encode(it); it.toByteArray()
    }
    fun create(password: String): KeePassDatabase {
        require(password.length >= 12) { "Мастер-пароль должен содержать минимум 12 символов" }
        val db = KeePassDatabase.Ver4x.create("KeyShelf", Meta(generator = "KeyShelf Android"), credentials(password))
        val kdf = db.header.kdfParameters as KdfParameters.Argon2
        return db.copy(header = db.header.copy(version = FormatVersion(4, 0), kdfParameters = kdf.copy(
            variant = KdfParameters.Argon2.Variant.Argon2id, memory = 64UL * 1024UL * 1024UL,
            iterations = 3UL, parallelism = 2U)))
    }
    fun rows(database: KeePassDatabase): List<VaultRow> {
        val result = mutableListOf<VaultRow>()
        fun visit(group: Group) {
            if (group.uuid == database.content.meta.recycleBinUuid) return
            group.entries.forEach { entry ->
                val meta = JSONObject(entry.fields[META]?.content ?: "{}")
                val fields = mutableListOf<VaultField>()
                val managed = mutableSetOf<String>()
                val descriptions = meta.optJSONArray("fields") ?: JSONArray()
                for (i in 0 until descriptions.length()) {
                    val f = descriptions.getJSONObject(i)
                    val key = f.getString("key")
                    require(managed.add(key)) { "Повторное описание поля" }
                    val v = entry.fields[key]
                    fields.add(VaultField(key, f.getString("label"), v?.content.orEmpty(), v is EntryValue.Encrypted))
                }
                if (!entry.fields.password?.content.isNullOrEmpty())
                    fields.add(0, VaultField("Password", "Пароль", entry.fields.password!!.content, true))
                if (!entry.fields.url?.content.isNullOrEmpty())
                    fields.add(VaultField("URL", "URL", entry.fields.url!!.content, false))
                entry.fields.forEach { (key, value) ->
                    if (key !in managed && key !in listOf(META, "Title", "UserName", "Password", "URL", "Notes"))
                        fields.add(VaultField(key, key, value.content, value is EntryValue.Encrypted))
                }
                val order = meta.optJSONArray("field_layout")
                if (order != null) {
                    val names = (0 until order.length()).map { order.getString(it) }
                    require(names.distinct().size == names.size) { "Повторные поля в порядке" }
                    fields.sortBy { names.indexOf(it.label).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                }
                result.add(VaultRow(entry, group, meta, fields, group.uuid == database.content.group.uuid))
            }
            group.groups.forEach(::visit)
        }
        visit(database.content.group)
        return result
    }
    fun search(rows: List<VaultRow>, query: String): List<VaultRow> {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return rows.filter { row ->
            val text = fold(listOf(row.service, row.title, row.alias, row.login,
                row.meta.optString("project"), row.meta.optString("tags"),
                row.fields.joinToString(" ") { it.label }).joinToString(" "))
            words.all { it in text }
        }.sortedWith(compareByDescending<VaultRow> { it.favorite }.thenBy { fold(it.title) })
    }
    // Java's lowercase differs from Python casefold for a few common characters.
    private fun fold(s: String) = s.lowercase(java.util.Locale.ROOT).replace("ß", "ss").replace("ς", "σ")
    fun saveRow(db: KeePassDatabase, old: VaultRow?, groupId: UUID, title: String, login: String,
                notes: String, kind: String, favorite: Boolean, values: List<VaultField>, paid: Boolean = false,
                preserveLayout: Boolean = false, alias: String? = null): KeePassDatabase {
        require(title.isNotBlank()) { "Укажите название" }
        if (old != null) {
            val current = db.getEntry { it.uuid == old.entry.uuid }?.second
            require(current != null && current.fields.entries.associate { it.key to it.value.content } == old.entry.fields.entries.associate { it.key to it.value.content }) {
                "Запись изменилась при синхронизации. Откройте редактор заново."
            }
        }
        if (kind == "account") values.firstOrNull { it.label == "Секрет 2FA" && it.value.isNotBlank() }?.let { Totp.code(it.value) }
        val meta = JSONObject(old?.meta?.toString() ?: "{}")
        if (alias != null) meta.put("alias", alias.trim())
        meta.put("kind", kind).put("favorite", favorite).put("api_paid", paid)
        val descriptions = JSONArray()
        val layout = JSONArray()
        if (kind == "account") layout.put("Логин")
        val map = (old?.entry?.fields ?: EntryFields.createDefault()).toMutableMap()
        // Only replace managed fields; preserve unrelated fields, attachments and history.
        old?.fields?.forEach { map.remove(it.key) }
        map["Title"] = EntryValue.Plain(title.trim())
        map["UserName"] = EntryValue.Plain(login)
        map["Notes"] = EntryValue.Plain(notes)
        var extraIndex = 0
        val normalized = values.map { field ->
            if (field.label.startsWith("Дополнительное поле ")) field.copy(label = "Дополнительное поле ${++extraIndex}") else field
        }
        val baseLabels = when (kind) { "account" -> listOf("Пароль", "Секрет 2FA"); "api" -> listOf("API-ключ"); "other" -> listOf("Поле 1", "Поле 2", "Поле 3"); else -> emptyList() }
        val removedBase = baseLabels.filter { label -> normalized.none { it.label == label } }.map { VaultField("", it, "", true) }
        (normalized + removedBase).forEach { field ->
            val key = if (field.key.isBlank()) PREFIX + UUID.randomUUID() else field.key
            map[key] = if (field.secret) secret(field.value) else EntryValue.Plain(field.value)
            if (key != "Password" && key != "URL")
                descriptions.put(JSONObject().put("key", key).put("label", field.label))
            if (field !in removedBase) layout.put(field.label)
        }
        meta.put("fields", descriptions).put("field_layout", layout)
        if (preserveLayout && old != null) {
            if (old.meta.has("field_layout")) meta.put("field_layout", old.meta.get("field_layout"))
            else meta.remove("field_layout")
        }
        map[META] = secret(meta.toString())
        var entry = (old?.entry ?: Entry(UUID.randomUUID())).copy(fields = EntryFields(map))
        entry = entry.copy(times = entry.times?.copy(lastModificationTime = java.time.Instant.now()),
            history = if (old == null) entry.history else entry.history + old.entry.copy(history = emptyList()))
        return if (old != null) db.modifyEntry(entry.uuid) { entry }.moveEntry(entry.uuid, groupId)
        else db.modifyGroup(groupId) { copy(entries = entries + entry) }
    }
    fun secret(value: String) = EntryValue.Encrypted(EncryptedValue.fromString(value))
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
