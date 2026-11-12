package com.namanbansal.forge.core.util

/** Keypad editing rules for the calculator, kept pure so they are testable. */
object CalcKeys {
    private const val OPS = "+−×÷^"
    private val FUNCTIONS = listOf("asin(", "acos(", "atan(", "sinh(", "cosh(", "tanh(", "sin(", "cos(", "tan(", "log2(", "ln(", "log(", "√(", "cbrt(", "abs(", "exp(")

    fun digit(expr: String, d: Char): String = expr + d

    fun decimal(expr: String): String {
        val tail = expr.takeLastWhile { it.isDigit() || it == '.' }
        return when {
            tail.contains('.') -> expr
            tail.isEmpty() -> "$expr" + "0."
            else -> "$expr."
        }
    }

    /** + − × ÷ ^ : replaces a trailing operator; "−" may follow × ÷ ^ ( as a sign; operators cannot start an expression except "−". */
    fun operator(expr: String, op: Char): String {
        if (expr.isEmpty()) return if (op == '−') "−" else expr
        val last = expr.last()
        if (last in OPS) {
            if (op == '−' && last in "×÷^") return expr + op
            // replace "x−" style pairs too, so 5×−+ ends as 5+
            var base = expr.dropLastWhile { it in OPS }
            if (base.isEmpty()) return if (op == '−') "−" else ""
            return base + op
        }
        if (last == '(') return if (op == '−') expr + op else expr
        return expr + op
    }

    fun percentOrFactorial(expr: String, c: Char): String = if (expr.isEmpty() || expr.last() in OPS || expr.last() == '(') expr else expr + c

    fun function(expr: String, name: String): String = expr + name

    /** "(" or ")" depending on what makes sense after the current text. */
    fun bracket(expr: String): String {
        val open = expr.count { it == '(' }; val close = expr.count { it == ')' }
        val last = expr.lastOrNull()
        val canClose = open > close && last != null && (last.isDigit() || last == ')' || last == 'π' || last == '!' || last == '%' || last == 'e' || last == '.')
        return expr + if (canClose) ")" else "("
    }

    fun backspace(expr: String): String {
        if (expr.isEmpty()) return expr
        for (f in FUNCTIONS) if (expr.endsWith(f)) return expr.dropLast(f.length)
        return expr.dropLast(1)
    }

    /** Result text that can be used as the start of the next expression. */
    fun toExpression(v: Double): String = Calc.format(v).replace('-', '−')
}
