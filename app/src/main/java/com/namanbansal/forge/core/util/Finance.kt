package com.namanbansal.forge.core.util

import kotlin.math.pow
import kotlin.math.round

data class EmiRow(val month: Int, val payment: Double, val interest: Double, val principal: Double, val balance: Double)
data class EmiResult(val emi: Double, val totalPaid: Double, val totalInterest: Double, val rows: List<EmiRow>)

object EmiMath {
    /** Monthly instalment for [principal] at [annualRatePct] over [months]. A 0% loan is simply principal / months. */
    fun emi(principal: Double, annualRatePct: Double, months: Int): Double {
        require(months > 0) { "months must be positive" }
        val r = annualRatePct / 12.0 / 100.0
        if (r == 0.0) return principal / months
        val f = (1 + r).pow(months)
        return principal * r * f / (f - 1)
    }

    /** Full schedule. Each row is rounded to cents and the last payment absorbs the rounding so the balance ends at exactly 0. */
    fun schedule(principal: Double, annualRatePct: Double, months: Int): EmiResult {
        val r = annualRatePct / 12.0 / 100.0
        val emi = round(emi(principal, annualRatePct, months) * 100) / 100
        var balance = round(principal * 100) / 100
        val rows = ArrayList<EmiRow>(months)
        for (m in 1..months) {
            val interest = round(balance * r * 100) / 100
            var pay = emi
            var princ = round((pay - interest) * 100) / 100
            if (m == months || princ > balance) { princ = balance; pay = round((princ + interest) * 100) / 100 }
            balance = round((balance - princ) * 100) / 100
            rows += EmiRow(m, pay, interest, princ, balance)
        }
        val paid = rows.sumOf { it.payment }
        return EmiResult(emi, round(paid * 100) / 100, round((paid - principal) * 100) / 100, rows)
    }

    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun toCsv(result: EmiResult, currency: String = ""): String = buildString {
        append(listOf("Month", "Payment", "Interest", "Principal", "Balance").joinToString(",") { csvCell(if (currency.isNotBlank() && it != "Month") "$it ($currency)" else it) }).append("\r\n")
        result.rows.forEach { append("${it.month},%.2f,%.2f,%.2f,%.2f\r\n".format(java.util.Locale.US, it.payment, it.interest, it.principal, it.balance)) }
    }
}

enum class Sex { MALE, FEMALE }
enum class Activity(val label: String, val factor: Double) {
    SEDENTARY("Little or no exercise", 1.2), LIGHT("Light, 1 to 3 days a week", 1.375), MODERATE("Moderate, 3 to 5 days", 1.55),
    ACTIVE("Hard exercise, 6 to 7 days", 1.725), EXTRA("Very hard, physical job", 1.9),
}

object Health {
    const val KG_PER_LB = 0.45359237
    const val CM_PER_IN = 2.54

    fun bmi(kg: Double, cm: Double): Double = kg / (cm / 100.0).pow(2)

    fun bmiCategory(bmi: Double): String = when {
        bmi < 16 -> "Severely underweight"; bmi < 18.5 -> "Underweight"; bmi < 25 -> "Healthy weight"; bmi < 30 -> "Overweight"
        bmi < 35 -> "Obese (class 1)"; bmi < 40 -> "Obese (class 2)"; else -> "Obese (class 3)"
    }

    /** Healthy weight band (BMI 18.5 to 24.9) for a height. */
    fun healthyRangeKg(cm: Double): Pair<Double, Double> = 18.5 * (cm / 100.0).pow(2) to 24.9 * (cm / 100.0).pow(2)

    /** Mifflin-St Jeor resting energy in kcal per day. */
    fun bmr(sex: Sex, kg: Double, cm: Double, age: Int): Double = 10 * kg + 6.25 * cm - 5 * age + if (sex == Sex.MALE) 5 else -161

    fun tdee(bmr: Double, activity: Activity): Double = bmr * activity.factor

    fun feetInchesToCm(ft: Double, inch: Double): Double = (ft * 12 + inch) * CM_PER_IN
    fun cmToFeetInches(cm: Double): Pair<Int, Double> { val totalIn = cm / CM_PER_IN; var ft = (totalIn / 12).toInt(); var inch = totalIn - ft * 12; if (inch >= 11.95) { ft++; inch = 0.0 }; return ft to inch }
    fun lbToKg(lb: Double) = lb * KG_PER_LB
    fun kgToLb(kg: Double) = kg / KG_PER_LB
}
