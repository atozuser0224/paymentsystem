package org.gang.paymentsystem

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform