package com.meow.mod.api

private val CONTROL_CODE = Regex("\u00a7.")

fun String.noControlCodes(): String = replace(CONTROL_CODE, "")
