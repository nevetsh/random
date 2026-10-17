package com.meow.mod.config

enum class ConfigType {
	SWITCH,
	SLIDER,
	TEXT,
	BUTTON,

	// A value picked from a fixed list, drawn as a dropdown.
	CHOICE,

	// A heading inside a subcategory: a marker the config screen draws, with no value of its own.
	SECTION
}
