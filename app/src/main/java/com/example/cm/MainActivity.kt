package com.example.cm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.compose.AsyncImage


data class CardData(
    val name: String,
    val imageUrl: String,
    val priceInfo: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CardPriceApp()
            }
        }
    }
}

@Composable
fun CardPriceApp() {
    var tag by remember { mutableStateOf("") }
    var cardInfo by remember { mutableStateOf<CardData?>(null) }
    var errorInfo by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Card Price Checker",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = tag,
                onValueChange = { tag = it.replace(" ", "") },
                label = { Text("Enter tag (e.g. sv2a182)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (tag.isBlank()) {
                        errorInfo = "Please enter a tag first."
                        return@Button
                    }

                    isLoading = true
                    cardInfo = null
                    errorInfo = null

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val html = fetchHtml(tag)
                            val data = parsePriceFromDoc(Jsoup.parse(html))
                            withContext(Dispatchers.Main) {
                                cardInfo = data
                                isLoading = false
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                errorInfo = "Error: ${e.message}"
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = !isLoading
            ) {
                Text(if (isLoading) "Fetching..." else "Get Price")
            }

            Spacer(Modifier.height(24.dp))

            if (isLoading) {
                CircularProgressIndicator()
            }

            cardInfo?.let { data ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = data.name,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                CardImage(url = data.imageUrl, description = data.name)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = data.priceInfo,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
            }

            errorInfo?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
            }
        }
    }
}

suspend fun fetchHtml(tag: String): String {
    val client = OkHttpClient()
    val url = buildUrlForTag(tag)

    val request = Request.Builder()
        .url(url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        )
        .header("Referer", "https://www.google.com")
        .header("Accept-Language", "en-US,en;q=0.9")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("HTTP error fetching URL. Status=${response.code}, URL=$url")
        }
        return response.body?.string() ?: throw Exception("Empty response body")
    }
}



fun buildUrlForTag(tag: String): String {
    val encoded = URLEncoder.encode(tag, "UTF-8")
    return "https://www.cardmarket.com/en/Pokemon/Products/Search?category=-1&searchString=$encoded&searchMode=v1"
}

suspend fun fetchHtmlAbsolute(url: String): String {
    val client = OkHttpClient()

    val request = Request.Builder()
        .url(url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        )
        .header("Referer", "https://www.google.com")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw Exception("HTTP error ${response.code}")
        return response.body?.string() ?: throw Exception("Empty response body")
    }
}


suspend fun parsePriceFromDoc(doc: Document): CardData {
    val firstResultLink = doc.selectFirst(".table-body a[href*='/en/Pokemon/Products/Singles/']")
        ?.attr("href")

    if (firstResultLink != null && !firstResultLink.contains("Search")) {
        println("DEBUG: On search page → following first result: $firstResultLink")
        val productUrl = "https://www.cardmarket.com$firstResultLink"
        val productHtml = fetchHtmlAbsolute(productUrl)
        return parsePriceFromDoc(Jsoup.parse(productHtml))
    }

    val cardName = doc.selectFirst("h1")?.text()?.trim() ?: "Name not found"

    var imageUrl = ""
    val imageSelectors = listOf(
        "img.is-front[src*='product-images.s3.cardmarket.com']",
        "div.image.card-image img[src*='product-images.s3.cardmarket.com']",
        "meta[property=og:image]"
    )

    for (sel in imageSelectors) {
        val el = doc.selectFirst(sel)
        if (el != null) {
            imageUrl = if (sel.startsWith("meta")) el.attr("content") else el.attr("src")
            if (imageUrl.isNotBlank() && !imageUrl.contains("transparent.gif")) break
        }
    }

    if (imageUrl.startsWith("//")) imageUrl = "https:$imageUrl"
    else if (imageUrl.startsWith("/")) imageUrl = "https://www.cardmarket.com$imageUrl"

    println("DEBUG imageUrl (final) = $imageUrl")

    val fromPrice = doc.selectFirst("dt:containsOwn(From) + dd")?.text()?.trim() ?: "N/A"
    val trendPrice = doc.selectFirst("dt:containsOwn(Price Trend) + dd span")?.text()?.trim() ?: "N/A"
    val avg30Price = doc.selectFirst("dt:matchesOwn(30-days average price) + dd span")?.text()?.trim() ?: "N/A"

    val priceString = """
        From: $fromPrice
        Price Trend: $trendPrice
        30-Day Avg: $avg30Price
    """.trimIndent()

    return CardData(
        name = cardName,
        imageUrl = imageUrl,
        priceInfo = priceString
    )
}



@Composable
fun CardImage(url: String, description: String) {
    if (url.isBlank()) return

    val ctx = LocalContext.current

    val imageLoader = remember {
        coil.ImageLoader.Builder(ctx)
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Referer", "https://www.cardmarket.com")
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
                            )
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            }
            .build()
    }

    AsyncImage(
        model = url,
        contentDescription = description,
        imageLoader = imageLoader,
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(0.717f)
    )
}

