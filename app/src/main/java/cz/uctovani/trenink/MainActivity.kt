package cz.uctovani.trenink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.random.Random
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.saveable.rememberSaveable

private val ComponentActivity.dataStore by preferencesDataStore(name = "uctovani_prefs")

data class Item(val doklad: String, val pripad: String, val md: String, val d: String)

data class Account(
    val number: String,
    val name: String,
    val classId: String,
    val className: String,
    val examples: List<String>
)

// ===== Zápočtový test =====
private enum class ExamQuestionType { MCQ_SINGLE, MCQ_MULTI, ENTRY_MD_D }

private data class ExamQuestion(
    val id: String,
    val type: ExamQuestionType,
    val question: String,
    val options: List<String> = emptyList(),
    val correctOptions: List<Int> = emptyList(),
    val md: String = "",
    val d: String = "",
    val source: String = "zapoctovy-test.docx"
)

private enum class Screen(val title: String) {
    TRAINER("Procvičování"),
    EXAM("Zápočtový test"),
    ACCOUNTS("Účtový rozvrh"),
}


data class Progress(
    val answered: Int,
    val correct: Int,
    val seen: Int,
    val lastIndex: Int,
    val lastMd: String,
    val lastD: String
)

private object Keys {
    val ANSWERED = intPreferencesKey("answered")
    val CORRECT = intPreferencesKey("correct")
    val SEEN = intPreferencesKey("seen")
    val LAST_INDEX = intPreferencesKey("last_index")
    val LAST_MD = stringPreferencesKey("last_md")
    val LAST_D = stringPreferencesKey("last_d")
}

private enum class ResultKind { Good, Bad }

private enum class Topic(val title: String) {
    ALL("Vše"),
    ZASOBY("Zásoby"),
    REKL_INVENT("Reklamace & inventarizace"),
    DPH("DPH"),
    MZDY("Mzdy"),
    POHLED_ZAVAZ("Pohledávky & závazky"),
    PENIZE_BANKY("Peníze & banky"),
    MAJETEK("Majetek"),
    NAKL_VYN("Náklady & výnosy"),
    KAPITAL_VH("Kapitál & VH"),
    ZAVERKA("Závěrka"),
    OSTATNI("Ostatní"),
}

private enum class Mode(val title: String) {
    LEARNING("Učení"),
    TEST("Test"),
}

private fun normalizeAcc(s: String): String = s.trim()

private fun Item.topic(): Topic {
    // Heuristika: podle účtů (MD/D) + textu případu (pro zkouškové okruhy)
    val mdN = normalizeAcc(md)
    val dN = normalizeAcc(d)
    val txt = (pripad + " " + doklad).lowercase()

    fun hasAcc(vararg acc: String): Boolean {
        val all = "$mdN $dN"
        return acc.any { all.contains(it) }
    }

    // 1) DPH
    if (hasAcc("343", "349") || txt.contains("dph") || txt.contains("samovyměř")) return Topic.DPH

    // 2) Mzdy
    if (hasAcc("521", "331", "336", "342", "524", "522", "366") ||
        txt.contains("mzda") || txt.contains("zvl") || txt.contains("sociáln") || txt.contains("zdravot")
    ) return Topic.MZDY

    // 3) Závěrka
    if (hasAcc("701", "702", "710") || txt.contains("závěrk") || txt.contains("uzav")) return Topic.ZAVERKA

    // 4) Kapitál & výsledek hospodaření
    if (hasAcc("411", "412", "413", "419", "421", "423", "428", "429", "431", "432", "451", "459", "481", "491", "364", "365") ||
        txt.contains("základn") || txt.contains("rezerv") || txt.contains("rozdělen") || txt.contains("podíl na zisku") || txt.contains("ztrát")
    ) return Topic.KAPITAL_VH

    // 5) Reklamace & inventarizace (manka/přebytky/škody)
    if (hasAcc("315", "559", "19", "547", "548") ||
        (hasAcc("549") && (txt.contains("manko") || txt.contains("reklam") || txt.contains("invent"))) ||
        txt.contains("reklam") || txt.contains("manko") || txt.contains("přebyt") || txt.contains("inventar") || txt.contains("škod") || txt.contains("živeln")
    ) return Topic.REKL_INVENT

    // 6) Zásoby
    if (hasAcc("111", "112", "119", "131", "132", "139", "121", "122", "123", "153", "504", "501", "542", "583") ||
        txt.contains("zásob") || txt.contains("zbož") || txt.contains("materiál") || txt.contains("sklad")
    ) return Topic.ZASOBY

    // 7) Peníze & banky
    if (hasAcc("211", "221", "261", "231", "213", "259", "251", "253", "241", "375") ||
        txt.contains("poklad") || txt.contains("banka") || txt.contains("úvěr") || txt.contains("peníze na cestě") || txt.contains("cenin")
    ) return Topic.PENIZE_BANKY

    // 8) Pohledávky & závazky (včetně směnek)
    if (hasAcc("311", "321", "314", "335", "378", "379", "389", "388", "391", "546", "256", "313", "232", "322", "333") ||
        txt.contains("pohled") || txt.contains("závaz") || txt.contains("záloh") || txt.contains("směnk") || txt.contains("eskont")
    ) return Topic.POHLED_ZAVAZ

    // 9) Majetek
    if (hasAcc("021", "022", "031", "041", "042", "07", "08", "09", "551") || txt.contains("majet")) return Topic.MAJETEK

    // 10) Náklady & výnosy (obecně)
    if (mdN.startsWith("5") || dN.startsWith("5") || mdN.startsWith("6") || dN.startsWith("6") ||
        txt.contains("náklad") || txt.contains("výnos")
    ) return Topic.NAKL_VYN

    return Topic.OSTATNI
}


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataset = loadDatasetFromAssets()
        val accounts = loadAccountsFromAssets()
        val examQuestions = loadExamQuestionsFromAssets()

        // “OLED-friendly” tmavý základ: černé pozadí, tmavé plochy.
        val oledDarkScheme = darkColorScheme(
            background = Color.Black,
            surface = Color(0xFF0E0E0E),
            surfaceVariant = Color(0xFF151515),
        )

        setContent {
            MaterialTheme(colorScheme = oledDarkScheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(dataset = dataset, accounts = accounts, examQuestions = examQuestions)
                }
            }
        }
    }

    private fun loadDatasetFromAssets(): List<Item> {
        val json = assets.open("dataset.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(json)
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Item(
                    doklad = o.optString("doklad", ""),
                    pripad = o.optString("pripad", ""),
                    md = o.optString("md", ""),
                    d = o.optString("d", "")
                )
            )
        }
        return out
    }

    private fun loadAccountsFromAssets(): List<Account> {
        val json = assets.open("accounts.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(json)
        val out = ArrayList<Account>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val examplesArr = o.optJSONArray("examples") ?: JSONArray()
            val ex = ArrayList<String>(examplesArr.length())
            for (j in 0 until examplesArr.length()) ex.add(examplesArr.optString(j))
            out.add(
                Account(
                    number = o.optString("number", ""),
                    name = o.optString("name", ""),
                    classId = o.optString("class", ""),
                    className = o.optString("className", ""),
                    examples = ex
                )
            )
        }
        return out
    }

    private fun loadExamQuestionsFromAssets(): List<ExamQuestion> {
        return try {
            val json = assets.open("questions.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(json)
            val out = ArrayList<ExamQuestion>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = when (o.optString("type")) {
                    "MCQ_MULTI" -> ExamQuestionType.MCQ_MULTI
                    "ENTRY_MD_D" -> ExamQuestionType.ENTRY_MD_D
                    else -> ExamQuestionType.MCQ_SINGLE
                }

                val optionsArr = o.optJSONArray("options") ?: JSONArray()
                val options = ArrayList<String>(optionsArr.length())
                for (j in 0 until optionsArr.length()) options.add(optionsArr.optString(j))

                val correctArr = o.optJSONArray("correctOptions") ?: JSONArray()
                val correct = ArrayList<Int>(correctArr.length())
                for (j in 0 until correctArr.length()) correct.add(correctArr.optInt(j))

                out.add(
                    ExamQuestion(
                        id = o.optString("id", "q_$i"),
                        type = type,
                        question = o.optString("question", ""),
                        options = options,
                        correctOptions = correct,
                        md = o.optString("md", ""),
                        d = o.optString("d", ""),
                        source = o.optString("source", "zapoctovy-test.docx")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }


    private suspend fun readProgress(): Progress {
        val prefs = dataStore.data.first()
        return Progress(
            answered = prefs[Keys.ANSWERED] ?: 0,
            correct = prefs[Keys.CORRECT] ?: 0,
            seen = prefs[Keys.SEEN] ?: 0,
            lastIndex = prefs[Keys.LAST_INDEX] ?: -1,
            lastMd = prefs[Keys.LAST_MD] ?: "",
            lastD = prefs[Keys.LAST_D] ?: ""
        )
    }

    private suspend fun writeProgress(p: Progress) {
        dataStore.edit { prefs ->
            prefs[Keys.ANSWERED] = p.answered
            prefs[Keys.CORRECT] = p.correct
            prefs[Keys.SEEN] = p.seen
            prefs[Keys.LAST_INDEX] = p.lastIndex
            prefs[Keys.LAST_MD] = p.lastMd
            prefs[Keys.LAST_D] = p.lastD
        }
    }

    @Composable
    private fun AppRoot(dataset: List<Item>, accounts: List<Account>, examQuestions: List<ExamQuestion>) {
        var screen by remember { mutableStateOf(Screen.TRAINER) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen == Screen.TRAINER,
                        onClick = { screen = Screen.TRAINER },
                        label = { Text(Screen.TRAINER.title) },
                        icon = { Text("🎯") }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.EXAM,
                        onClick = { screen = Screen.EXAM },
                        label = { Text(Screen.EXAM.title) },
                        icon = { Text("🧪") }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.ACCOUNTS,
                        onClick = { screen = Screen.ACCOUNTS },
                        label = { Text(Screen.ACCOUNTS.title) },
                        icon = { Text("📚") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.TRAINER -> TrainerScreen(dataset = dataset, accounts = accounts)
                    Screen.EXAM -> ExamScreen(questions = examQuestions, accounts = accounts)
                    Screen.ACCOUNTS -> AccountPlanScreen(accounts = accounts)
                }
            }
        }
    }

    @Composable
    private fun ExamScreen(questions: List<ExamQuestion>, accounts: List<Account>) {
        val focusManager = LocalFocusManager.current

        enum class ExamMode { PRACTICE, TEST }

        data class ExamUserAnswer(
            val type: ExamQuestionType,
            val single: Int? = null,
            val multi: Set<Int>? = null,
            val md: String? = null,
            val d: String? = null,
            val isCorrect: Boolean = false,
        )

        var mode by rememberSaveable { mutableStateOf(ExamMode.PRACTICE) }

        // Testovací režim
        var testSizeText by rememberSaveable { mutableStateOf("20") }
        var testStarted by rememberSaveable { mutableStateOf(false) }
        var testFinished by rememberSaveable { mutableStateOf(false) }
        var testReview by rememberSaveable { mutableStateOf(false) }
        var testOrder by rememberSaveable { mutableStateOf(listOf<Int>()) }
        var testPos by rememberSaveable { mutableIntStateOf(0) }
        val testAnswers = remember { mutableStateMapOf<String, ExamUserAnswer>() }
        var testScore by rememberSaveable { mutableIntStateOf(0) }

        var idx by rememberSaveable { mutableIntStateOf(0) }
        var current by remember { mutableStateOf<ExamQuestion?>(null) }

        // odpovědi
        var selectedSingle by rememberSaveable { mutableIntStateOf(-1) }
        var selectedMulti by rememberSaveable { mutableStateOf(setOf<Int>()) }
        var md by rememberSaveable { mutableStateOf("") }
        var d by rememberSaveable { mutableStateOf("") }

        // výsledek
        var checked by rememberSaveable { mutableStateOf(false) }
        var resultText by rememberSaveable { mutableStateOf("—") }
        var resultKind by rememberSaveable { mutableStateOf<ResultKind?>(null) }

        fun setQuestion(newIdx: Int) {
            if (questions.isEmpty()) {
                current = null
                idx = 0
                checked = false
                resultText = "V assets chybí questions.json 🙂"
                resultKind = ResultKind.Bad
                selectedSingle = -1
                selectedMulti = emptySet()
                md = ""
                d = ""
                return
            }
            val safe = newIdx.coerceIn(0, questions.lastIndex)
            idx = safe
            current = questions[safe]
            checked = false
            resultText = "—"
            resultKind = null
            selectedSingle = -1
            selectedMulti = emptySet()
            md = ""
            d = ""
        }

        fun nextRandom() {
            if (questions.isEmpty()) {
                setQuestion(0)
                return
            }
            setQuestion(Random.nextInt(questions.size))
        }

        fun setQuestionFromTestPosition() {
            if (testOrder.isEmpty()) {
                setQuestion(0)
                return
            }
            val safePos = testPos.coerceIn(0, testOrder.lastIndex)
            val qIdx = testOrder[safePos]
            setQuestion(qIdx)

            // Při prohlížení/vrácení vyplň předchozí odpověď
            val q = current
            val saved = if (q != null) testAnswers[q.id] else null
            if (saved != null) {
                when (saved.type) {
                    ExamQuestionType.MCQ_SINGLE -> selectedSingle = saved.single ?: -1
                    ExamQuestionType.MCQ_MULTI -> selectedMulti = saved.multi ?: emptySet()
                    ExamQuestionType.ENTRY_MD_D -> {
                        md = saved.md ?: ""
                        d = saved.d ?: ""
                    }
                }
            }
        }

        fun startTest() {
            val requested = testSizeText.trim().toIntOrNull() ?: 20
            val size = requested.coerceIn(1, questions.size.coerceAtLeast(1))
            testOrder = questions.indices.shuffled().take(size)
            testPos = 0
            testAnswers.clear()
            testScore = 0
            testStarted = true
            testFinished = false
            testReview = false
            checked = false
            resultKind = null
            resultText = "—"
            setQuestionFromTestPosition()
        }

        fun finishTest() {
            testScore = testAnswers.values.count { it.isCorrect }
            testFinished = true
            testReview = false
            checked = false
            resultKind = null
            resultText = "Hotovo ✅"
        }

        fun saveCurrentAnswerAndEvaluate(showImmediateFeedback: Boolean) {
            val q = current ?: return

            fun save(isCorrect: Boolean) {
                val ans = when (q.type) {
                    ExamQuestionType.MCQ_SINGLE -> ExamUserAnswer(
                        type = q.type,
                        single = selectedSingle,
                        isCorrect = isCorrect,
                    )
                    ExamQuestionType.MCQ_MULTI -> ExamUserAnswer(
                        type = q.type,
                        multi = selectedMulti,
                        isCorrect = isCorrect,
                    )
                    ExamQuestionType.ENTRY_MD_D -> ExamUserAnswer(
                        type = q.type,
                        md = md.trim(),
                        d = d.trim(),
                        isCorrect = isCorrect,
                    )
                }
                testAnswers[q.id] = ans
            }

            when (q.type) {
                ExamQuestionType.MCQ_SINGLE -> {
                    val ok = selectedSingle != -1 && q.correctOptions.contains(selectedSingle)
                    save(ok)
                    if (showImmediateFeedback) {
                        resultKind = if (ok) ResultKind.Good else ResultKind.Bad
                        resultText = if (ok) "✅ Správně!" else "❌ Špatně."
                        checked = true
                    } else {
                        resultKind = null
                        resultText = "Uloženo"
                        checked = false
                    }
                }
                ExamQuestionType.MCQ_MULTI -> {
                    val ok = selectedMulti.isNotEmpty() && selectedMulti.sorted() == q.correctOptions.sorted()
                    save(ok)
                    if (showImmediateFeedback) {
                        resultKind = if (ok) ResultKind.Good else ResultKind.Bad
                        resultText = if (ok) "✅ Správně!" else "❌ Špatně."
                        checked = true
                    } else {
                        resultKind = null
                        resultText = "Uloženo"
                        checked = false
                    }
                }
                ExamQuestionType.ENTRY_MD_D -> {
                    val mdN = md.trim()
                    val dN = d.trim()
                    if (mdN.isEmpty() || dN.isEmpty()) {
                        resultKind = ResultKind.Bad
                        resultText = "Doplň MD i D 🙂"
                        checked = true
                        return
                    }
                    val ok = mdN == q.md.trim() && dN == q.d.trim()
                    save(ok)
                    if (showImmediateFeedback) {
                        resultKind = if (ok) ResultKind.Good else ResultKind.Bad
                        resultText = if (ok) "✅ Správně!" else "❌ Špatně."
                        checked = true
                    } else {
                        resultKind = null
                        resultText = "Uloženo"
                        checked = false
                    }
                }
            }
        }

        fun check() {
            // Procvičování = okamžité vyhodnocení.
            // Test = odpověď uložit + vyhodnotit interně, ale NEukazovat správně/špatně průběžně.
            if (mode == ExamMode.TEST && testStarted && !testFinished) {
                saveCurrentAnswerAndEvaluate(showImmediateFeedback = false)
            } else {
                saveCurrentAnswerAndEvaluate(showImmediateFeedback = true)
            }
        }

        LaunchedEffect(questions) {
            if (current == null) setQuestion(0)
        }

        val q = current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Zápočtový test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (q == null) {
                Text("V assets chybí questions.json 🙂")
                return@Column
            }

            // Přepínač režimu
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Režim:")
                        FilterChip(
                            selected = mode == ExamMode.PRACTICE,
                            onClick = {
                                mode = ExamMode.PRACTICE
                                testStarted = false
                                testFinished = false
                                testReview = false
                                checked = false
                                resultKind = null
                                resultText = "—"
                                nextRandom()
                            },
                            label = { Text("Procvičování") }
                        )
                        FilterChip(
                            selected = mode == ExamMode.TEST,
                            onClick = {
                                mode = ExamMode.TEST
                                testStarted = false
                                testFinished = false
                                testReview = false
                                checked = false
                                resultKind = null
                                resultText = "—"
                                setQuestion(0)
                            },
                            label = { Text("Test") }
                        )
                    }

                    if (mode == ExamMode.TEST) {
                        if (!testStarted) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = testSizeText,
                                    onValueChange = { testSizeText = it.filter { ch -> ch.isDigit() }.take(3) },
                                    label = { Text("Počet otázek") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                )
                                Button(
                                    onClick = { focusManager.clearFocus(); startTest() },
                                    enabled = questions.isNotEmpty(),
                                ) { Text("Spustit") }
                            }
                            Text(
                                "V testu se průběžně neukazuje správně/špatně. Vyhodnocení uvidíš až na konci.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val total = testOrder.size.coerceAtLeast(1)
                            val answered = testAnswers.size
                            val posHuman = (testPos + 1).coerceAtMost(total)
                            Text(
                                buildString {
                                    append("Otázka ")
                                    append(posHuman)
                                    append("/")
                                    append(total)
                                    if (!testFinished) {
                                        append(" • zodpovězeno ")
                                        append(answered)
                                    } else {
                                        append(" • skóre ")
                                        append(testScore)
                                        append("/")
                                        append(total)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(q.question)

                    when (q.type) {
                        ExamQuestionType.MCQ_SINGLE -> {
                            q.options.forEachIndexed { i, opt ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedSingle = i }
                                        .padding(vertical = 6.dp)
                                ) {
                                    RadioButton(selected = selectedSingle == i, onClick = { selectedSingle = i })
                                    Spacer(Modifier.width(8.dp))
                                    Text(opt)
                                }
                            }
                        }
                        ExamQuestionType.MCQ_MULTI -> {
                            q.options.forEachIndexed { i, opt ->
                                val checkedBox = selectedMulti.contains(i)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedMulti = if (checkedBox) selectedMulti - i else selectedMulti + i
                                        }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Checkbox(
                                        checked = checkedBox,
                                        onCheckedChange = {
                                            selectedMulti = if (checkedBox) selectedMulti - i else selectedMulti + i
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(opt)
                                }
                            }
                        }
                        ExamQuestionType.ENTRY_MD_D -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = md,
                                    onValueChange = { md = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("MD") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right) })
                                )
                                OutlinedTextField(
                                    value = d,
                                    onValueChange = { d = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("D") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); check() })
                                )
                            }

                            // Bez názvů účtů (neprozrazovat). Jen „A/P/N/V“ hint.
                            fun natureHint(acc: String): String {
                                val a = accounts.firstOrNull { it.number == acc.trim() } ?: return ""
                                return when {
                                    a.classId.startsWith("0") || a.classId.startsWith("1") -> "Aktivní (typicky)"
                                    a.classId.startsWith("2") || a.classId.startsWith("3") -> "Pasivní (typicky)"
                                    a.classId.startsWith("5") -> "Nákladový"
                                    a.classId.startsWith("6") -> "Výnosový"
                                    else -> ""
                                }
                            }

                            val mdHint = natureHint(md)
                            val dHint = natureHint(d)
                            if (mdHint.isNotBlank() || dHint.isNotBlank()) {
                                Text(
                                    buildString {
                                        if (mdHint.isNotBlank()) append("MD: $mdHint")
                                        if (mdHint.isNotBlank() && dHint.isNotBlank()) append(" • ")
                                        if (dHint.isNotBlank()) append("D: $dHint")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (mode == ExamMode.TEST && testStarted) {
                            if (!testFinished) {
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        // uložit odpověď a jít dál
                                        saveCurrentAnswerAndEvaluate(showImmediateFeedback = false)
                                        if (testPos >= testOrder.lastIndex) {
                                            finishTest()
                                        } else {
                                            testPos += 1
                                            setQuestionFromTestPosition()
                                        }
                                    }
                                ) { Text("Uložit a další") }

                                OutlinedButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        // přeskočit bez uložení
                                        if (testPos >= testOrder.lastIndex) finishTest()
                                        else {
                                            testPos += 1
                                            setQuestionFromTestPosition()
                                        }
                                    }
                                ) { Text("Přeskočit") }
                            } else {
                                Button(
                                    onClick = {
                                        testReview = true
                                        testPos = 0
                                        setQuestionFromTestPosition()
                                    }
                                ) { Text("Prohlédnout") }
                                OutlinedButton(
                                    onClick = { focusManager.clearFocus(); startTest() }
                                ) { Text("Nový test") }
                            }
                        } else {
                            Button(onClick = { focusManager.clearFocus(); check() }) { Text("Zkontrolovat") }
                            OutlinedButton(onClick = { focusManager.clearFocus(); nextRandom() }) { Text("Další") }
                        }
                    }
                }
            }

            val rk = resultKind
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (rk) {
                        ResultKind.Good -> Color(0xFF10351A)
                        ResultKind.Bad -> Color(0xFF3A1212)
                        null -> MaterialTheme.colorScheme.surface
                    }
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(resultText, fontWeight = FontWeight.SemiBold)

                    val showSolutionNow = checked || (mode == ExamMode.TEST && testStarted && testFinished && testReview)
                    if (showSolutionNow) {
                        when (q.type) {
                            ExamQuestionType.MCQ_SINGLE, ExamQuestionType.MCQ_MULTI -> {
                                val correct = q.correctOptions
                                    .sorted()
                                    .mapNotNull { i -> q.options.getOrNull(i) }
                                if (correct.isNotEmpty()) {
                                    Text("Správně: ${correct.joinToString("; ")}")
                                }

                                if (mode == ExamMode.TEST && testStarted && testFinished && testReview) {
                                    val ua = testAnswers[q.id]
                                    val yours = when (q.type) {
                                        ExamQuestionType.MCQ_SINGLE -> ua?.single?.let { i -> q.options.getOrNull(i) }
                                        ExamQuestionType.MCQ_MULTI -> ua?.multi?.sorted()?.mapNotNull { i -> q.options.getOrNull(i) }?.joinToString("; ")
                                        else -> null
                                    }
                                    if (!yours.isNullOrBlank()) Text("Tvoje: $yours")
                                }
                            }
                            ExamQuestionType.ENTRY_MD_D -> {
                                if (q.md.isNotBlank() && q.d.isNotBlank()) {
                                    Text("Správně: ${q.md}/${q.d}")
                                }

                                if (mode == ExamMode.TEST && testStarted && testFinished && testReview) {
                                    val ua = testAnswers[q.id]
                                    val yours = listOfNotNull(ua?.md, ua?.d).joinToString("/")
                                    if (yours.isNotBlank()) Text("Tvoje: $yours")
                                }
                            }
                        }

                        Text(
                            "Zdroj: ${q.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (mode == ExamMode.TEST && testStarted && testFinished && testReview) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (testPos > 0) {
                                testPos -= 1
                                setQuestionFromTestPosition()
                            }
                        },
                        enabled = testPos > 0
                    ) { Text("Předchozí") }
                    Button(
                        onClick = {
                            if (testPos < testOrder.lastIndex) {
                                testPos += 1
                                setQuestionFromTestPosition()
                            }
                        },
                        enabled = testPos < testOrder.lastIndex
                    ) { Text("Další") }
                }
            }
        }
    }

@Composable
    private fun TrainerScreen(dataset: List<Item>, accounts: List<Account>) {
        val scope = rememberCoroutineScope()

        val focusManager = LocalFocusManager.current
        val context = LocalContext.current

        var progress by remember { mutableStateOf(Progress(0, 0, 0, -1, "", "")) }

        var selectedTopics by rememberSaveable { mutableStateOf(setOf(Topic.ALL)) }
        var mode by rememberSaveable { mutableStateOf(Mode.LEARNING) }

        // Když přepneš na „Účtový rozvrh“ a zpět, nechceme vygenerovat novou otázku.
        // Proto si držíme init flag + poslední klíč okruhů.
        var trainerInitialized by rememberSaveable { mutableStateOf(false) }
        var lastTopicsKey by rememberSaveable { mutableStateOf("") }

        fun topicsKey(set: Set<Topic>): String = set.map { it.name }.sorted().joinToString("|")

        val filteredDataset by remember(selectedTopics, dataset) {
            derivedStateOf {
                if (selectedTopics.isEmpty() || selectedTopics.contains(Topic.ALL)) dataset
                else dataset.filter { selectedTopics.contains(it.topic()) }
            }
        }

        var currentIndex by remember { mutableIntStateOf(0) }
        var current by remember { mutableStateOf<Item?>(null) }

        var md by remember { mutableStateOf("") }
        var d by remember { mutableStateOf("") }

        var showSolution by remember { mutableStateOf(false) }
        var resultText by remember { mutableStateOf("—") }
        var resultKind by remember { mutableStateOf<ResultKind?>(null) }

        var mdOk by remember { mutableStateOf<Boolean?>(null) }
        var dOk by remember { mutableStateOf<Boolean?>(null) }

        fun setQuestion(idx: Int, restoreInputs: Boolean) {
            if (filteredDataset.isEmpty()) {
                current = null
                currentIndex = 0
                showSolution = false
                resultText = "V tomhle balíčku zatím nic není 🙂"
                resultKind = null
                mdOk = null
                dOk = null
                md = ""
                d = ""
                return
            }
            val safeIdx = idx.coerceIn(0, filteredDataset.lastIndex)
            currentIndex = safeIdx
            current = filteredDataset[safeIdx]
            showSolution = false
            resultText = "—"
            resultKind = null
            mdOk = null
            dOk = null

            if (restoreInputs) {
                md = progress.lastMd
                d = progress.lastD
            } else {
                md = ""
                d = ""
            }
        }

        fun persistLast() {
            scope.launch {
                val p = progress.copy(lastIndex = currentIndex, lastMd = md, lastD = d)
                progress = p
                writeProgress(p)
            }
        }

        fun next() {
            if (filteredDataset.isEmpty()) {
                setQuestion(0, restoreInputs = false)
                return
            }
            val idx = Random.nextInt(filteredDataset.size)
            setQuestion(idx, restoreInputs = false)
            scope.launch {
                val p = progress.copy(
                    seen = progress.seen + 1,
                    lastIndex = idx,
                    lastMd = md,
                    lastD = d
                )
                progress = p
                writeProgress(p)
            }
        }

        fun check() {
            val item = current ?: return
            val mdN = md.trim()
            val dN = d.trim()
            persistLast()

            if (mdN.isEmpty() || dN.isEmpty()) {
                resultText = "Doplň MD i D 🙂"
                resultKind = ResultKind.Bad
                showSolution = false
                mdOk = if (mdN.isNotEmpty()) mdN == item.md.trim() else null
                dOk = if (dN.isNotEmpty()) dN == item.d.trim() else null
                return
            }

            val okMd = mdN == item.md.trim()
            val okD = dN == item.d.trim()
            mdOk = okMd
            dOk = okD
            showSolution = false

            val allOk = okMd && okD
            scope.launch {
                val p = progress.copy(
                    answered = progress.answered + 1,
                    correct = progress.correct + (if (allOk) 1 else 0),
                    lastIndex = currentIndex,
                    lastMd = md,
                    lastD = d
                )
                progress = p
                writeProgress(p)
            }

            resultText = if (allOk) {
                "✅ Správně!"
            } else {
                "❌ Špatně. MD ${if (okMd) "OK" else "špatně"}, D ${if (okD) "OK" else "špatně"}."
            }
            resultKind = if (allOk) ResultKind.Good else ResultKind.Bad
        }

        fun resetAll() {
            scope.launch {
                val p0 = Progress(0, 0, 0, -1, "", "")
                progress = p0
                writeProgress(p0)
                resultText = "Reset hotový."
                resultKind = null
                mdOk = null
                dOk = null
                showSolution = false
                next()
            }
        }

        // Načti progress a nastav první otázku (jen jednou – i když přepínáš záložky)
        LaunchedEffect(Unit) {
            progress = readProgress()

            if (!trainerInitialized) {
                val startIdx = if (progress.lastIndex in 0..filteredDataset.lastIndex) {
                    progress.lastIndex
                } else if (filteredDataset.isNotEmpty()) {
                    Random.nextInt(filteredDataset.size)
                } else {
                    0
                }

                setQuestion(startIdx, restoreInputs = true)

                if (filteredDataset.isNotEmpty()) {
                    val p = progress.copy(seen = progress.seen + 1, lastIndex = startIdx)
                    progress = p
                    writeProgress(p)
                }

                lastTopicsKey = topicsKey(selectedTopics)
                trainerInitialized = true
            }
        }

        // Když se změní balíček (okruhy), skoč na náhodnou otázku – ale jen pokud se okruhy opravdu změnily.
        LaunchedEffect(selectedTopics) {
            val key = topicsKey(selectedTopics)
            if (trainerInitialized && key != lastTopicsKey) {
                lastTopicsKey = key
                if (filteredDataset.isNotEmpty()) {
                    val idx = Random.nextInt(filteredDataset.size)
                    setQuestion(idx, restoreInputs = false)
                } else {
                    setQuestion(0, restoreInputs = false)
                }
            }
        }

        val item = current
        val topicCounts = remember(dataset) {
            Topic.entries.associateWith { t ->
                when (t) {
                    Topic.ALL -> dataset.size
                    else -> dataset.count { it.topic() == t }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.Top
        ) {
Column(Modifier.fillMaxWidth()) {

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Účtování – procvičování",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold
        )

        Button(onClick = { resetAll() }) { Text("Reset") }
    }

    Spacer(Modifier.height(12.dp))

    Text("Okruhy (můžeš kombinovat):", style = MaterialTheme.typography.bodyMedium)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Topic.entries
                .filter { it != Topic.OSTATNI }
                .forEach { t ->
                    val count = topicCounts[t] ?: 0
                    val selected = selectedTopics.contains(t) || (t == Topic.ALL && (selectedTopics.isEmpty() || selectedTopics.contains(Topic.ALL)))
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedTopics = when (t) {
                                Topic.ALL -> setOf(Topic.ALL)
                                else -> {
                                    val next = selectedTopics.toMutableSet()
                                    next.remove(Topic.ALL)
                                    if (!next.add(t)) next.remove(t)
                                    if (next.isEmpty()) setOf(Topic.ALL) else next.toSet()
                                }
                            }
                        },
                        label = { Text("${t.title} ($count)") }
                    )
                }
        }

        Spacer(Modifier.height(10.dp))
        Text("Režim:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == Mode.LEARNING,
                onClick = { mode = Mode.LEARNING },
                label = { Text("🎓 ${Mode.LEARNING.title}") }
            )
            FilterChip(
                selected = mode == Mode.TEST,
                onClick = { mode = Mode.TEST },
                label = { Text("📝 ${Mode.TEST.title}") }
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            if (mode == Mode.LEARNING)
                "Učení: můžeš si kdykoliv zobrazit řešení."
            else
                "Test: nejdřív odpověz, pak vyhodnocení (řešení až na vyžádání).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
}

            Spacer(Modifier.height(16.dp))

            Text("Účetní případ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(item?.pripad ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)

            Spacer(Modifier.height(12.dp))

            Text("Účetní doklad", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            AssistChip(onClick = {}, label = { Text(item?.doklad ?: "—", fontWeight = FontWeight.Bold) })

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // DŮLEŽITÉ: pole jsou v jednom řádku, proto je zabalíme do weight(1f),
                // jinak by každé mělo fillMaxWidth() a druhé by "zmizelo".
                Box(Modifier.weight(1f)) {
                    AccountAutoCompleteField(
                        label = "MD",
                        value = md,
                        onValueChange = { md = it.filter { c -> c.isDigit() }; mdOk = null; persistLast() },
                        isError = (mdOk == false),
                        accounts = accounts,
                        onPick = { picked ->
                            md = picked
                            mdOk = null
                            persistLast()
                            focusManager.clearFocus()
                        }
                    )
                }
                Box(Modifier.weight(1f)) {
                    AccountAutoCompleteField(
                        label = "D",
                        value = d,
                        onValueChange = { d = it.filter { c -> c.isDigit() }; dOk = null; persistLast() },
                        isError = (dOk == false),
                        accounts = accounts,
                        onPick = { picked ->
                            d = picked
                            dOk = null
                            persistLast()
                            focusManager.clearFocus()
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { check() }, enabled = item != null) { Text("Zkontrolovat") }
                OutlinedButton(onClick = { next() }) { Text("Další") }
                val canShowSolution = (mode == Mode.LEARNING) || (resultKind != null)
                OutlinedButton(
                    onClick = { showSolution = true },
                    enabled = (item != null) && canShowSolution
                ) { Text("Ukázat řešení") }
            }

            Spacer(Modifier.height(12.dp))

            val cardColor = when (resultKind) {
                ResultKind.Good -> MaterialTheme.colorScheme.tertiaryContainer
                ResultKind.Bad -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }

            Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(resultText, fontWeight = FontWeight.SemiBold)

                    // Odkaz na ChatGPT – pouze pokud je odpověď špatně
                    if (resultKind == ResultKind.Bad && item != null) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                val query = """
                                Vysvětli mi proč je správně účetní zápis: ${item.md}/${item.d}

                                Účetní případ: ${item.pripad}
                                Doklad: ${item.doklad}

                                Já jsem zadal: ${md.trim()}/${d.trim()}

                                Vysvětli to jednoduše (učebnicově) podle českého účetnictví.
                                """.trimIndent()

                                openChatGPTApp(context, query)
                            }
                        ) {
                            Text("Vysvětlit pomocí AI")
                        }
                    }

                    if (showSolution && item != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Řešení: MD ${item.md} / D ${item.d}")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                "Zodpovězeno: ${progress.answered} • Správně: ${progress.correct} • Zobrazení: ${progress.seen}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Tip: ukládá se to lokálně v telefonu (DataStore). Nic se nikam neposílá.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun AccountPlanScreen(accounts: List<Account>) {
        var query by remember { mutableStateOf("") }
        var selectedClass by remember { mutableStateOf<String?>(null) }

        val classes = remember(accounts) {
            accounts
                .map { it.classId to it.className }
                .distinctBy { it.first }
                .sortedBy { it.first }
        }

        val filtered = remember(query, selectedClass, accounts) {
            val q = query.trim().lowercase()
            accounts.asSequence()
                .filter { acc -> selectedClass == null || acc.classId == selectedClass }
                .filter { acc ->
                    if (q.isEmpty()) true
                    else {
                        acc.number.contains(q) ||
                                acc.name.lowercase().contains(q) ||
                                acc.examples.any { it.lowercase().contains(q) }
                    }
                }
                .sortedBy { it.number }
                .toList()
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Účtový rozvrh", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Hledat účet (číslo / název / příklad)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedClass == null,
                    onClick = { selectedClass = null },
                    label = { Text("Vše") }
                )
                classes.forEach { (id, name) ->
                    FilterChip(
                        selected = selectedClass == id,
                        onClick = { selectedClass = if (selectedClass == id) null else id },
                        label = { Text("$id – $name") }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered) { acc ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("${acc.number} – ${acc.name}", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Třída: ${acc.classId} – ${acc.className}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (acc.examples.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Typické použití:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                acc.examples.take(6).forEach { ex ->
                                    Text("• $ex", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountAutoCompleteField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    accounts: List<Account>,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // Nabídky zobrazíme jen pokud uživatel něco píše
    val query = value.trim()
    val suggestions = remember(query, accounts) {
        if (query.isBlank()) emptyList() else
            accounts
                .asSequence()
                .filter { it.number.startsWith(query) }
                .take(12)
                .toList()
    }

    // Pokud už nejsou žádné návrhy, menu schováme
    LaunchedEffect(query, suggestions) {
        if (query.isBlank() || suggestions.isEmpty()) expanded = false
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (suggestions.isNotEmpty()) expanded = !expanded
        }
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = it.isNotBlank() && suggestions.isNotEmpty()
            },
            label = { Text(label) },
            isError = isError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            suggestions.forEach { acc ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${acc.number} – ${acc.name}", fontWeight = FontWeight.SemiBold)
                            if (acc.classId.isNotBlank() || acc.className.isNotBlank()) {
                                Text(
                                    "Třída ${acc.classId} • ${acc.className}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onPick(acc.number)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun openChatGPTApp(context: Context, query: String) {
    // ChatGPT aplikace (pokud je nainstalovaná) se typicky nabídne jako cíl pro https://chat.openai.com
    val uri = Uri.parse("https://chat.openai.com/?q=${Uri.encode(query)}")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}