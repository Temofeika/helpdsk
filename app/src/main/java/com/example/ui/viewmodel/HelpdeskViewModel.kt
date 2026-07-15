package com.example.ui.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.ArticleEntity
import com.example.data.database.AttachmentEntity
import com.example.data.database.TicketEntity
import com.example.data.repository.TicketRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface LoginUiState {
    object Idle : LoginUiState
    object Loading : LoginUiState
    data class Success(val sessionId: String) : LoginUiState
    data class Error(val message: String) : LoginUiState
}

sealed interface SyncUiState {
    object Idle : SyncUiState
    object Loading : SyncUiState
    data class Success(val message: String) : SyncUiState
    data class Error(val message: String) : SyncUiState
}

data class NotificationModel(
    val id: Long,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

class HelpdeskViewModel(
    application: Application,
    private val repository: TicketRepository
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    // Auth States
    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    // Sync States
    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    // Filters and UI parameters
    val searchQuery = MutableStateFlow("")
    val selectedQueue = MutableStateFlow("Все")

    // Dynamic Connection State
    val isOnline = MutableStateFlow(true)

    // In-app Notification list (For simulation / Push display)
    private val _inAppNotifications = MutableStateFlow<List<NotificationModel>>(emptyList())
    val inAppNotifications: StateFlow<List<NotificationModel>> = _inAppNotifications.asStateFlow()

    // Local copy of all tickets flowing from repository
    val tickets: StateFlow<List<TicketEntity>> = repository.allTickets
        .combine(searchQuery) { list, query ->
            if (query.isBlank()) list else list.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.number.contains(query, ignoreCase = true) ||
                it.customerUser.contains(query, ignoreCase = true)
            }
        }
        .combine(selectedQueue) { list, queue ->
            if (queue == "Все") list else list.filter { it.queue == queue }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Queues for filters
    val availableQueues: StateFlow<List<String>> = repository.allTickets
        .map { list ->
            listOf("Все") + list.map { it.queue }.distinct().sorted()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = listOf("Все")
        )

    // Selected Ticket Details
    private val _selectedTicketId = MutableStateFlow<Int?>(null)
    val selectedTicketId: StateFlow<Int?> = _selectedTicketId.asStateFlow()

    val selectedTicket: StateFlow<TicketEntity?> = _selectedTicketId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repository.getTicket(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val selectedTicketArticles: StateFlow<List<ArticleEntity>> = _selectedTicketId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.getArticlesForTicket(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var pollJob: Job? = null

    init {
        createNotificationChannel()
        startBackgroundPollingSimulator()
    }

    fun selectTicket(ticketId: Int?) {
        _selectedTicketId.value = ticketId
    }

    fun getLoggedInUser(): String? {
        return repository.getLoggedInUser()
    }

    // Login action
    fun performLogin(email: String, passwordText: String) {
        viewModelScope.launch {
            _loginState.value = LoginUiState.Loading
            val result = repository.login(email, passwordText)
            result.fold(
                onSuccess = { sessionId ->
                    _loginState.value = LoginUiState.Success(sessionId)
                    // Trigger first data load
                    refreshTickets()
                },
                onFailure = { error ->
                    _loginState.value = LoginUiState.Error(error.localizedMessage ?: "Ошибка аутентификации")
                }
            )
        }
    }

    fun logout() {
        repository.logout()
        _loginState.value = LoginUiState.Idle
        _selectedTicketId.value = null
    }

    // Refresh Ticket records
    fun refreshTickets() {
        viewModelScope.launch {
            if (!isOnline.value) {
                _syncState.value = SyncUiState.Success("Работа в офлайн режиме. Используются кэшированные данные.")
                return@launch
            }
            _syncState.value = SyncUiState.Loading
            val result = repository.refreshTickets()
            result.fold(
                onSuccess = {
                    _syncState.value = SyncUiState.Success("Данные успешно синхронизированы")
                    // Try to sync any pending replies too
                    syncPendingRepliesLocally()
                },
                onFailure = { error ->
                    _syncState.value = SyncUiState.Error(error.localizedMessage ?: "Ошибка синхронизации данных")
                }
            )
        }
    }

    // Reply to a Ticket
    fun sendReply(ticketId: Int, messageText: String, onFinished: () -> Unit = {}) {
        viewModelScope.launch {
            val subject = "Ответ сотрудника через мобильное приложение"
            val result = repository.submitReply(ticketId, subject, messageText)
            result.fold(
                onSuccess = {
                    onFinished()
                },
                onFailure = { error ->
                    Log.e("HelpdeskViewModel", "Failed to submit reply: ${error.message}")
                }
            )
        }
    }

    // Manually sync pending offline replies
    private fun syncPendingRepliesLocally() {
        viewModelScope.launch {
            val result = repository.syncPendingReplies()
            result.fold(
                onSuccess = { count ->
                    if (count > 0) {
                        Log.d("HelpdeskViewModel", "Successfully synced $count offline replies!")
                    }
                },
                onFailure = { error ->
                    Log.e("HelpdeskViewModel", "Failed offline sync: ${error.message}")
                }
            )
        }
    }

    // Dynamic Online/Offline Toggle
    fun toggleConnectionMode() {
        isOnline.value = !isOnline.value
        if (isOnline.value) {
            refreshTickets()
        }
    }

    fun getAttachmentsForArticle(articleId: Int): Flow<List<AttachmentEntity>> {
        return repository.getAttachmentsForArticle(articleId)
    }

    // Background push notifications simulation
    private fun startBackgroundPollingSimulator() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // Keep running to simulate background ticketing events
            while (true) {
                delay(45000) // Poll simulation every 45 seconds
                if (isOnline.value && _loginState.value is LoginUiState.Success) {
                    // Random ticket update simulation
                    val isNewEvent = (1..10).random() > 6
                    if (isNewEvent) {
                        val titles = listOf(
                            "Новый тикет: Сбой фискального накопителя в кафе Bistrot",
                            "Новый ответ: Летнее меню утверждено (Formaggi)",
                            "Новый тикет: Проблема с курьерским планшетом доставки №4",
                            "Новый ответ: La Marzocco кофемашина починена!"
                        )
                        val text = titles.random()
                        val notification = NotificationModel(
                            id = System.currentTimeMillis(),
                            title = "Otobo Helpdesk",
                            body = text
                        )
                        _inAppNotifications.value = listOf(notification) + _inAppNotifications.value
                        
                        // Fire standard system notification
                        triggerSystemNotification("Otobo Alert", text)
                    }
                }
            }
        }
    }

    // System Notifications Helper
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Otobo Helpdesk Alerts"
            val descriptionText = "Получение оперативных уведомлений о новых тикетах и ответах"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("otobo_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun triggerSystemNotification(title: String, body: String) {
        try {
            val builder = NotificationCompat.Builder(context, "otobo_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            Log.e("HelpdeskViewModel", "Could not show notification", e)
        }
    }
}
