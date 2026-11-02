package com.example.test

import android.os.Bundle
import androidx.activity.compose.setContent
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.test.ui.theme.TestTheme
import com.payu.base.models.ErrorResponse
import com.payu.base.models.PayUPaymentParams
import com.payu.checkoutpro.PayUCheckoutPro
import com.payu.checkoutpro.models.PayUCheckoutProConfig
import com.payu.ui.model.listeners.PayUCheckoutProListener
import com.payu.ui.model.listeners.PayUHashGenerationListener
import java.util.UUID

val PayUBlue       = Color(0xFF002970)
val PriceGreen     = Color(0xFF1AAD4A)
val DiscountRed    = Color(0xFFE53935)
val MrpGrey        = Color(0xFF9E9E9E)
val TextPrimary    = Color(0xFF1C1C1E)
val TextSecondary  = Color(0xFF6D6D72)
val BadgeBg        = Color(0xFFFFF3E0)
val BadgeText      = Color(0xFFE65100)
val StockGreenBg   = Color(0xFFE8F5E9)
val StockGreenText = Color(0xFF2E7D32)
val CartBtnBg      = Color(0xFFE8F0FE)
val CardWhite      = Color(0xFFFFFFFF)
val PageGrey       = Color(0xFFF2F2F2)

private const val TAG = "PayUDebug"
private const val PAYU_KEY = "a4vGC2"

//  Emulator  → http://10.0.2.2:8000/hash.php
//  Real device on same Wi-Fi → http://192.168.x.x:8000/hash.php
//  Real device anywhere      → https://xxxx.ngrok-free.app/hash.php
private const val HASH_BACKEND_URL = "http://0.0.0.0:8000/hash.php"

private const val SURL = "https://cbjs.payu.in/sdk/success"
private const val FURL = "https://cbjs.payu.in/sdk/failure"

class MainActivity : ComponentActivity(), PayUCheckoutProListener {

    private var checkoutAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkoutAttempted = savedInstanceState?.getBoolean("checkoutAttempted") ?: false
        setContent {
            TestTheme {
                ProductPage(
                    onPayNowClicked = { launchPayUCheckout() },
                    onBackClicked   = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("checkoutAttempted", checkoutAttempted)
        super.onSaveInstanceState(outState)
    }

    private fun configuredHashEndpoint(): URL? {
        if (HASH_BACKEND_URL.isBlank()) return null
        return try {
            URL(HASH_BACKEND_URL).takeIf {
                (it.protocol.equals("https", ignoreCase = true) ||
                        it.protocol.equals("http",  ignoreCase = true)) &&
                        it.host.isNotBlank() &&
                        it.userInfo == null &&
                        it.ref == null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun launchPayUCheckout() {
        if (isFinishing || isDestroyed) return
        if (PAYU_KEY.isBlank() || configuredHashEndpoint() == null) {
            showMessage("Configure your test merchant key and hash backend URL first.")
            return
        }
        if (checkoutAttempted) {
            showMessage("Checkout already requested. Verify the order on your server before another attempt.")
            return
        }
        checkoutAttempted = true
        try {
            val txnId = "TXN" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .take(20)

            Log.d(TAG, "Launching checkout — txnId: $txnId")

            val paymentParams = PayUPaymentParams.Builder()
                .setKey(PAYU_KEY)
                .setTransactionId(txnId)
                .setAmount("24990.00")
                .setProductInfo("Sony WH-1000XM5 Headphones")
                .setFirstName("Gaurav")
                .setEmail("gaurav@example.com")
                .setPhone("9999999999")
                .setSurl(SURL)
                .setFurl(FURL)
                .setIsProduction(false)
                .setUserCredential("$PAYU_KEY:gaurav@example.com")
                .build()

            val config = PayUCheckoutProConfig().apply {
                merchantName = "Mishra Shop"
            }
            PayUCheckoutPro.open(this, paymentParams, config, this)
        } catch (e: Exception) {
            // FIX 1: reset flag so the user can retry after an exception
            checkoutAttempted = false
            Log.e(TAG, "launchPayUCheckout exception", e)
            showMessage("Checkout could not be opened: ${e.message}")
        }
    }

    override fun generateHash(
        map: HashMap<String, String?>,
        hashGenerationListener: PayUHashGenerationListener
    ) {
        if (isFinishing || isDestroyed) return

        // FIX 2: log every field PayU sends so you can verify the hash string
        Log.d(TAG, "generateHash called — map: $map")

        val hashName   = map["hashName"]
        val hashString = map["hashString"]
        if (hashName.isNullOrBlank() || hashString.isNullOrBlank()) {
            showMessage("PayU supplied an incomplete hash request.")
            return
        }
        val endpoint = configuredHashEndpoint() ?: run {
            showMessage("Configure your hash backend URL first.")
            return
        }
        val requestBody = JSONObject().apply {
            map.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
        }.toString()

        Log.d(TAG, "Sending to backend: $requestBody")

        Thread {
            if (isFinishing || isDestroyed) return@Thread
            try {
                val generatedHash = requestHashFromBackend(endpoint, requestBody)
                Log.d(TAG, "Hash received for $hashName: $generatedHash")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        hashGenerationListener.onHashGenerated(
                            hashMapOf(hashName to generatedHash)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hash generation failed", e)
                runOnUiThread {
                    showMessage("Hash service error: ${e.message}. Check your backend is running.")
                }
            }
        }.start()
    }

    private fun requestHashFromBackend(endpoint: URL, requestBody: String): String {
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout    = 15_000
            connection.doOutput       = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            check(connection.responseCode in 200..299) {
                "Hash backend HTTP ${connection.responseCode}"
            }

            // FIX 3: trim() removes any leading/trailing whitespace or newlines
            //         that could break JSON parsing (e.g. a space before <?php)
            val responseBody = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .trim()

            Log.d(TAG, "Raw backend response: '$responseBody'")

            val hash = JSONObject(responseBody).get("hash") as? String
                ?: error("Backend returned a non-string 'hash' field")
            check(hash.isNotBlank()) { "Backend returned an empty hash" }
            return hash
        } finally {
            connection.disconnect()
        }
    }

    private fun showMessage(message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(response: Any) {
        // FIX 4: reset flag after terminal states so user can make a new payment
        checkoutAttempted = false
        Log.d(TAG, "onPaymentSuccess: $response")
        showMessage("Payment successful!")
    }

    override fun onPaymentFailure(response: Any) {
        // FIX 4 (cont): reset on failure too
        checkoutAttempted = false
        Log.w(TAG, "onPaymentFailure: $response")
        showMessage("Payment failed. Please try again.")
    }

    override fun onPaymentCancel(isTxnInitiated: Boolean) {
        // FIX 4 (cont): reset on cancel so user can retry
        checkoutAttempted = false
        Log.d(TAG, "onPaymentCancel — txnInitiated: $isTxnInitiated")
        showMessage(
            if (isTxnInitiated) "Cancelled after initiation. Verify order on server."
            else "Checkout cancelled."
        )
    }

    // FIX 5: show the actual PayU error code + message instead of a generic string
    override fun onError(errorResponse: ErrorResponse) {
        checkoutAttempted = false
        Log.e(TAG, "onError — code: ${errorResponse.errorCode}, " +
                "message: ${errorResponse.errorMessage}, " +
                "full: $errorResponse")
        showMessage("PayU error [${errorResponse.errorCode}]: ${errorResponse.errorMessage}")
    }

    override fun setWebViewProperties(webView: WebView?, bank: Any?) { /* retain SDK defaults */ }
}

// ── Product Page ───────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductPage(onPayNowClicked: () -> Unit = {}, onBackClicked: () -> Unit = {}) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Product Details", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PayUBlue)
            )
        },
        bottomBar = {
            BottomActionBar(
                onAddToCart = { Toast.makeText(context, "Added to cart!", Toast.LENGTH_SHORT).show() },
                onPayNow    = onPayNowClicked
            )
        },
        containerColor = PageGrey
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            ProductImageSection()
            Spacer(Modifier.height(8.dp))
            ProductInfoCard()
            Spacer(Modifier.height(8.dp))
            HighlightsCard()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ProductImageSection() {
    Box(
        modifier = Modifier.fillMaxWidth().height(290.dp)
            .background(Brush.linearGradient(listOf(Color(0xFFE3EAF6), Color(0xFFC8D8F0)))),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp)
                .clip(RoundedCornerShape(4.dp)).background(BadgeBg)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text("Limited time deal", color = BadgeText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.size(170.dp).clip(RoundedCornerShape(85.dp)).background(Color(0xFFDDE8F8)),
            contentAlignment = Alignment.Center
        ) { Text("🎧", fontSize = 80.sp) }
    }
}

@Composable
fun ProductInfoCard() {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("SONY", color = PayUBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text("Sony WH-1000XM5 Wireless Noise Cancelling Headphones",
                color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(PriceGreen).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text("4.6 ★", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text("12,843 ratings", color = TextSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color(0xFFEBEBEB))
            Spacer(Modifier.height(14.dp))
            Text("₹24,990", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("M.R.P.: ", color = MrpGrey, fontSize = 13.sp)
                Text("₹34,990", color = MrpGrey, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough)
                Spacer(Modifier.width(8.dp))
                Text("28% off", color = DiscountRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("🚚  Free delivery by Tomorrow", color = PriceGreen, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(StockGreenBg).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("In Stock", color = StockGreenText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun HighlightsCard() {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Highlights", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            listOf(
                "• Industry-leading noise cancellation",
                "• Up to 30 hours battery life",
                "• Multipoint Bluetooth connection (2 devices)",
                "• Speak-to-Chat technology",
                "• Foldable design with carrying case"
            ).forEach {
                Text(it, color = TextSecondary, fontSize = 14.sp,
                    lineHeight = 22.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

@Composable
fun BottomActionBar(onAddToCart: () -> Unit, onPayNow: () -> Unit) {
    Surface(shadowElevation = 16.dp, color = CardWhite) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = onAddToCart, modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CartBtnBg, contentColor = PayUBlue)
            ) { Text("Add to Cart", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            Button(onClick = onPayNow, modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PayUBlue, contentColor = Color.White)
            ) { Text("Pay Now with PayU", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun ProductPagePreview() { TestTheme { ProductPage() } }