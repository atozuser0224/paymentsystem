package org.gang.paymentsystem

fun formatAmount(amount: Long): String {
    val str = amount.toString()
    val sb = StringBuilder()
    var count = 0
    for (i in str.length - 1 downTo 0) {
        sb.append(str[i])
        count++
        if (count == 3 && i > 0) {
            sb.append(',')
            count = 0
        }
    }
    return sb.reverse().toString()
}
