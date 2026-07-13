package com.smart.android.ad_app.bean
data class AdConfigDto(
    val adId: String?,
    val adType: Int?,
    val adUrl: String?,
    val contentType: Int?,
    val displayDuration: Int?,
    val floatingHeight: Int?,
    val floatingWidth: Int?,
    val floatingX: Int?,
    val floatingY: Int?,
    val imageUrl: String?,
    val isClosable: Int?,
    val isCountdownVisible: Boolean?,
    val position: Int?,
    val videoUrl: String?,
    val soundEnabled: Boolean = false
){
    val positionEnum: Position get() = Position.fromInt(position?:0)
}

// 广告显示位置：例如居中与右下角
enum class Position(val backendValue: Int) {
    RIGHT_BOTTOM(0),    // 右下角
    LEFT_TOP(1),        // 左上
    TOP_CENTER(2),      // 上中
    RIGHT_TOP(3),       // 右上
    LEFT_BOTTOM(4),     // 左下
    BOTTOM_CENTER(5),   // 下中
    CENTER(6),          // 中间
    LEFT_CENTER(7),     // 左中
    RIGHT_CENTER(8);    // 右中

    companion object {
        fun fromInt(value: Int): Position {
            return entries.firstOrNull { it.backendValue == value }
                ?: RIGHT_BOTTOM // 默认返回右下角
        }
    }
}


/*
*
*
* {
  "adId": "default_splash_ad",
  "adType": 0,
  "contentType": 0,
  "displayDuration": 10000,
  "imageUrl": "https://xfile.f3tcp.cc/imgs/default_splash.webp",
  "isClosable": 1,
  "position": 0,
  "videoUrl": "https://xfile.f3tcp.cc/imgs/default_video.mp4",
  "adUrl": "https://www.baidu.com",
  "isCountdownVisible": true,
  "floatingWidth": 400,
  "floatingHeight": 240,
  "floatingX": 0,
  "floatingY": 0
}
*
* */
