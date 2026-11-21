package io.github.wingedpanther.openshelf

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Lock {
    private const val ROUNDS = 20_000
    private const val OPEN_FOR = 10 * 60_000L
    private var openUntil = 0L
    private var fails = 0
    private var waitUntil = 0L

    val pending = mutableStateListOf<() -> Unit>()
    var on by mutableStateOf(false)
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "lock.json")

    fun init(ctx: Context) {
        on = file(ctx).exists()
    }

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val tmp = File(ctx.filesDir, "lock.json.tmp")
        tmp.writeText(JSONObject().put("salt", hex(salt)).put("hash", hex(hash(pin, salt))).toString())
        tmp.renameTo(file(ctx))
        on = true
        openUntil = 0L
    }

    fun clearPin(ctx: Context) {
        file(ctx).delete()
        on = false
    }

    fun guard(action: () -> Unit) {
        if (!on || SystemClock.elapsedRealtime() < openUntil) action() else pending.add(action)
    }

    fun release() {
        val todo = pending.toList()
        pending.clear()
        todo.forEach { it() }
    }

    @Synchronized
    fun waitSeconds(): Int = ((waitUntil - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)

    @Synchronized
    fun check(ctx: Context, pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val saved = runCatching { JSONObject(file(ctx).readText()) }.getOrNull() ?: return false
        val ok = MessageDigest.isEqual(hash(pin, unhex(saved.getString("salt"))), unhex(saved.getString("hash")))
        if (ok) {
            fails = 0
            openUntil = SystemClock.elapsedRealtime() + OPEN_FOR
        } else if (++fails % 5 == 0) {
            waitUntil = SystemClock.elapsedRealtime() + 30_000L * (fails / 5)
        }
        return ok
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ROUNDS, 256)).encoded

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private val digits = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)

@Composable
fun PinDialog(title: String, onEntered: ((String) -> String?)? = null, onClose: () -> Unit, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var wait by remember { mutableIntStateOf(Lock.waitSeconds()) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(wait) {
        if (wait > 0) {
            note = str(R.string.pin_wait, wait)
            delay(1000)
            wait = Lock.waitSeconds()
            if (wait == 0) note = ""
        }
    }
    fun submit() {
        if (pin.length < 4) {
            note = str(R.string.pin_short)
            return
        }
        if (onEntered != null) {
            note = onEntered(pin).orEmpty()
            pin = ""
            return
        }
        if (Lock.check(ctx, pin)) {
            onUnlocked()
            return
        }
        pin = ""
        wait = Lock.waitSeconds()
        if (wait == 0) note = str(R.string.pin_wrong)
        scope.launch { for (x in listOf(14f, -12f, 8f, -4f, 0f)) shake.animateTo(x, tween(45)) }
    }
    fun type(c: Char) {
        if (wait > 0 || pin.length >= 6) return
        pin += c
        note = ""
        if (pin.length == 6) scope.launch { delay(120); submit() }
    }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(380.dp).shadow(16.dp, RoundedCornerShape(18.dp)).background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp)
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.Backspace || e.key == Key.Delete || e.key == Key.Clear) {
                        if (e.type == KeyEventType.KeyDown && wait == 0) { pin = pin.dropLast(1); note = "" }
                        return@onPreviewKeyEvent true
                    }
                    val d = digits[e.key] ?: return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) type(d)
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            T(title, 20.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 2)
            Row(Modifier.padding(top = 18.dp).offset { IntOffset(shake.value.dp.roundToPx(), 0) }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(maxOf(4, pin.length)) { i ->
                    Box(Modifier.size(13.dp).background(if (i < pin.length) C.accent else C.Hair2, CircleShape))
                }
            }
            T(note, 13.sp, Modifier.padding(top = 10.dp).height(18.dp), color = C.Stone, maxLines = 1)
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "del", "0", "ok").chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            Focusable(
                                onClick = { when (k) { "del" -> pin = pin.dropLast(1); "ok" -> submit(); else -> type(k[0]) } },
                                requester = if (k == "1") first else null,
                            ) { f ->
                                Box(
                                    Modifier.size(86.dp, 48.dp).lift(f, 1.06f, RoundedCornerShape(12.dp), rest = 0.dp)
                                        .background(if (f) C.accent else if (k == "ok") C.Ink else C.WallDeep, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val fg = if (f) C.OnAccent else if (k == "ok") C.OnInk else C.Ink
                                    when (k) {
                                        "del" -> Ico(Icons.Delete, 20.dp, fg)
                                        "ok" -> T("OK", 16.sp, color = fg, weight = FontWeight.SemiBold)
                                        else -> T(k, 20.sp, color = fg, weight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}
