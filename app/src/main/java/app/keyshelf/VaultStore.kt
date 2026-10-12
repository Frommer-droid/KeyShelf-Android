package app.keyshelf

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import app.keemobile.kotpass.database.KeePassDatabase
import java.io.File
import java.util.concurrent.Executors

class KeyShelfApp : Application() {
    lateinit var store: VaultStore
    override fun onCreate() { super.onCreate(); store = VaultStore(this) }
}

class VaultStore(val app: Application, private val access: DeviceUnlock = DeviceUnlock(app),
                 private val syncClient: () -> SyncTransport = { SyncClient(app) }) {
    val file = File(app.filesDir, "vault.kdbx")
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var database: KeePassDatabase? = null; private set
    val quickSearchState = QuickSearchState()
    private var password: String? = null
    var busy = false; private set
    private var generation = 0
    private var digest: String? = null
    private var expires = 0L
    private val listeners = mutableSetOf<() -> Unit>()
    var syncNotification = false; private set
    var error = ""; private set
    var syncStatus = ""; private set
    var syncError = ""; private set
    var syncInProgress = false; private set
    val syncSummary: String get() = when {
        syncInProgress -> "Проверка соединения…"
        !SyncClient.isPaired(app) -> "Сканируйте QR на компьютере"
        syncError.isNotEmpty() -> syncError
        pendingSync != null -> "Изменения компьютера ожидают подтверждения"
        syncStatus.isNotEmpty() -> syncStatus
        else -> "Соединение ещё не проверено"
    }
    val syncReview = MobileSyncReview(app)
    val manual = ManualSync(app)
    val hasUnsavedChanges get() = manual.dirty(digest)
    val databaseName get() = app.getSharedPreferences("database_info", 0).getString("name", "KeyShelf.kdbx").orEmpty()
    private fun rememberName(name: String) {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\'))
        check(app.getSharedPreferences("database_info", 0).edit().putString("name", name).commit())
    }
    fun saveChanges() {
        val expected = digest ?: return
        if (database == null || busy || !hasUnsavedChanges) return
        job({
            val bytes = file.readBytes()
            require(VaultCodec.hash(bytes) == expected) { "База изменилась. Откройте её заново." }
            manual.publish(bytes)
        }) { syncStatus = "Пакет сохранён. Ожидается ответ компьютера."; sync() }
    }

    var pendingSync: SyncProposal? = null; private set
    val needsSyncPrompt get() = pendingSync != null
    private data class SyncOutcome(val db: KeePassDatabase?, val hash: String, val status: String, val proposal: SyncProposal? = null)
    private var subscription: AutoCloseable? = null
    private var deliveryRequested = false
    fun startDelivery() {
        if (subscription != null || database == null || !SyncClient.isPaired(app)) return
        subscription = syncClient().startListening { main.post { deliveryRequested = true; if (!busy) { deliveryRequested = false; sync() } } }
    }
    private val lockTimer = object : Runnable {
        override fun run() {
            if (database != null && access.required && android.os.SystemClock.elapsedRealtime() >= expires) lock()
            main.postDelayed(this, 5000)
        }
    }
    init { main.postDelayed(lockTimer, 5000); main.post { if (!provisionPrivateSeed()) autoUnlock() } }
    val masterPasswordRequired get() = access.required
    val hideRecordSecrets get() = app.getSharedPreferences("display", 0).getBoolean("hide_record_secrets", true)
    fun setHideRecordSecrets(hidden: Boolean) {
        app.getSharedPreferences("display", 0).edit().putBoolean("hide_record_secrets", hidden).apply()
        notifyState()
    }
    val needsPassword get() = access.required || access.password() == null
    fun setMasterPasswordRequired(required: Boolean) {
        val pass = password
        job({ access.setRequired(required, pass) })
    }
    fun autoUnlock() {
        // App/Activity/accessibility startup must not race the private verified import.
        if (File(app.filesDir, "provision.json").exists() &&
            app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        if (database != null || busy || !file.exists()) return
        val pass = access.password() ?: return
        unlock(pass, automatic = true)
    }
    fun listen(callback: () -> Unit) { listeners.add(callback) }
    fun unlisten(callback: () -> Unit) { listeners.remove(callback) }
    private fun notifyState(sync: Boolean = false) {
        syncNotification = sync
        try { listeners.toList().forEach { it() } } finally { syncNotification = false }
    }
    fun touch() { expires = android.os.SystemClock.elapsedRealtime() + 5 * 60 * 1000 }
    fun lock() {
        quickSearchState.clear()
        subscription?.close(); subscription = null; deliveryRequested = false
        generation++; database = null; password = null; digest = null; error = ""; pendingSync = null
        FloatingService.instance?.closeSearch(); notifyState()
    }
    fun job(work: () -> Any?, syncOperation: Boolean = false, done: (Any?) -> Unit = {}) {
        if (busy) return
        busy = true; error = ""; syncInProgress = syncOperation
        val epoch = generation; notifyState(syncOperation)
        executor.execute {
            val result = runCatching(work)
            main.post {
                busy = false; syncInProgress = false
                if (epoch == generation) {
                    if (result.isSuccess) {
                        if (syncOperation) syncError = ""
                        done(result.getOrNull())
                    }
                    else {
                        val failure = result.exceptionOrNull()!!
                        android.util.Log.e("KeyShelfDiagnostics", OperationErrors.types(failure))
                        error = OperationErrors.message(failure)
                        if (syncOperation) syncError = error
                    }
                }
                notifyState(syncOperation)
                if (!busy && deliveryRequested && database != null) { deliveryRequested = false; sync() }
                startDelivery()
            }
        }
    }
    fun pair(invitation: String) = job({ syncClient().pair(invitation) }) {
        syncError = ""
        syncStatus = "Сопряжение сохранено. Соединение проверено."
    }
    fun unlock(pass: String, automatic: Boolean = false) = job({
        val bytes = AtomicFile(file).openRead().use { it.readBytes() }
        val verified = try { VaultCodec.decode(bytes, pass) } catch (failure: Exception) {
            if (automatic) access.forget()
            throw failure
        }
        access.remember(pass)
        Triple(verified, VaultCodec.hash(bytes), syncReview.load(pass, bytes))
    }) {
        @Suppress("UNCHECKED_CAST") val result = it as Triple<KeePassDatabase, String, SyncProposal?>
        password = pass; database = result.first; digest = result.second; manual.initialize(result.second); touch(); pendingSync = result.third
    }
    fun create(pass: String) = job({
        require(!file.exists()) { "Хранилище уже существует. Сначала сохраните его копию." }
        val db = VaultCodec.create(pass)
        val bytes = VaultCodec.encode(db)
        val verified = VaultCodec.decode(bytes, pass)
        access.remember(pass)
        write(bytes, false)
        Pair(verified, VaultCodec.hash(bytes))
    }) {
        @Suppress("UNCHECKED_CAST") val result = it as Pair<KeePassDatabase, String>
        password = pass; database = result.first; digest = result.second; manual.initialize(result.second); touch()
    }
    fun importFile(bytes: ByteArray, pass: String) = job({
        require(!file.exists()) { "Хранилище уже существует. Используйте синхронизацию или сохраните копию и удалите локальную базу." }
        val verified = VaultCodec.decode(bytes, pass)
        access.remember(pass)
        write(bytes, false); Pair(verified, VaultCodec.hash(bytes))
    }) {
        @Suppress("UNCHECKED_CAST") val result = it as Pair<KeePassDatabase, String>
        password = pass; database = result.first; digest = result.second; manual.initialize(result.second); touch()
    }
    fun download(pass: String) = job({
        require(!file.exists()) { "Локальное хранилище уже существует" }
        val client = syncClient()
        val result = client.exchange(null)
        val bytes = result.bytes ?: error("Нет хранилища")
        require(VaultCodec.hash(bytes) == result.hash)
        val verified = VaultCodec.decode(bytes, pass)
        access.remember(pass)
        write(bytes, false); client.acknowledge(result.hash); manual.received(result.hash); rememberName(result.name)
        Pair(verified, result.hash)
    }, syncOperation = true) {
        @Suppress("UNCHECKED_CAST") val result = it as Pair<KeePassDatabase, String>
        password = pass; database = result.first; digest = result.second; manual.initialize(result.second); touch()
        syncStatus = "Хранилище получено с компьютера"
    }
    fun mutate(change: (KeePassDatabase) -> KeePassDatabase) {
        val db = database ?: return
        val pass = password ?: return
        val expected = digest
        job({
            require(VaultCodec.hash(file.readBytes()) == expected) { "Файл изменился. Откройте его заново." }
            val modified = change(db)
            val bytes = VaultCodec.encode(modified)
            val verified = VaultCodec.decode(bytes, pass)
            require(VaultCodec.hash(file.readBytes()) == expected) { "Файл изменился во время сохранения." }
            write(bytes, true); Pair(verified, VaultCodec.hash(bytes))
        }) {
            @Suppress("UNCHECKED_CAST") val result = it as Pair<KeePassDatabase, String>
            database = result.first; digest = result.second; touch(); pendingSync = null; SyncAlerts.clear(app)
        }
    }
    private fun write(bytes: ByteArray, backup: Boolean) {
        if (backup && file.exists()) atomic(File(app.filesDir, "vault.previous.kdbx"), file.readBytes())
        atomic(file, bytes)
    }
    private fun atomic(target: File, bytes: ByteArray) {
        val f = AtomicFile(target)
        val stream = f.startWrite()
        try { stream.write(bytes); f.finishWrite(stream) } catch (e: Exception) { f.failWrite(stream); throw e }
    }
    fun sync() {
        if (database == null || busy || !SyncClient.isPaired(app)) return
        val pass = password ?: return
        val expected = digest ?: return
        val current = database
        val epoch = generation
                job({
            val client = syncClient()
            val bytes = file.readBytes()
            require(VaultCodec.hash(bytes) == expected) { "Локальная база изменилась" }
            val result = client.exchange(bytes, manual.outgoing())
            require(epoch == generation && database != null) { "Хранилище заблокировано" }
            when (result.action) {
                "restore" -> {
                    require(result.expectedLocal == expected) { "База телефона изменилась после ответа компьютера" }
                    val remote = result.bytes ?: error("Нет базы восстановления")
                    require(VaultCodec.hash(remote) == result.hash)
                    val decoded = VaultCodec.decode(remote, pass)
                    require(epoch == generation && database != null && VaultCodec.hash(file.readBytes()) == expected) { "База телефона изменилась" }
                    syncReview.copy(bytes); syncReview.copy(remote)
                    write(remote, true); client.acknowledge(result.hash); syncReview.clear(); manual.received(result.hash)
                    SyncOutcome(decoded, result.hash, "Компьютер отклонил изменения. База телефона восстановлена.")
                }
                "pull" -> {
                    val proposal = syncReview.stage(bytes, result.bytes ?: error("Нет входящей базы"), pass, result)
                    rememberName(result.name)
                    SyncOutcome(current, expected, "Изменения ожидают подтверждения", proposal)
                }
                "push" -> SyncOutcome(current, expected, "Ожидается подтверждение компьютера")
                "ack" -> {
                    manual.acknowledge(result.hash); client.acknowledge(result.hash)
                    syncReview.clear(); SyncOutcome(current, expected, "Сохранённый пакет принят компьютером")
                }
                "idle" -> { syncReview.clear(); SyncOutcome(current, expected, if (manual.dirty(expected)) "Есть локальные изменения · нажмите «Сохранить»" else "Ручная отправка изменений") }
                else -> { manual.acknowledge(result.hash); syncReview.clear(); SyncOutcome(current, expected, "Базы одинаковые") }
            }
        }, syncOperation = true) {
            val result = it as SyncOutcome
            database = result.db; digest = result.hash; syncStatus = result.status; pendingSync = result.proposal
            if (pendingSync == null) SyncAlerts.clear(app)
            else if (needsSyncPrompt) SyncAlerts.show(app, pendingSync!!.modifiedAt)
        }
    }
    fun acceptSync(reviewed: SyncProposal? = pendingSync) {
        val proposal = reviewed ?: return
        val pass = password ?: return
        val expected = digest ?: return
        if (busy || proposal != pendingSync || expected != proposal.localHash) return
        val epoch = generation
        job({
            val local = file.readBytes()
            require(VaultCodec.hash(local) == proposal.localHash) { "База телефона изменилась. Проверьте синхронизацию заново." }
            val remote = syncReview.remote(proposal)
            val decoded = VaultCodec.decode(remote, pass)
            val client = syncClient()
            client.verifyRemote(local, proposal.remoteHash)
            require(epoch == generation && database != null && VaultCodec.hash(file.readBytes()) == expected) { "База телефона изменилась или заблокирована во время подтверждения" }
            syncReview.copy(local); syncReview.copy(remote)
            write(remote, true); client.acknowledge(proposal.remoteHash); syncReview.clear(); manual.received(proposal.remoteHash)
            Pair(decoded, proposal.remoteHash)
        }, syncOperation = true) {
            @Suppress("UNCHECKED_CAST") val result = it as Pair<KeePassDatabase, String>
            database = result.first; digest = result.second; pendingSync = null
            syncStatus = "Версия компьютера принята. Обе копии сохранены."
            SyncAlerts.clear(app); sync()
        }
    }
    fun rejectSync(reviewed: SyncProposal? = pendingSync) {
        val proposal = reviewed ?: return
        val expected = digest ?: return
        if (busy || proposal != pendingSync || expected != proposal.localHash) return
        val epoch = generation
        job({
            val local = file.readBytes()
            require(VaultCodec.hash(local) == expected) { "База телефона изменилась. Проверьте синхронизацию заново." }
            syncReview.copy(local); syncReview.copy(syncReview.remote(proposal))
            syncClient().rejectRemote(local, proposal.remoteHash)
            require(epoch == generation && database != null) { "Хранилище заблокировано" }
            syncReview.clear(); manual.received(expected)
        }, syncOperation = true) {
            pendingSync = null
            syncStatus = "Изменения отклонены. База компьютера восстановлена по версии телефона."
            SyncAlerts.clear(app)
        }
    }
    private fun provisionPrivateSeed(): Boolean {
        val metadata = File(app.filesDir, "provision.json")
        if (!metadata.exists() || app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        job({
            val info = org.json.JSONObject(metadata.readText(Charsets.UTF_8))
            val pass = info.getString("password")
            val seed = File(app.filesDir, "provision.kdbx")
            val bytes = seed.readBytes()
            require(VaultCodec.hash(bytes) == info.getString("hash")) { "Неверная исходная база" }
            val db = VaultCodec.decode(bytes, pass)
            // Validate before deleting the obsolete phone databases explicitly authorised by the owner.
            for (old in app.filesDir.listFiles().orEmpty()) {
                if (old.name in listOf("provision.json", "provision.kdbx")) continue
                require(old.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator))
                check(old.deleteRecursively())
            }
            syncReview.clear(); write(bytes, false); manual.received(VaultCodec.hash(bytes)); rememberName(info.getString("name"))
            access.remember(pass); if (SyncClient.isPaired(app)) syncClient().acknowledge(VaultCodec.hash(bytes))
            check(seed.delete()); check(metadata.delete())
            atomic(File(app.filesDir, "provision-receipt.json"), org.json.JSONObject().put("hash", VaultCodec.hash(bytes))
                .put("name", databaseName).put("ok", true).toString().toByteArray(Charsets.UTF_8))
            Triple(db, VaultCodec.hash(bytes), pass)
        }) {
            @Suppress("UNCHECKED_CAST") val result = it as Triple<KeePassDatabase, String, String>
            database = result.first; digest = result.second; password = result.third; pendingSync = null; touch()
            syncStatus = "Открыта точная копия базы компьютера"
        }
        return true
    }
    fun clearPairing() {
        subscription?.close(); subscription = null;
        SyncClient.clear(app); syncReview.clear(); pendingSync = null; SyncAlerts.clear(app); syncError = ""; syncStatus = ""; notifyState()
    }
}
