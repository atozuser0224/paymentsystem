package org.gang

import org.mindrot.jbcrypt.BCrypt
import java.util.Locale

fun isValidNfcFormat(input: String?): Boolean {
    if (input == null) return false

    val cleanUid = input.replace(Regex("[^A-Fa-f0-9]"), "").uppercase(Locale.getDefault())

    val validLengths = listOf(8, 14, 20)
    if (cleanUid.length !in validLengths) {
        return false
    }

    val hexPattern = Regex("^[0-9A-F]+$")
    return hexPattern.matches(cleanUid)
}

fun String.toHash(): String {
    return BCrypt.hashpw(this,BCrypt.gensalt())
}

