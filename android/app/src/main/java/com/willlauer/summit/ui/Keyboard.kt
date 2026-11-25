package com.willlauer.summit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The field Summit's keyboard is typing into: its text and cursor, digits only or not, what Done does. */
class KeyTarget(val get: () -> TextFieldValue, val set: (TextFieldValue) -> Unit, val digits: Boolean, val capitalize: Boolean, val onDone: () -> Unit)

/**
 * Summit's own keyboard (Ice Wire: cut-corner neon tube keys in Exo 2, a glow when pressed) instead of Android's.
 * Every text field in the app is a [KeyField]: focusing it points [target] at it and the keyboard appears at the
 * bottom (in a dialog, inside the dialog: [KeyDialog]); the system keyboard is never opened.
 */
object Keys {
    var target by mutableStateOf<KeyTarget?>(null)
    var inDialog by mutableStateOf(false)
    val height: Dp = 248.dp
}

/** A text field typed with Summit's keyboard. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
             digits: Boolean = false, capitalize: Boolean = false, onDone: () -> Unit = {}) {
    val change by rememberUpdatedState(onValueChange)
    val done by rememberUpdatedState(onDone)
    // Text and cursor together: the keyboard types at the cursor and the cursor moves with it.
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (tfv.text != value) tfv = TextFieldValue(value, TextRange(value.length))  // changed elsewhere (e.g. the clear button)
    val target = remember { KeyTarget({ tfv }, { v -> tfv = v; change(v.text) }, digits, capitalize) { done() } }
    DisposableEffect(Unit) { onDispose { if (Keys.target === target) Keys.target = null } }
    // Android's keyboard never starts for these fields: the request is held until the field lets go.
    InterceptPlatformTextInput({ _, _ -> awaitCancellation() }) {
        BasicTextField(tfv, { v -> tfv = v; if (v.text != value) change(v.text) }, modifier.onFocusChanged { if (it.isFocused) Keys.target = target else if (Keys.target === target) Keys.target = null },
            singleLine = true, textStyle = textStyle.copy(color = C.Text), cursorBrush = SolidColor(C.Tube),
            keyboardOptions = KeyboardOptions.Default, keyboardActions = KeyboardActions.Default)
    }
}

private val LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYMBOLS = listOf("1234567890", "-/:;()&@\"'", ".,?!#+=_")

/** The keyboard for the focused [KeyField] (nothing when none is focused). */
@Composable
fun SummitKeyboard(modifier: Modifier = Modifier) {
    val t = Keys.target ?: return
    val fm = LocalFocusManager.current
    var shift by remember(t) { mutableStateOf(t.capitalize && t.get().text.isEmpty()) }
    var caps by remember(t) { mutableStateOf(false) }
    var symbols by remember(t) { mutableStateOf(false) }
    fun type(s: String) {
        val ins = if ((shift || caps) && !symbols) s.uppercase() else s
        val v = t.get(); val a = v.selection.min; val b = v.selection.max
        t.set(TextFieldValue(v.text.replaceRange(a, b, ins), TextRange(a + ins.length)))
        if (shift && !caps) shift = false
        if (t.capitalize && s == " " && t.get().text.take(a).trimEnd().endsWith(".")) shift = true
    }
    fun back() {
        val v = t.get(); val a = v.selection.min; val b = v.selection.max
        if (a != b) t.set(TextFieldValue(v.text.removeRange(a, b), TextRange(a)))
        else if (a > 0) t.set(TextFieldValue(v.text.removeRange(a - 1, a), TextRange(a - 1)))
        if (t.capitalize && t.get().text.isEmpty()) shift = true
    }
    fun done() { t.onDone(); fm.clearFocus(); Keys.target = null }
    BackHandler { fm.clearFocus(); Keys.target = null }
    Column(modifier.fillMaxWidth().height(Keys.height)
        .background(Brush.verticalGradient(listOf(C.Panel, C.Bg)))
        .drawTopEdge(C.Sign, C.Tube)
        .navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (t.digits) {
            listOf("123", "456", "789").forEach { row -> KeyRow { row.forEach { c -> Key(c.toString(), Modifier.weight(1f)) { type(c.toString()) } } } }
            KeyRow {
                Key("⌫", Modifier.weight(1f), repeat = true) { back() }
                Key("0", Modifier.weight(1f)) { type("0") }
                Key("DONE", Modifier.weight(1f), primary = true) { done() }
            }
            return@Column
        }
        val rows = if (symbols) SYMBOLS else LETTERS
        KeyRow { rows[0].forEach { c -> Key(c.label(shift || caps, symbols), Modifier.weight(1f)) { type(c.toString()) } } }
        KeyRow(Modifier.padding(horizontal = if (symbols) 0.dp else 16.dp)) { rows[1].forEach { c -> Key(c.label(shift || caps, symbols), Modifier.weight(1f)) { type(c.toString()) } } }
        KeyRow {
            if (!symbols) Key(if (caps) "⇪" else "⇧", Modifier.weight(1.5f), lit = shift || caps) {
                if (caps) { caps = false; shift = false } else if (shift) caps = true else shift = true
            } else Spacer(Modifier.weight(.5f))
            rows[2].forEach { c -> Key(c.label(shift || caps, symbols), Modifier.weight(1f)) { type(c.toString()) } }
            if (symbols) Spacer(Modifier.weight(.5f))
            Key("⌫", Modifier.weight(1.5f), repeat = true) { back() }
        }
        KeyRow {
            Key(if (symbols) "ABC" else "123", Modifier.weight(1.6f), small = true) { symbols = !symbols }
            Key(",", Modifier.weight(1f)) { type(",") }
            Key("SPACE", Modifier.weight(4.2f), small = true) { type(" ") }
            Key(".", Modifier.weight(1f)) { type(".") }
            Key("DONE", Modifier.weight(2f), primary = true, small = true) { done() }
        }
    }
}

private fun Char.label(up: Boolean, symbols: Boolean) = if (up && !symbols) uppercase() else toString()

@Composable
private fun ColumnScope.KeyRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)

/** One neon tube key: cut corners, ice outline; pressed it lights up blue and glows. [repeat] keeps firing while held. */
@Composable
private fun Key(label: String, modifier: Modifier, primary: Boolean = false, lit: Boolean = false, small: Boolean = false, repeat: Boolean = false, onTap: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val on = down || lit
    val bg = when { primary -> if (down) C.Tube.copy(alpha = .9f) else C.Sign; on -> C.Sign.copy(alpha = .38f); else -> C.Tube.copy(alpha = .06f) }
    val fg = when { primary -> if (down) C.Ink else C.Hi; on -> C.Hi; else -> C.Tube }
    Box(modifier.fillMaxHeight().background(bg, PxSmall)
        .border(1.dp, when { primary -> Color.Transparent; on -> C.Sign; else -> C.Tube.copy(alpha = .3f) }, PxSmall)
        .pointerInput(repeat) {
            val pointer = this
            coroutineScope {
                val cs = this
                pointer.awaitEachGesture {
                    awaitFirstDown()
                    down = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val j = if (repeat) { tap(); cs.launch { delay(420); while (true) { tap(); delay(55) } } } else null
                    val up = waitForUpOrCancellation()
                    j?.cancel()
                    if (!repeat && up != null) tap()
                    down = false
                }
            }
        }, contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = if (small) 12.sp else 19.sp, letterSpacing = if (small) 1.6.sp else 0.sp,
            shadow = if (on || primary) Shadow(if (primary) Color.White.copy(alpha = .6f) else C.Tube, Offset.Zero, 14f) else null), color = fg, maxLines = 1)
    }
}

/** The keyboard's lit top edge: blue to ice to blue, like the mini player's. */
private fun Modifier.drawTopEdge(a: Color, b: Color) = this.drawBehind {
    drawRect(Brush.horizontalGradient(listOf(a, b, a)), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
}

/** A dialog with a text field: the card sits up top and Summit's keyboard at the bottom (dialogs are their own window). */
@Composable
fun KeyDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DisposableEffect(Unit) { Keys.inDialog = true; onDispose { Keys.inDialog = false } }
        Box(Modifier.fillMaxSize().background(Color(0xB302060C))) {
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 20.dp, end = 20.dp).fillMaxWidth()
                .clip(PxBig).background(C.Raised2).border(1.dp, Brush.horizontalGradient(listOf(C.Sign, C.Tube)), PxBig).padding(20.dp), content = content)
            SummitKeyboard(Modifier.align(Alignment.BottomCenter))
        }
    }
}
