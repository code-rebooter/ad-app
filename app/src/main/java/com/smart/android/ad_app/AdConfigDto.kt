package com.smart.android.ad_app
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
    val videoUrl: String?
)


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