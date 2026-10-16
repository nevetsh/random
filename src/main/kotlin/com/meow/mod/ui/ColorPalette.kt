package com.meow.mod.ui

import java.awt.Color

object ColorPalette {
	val PRIMARY = Color(15, 15, 15)
	val SECONDARY = Color(25, 25, 25)
	val TERTIARY = Color(35, 35, 35)
	val TEXT = Color(255, 255, 255)
	val LIGHT_TEXT = Color(255, 255, 255, 150)
	val OUTLINE = Color(50, 50, 50)
	val OUTLINE2 = Color(70, 70, 70)
	val TITLE = Color(255, 255, 255, 80)
	val ENABLED = Color(0, 255, 0)
	val DISABLED = Color(255, 0, 0)
	val ACCENT = Color(255, 105, 180)
	val BACKGROUND = Color(0, 0, 0, 150)

	fun argb(color: Color): Int =
		(color.alpha shl 24) or (color.red shl 16) or (color.green shl 8) or color.blue
}
