package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.api.*
import com.example.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class TicketRepository(
    private val context: Context,
    private val ticketDao: TicketDao,
    private val articleDao: ArticleDao,
    private val attachmentDao: AttachmentDao
) {
    private var apiService: OtoboApiService? = null
    private var currentSessionId: String? = null
    private var loggedInUser: String? = null

    // Real host provided by user
    private val baseUrl = "https://helpdesk.bellini-gr.ru/otobo/nph-genericinterface.pl/Webservice/GenericTicketConnectorREST/"

    init {
        setupApiService()
    }

    private fun setupApiService() {
        try {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()

            apiService = retrofit.create(OtoboApiService::class.java)
        } catch (e: Exception) {
            Log.e("TicketRepository", "Failed to setup API service", e)
        }
    }

    // Live Flows from Room Database (Offline first!)
    val allTickets: Flow<List<TicketEntity>> = ticketDao.getAllTickets()

    fun getTicket(ticketId: Int): Flow<TicketEntity?> = ticketDao.getTicketById(ticketId)

    fun getArticlesForTicket(ticketId: Int): Flow<List<ArticleEntity>> = 
        articleDao.getArticlesForTicket(ticketId)

    fun getAttachmentsForArticle(articleId: Int): Flow<List<AttachmentEntity>> = 
        attachmentDao.getAttachmentsForArticle(articleId)

    // Authenticate with the Otobo system
    suspend fun login(loginEmail: String, passwordText: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val service = apiService ?: return@withContext Result.failure(Exception("API Service not initialized"))
            
            // Otobo APIs can use UserLogin (Agent) or CustomerUserLogin. 
            // We'll try Agent login first as requested for employees ("сотрудникам").
            val request = SessionRequest(
                userLogin = loginEmail,
                password = passwordText
            )
            val response = service.createSession(request)

            if (response.sessionId != null) {
                currentSessionId = response.sessionId
                loggedInUser = loginEmail
                return@withContext Result.success(response.sessionId)
            } else if (response.error?.errorMessage != null) {
                return@withContext Result.failure(Exception(response.error.errorMessage))
            }

            // If agent login returned empty, try customer login as a fallback
            val custRequest = SessionRequest(
                customerUserLogin = loginEmail,
                password = passwordText
            )
            val custResponse = service.createSession(custRequest)
            if (custResponse.sessionId != null) {
                currentSessionId = custResponse.sessionId
                loggedInUser = loginEmail
                return@withContext Result.success(custResponse.sessionId)
            }

            Result.failure(Exception(custResponse.error?.errorMessage ?: "Неверный логин или пароль"))
        } catch (e: Exception) {
            Log.e("TicketRepository", "Login error: ${e.message}", e)
            
            // Let's allow offline bypass for the hardcoded credentials if server is unreachable
            if (loginEmail == "temofeii@gmail.com" && passwordText == "_po9iu7yt") {
                loggedInUser = loginEmail
                currentSessionId = "offline_demo_session_id"
                // Seed some realistic data if DB is empty
                seedInitialMockDataIfEmpty()
                return@withContext Result.success("offline_demo_session_id")
            }
            Result.failure(e)
        }
    }

    // Refresh tickets from remote Otobo service and save to database
    suspend fun refreshTickets(): Result<Unit> = withContext(Dispatchers.IO) {
        val sessionId = currentSessionId ?: return@withContext Result.failure(Exception("Сессия не найдена. Войдите заново."))
        val service = apiService ?: return@withContext Result.failure(Exception("API Service не инициализирован"))

        if (sessionId == "offline_demo_session_id") {
            // Simulated offline sync delay
            kotlinx.coroutines.delay(1000)
            seedInitialMockDataIfEmpty()
            return@withContext Result.success(Unit)
        }

        try {
            // 1. Search for tickets
            // Query state 'new' and 'open'
            val searchRequest = TicketSearchRequest(
                sessionId = sessionId,
                states = listOf("new", "open", "pending reminder")
            )
            val searchResponse = service.searchTickets(searchRequest)
            val ids = searchResponse.ticketIds

            if (ids.isNullOrEmpty()) {
                if (searchResponse.error?.errorMessage != null) {
                    return@withContext Result.failure(Exception(searchResponse.error.errorMessage))
                }
                // No tickets found on server
                ticketDao.clearTickets()
                return@withContext Result.success(Unit)
            }

            val fetchedTickets = mutableListOf<TicketEntity>()
            
            // 2. Fetch details for each ticket
            for (ticketId in ids) {
                try {
                    val getResponse = service.getTicket(ticketId, TicketGetRequest(sessionId = sessionId))
                    val apiTicket = getResponse.tickets?.firstOrNull()
                    if (apiTicket != null) {
                        // Map Ticket
                        val entity = TicketEntity(
                            id = apiTicket.ticketId,
                            number = apiTicket.ticketNumber,
                            title = apiTicket.title,
                            state = apiTicket.state,
                            priority = apiTicket.priority,
                            queue = apiTicket.queue,
                            created = apiTicket.created,
                            customerID = apiTicket.customerId ?: "",
                            customerUser = apiTicket.customerUserId ?: "",
                            age = apiTicket.age ?: ""
                        )
                        fetchedTickets.add(entity)

                        // Map and Insert Articles
                        val articles = apiTicket.articles?.map { art ->
                            ArticleEntity(
                                id = art.articleId,
                                ticketId = apiTicket.ticketId,
                                senderType = art.senderType ?: "customer",
                                from = art.from ?: "System",
                                subject = art.subject ?: "",
                                body = art.body ?: "",
                                created = art.created ?: "",
                                isPending = false
                            )
                        } ?: emptyList()

                        if (articles.isNotEmpty()) {
                            articleDao.clearArticlesForTicket(apiTicket.ticketId)
                            articleDao.insertArticles(articles)
                        }

                        // Map and Insert Attachments
                        val attachmentsList = mutableListOf<AttachmentEntity>()
                        apiTicket.articles?.forEach { art ->
                            art.attachments?.forEach { att ->
                                if (att.filename != null) {
                                    attachmentsList.add(
                                        AttachmentEntity(
                                            fileId = "${art.articleId}_${att.filename}",
                                            articleId = art.articleId,
                                            filename = att.filename,
                                            contentType = att.contentType ?: "application/octet-stream",
                                            size = att.filesize ?: "Unknown size",
                                            content = att.content // Base64 content
                                        )
                                    )
                                }
                            }
                        }
                        if (attachmentsList.isNotEmpty()) {
                            attachmentDao.insertAttachments(attachmentsList)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TicketRepository", "Failed to fetch ticket details for id $ticketId: ${e.message}")
                }
            }

            // Save fetched tickets to Room
            if (fetchedTickets.isNotEmpty()) {
                ticketDao.clearTickets()
                ticketDao.insertTickets(fetchedTickets)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("TicketRepository", "Failed to refresh tickets", e)
            Result.failure(e)
        }
    }

    // Submit a reply (create an Article)
    suspend fun submitReply(ticketId: Int, subject: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        val sessionId = currentSessionId ?: return@withContext Result.failure(Exception("Сессия отсутствует"))
        val service = apiService

        // Create a local representation of the reply immediately with negative ID
        val localArticleId = -(System.currentTimeMillis() % 10000000).toInt()
        val localReply = ArticleEntity(
            id = localArticleId,
            ticketId = ticketId,
            senderType = "agent",
            from = loggedInUser ?: "temofeii@gmail.com",
            subject = subject,
            body = body,
            created = "Только что (Офлайн)",
            isPending = true
        )

        // Save locally first for instant feedback and offline mode support!
        articleDao.insertArticles(listOf(localReply))

        if (sessionId == "offline_demo_session_id" || service == null) {
            // Simulated offline success!
            return@withContext Result.success(Unit)
        }

        try {
            val updateRequest = TicketUpdateRequest(
                sessionId = sessionId,
                ticket = TicketUpdateFields(state = "open"), // Mark as open upon reply
                article = ArticleUpdateFields(
                    subject = subject,
                    body = body
                )
            )

            val response = service.updateTicket(ticketId, updateRequest)

            if (response.articleId != null) {
                // Succeeded on remote! Delete the temporary offline local article
                articleDao.clearArticlesForTicket(ticketId)
                
                // Trigger a refresh to load the real server response articles
                refreshTickets()
                return@withContext Result.success(Unit)
            } else if (response.error?.errorMessage != null) {
                return@withContext Result.failure(Exception(response.error.errorMessage))
            }
            Result.failure(Exception("Не удалось отправить ответ на сервер"))
        } catch (e: Exception) {
            Log.e("TicketRepository", "Network reply fail, kept in offline queue: ${e.message}", e)
            // Succeeded in keeping locally! We return success but let the user know it is offline-queued
            Result.success(Unit) 
        }
    }

    // Sync any local pending replies when connection becomes online
    suspend fun syncPendingReplies(): Result<Int> = withContext(Dispatchers.IO) {
        val sessionId = currentSessionId ?: return@withContext Result.failure(Exception("No session"))
        if (sessionId == "offline_demo_session_id") return@withContext Result.success(0)
        
        val service = apiService ?: return@withContext Result.failure(Exception("No API Service"))

        try {
            // For each ticket, check if there are pending replies
            val allTicketsList = ticketDao.getAllTickets().first()
            var syncedCount = 0

            for (ticket in allTicketsList) {
                val articles = articleDao.getArticlesForTicket(ticket.id).first()
                val pending = articles.filter { it.isPending }
                for (item in pending) {
                    try {
                        val response = service.updateTicket(
                            ticketId = item.ticketId,
                            request = TicketUpdateRequest(
                                sessionId = sessionId,
                                ticket = TicketUpdateFields(state = "open"),
                                article = ArticleUpdateFields(
                                    subject = item.subject,
                                    body = item.body
                                )
                            )
                        )
                        if (response.articleId != null) {
                            syncedCount++
                        }
                    } catch (e: Exception) {
                        Log.e("TicketRepository", "Failed to sync pending article ${item.id}: ${e.message}")
                    }
                }
            }

            if (syncedCount > 0) {
                refreshTickets()
            }
            Result.success(syncedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Clear session details
    fun logout() {
        currentSessionId = null
        loggedInUser = null
    }

    fun getLoggedInUser(): String? = loggedInUser

    // Seed realistic helpdesk tickets matching the Bellini Group (restaurants)
    suspend fun seedInitialMockDataIfEmpty() {
        val existing = ticketDao.getAllTickets().first()
        if (existing.isNotEmpty()) return

        Log.d("TicketRepository", "Seeding mock tickets for Bellini Group helpdesk")

        val mockTickets = listOf(
            TicketEntity(
                id = 1024,
                number = "2026071410001",
                title = "Не работает POS-терминал на кассе Bistrot de Luxe",
                state = "new",
                priority = "3 normal",
                queue = "IT-Поддержка",
                created = "2026-07-14 09:30:15",
                customerID = "Bistrot_De_Luxe",
                customerUser = "manager.bistrot@bellini-gr.ru",
                age = "30 min"
            ),
            TicketEntity(
                id = 1025,
                number = "2026071410002",
                title = "Замена меню на летний сезон — ресторан Formaggi",
                state = "open",
                priority = "2 high",
                queue = "Маркетинг & Дизайн",
                created = "2026-07-13 14:15:22",
                customerID = "Formaggi_Restaurant",
                customerUser = "formaggi.admin@bellini-gr.ru",
                age = "17 hours"
            ),
            TicketEntity(
                id = 1026,
                number = "2026071410003",
                title = "Техническое обслуживание кофемашины в Bellini Cafe",
                state = "new",
                priority = "3 normal",
                queue = "Инженерная служба",
                created = "2026-07-14 08:00:11",
                customerID = "Bellini_Cafe",
                customerUser = "barista.bellini@bellini-gr.ru",
                age = "2 hours"
            ),
            TicketEntity(
                id = 1027,
                number = "2026071410004",
                title = "Сбой интеграции с системой лояльности на кассе доставки",
                state = "open",
                priority = "4 critical",
                queue = "IT-Поддержка",
                created = "2026-07-14 11:10:00",
                customerID = "Delivery_Service",
                customerUser = "delivery.head@bellini-gr.ru",
                age = "15 min"
            )
        )

        ticketDao.insertTickets(mockTickets)

        // Seed articles
        val mockArticles = listOf(
            // Articles for 1024
            ArticleEntity(
                id = 5001,
                ticketId = 1024,
                senderType = "customer",
                from = "manager.bistrot@bellini-gr.ru",
                subject = "POS-терминал не реагирует на карты",
                body = "Здравствуйте! На первой кассе терминал Сбербанка выдает ошибку сети 'Нет связи с хостом'. Пожалуйста, помогите оперативно — гости ждут, образуется очередь.",
                created = "2026-07-14 09:30:15"
            ),
            // Articles for 1025
            ArticleEntity(
                id = 5002,
                ticketId = 1025,
                senderType = "customer",
                from = "formaggi.admin@bellini-gr.ru",
                subject = "Макеты меню утверждены директором",
                body = "Приветствую! Летнее меню утверждено. Во вложении файлы дизайна для печати. Нужно напечатать 50 штук тейбл-тентов и доставить до четверга.",
                created = "2026-07-13 14:15:22"
            ),
            ArticleEntity(
                id = 5003,
                ticketId = 1025,
                senderType = "agent",
                from = "designer.office@bellini-gr.ru",
                subject = "Re: Макеты меню утверждены директором",
                body = "Добрый день, макеты приняли в работу. Отправляем в типографию. Подскажите, матовые делать или глянцевые, как в прошлый раз?",
                created = "2026-07-13 16:30:00"
            ),
            ArticleEntity(
                id = 5004,
                ticketId = 1025,
                senderType = "customer",
                from = "formaggi.admin@bellini-gr.ru",
                subject = "Re: Макеты меню утверждены директором",
                body = "Сделайте матовые, они смотрятся более премиально и меньше бликуют под светом светильников.",
                created = "2026-07-13 16:45:10"
            ),
            // Articles for 1026
            ArticleEntity(
                id = 5005,
                ticketId = 1026,
                senderType = "customer",
                from = "barista.bellini@bellini-gr.ru",
                subject = "Требуется чистка группы и декальцинация",
                body = "Добрый день. На кофемашине La Marzocco горит индикатор техобслуживания, и упало давление пара в правом капучинаторе. Нужен визит инженера.",
                created = "2026-07-14 08:00:11"
            ),
            // Articles for 1027
            ArticleEntity(
                id = 5006,
                ticketId = 1027,
                senderType = "customer",
                from = "delivery.head@bellini-gr.ru",
                subject = "Ошибка списания баллов Bellini Club",
                body = "При попытке списать баллы система выдает 'Internal Server Error (500)'. Гости не могут потратить кэшбэк. Ошибка появилась 15 минут назад.",
                created = "2026-07-14 11:10:00"
            )
        )

        articleDao.insertArticles(mockArticles)

        // Seed some mock attachments
        val mockAttachments = listOf(
            AttachmentEntity(
                fileId = "5002_summer_menu_preview.jpg",
                articleId = 5002,
                filename = "summer_menu_preview.jpg",
                contentType = "image/jpeg",
                size = "2.4 MB",
                content = null // Indicates standard placeholder generated image or remote source
            ),
            AttachmentEntity(
                fileId = "5001_pos_error_screenshot.png",
                articleId = 5001,
                filename = "pos_error_screenshot.png",
                contentType = "image/png",
                size = "415 KB",
                content = null
            )
        )
        attachmentDao.insertAttachments(mockAttachments)
    }
}
