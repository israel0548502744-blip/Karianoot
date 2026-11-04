package com.karianoot.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln

/**
 * Hebrew text -> IPA, a Kotlin port of RenikudPlus (`renikud_onnx.G2P.phonemize`,
 * niqqud="strip", exact-MAP decode). Works on Unicode code points, like Python strings.
 *
 * Tested against the Python package on the same sentences (tools/reference.py).
 */
class HebrewG2P(modelPath: String, env: OrtEnvironment = OrtEnvironment.getEnvironment(),
                options: OrtSession.SessionOptions = OrtSession.SessionOptions()) : AutoCloseable {

    private val env = env
    private val session: OrtSession = env.createSession(modelPath, options)

    private val vocab: Map<String, Int>
    private val consonantVocab: Map<Int, String>
    private val vowelVocab: Map<Int, String>
    private val clsId: Long
    private val sepId: Long
    private val letterConstraints: Map<String, IntArray>
    private val gereshMap: Map<String, String>
    private val supportsGender: Boolean
    private val supportsExactMap: Boolean
    private val condSoftmax: Boolean
    private val wvC: Array<FloatArray>   // [C][V]
    private val wsC: Array<FloatArray>   // [C][2]
    private val wsV: Array<FloatArray>   // [V][2]
    private val forbidden: Array<BooleanArray> // [letter][C]

    init {
        val meta = session.metadata.customMetadata
        vocab = JSONObject(meta.getValue("vocab")).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
        consonantVocab = intKeyed(meta.getValue("consonant_vocab"))
        vowelVocab = intKeyed(meta.getValue("vowel_vocab"))
        clsId = meta.getValue("cls_token_id").toLong()
        sepId = meta.getValue("sep_token_id").toLong()
        letterConstraints = JSONObject(meta.getValue("letter_consonant_constraints")).let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> IntArray(a.length()) { a.getInt(it) } } }
        }
        gereshMap = JSONObject(meta["geresh_map"] ?: "{}").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        supportsGender = session.inputNames.containsAll(listOf("speaker", "target_speaker"))
        supportsExactMap = listOf("vowel_cond_consonant", "stress_cond_consonant", "stress_cond_vowel").all { it in meta }
        condSoftmax = (meta["cascade_cond"] ?: "softmax") == "softmax"
        if (supportsExactMap) {
            wvC = matrix(meta.getValue("vowel_cond_consonant"))
            wsC = matrix(meta.getValue("stress_cond_consonant"))
            wsV = matrix(meta.getValue("stress_cond_vowel"))
            val nCons = wvC.size
            forbidden = Array(TAF - ALEF + 1) { BooleanArray(nCons) { true } }
            for ((letter, allowed) in letterConstraints) {
                val row = forbidden[letter.codePointAt(0) - ALEF]
                for (c in allowed) row[c] = false
            }
        } else {
            wvC = emptyArray(); wsC = emptyArray(); wsV = emptyArray(); forbidden = emptyArray()
        }
    }

    /** Unvocalized Hebrew -> IPA. Non-Hebrew characters pass through. */
    fun phonemize(text: String, speaker: Int = 0, targetSpeaker: Int = 0): String {
        val t = normalizeGraphemes(text)
        val normalized = stripNiqqud(Normalizer.normalize(t, Normalizer.Form.NFD))
        val cps = codePoints(normalized)
        val windows = splitForDecode(cps, SAFE_CHUNK_CHARS)
        val sb = StringBuilder()
        for (w in windows) sb.append(phonemizeWindow(w, speaker, targetSpeaker))
        return sb.toString()
    }

    private fun phonemizeWindow(cps: List<String>, speaker: Int, targetSpeaker: Int): String {
        // Tokenize: CLS, one id per code point, SEP. Offsets are code-point indices.
        val n = cps.size
        val unk = vocab["[UNK]"] ?: 0
        val ids = LongArray(n + 2)
        ids[0] = clsId
        for (i in 0 until n) ids[i + 1] = (vocab[cps[i]] ?: unk).toLong()
        ids[n + 1] = sepId
        val mask = LongArray(n + 2) { 1L }
        val shape = longArrayOf(1, (n + 2).toLong())

        val feeds = HashMap<String, OnnxTensor>()
        feeds["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        feeds["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
        if (supportsGender) {
            feeds["speaker"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speaker.toLong())), longArrayOf(1))
            feeds["target_speaker"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(targetSpeaker.toLong())), longArrayOf(1))
        }
        val consLogits: Array<FloatArray>
        val vowLogits: Array<FloatArray>
        val strLogits: Array<FloatArray>
        try {
            session.run(feeds, setOf("consonant_logits", "vowel_logits", "stress_logits")).use { r ->
                @Suppress("UNCHECKED_CAST")
                fun out(name: String) = (r.get(name).get().value as Array<Array<FloatArray>>)[0]
                consLogits = out("consonant_logits")
                vowLogits = out("vowel_logits")
                strLogits = out("stress_logits")
            }
        } finally {
            feeds.values.forEach { it.close() }
        }

        // Token index t (1..n) <-> code point t-1.
        val S = n + 2
        val consPred = IntArray(S)
        val vowPred = IntArray(S)
        val stressed: Set<Int>
        if (supportsExactMap) {
            stressed = exactMap(cps, consLogits, vowLogits, strLogits, consPred, vowPred)
        } else {
            for (t in 0 until S) { consPred[t] = argmax(consLogits[t]); vowPred[t] = argmax(vowLogits[t]) }
            stressed = greedyStress(cps, strLogits, vowPred)
        }

        val sb = StringBuilder()
        for (i in 0 until n) {
            val tok = i + 1
            val ch = cps[i]
            if (!isHebrew(ch)) {
                if (ch != "'" && ch != "\"") sb.append(ch)
                continue
            }
            var cid = consPred[tok]
            val allowed = letterConstraints[ch]
            if (allowed != null && cid !in allowed) {
                cid = allowed.maxBy { consLogits[tok][it] }
            }
            var consonant = consonantVocab[cid] ?: NONE
            if (ch in gereshMap && i + 1 < n && cps[i + 1] == "'") consonant = gereshMap.getValue(ch)
            val vowel = vowelVocab[vowPred[tok]] ?: NONE
            val stress = tok in stressed
            val wordFinal = i + 1 >= n || !Character.isLetter(cps[i + 1].codePointAt(0))
            if (ch == "ח" && wordFinal && vowel == "a") {
                if (stress) sb.append(STRESS)
                sb.append("aχ")
            } else {
                if (consonant != NONE) sb.append(consonant)
                if (stress && vowel != NONE) sb.append(STRESS)
                if (vowel != NONE) sb.append(vowel)
            }
        }
        return sb.toString()
    }

    /** Closed-form MAP over E(c, v, s) = log P(c) + log P(v | c) + log P(s | c, v). */
    private fun exactMap(cps: List<String>, consL: Array<FloatArray>, vowL: Array<FloatArray>, strL: Array<FloatArray>,
                         consOut: IntArray, vowOut: IntArray): Set<Int> {
        val S = consL.size
        val C = wvC.size
        val V = wvC[0].size
        val bestU = FloatArray(S); val bestS = FloatArray(S)
        val argU = IntArray(S); val argS = IntArray(S)
        val e = Array(C) { Array(V) { FloatArray(2) } }
        for (t in 0 until S) {
            val condC = if (condSoftmax) softmax(consL[t]) else onehot(argmax(consL[t]), C)
            val condV = if (condSoftmax) softmax(vowL[t]) else onehot(argmax(vowL[t]), V)
            val baseV = FloatArray(V) { v -> var acc = vowL[t][v]; for (c in 0 until C) acc -= condC[c] * wvC[c][v]; acc }
            // Match numpy: (cond_c @ ws_c) and (cond_v @ ws_v) are separate matmuls subtracted in order.
            val baseS = FloatArray(2) { k ->
                var m1 = 0f; for (c in 0 until C) m1 += condC[c] * wsC[c][k]
                var m2 = 0f; for (v in 0 until V) m2 += condV[v] * wsV[v][k]
                strL[t][k] - m1 - m2
            }
            val logc = logSoftmax(consL[t])
            val cp = if (t in 1..cps.size) cps[t - 1] else null
            val forb = if (cp != null && isHebrew(cp)) forbidden[cp.codePointAt(0) - ALEF] else null
            for (c in 0 until C) {
                val lv = logSoftmax(FloatArray(V) { v -> baseV[v] + wvC[c][v] })
                for (v in 0 until V) {
                    val l0 = baseS[0] + wsC[c][0] + wsV[v][0]
                    val l1 = baseS[1] + wsC[c][1] + wsV[v][1]
                    val mx = maxOf(l0, l1)
                    val lse = mx + ln(exp((l0 - mx).toDouble()) + exp((l1 - mx).toDouble())).toFloat()
                    e[c][v][0] = logc[c] + lv[v] + (l0 - lse)
                    e[c][v][1] = logc[c] + lv[v] + (l1 - lse)
                    if (forb != null && forb[c]) { e[c][v][0] = NEG; e[c][v][1] = NEG }
                }
                e[c][0][1] = NEG // stress needs a vowel
            }
            var bu = Float.NEGATIVE_INFINITY; var au = 0
            var bs = Float.NEGATIVE_INFINITY; var aS = 0
            for (c in 0 until C) for (v in 0 until V) {
                val idx = c * V + v
                if (e[c][v][0] > bu) { bu = e[c][v][0]; au = idx }
                if (e[c][v][1] > bs) { bs = e[c][v][1]; aS = idx }
            }
            bestU[t] = bu; bestS[t] = bs; argU[t] = au; argS[t] = aS
        }
        val stressed = HashSet<Int>()
        for ((ws, we) in wordSpans(cps)) {
            var best = -1; var bestGain = Float.NEGATIVE_INFINITY
            for (i in ws until we) {
                if (!isHebrew(cps[i])) continue
                val t = i + 1
                if (bestS[t] > NEG / 2) {
                    val g = bestS[t] - bestU[t]
                    if (best < 0 || g > bestGain) { best = t; bestGain = g }
                }
            }
            if (best >= 0) stressed.add(best)
        }
        for (t in 0 until S) {
            val flat = if (t in stressed) argS[t] else argU[t]
            consOut[t] = flat / V
            vowOut[t] = flat % V
        }
        return stressed
    }

    private fun greedyStress(cps: List<String>, strL: Array<FloatArray>, vowPred: IntArray): Set<Int> {
        val stressed = HashSet<Int>()
        for ((ws, we) in wordSpans(cps)) {
            var best = -1; var bestM = Float.NEGATIVE_INFINITY
            for (i in ws until we) {
                val t = i + 1
                if ((vowelVocab[vowPred[t]] ?: NONE) == NONE) continue
                val m = strL[t][1] - strL[t][0]
                if (best < 0 || m > bestM) { best = t; bestM = m }
            }
            if (best >= 0) stressed.add(best)
        }
        return stressed
    }

    override fun close() = session.close()

    companion object {
        const val ALEF = 0x05D0
        const val TAF = 0x05EA
        const val STRESS = "ˈ"
        const val NONE = "∅"
        const val NEG = -1e30f
        const val SAFE_CHUNK_CHARS = 2046
        private const val SENTENCE_ENDS = ".!?;\n\r…׃"
        private const val CLAUSE_ENDS = ",:–—"
        private val NIQQUD_RE = Regex("[֑-ׇֽֿׁׂׅׄ]")

        fun isHebrew(cp: String): Boolean = cp.length == 1 && cp[0].code in ALEF..TAF

        fun normalizeGraphemes(text: String): String =
            text.replace(Regex("[׳'`´]"), "'").replace(Regex("[״\"]"), "\"")

        fun stripNiqqud(text: String): String = NIQQUD_RE.replace(text, "")

        fun codePoints(s: String): List<String> {
            val out = ArrayList<String>(s.length)
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val len = Character.charCount(cp)
                out.add(s.substring(i, i + len))
                i += len
            }
            return out
        }

        /** Python str.isspace() for one code point. */
        fun isSpace(cp: String): Boolean {
            val c = cp.codePointAt(0)
            return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x1C || c == 0x1D || c == 0x1E || c == 0x1F || c == 0x85
        }

        /** Spans of `\S+` in code-point indices. */
        fun wordSpans(cps: List<String>): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var i = 0
            while (i < cps.size) {
                if (isSpace(cps[i])) { i++; continue }
                val s = i
                while (i < cps.size && !isSpace(cps[i])) i++
                out.add(s to i)
            }
            return out
        }

        private fun splitAfter(t: List<String>, terms: String): List<List<String>> {
            val pieces = ArrayList<List<String>>()
            var start = 0; var i = 0; val n = t.size
            while (i < n) {
                if (terms.contains(t[i])) {
                    var j = i + 1
                    while (j < n && terms.contains(t[j])) j++
                    while (j < n && isSpace(t[j])) j++
                    pieces.add(t.subList(start, j)); start = j; i = j
                } else i++
            }
            if (start < n) pieces.add(t.subList(start, n))
            return pieces
        }

        private fun splitWords(t: List<String>): List<List<String>> {
            val pieces = ArrayList<List<String>>()
            var start = 0; var i = 0; val n = t.size
            while (i < n) {
                if (isSpace(t[i])) {
                    var j = i
                    while (j < n && isSpace(t[j])) j++
                    pieces.add(t.subList(start, j)); start = j; i = j
                } else i++
            }
            if (start < n) pieces.add(t.subList(start, n))
            return pieces
        }

        private fun pack(pieces: List<List<String>>, limit: Int): List<List<String>> {
            val out = ArrayList<List<String>>()
            var cur = ArrayList<String>()
            for (p in pieces) {
                if (cur.isNotEmpty() && cur.size + p.size > limit) { out.add(cur); cur = ArrayList(p) } else cur.addAll(p)
            }
            if (cur.isNotEmpty()) out.add(cur)
            return out
        }

        /** Lossless split into windows of at most [limit] code points. */
        fun splitForDecode(t: List<String>, limit: Int): List<List<String>> {
            if (t.size <= limit) return listOf(t)
            val out = ArrayList<List<String>>()
            for (s in pack(splitAfter(t, SENTENCE_ENDS), limit)) {
                if (s.size <= limit) { out.add(s); continue }
                for (c in pack(splitAfter(s, CLAUSE_ENDS), limit)) {
                    if (c.size <= limit) { out.add(c); continue }
                    for (w in pack(splitWords(c), limit)) {
                        if (w.size <= limit) { out.add(w); continue }
                        var i = 0
                        while (i < w.size) { out.add(w.subList(i, minOf(i + limit, w.size))); i += limit }
                    }
                }
            }
            return out
        }

        private fun intKeyed(json: String): Map<Int, String> =
            JSONObject(json).let { o -> o.keys().asSequence().associate { it.toInt() to o.getString(it) } }

        private fun matrix(json: String): Array<FloatArray> {
            val a = JSONArray(json)
            return Array(a.length()) { i -> a.getJSONArray(i).let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } } }
        }

        fun argmax(a: FloatArray): Int { var b = 0; for (i in 1 until a.size) if (a[i] > a[b]) b = i; return b }
        private fun onehot(i: Int, n: Int) = FloatArray(n).also { it[i] = 1f }
        fun softmax(a: FloatArray): FloatArray {
            val m = a.max(); val e = FloatArray(a.size) { exp((a[it] - m).toDouble()).toFloat() }; val s = e.sum()
            for (i in e.indices) e[i] /= s
            return e
        }
        fun logSoftmax(a: FloatArray): FloatArray {
            val m = a.max(); var s = 0.0
            val x = FloatArray(a.size) { a[it] - m }
            for (v in x) s += exp(v.toDouble())
            val l = ln(s).toFloat()
            for (i in x.indices) x[i] -= l
            return x
        }
    }
}
