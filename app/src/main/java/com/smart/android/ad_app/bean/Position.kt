package com.smart.android.ad_app.bean

enum class Position(val backendValue: Int) {
    RIGHT_BOTTOM(0),
    LEFT_TOP(1),
    TOP_CENTER(2),
    RIGHT_TOP(3),
    LEFT_BOTTOM(4),
    BOTTOM_CENTER(5),
    CENTER(6),
    LEFT_CENTER(7),
    RIGHT_CENTER(8);

    companion object {
        fun fromInt(value: Int): Position {
            return entries.firstOrNull { it.backendValue == value } ?: RIGHT_BOTTOM
        }
    }
}
