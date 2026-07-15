package com.example

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExampleUnitTest {
    @Test
    fun testLiveOtoboWebLogin() = runBlocking {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val formBody = FormBody.Builder()
            .add("Action", "Login")
            .add("RequestedURL", "")
            .add("Lang", "ru")
            .add("User", "temofeii@gmail.com")
            .add("Password", "_po9iu7yt")
            .build()

        val request = okhttp3.Request.Builder()
            .url("https://helpdesk.bellini-gr.ru/otobo/index.pl")
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .build()

        println("API_LOG: --- Starting Web Form Login ---")
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val location = response.header("Location") ?: ""
                val cookies = response.headers("Set-Cookie").joinToString("; ")
                
                val combined = "$body\nLOCATION_HEADER:$location\nCOOKIES_HEADER:$cookies"
                
                val sessionIdRegex = """OTOBOAgentInterface=([A-Za-z0-9]+)""".toRegex()
                val matchResult = sessionIdRegex.find(combined)
                val sessionId = matchResult?.groupValues?.get(1)

                println("API_LOG: Redirection Location: $location")
                println("API_LOG: Extracted Session ID: $sessionId")
                
                assertNotNull("Session ID must not be null for successful login", sessionId)
            }
        } catch (e: Exception) {
            println("API_LOG: Web Login Exception: ${e.message}")
            throw e
        }
    }
}
