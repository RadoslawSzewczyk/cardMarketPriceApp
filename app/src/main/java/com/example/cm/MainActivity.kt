package com.example.cm

import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeoutException

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
    var result by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    var urlToScrape by remember { mutableStateOf<String?>(null) }
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
                onValueChange = { tag = it.replace(" ", "") }, // Remove spaces
                label = { Text("Enter tag (e.g. sv2a182)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (tag.isNotBlank()) {
                        isLoading = true
                        result = ""
                        urlToScrape = buildUrlForTag(tag)
                    } else {
                        result = "Please enter a tag first."
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

            if (result.isNotEmpty()) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                )
            }
        }


        if (urlToScrape != null) {
            ScraperWebView(
                urlToLoad = urlToScrape,
                onScrapeResult = { html ->
                    coroutineScope.launch(Dispatchers.Default) {
                        try {
                            val doc = Jsoup.parse(html)
                            val price = parsePriceFromDoc(doc)
                            withContext(Dispatchers.Main) {
                                result = price
                                isLoading = false
                                urlToScrape = null
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                result = "Error parsing HTML: ${e.message}"
                                isLoading = false
                                urlToScrape = null
                            }
                        }
                    }
                },
                onError = { errorMsg ->
                    result = errorMsg
                    isLoading = false
                    urlToScrape = null
                }
            )
        }
    }
}
@Composable
fun ScraperWebView(
    urlToLoad: String?,
    onScrapeResult: (String) -> Unit,
    onError: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var loadJob by remember { mutableStateOf<Job?>(null) }

    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    AndroidView(
        modifier = Modifier.size(0.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = userAgent

                webViewClient = object : WebViewClient() {
                    private var hasTimedOut = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        if (hasTimedOut || view == null) return
                        loadJob?.cancel()

                        view.evaluateJavascript("javascript:document.documentElement.outerHTML") { htmlResult ->
                            if (htmlResult == null || htmlResult == "null") {
                                onError("Failed to get HTML from WebView.")
                                return@evaluateJavascript
                            }
                            val cleanedHtml = htmlResult
                                .removePrefix("\"")
                                .removeSuffix("\"")
                                .replace("\\u003C", "<")
                                .replace("\\\"", "\"")
                                .replace("\\n", "\n")

                            onScrapeResult(cleanedHtml)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            hasTimedOut = true
                            loadJob?.cancel()
                            onError("WebView Error: ${error?.description}")
                        }
                    }
                }
            }
        },
        update = { webView ->
            if (urlToLoad != null) {
                loadJob?.cancel()
                loadJob = coroutineScope.launch {
                    try {
                        withTimeout(20_000L) {
                            delay(Long.MAX_VALUE)
                        }
                    } catch (e: TimeoutCancellationException) {
                        withContext(Dispatchers.Main) {
                            onError("Error: Page load timed out (20s).")
                        }
                    }
                }
                webView.loadUrl(urlToLoad)
            }
        }
    )
}



fun buildUrlForTag(tag: String): String {
    val encoded = URLEncoder.encode(tag, "UTF-8")
    return "https://www.cardmarket.com/en/Pokemon/Products/Search?category=-1&searchString=$encoded&searchMode=v1"
}

fun parsePriceFromDoc(doc: Document): String {
    if (doc.title().contains("Search", ignoreCase = true)) {
        return "Error: Tag was not specific. Landed on a search results page."
    }

    val fromPriceEl = doc.selectFirst("dt:containsOwn(From) + dd")
    val fromPrice = fromPriceEl?.text()?.trim() ?: "N/A"

    val trendPriceEl = doc.selectFirst("dt:containsOwn(Price Trend) + dd span")
    val trendPrice = trendPriceEl?.text()?.trim() ?: "N/A"

    val avg30El = doc.selectFirst("dt:containsOwn(30-days average price) + dd span")
    val avg30Price = avg30El?.text()?.trim() ?: "N/A"

    if (fromPrice == "N/A" && trendPrice == "N/A") {
        val noResults = doc.selectFirst(".no-results-text")
        if (noResults != null) {
            return "Error: No product found for tag."
        }
        return "Price not found on page. CSS selectors might be outdated."
    }

    return """
        From: $fromPrice
        Price Trend: $trendPrice
        30-Day Avg: $avg30Price
    """.trimIndent()
}