package com.mamekyo.usagetracker.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Single source of truth, stored only in app-private storage.
 * Non-secret state lives in `state.json`; tokens live in a Keystore-encrypted file.
 */
class Store private constructor(context: Context) {
    private val stateFile = AtomicFile(File(context.filesDir, "state.json"))
    private val credentialsFile = AtomicFile(File(context.filesDir, "credentials.bin"))
    private val mutex = Mutex()
    private val credentials: MutableMap<String, Credentials> = readCredentials()
    private val _state = MutableStateFlow(readState())

    val state: StateFlow<AppState> = _state.asStateFlow()

    suspend fun update(transform: (AppState) -> AppState): AppState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(_state.value)
            if (next != _state.value) {
                _state.value = next
                writeState(next)
            }
            next
        }
    }

    suspend fun credentials(accountId: String): Credentials? = mutex.withLock { credentials[accountId] }

    suspend fun saveCredentials(accountId: String, value: Credentials) = withContext(Dispatchers.IO) {
        mutex.withLock {
            credentials[accountId] = value
            writeCredentials()
        }
    }

    /**
     * Adds a new account, or refreshes an existing one when the same provider identity logs in again.
     * Returns the stored account.
     */
    suspend fun upsertAccount(account: Account, value: Credentials): Account = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _state.value
            val existing = current.accounts.firstOrNull {
                it.provider == account.provider && account.externalId != null && it.externalId == account.externalId
            }
            val stored = existing?.copy(
                email = account.email ?: existing.email,
                plan = account.plan ?: existing.plan,
                needsReauth = false,
            ) ?: account
            val accounts = if (existing != null) {
                current.accounts.map { if (it.id == existing.id) stored else it }
            } else {
                current.accounts + stored
            }
            credentials[stored.id] = value
            writeCredentials()
            val next = current.copy(accounts = accounts)
            _state.value = next
            writeState(next)
            stored
        }
    }

    suspend fun removeAccount(accountId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            credentials.remove(accountId)
            writeCredentials()
            val current = _state.value
            val removedRules = current.rules.filter { (it.source as? Source.Single)?.accountId == accountId }.map { it.id }.toSet()
            val next = current.copy(
                accounts = current.accounts.filterNot { it.id == accountId },
                usage = current.usage - accountId,
                rules = current.rules.filterNot { it.id in removedRules },
                firedAlerts = current.firedAlerts.filterNot { it.substringBefore('|') in removedRules }.toSet(),
                widgets = current.widgets.mapValues { (_, source) ->
                    if (source is Source.Single && source.accountId == accountId) Source.All else source
                },
            )
            _state.value = next
            writeState(next)
        }
    }

    private fun readState(): AppState = try {
        if (stateFile.baseFile.exists()) {
            json.decodeFromString(AppState.serializer(), stateFile.readFully().decodeToString())
        } else {
            AppState()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read state, starting fresh", e)
        AppState()
    }

    private fun writeState(state: AppState) {
        write(stateFile, json.encodeToString(AppState.serializer(), state).encodeToByteArray())
    }

    private fun readCredentials(): MutableMap<String, Credentials> = try {
        if (credentialsFile.baseFile.exists()) {
            val plain = SecureBox.decrypt(credentialsFile.readFully())
            json.decodeFromString(credentialsSerializer, plain.decodeToString()).toMutableMap()
        } else {
            mutableMapOf()
        }
    } catch (e: Exception) {
        // Keystore key lost (e.g. device restore): accounts will ask for a new login.
        Log.e(TAG, "Failed to read credentials", e)
        mutableMapOf()
    }

    private fun writeCredentials() {
        val plain = json.encodeToString(credentialsSerializer, credentials).encodeToByteArray()
        write(credentialsFile, SecureBox.encrypt(plain))
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        private const val TAG = "Store"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        private val credentialsSerializer = MapSerializer(String.serializer(), Credentials.serializer())

        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext).also { instance = it }
            }
    }
}
