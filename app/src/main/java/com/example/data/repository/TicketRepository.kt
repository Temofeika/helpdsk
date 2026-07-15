package com.example.data.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.api.*
import com.example.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TicketRepository(
    private val context: Context,
    private val ticketDao: TicketDao,
    private val articleDao: ArticleDao,
    private val attachmentDao: AttachmentDao
) {
    private var currentSessionId: String? = null
    private var loggedInUser: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Base URL for web interface scraper fallback
    private val webBaseUrl = "https://helpdesk.bellini-gr.ru/otobo/index.pl"

    // Live Flows from Room Database (Offline first!)
    val allTickets: Flow<List<TicketEntity>> = ticketDao.getAllTickets()

    fun getTicket(ticketId: Int): Flow<TicketEntity?> = ticketDao.getTicketById(ticketId)

    fun getArticlesForTicket(ticketId: Int): Flow<List<ArticleEntity>> = 
        articleDao.getArticlesForTicket(ticketId)

    fun getAttachmentsForArticle(articleId: Int): Flow<List<AttachmentEntity>> = 
        attachmentDao.getAttachmentsForArticle(articleId)

    // Helper GET method
    private fun httpGet(url: String, sessionId: String? = null): String {
        val finalUrl = if (sessionId != null) {
            if (url.contains("?")) "$url&OTOBOAgentInterface=$sessionId" else "$url?OTOBOAgentInterface=$sessionId"
        } else {
            url
        }
        val request = okhttp3.Request.Builder()
            .url(finalUrl)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    // Helper GET bytes method
    private fun httpGetBytes(url: String, sessionId: String? = null): ByteArray {
        val finalUrl = if (sessionId != null) {
            if (url.contains("?")) "$url&OTOBOAgentInterface=$sessionId" else "$url?OTOBOAgentInterface=$sessionId"
        } else {
            url
        }
        val request = okhttp3.Request.Builder()
            .url(finalUrl)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    // Helper POST method
    private fun httpPost(url: String, params: Map<String, String>, sessionId: String? = null): String {
        val finalUrl = if (sessionId != null) {
            if (url.contains("?")) "$url&OTOBOAgentInterface=$sessionId" else "$url?OTOBOAgentInterface=$sessionId"
        } else {
            url
        }
        val formBuilder = FormBody.Builder()
        params.forEach { (k, v) -> formBuilder.add(k, v) }
        val request = okhttp3.Request.Builder()
            .url(finalUrl)
            .post(formBuilder.build())
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 302) {
                throw Exception("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: ""
            val location = response.header("Location") ?: ""
            val cookies = response.headers("Set-Cookie").joinToString("; ")
            return "$body\nLOCATION_HEADER:$location\nCOOKIES_HEADER:$cookies"
        }
    }

    // Authenticate with the Otobo system
    suspend fun login(loginEmail: String, passwordText: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d("TicketRepository", "Starting login process for: $loginEmail")
            
            // Perform web form login as Agent on index.pl
            val loginParams = mapOf(
                "Action" to "Login",
                "RequestedURL" to "",
                "Lang" to "ru",
                "User" to loginEmail,
                "Password" to passwordText
            )
            
            val responseText = httpPost(webBaseUrl, loginParams)
            
            // Extract OTOBOAgentInterface session ID from redirect URL or cookie
            val sessionIdRegex = """OTOBOAgentInterface=([A-Za-z0-9]+)""".toRegex()
            val matchResult = sessionIdRegex.find(responseText)
            val sessionId = matchResult?.groupValues?.get(1)

            if (sessionId != null) {
                currentSessionId = sessionId
                loggedInUser = loginEmail
                Log.d("TicketRepository", "Successfully logged in with Session ID: $sessionId")
                return@withContext Result.success(sessionId)
            } else {
                Log.e("TicketRepository", "Login failed: Session ID not found in response")
                // Check if the login matches the user requested demo credentials as local fallback
                if (loginEmail == "temofeii@gmail.com" && passwordText == "_po9iu7yt") {
                    loggedInUser = loginEmail
                    currentSessionId = "offline_demo_session_id"
                    seedInitialMockDataIfEmpty()
                    return@withContext Result.success("offline_demo_session_id")
                }
                return@withContext Result.failure(Exception("Не удалось войти. Пожалуйста, проверьте логин и пароль."))
            }
        } catch (e: Exception) {
            Log.e("TicketRepository", "Login exception: ${e.message}", e)
            if (loginEmail == "temofeii@gmail.com" && passwordText == "_po9iu7yt") {
                loggedInUser = loginEmail
                currentSessionId = "offline_demo_session_id"
                seedInitialMockDataIfEmpty()
                return@withContext Result.success("offline_demo_session_id")
            }
            Result.failure(e)
        }
    }

    // Refresh tickets from remote Otobo service and save to database
    suspend fun refreshTickets(): Result<Unit> = withContext(Dispatchers.IO) {
        val sessionId = currentSessionId ?: return@withContext Result.failure(Exception("Сессия не найдена. Войдите заново."))

        if (sessionId == "offline_demo_session_id") {
            kotlinx.coroutines.delay(1000)
            seedInitialMockDataIfEmpty()
            return@withContext Result.success(Unit)
        }

        try {
            Log.d("TicketRepository", "Starting ticket refresh using Session ID: $sessionId")
            
            // Fetch All active tickets from queue view
            val queueUrl = "$webBaseUrl?Action=AgentTicketQueue;Filter=All;View=Small"
            val queueHtml = httpGet(queueUrl, sessionId)

            val trRegex = """<tr id="TicketID_(\d+)"[^>]*>([\s\S]+?)</tr>""".toRegex()
            val tdRegex = """<td[^>]*>([\s\S]+?)</td>""".toRegex()
            val titleAttrRegex = """title="([^"]+)"""".toRegex()
            val divTitleRegex = """<div title="([^"]+)"[^>]*>([\s\S]+?)</div>""".toRegex()

            val matches = trRegex.findAll(queueHtml)
            val fetchedTickets = mutableListOf<TicketEntity>()

            for (match in matches) {
                val ticketIdStr = match.groupValues[1]
                val ticketId = ticketIdStr.toIntOrNull() ?: continue
                val trContent = match.groupValues[2]
                
                val tds = tdRegex.findAll(trContent).map { it.groupValues[1].trim() }.toList()
                if (tds.size < 11) continue
                
                // Cell 1: Flags / Priority
                val priorityMatch = titleAttrRegex.find(tds[1])
                val priority = priorityMatch?.groupValues[1]?.trim() ?: "3 normal"
                
                // Cell 3: Ticket Number
                val ticketNumMatch = """class="MasterActionLink"[^>]*>([^<]+)</a>""".toRegex().find(tds[3])
                val ticketNumber = ticketNumMatch?.groupValues[1]?.trim() ?: ticketIdStr
                
                // Cell 4: Age
                val ageMatch = divTitleRegex.find(tds[4])
                val age = ageMatch?.groupValues[1]?.trim() ?: tds[4].replace(Regex("<[^>]*>"), "").trim()
                
                // Cell 5: Customer/Sender
                val senderMatch = divTitleRegex.find(tds[5])
                val customerUser = senderMatch?.groupValues[1]?.trim() ?: tds[5].replace(Regex("<[^>]*>"), "").trim()
                
                // Cell 6: Title
                val titleMatch = divTitleRegex.find(tds[6])
                val title = titleMatch?.groupValues[1]?.trim() ?: tds[6].replace(Regex("<[^>]*>"), "").trim()
                
                // Cell 7: State
                val stateMatch = divTitleRegex.find(tds[7])
                val state = stateMatch?.groupValues[1]?.trim() ?: tds[7].replace(Regex("<[^>]*>"), "").trim()
                
                // Cell 9: Queue
                val queueMatch = divTitleRegex.find(tds[9])
                val queue = queueMatch?.groupValues[1]?.trim() ?: tds[9].replace(Regex("<[^>]*>"), "").trim()
                
                // Cell 10: Owner
                val ownerMatch = divTitleRegex.find(tds[10])
                val owner = ownerMatch?.groupValues[1]?.trim() ?: tds[10].replace(Regex("<[^>]*>"), "").trim()
                
                val entity = TicketEntity(
                    id = ticketId,
                    number = ticketNumber,
                    title = title,
                    state = state,
                    priority = priority,
                    queue = queue,
                    created = "",
                    customerID = "",
                    customerUser = customerUser,
                    age = age
                )
                fetchedTickets.add(entity)

                // 2. Fetch Details (Articles/Messages) and Attachments for this Ticket
                try {
                    val zoomUrl = "$webBaseUrl?Action=AgentTicketZoom;TicketID=$ticketId"
                    val zoomHtml = httpGet(zoomUrl, sessionId)

                    // Find all Article update links
                    val articleLinkRegex = """value="([^"]+?Subaction=ArticleUpdate[^"]+ArticleID=(\d+)[^"]*)"""".toRegex()
                    val articleLinks = articleLinkRegex.findAll(zoomHtml).map {
                        val path = it.groupValues[1].replace("&amp;", "&")
                        val articleId = it.groupValues[2].toInt()
                        path to articleId
                    }.toList()

                    val articles = mutableListOf<ArticleEntity>()
                    for ((path, articleId) in articleLinks) {
                        try {
                            val snippetUrl = "https://helpdesk.bellini-gr.ru$path"
                            val snippetHtml = httpGet(snippetUrl, sessionId)
                            
                            // Parse Sender
                            val senderMatch = """title="&quot;([^"]+?)&quot;\s+&lt;([^>]+?)&gt;"""".toRegex().find(snippetHtml)
                                ?: """title="([^"]+?)"""".toRegex().find(snippetHtml)
                            val fromUser = senderMatch?.groupValues?.get(1) ?: "System"
                            val email = senderMatch?.groupValues?.getOrNull(2) ?: ""
                            
                            // Parse Subject
                            val subjectMatch = """<label>Тема:</label>\s*<p class="Value">([^<]+)</p>""".toRegex().find(snippetHtml)
                            val subject = subjectMatch?.groupValues?.get(1)?.trim() ?: ""
                            
                            // Parse Date
                            val dateMatch = """title="Создан/а:\s*([^"]+)"""".toRegex().find(snippetHtml)
                            val createdDate = dateMatch?.groupValues?.get(1)?.trim() ?: ""
                            
                            // Parse Body inside Iframe
                            val iframeMatch = """<iframe[^>]*src="([^"]+?Action=AgentTicketArticleContent[^"]+)"[^>]*>""".toRegex().find(snippetHtml)
                            var body = ""
                            if (iframeMatch != null) {
                                val iframeUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                                val iframeHtml = httpGet("https://helpdesk.bellini-gr.ru$iframeUrl", sessionId)
                                val bodyMatch = """<body[^>]*>([\s\S]+?)</body>""".toRegex().find(iframeHtml)
                                body = bodyMatch?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""
                            }
                            
                            val article = ArticleEntity(
                                id = articleId,
                                ticketId = ticketId,
                                senderType = if (fromUser.contains("Admin", ignoreCase = true) || fromUser.contains("temofeii", ignoreCase = true)) "agent" else "customer",
                                from = if (email.isNotEmpty()) "$fromUser <$email>" else fromUser,
                                subject = subject,
                                body = body,
                                created = createdDate,
                                isPending = false
                            )
                            articles.add(article)
                            
                            // Parse Attachments inside snippetHtml
                            val attachmentRegex = """<a href="([^"]+?Action=AgentTicketAttachment[^"]+?FileID=(\d+)[^"]*)"[^>]*>([^<]+)</a>""".toRegex()
                            val attMatches = attachmentRegex.findAll(snippetHtml)
                            val attachmentsList = mutableListOf<AttachmentEntity>()
                            for (attMatch in attMatches) {
                                val attUrl = attMatch.groupValues[1].replace("&amp;", "&")
                                val fileId = attMatch.groupValues[2]
                                val filename = attMatch.groupValues[3].trim()
                                
                                // Download the attachment content as Base64 for fully functional offline availability
                                var base64Content: String? = null
                                try {
                                    val bytes = httpGetBytes("https://helpdesk.bellini-gr.ru$attUrl", sessionId)
                                    if (bytes.isNotEmpty()) {
                                        base64Content = Base64.encodeToString(bytes, Base64.DEFAULT)
                                    }
                                } catch (ex: Exception) {
                                    Log.e("TicketRepository", "Failed to download attachment $filename: ${ex.message}")
                                }
                                
                                attachmentsList.add(
                                    AttachmentEntity(
                                        fileId = "${articleId}_${filename}",
                                        articleId = articleId,
                                        filename = filename,
                                        contentType = if (filename.lowercase().endsWith(".jpg") || filename.lowercase().endsWith(".jpeg") || filename.lowercase().endsWith(".png")) "image/png" else "application/octet-stream",
                                        size = "Загружено",
                                        content = base64Content
                                    )
                                )
                            }
                            if (attachmentsList.isNotEmpty()) {
                                attachmentDao.insertAttachments(attachmentsList)
                            }
                            
                        } catch (e: Exception) {
                            Log.e("TicketRepository", "Failed to fetch article details for $articleId: ${e.message}")
                        }
                    }

                    if (articles.isNotEmpty()) {
                        articleDao.clearArticlesForTicket(ticketId)
                        articleDao.insertArticles(articles)
                    }
                } catch (e: Exception) {
                    Log.e("TicketRepository", "Failed to fetch zoom details for ticket id $ticketId: ${e.message}")
                }
            }

            // Save fetched tickets to Room database
            if (fetchedTickets.isNotEmpty()) {
                ticketDao.clearTickets()
                ticketDao.insertTickets(fetchedTickets)
            }
            Log.d("TicketRepository", "Successfully synced ${fetchedTickets.size} tickets from Otobo server")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("TicketRepository", "Failed to refresh tickets", e)
            Result.failure(e)
        }
    }

    // Submit a reply (create an Article / Note)
    suspend fun submitReply(ticketId: Int, subject: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        val sessionId = currentSessionId ?: return@withContext Result.failure(Exception("Сессия отсутствует"))

        // Create a local representation of the reply immediately with negative ID for offline support
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

        if (sessionId == "offline_demo_session_id") {
            return@withContext Result.success(Unit)
        }

        try {
            Log.d("TicketRepository", "Submitting reply note to ticket ID: $ticketId")
            
            // 1. Fetch ChallengeToken and FormID from the Note form page
            val noteFormUrl = "$webBaseUrl?Action=AgentTicketNote;TicketID=$ticketId"
            val noteFormHtml = httpGet(noteFormUrl, sessionId)

            val challengeTokenMatch = """name="ChallengeToken" value="([^"]+)"""".toRegex().find(noteFormHtml)
                ?: """ChallengeToken":"([^"]+)"""".toRegex().find(noteFormHtml)
            val challengeToken = challengeTokenMatch?.groupValues?.get(1) ?: ""

            val formIdMatch = """name="FormID" value="([^"]+)"""".toRegex().find(noteFormHtml)
            val formId = formIdMatch?.groupValues?.get(1) ?: "${System.currentTimeMillis() / 1000}.${System.currentTimeMillis() % 1000}"

            // 2. Submit the reply form via POST
            val formParams = mapOf(
                "ChallengeToken" to challengeToken,
                "Action" to "AgentTicketNote",
                "Subaction" to "Store",
                "TicketID" to ticketId.toString(),
                "ArticleID" to "",
                "ReplyToArticle" to "",
                "Expand" to "",
                "FormID" to formId,
                "FormDraftTitle" to "",
                "FormDraftID" to "",
                "Subject" to subject,
                "Body" to body,
                "IsVisibleForCustomer" to "1",
                "TimeUnits" to ""
            )

            httpPost(webBaseUrl, formParams, sessionId)

            // Reply stored successfully! Clear temporary offline article
            articleDao.clearArticlesForTicket(ticketId)
            
            // Trigger background refresh to reload updated messages
            refreshTickets()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("TicketRepository", "Network reply submit fail, kept in offline queue: ${e.message}", e)
            Result.success(Unit) 
        }
    }

    // Sync any local pending replies when connection becomes online
    suspend fun syncPendingReplies(): Result<Int> = withContext(Dispatchers.IO) {
        val sessionId = currentSessionId ?: return@withContext Result.failure(Exception("No session"))
        if (sessionId == "offline_demo_session_id") return@withContext Result.success(0)
        
        try {
            val allTicketsList = ticketDao.getAllTickets().first()
            var syncedCount = 0

            for (ticket in allTicketsList) {
                val articles = articleDao.getArticlesForTicket(ticket.id).first()
                val pending = articles.filter { it.isPending }
                for (item in pending) {
                    try {
                        submitReply(item.ticketId, item.subject, item.body)
                        syncedCount++
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
            ArticleEntity(
                id = 5001,
                ticketId = 1024,
                senderType = "customer",
                from = "manager.bistrot@bellini-gr.ru",
                subject = "POS-терминал не реагирует на карты",
                body = "Здравствуйте! На первой кассе терминал Сбербанка выдает ошибку сети 'Нет связи с хостом'. Пожалуйста, помогите оперативно — гости ждут, образуется очередь.",
                created = "2026-07-14 09:30:15"
            ),
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
            ArticleEntity(
                id = 5005,
                ticketId = 1026,
                senderType = "customer",
                from = "barista.bellini@bellini-gr.ru",
                subject = "Требуется чистка группы и декальцинация",
                body = "Добрый день. На кофемашине La Marzocco горит индикатор техобслуживания, и упало давление пара в правом капучинаторе. Нужен визит инженера.",
                created = "2026-07-14 08:00:11"
            ),
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
                content = null
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
