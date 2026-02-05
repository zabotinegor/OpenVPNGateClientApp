package com.yahorzabotsin.openvpnclientgate.core.about

interface YearProvider {
    fun currentYear(): Int
}

class SystemYearProvider : YearProvider {
    override fun currentYear(): Int = java.time.Year.now().value
}
