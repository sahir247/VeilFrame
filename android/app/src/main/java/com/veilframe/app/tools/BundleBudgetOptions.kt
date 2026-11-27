package com.veilframe.app.tools

internal object BundleBudgetOptions {
    data class Choice(val tokens: Long, val encoding: String, val label: String)
    val choices = listOf(
        Choice(32_000, BundleTokenizer.NAME, "32K · o200k"),
        Choice(64_000, BundleTokenizer.NAME, "64K · o200k"),
        Choice(128_000, BundleTokenizer.NAME, "128K · o200k"),
        Choice(200_000, BundleTokenizer.NAME, "200K · o200k"),
        Choice(0, BundleTokenizer.NAME, "32 MiB · bytes"),
        Choice(32_000, BundleTokenizer.CL100K, "32K · cl100k"),
        Choice(64_000, BundleTokenizer.CL100K, "64K · cl100k"),
        Choice(128_000, BundleTokenizer.CL100K, "128K · cl100k")
    )
    fun at(index: Int): Choice = choices.getOrNull(index) ?: choices[1]
}
