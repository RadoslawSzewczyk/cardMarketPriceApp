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
                AsyncImage(
                    model = data.imageUrl,
                    contentDescription = data.name,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(0.717f)
                )
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

fun parsePriceFromDoc(doc: Document): CardData {
    if (doc.title().contains("Search", ignoreCase = true)) {
        throw Exception("Tag was not specific. Landed on a search results page.")
    }

    val imgEl = doc.selectFirst("img.image-card-image")
    val cardName = imgEl?.attr("alt")
        ?.split(" - ")?.firstOrNull()
        ?: "Name not found"
    val imageUrl = imgEl?.attr("src") ?: ""

    val fromPriceEl = doc.selectFirst("dt:containsOwn(From) + dd")
    val fromPrice = fromPriceEl?.text()?.trim() ?: "N/A"

    val trendPriceEl = doc.selectFirst("dt:containsOwn(Price Trend) + dd span")
    val trendPrice = trendPriceEl?.text()?.trim() ?: "N/A"

    val avg30El = doc.selectFirst("dt:containsOwn(30-days average price) + dd span")
    val avg30Price = avg30El?.text()?.trim() ?: "N/A"

    if (fromPrice == "N/A" && trendPrice == "N/A") {
        val noResults = doc.selectFirst(".no-results-text")
        if (noResults != null) {
            throw Exception("No product found for tag.")
        }
        throw Exception("Price not found on page. CSS selectors might be outdated.")
    }

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
