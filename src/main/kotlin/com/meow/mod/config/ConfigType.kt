package com.meow.mod.config

enum class ConfigType {
	SWITCH,
	SLIDER,
	TEXT,
	BUTTON,

	// A heading inside a subcategory: a marker the config screen draws, with no value of its own.
	SECTION
}
