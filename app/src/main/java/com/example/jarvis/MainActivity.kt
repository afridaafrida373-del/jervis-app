package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

// Free Gemini model. Jodi kaj na kore, aistudio.google.com-e dekho kon model free ache.
const val GEMINI_MODEL = "gemini-3.8-flash"

const val SYSTEM_PROMPT =
    "Tumi Jarvis, ekjon voice assistant. Shobshomoy shohoj Bangla-te, choto uttor dao " +
        "(1-3 line), karon uttor mukhe bola hobe. Markdown ba emoji byabohar korbe na."

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var statusText: TextView
    private lateinit var keyInput: EditText
    private val client = OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).callTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
    private val history = JSONArray()
    private val bangla = Locale("bn", "BD")

    private fun apiKey(): String =
        getSharedPreferences("jarvis", MODE_PRIVATE).getString("gemini_key", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        keyInput = EditText(this).apply {
            hint = if (apiKey().isBlank()) "Gemini API key boshao" else "API key save kora ache"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val saveButton = Button(this).apply {
            text = "Key save koro"
            setOnClickListener {
                val k = keyInput.text.toString().trim()
                if (k.isNotEmpty()) {
                    getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                        .putString("gemini_key", k).apply()
                    keyInput.setText("")
                    keyInput.hint = "API key save kora ache"
                    statusText.text = "Key save hoyeche. Ekhon mic chipe kotha bolo"
                }
            }
        }
        statusText = TextView(this).apply {
            text = "Mic chipe kotha bolo"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 48)
        }
        val micButton = Button(this).apply {
            text = "Jarvis (mic)"
            setOnClickListener { startListening() }
        }
        root.addView(keyInput)
        root.addView(saveButton)
        root.addView(statusText)
        root.addView(micButton)
        setContentView(root)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) {
                    statusText.text = "Bujhte parini, abar bolo"
                } else {
                    statusText.text = "Tumi: $text"
                    askAi(text)
                }
            }

            override fun onError(error: Int) {
                statusText.text = "Shunte parini (error $error)"
            }

            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = "Shunchi..."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(bangla)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                statusText.text = "Phone-e Bangla voice nai. Settings > Text-to-speech-e download koro"
            }
        }
    }

    private fun startListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "Age mic permission dao"
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        }
        recognizer.startListening(intent)
    }

    private fun dropLastUserMessage() {
        if (history.length() > 0) history.remove(history.length() - 1)
    }

    private fun message(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun askAi(userText: String) {
        val key = apiKey()
        if (key.isBlank()) {
            statusText.text = "Age upore API key save koro"
            return
        }
        history.put(message("user", userText))

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            .put("contents", history)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent")
            .addHeader("x-goog-api-key", key)
            .post(body)
            .build()

        statusText.text = "Vabchi..."

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                dropLastUserMessage()
                runOnUiThread { statusText.text = "Internet nai ba error: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string().orEmpty()
                try {
                    val parts = JSONObject(raw)
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        sb.append(parts.getJSONObject(i).optString("text", ""))
                    }
                    val reply = sb.toString().trim()
                    if (reply.isEmpty()) throw IllegalStateException("empty reply")
                    history.put(message("model", reply))
                    runOnUiThread {
                        statusText.text = "Jarvis: $reply"
                        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
                    }
                } catch (e: Exception) {
                    dropLastUserMessage()
                    runOnUiThread { statusText.text = "Error: $raw" }
                }
            }
        })
    }

    override fun onDestroy() {
        recognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
