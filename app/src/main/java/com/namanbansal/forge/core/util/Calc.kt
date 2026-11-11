package com.namanbansal.forge.core.util

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

class CalcError(message: String) : Exception(message)

/**
 * Expression evaluator for the calculator. Recursive descent with the usual precedence:
 * brackets, functions, postfix `!` and `%`, `^` (right-associative, binds tighter than unary minus: -2^2 = -4),
 * `* /` (and implicit multiplication such as 2(3) or 2pi), then `+ -`.
 * `a + b%` and `a - b%` mean "plus / minus b percent of a", like a pocket calculator; elsewhere `b%` is b/100.
 * Missing closing brackets at the end are closed for you.
 */
object Calc {
    fun evaluate(input: String, degrees: Boolean = true): Double {
        val p = Parser(tokenize(input), degrees)
        val v = p.parseAll()
        if (v.isNaN()) throw CalcError("Not a number")
        if (v.isInfinite()) throw CalcError("Result too large")
        return v
    }

    /** Evaluates, or null for an empty / incomplete / invalid expression (used for the live preview). */
    fun tryEvaluate(input: String, degrees: Boolean = true): Double? = try { if (input.isBlank()) null else evaluate(input, degrees) } catch (_: CalcError) { null }

    /** Display form: 12 significant digits so 0.1 + 0.2 shows 0.3. */
    fun format(v: Double): String = NumberText.format(v, sig = 12, group = false)

    private val FUNCTIONS = setOf("sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "ln", "log", "log2", "sqrt", "cbrt", "abs", "exp", "floor", "ceil", "round", "fact")

    private sealed interface Tok {
        data class Num(val v: Double) : Tok
        data class Op(val c: Char) : Tok
        data class Id(val name: String) : Tok
    }

    private fun tokenize(src: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    var dots = 0
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) { if (src[i] == '.') dots++; i++ }
                    if (dots > 1) throw CalcError("Too many decimal points")
                    val text = src.substring(start, i)
                    if (text == ".") throw CalcError("Misplaced decimal point")
                    out += Tok.Num(text.toDouble())
                }
                ch.isLetter() || ch == 'π' || ch == '√' -> {
                    if (ch == 'π') { out += Tok.Id("pi"); i++ }
                    else if (ch == '√') { out += Tok.Id("sqrt"); i++ }
                    else {
                        val start = i
                        while (i < src.length && (src[i].isLetter() || src[i].isDigit() && i > start)) i++
                        out += Tok.Id(src.substring(start, i).lowercase())
                    }
                }
                ch in "+-*/^()!%" -> { out += Tok.Op(ch); i++ }
                ch == '×' || ch == '·' -> { out += Tok.Op('*'); i++ }
                ch == '÷' -> { out += Tok.Op('/'); i++ }
                ch == '−' || ch == '–' -> { out += Tok.Op('-'); i++ }
                ch == ',' -> i++
                else -> throw CalcError("Unexpected \"$ch\"")
            }
        }
        return out
    }

    private class Parser(val t: List<Tok>, val deg: Boolean) {
        var i = 0
        private fun peek(): Tok? = t.getOrNull(i)
        private fun isOp(c: Char) = (peek() as? Tok.Op)?.c == c

        fun parseAll(): Double {
            if (t.isEmpty()) throw CalcError("Nothing to calculate")
            val v = expr()
            if (i < t.size) {
                if (isOp(')')) throw CalcError("Check your brackets")
                throw CalcError("Unexpected input")
            }
            return v
        }

        /** A value together with "this was written as n%" so `a + n%` can be relative. */
        private class V(val v: Double, val percent: Boolean = false)

        private fun expr(): Double {
            var left = term().v
            while (true) {
                val op = (peek() as? Tok.Op)?.c
                if (op != '+' && op != '-') return left
                i++
                val right = term()
                val rv = if (right.percent) left * right.v else right.v
                left = if (op == '+') left + rv else left - rv
            }
        }

        private fun term(): V {
            var left = unary()
            while (true) {
                val tok = peek()
                val op = (tok as? Tok.Op)?.c
                when {
                    op == '*' || op == '/' -> {
                        i++
                        val right = unary()
                        if (op == '/' && right.v == 0.0) throw CalcError("Can't divide by zero")
                        left = V(if (op == '*') left.v * right.v else left.v / right.v)
                    }
                    tok is Tok.Num || tok is Tok.Id || (tok is Tok.Op && tok.c == '(') -> left = V(left.v * unary().v) // implicit multiplication
                    else -> return left
                }
            }
        }

        private fun unary(): V {
            val op = (peek() as? Tok.Op)?.c
            if (op == '-') { i++; val v = unary(); return V(-v.v) }
            if (op == '+') { i++; return unary() }
            return power()
        }

        private fun power(): V {
            val base = postfix()
            if (isOp('^')) {
                i++
                val exp = unary()
                val r = base.v.pow(exp.v)
                if (r.isNaN()) throw CalcError("Invalid power")
                return V(r)
            }
            return base
        }

        private fun postfix(): V {
            var v = primary()
            while (true) {
                if (isOp('!')) { i++; v = V(factorial(v.v)) }
                else if (isOp('%')) { i++; v = V(v.v / 100.0, percent = true) }
                else return v
            }
        }

        private fun primary(): V {
            val tok = peek() ?: throw CalcError("Incomplete expression")
            i++
            when (tok) {
                is Tok.Num -> return V(tok.v)
                is Tok.Op -> {
                    if (tok.c == '(') {
                        val v = expr()
                        if (isOp(')')) i++ else if (peek() != null) throw CalcError("Check your brackets") // else: auto-close at the end
                        return V(v)
                    }
                    throw CalcError("Unexpected \"${tok.c}\"")
                }
                is Tok.Id -> {
                    when (tok.name) { "pi" -> return V(Math.PI); "e" -> return V(Math.E) }
                    if (tok.name !in FUNCTIONS) throw CalcError("Unknown function \"${tok.name}\"")
                    val arg = if (isOp('(')) { i++; val v = expr(); if (isOp(')')) i++ else if (peek() != null) throw CalcError("Check your brackets"); v }
                    else unary().v.also { /* sin 30 without brackets */ }
                    return V(function(tok.name, arg))
                }
            }
        }

        private fun toRad(x: Double) = if (deg) Math.toRadians(x) else x
        private fun fromRad(x: Double) = if (deg) Math.toDegrees(x) else x
        private fun snap(v: Double) = if (abs(v) < 1e-12) 0.0 else v

        private fun function(name: String, x: Double): Double = when (name) {
            "sin" -> snap(sin(toRad(x)))
            "cos" -> snap(cos(toRad(x)))
            "tan" -> {
                if (deg && abs(x % 180.0) == 90.0) throw CalcError("Tangent is undefined here")
                snap(tan(toRad(x)))
            }
            "asin" -> { if (x !in -1.0..1.0) throw CalcError("Input must be between -1 and 1"); fromRad(asin(x)) }
            "acos" -> { if (x !in -1.0..1.0) throw CalcError("Input must be between -1 and 1"); fromRad(acos(x)) }
            "atan" -> fromRad(atan(x))
            "sinh" -> sinh(x); "cosh" -> cosh(x); "tanh" -> tanh(x)
            "ln" -> { if (x <= 0) throw CalcError("ln needs a positive number"); ln(x) }
            "log" -> { if (x <= 0) throw CalcError("log needs a positive number"); snap(log10(x)) }
            "log2" -> { if (x <= 0) throw CalcError("log2 needs a positive number"); log2(x) }
            "sqrt" -> { if (x < 0) throw CalcError("Square root of a negative number"); sqrt(x) }
            "cbrt" -> cbrt(x)
            "abs" -> abs(x)
            "exp" -> exp(x)
            "floor" -> floor(x); "ceil" -> ceil(x); "round" -> round(x)
            "fact" -> factorial(x)
            else -> throw CalcError("Unknown function \"$name\"")
        }

        private fun factorial(x: Double): Double {
            if (x < 0 || x != floor(x)) throw CalcError("Factorial needs a whole number, 0 or more")
            if (x > 170) throw CalcError("Result too large")
            var r = 1.0
            for (n in 2..x.toInt()) r *= n
            return r
        }
    }

    /** One line of history: "expression = result". */
    object History {
        private const val SEP = "\u001e"
        const val MAX = 50
        fun encode(items: List<Pair<String, String>>): String = items.take(MAX).joinToString("\n") { (e, r) -> e.replace("\n", " ") + SEP + r }
        fun decode(text: String): List<Pair<String, String>> = text.lines().mapNotNull { l -> l.split(SEP).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] } }
        fun add(items: List<Pair<String, String>>, expr: String, result: String): List<Pair<String, String>> =
            (listOf(expr to result) + items.filterNot { it.first == expr && it.second == result }).take(MAX)
    }

}
