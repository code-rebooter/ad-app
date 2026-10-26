# 历史实验 flavor：已统一为最新 Google 链路

2026-10-23：纯 TCL 实验执行方案及对应 resolve、CMP 替代实现、网络观察器和构建校验脚本已删除。

保留 `google_ad_tv_desktop_tcl_test` 的历史构建名称及原包名 `com.localvideoplayer.tv`、后台渠道 `GOOGLE_AD_TV_DESKTOP_V260904_1`、签名和版本配置。该 flavor 现在与其他 Google 渠道共用 `app/src/google_ad_tv_desktop`：

`authorize → independent 本地生成 URL → 公共修正及空 rdid 兜底 → google-gam/patch → 后台 URL → TCL 请求/解析/播放/监测`

这已不是独立实验实现，而是与正式 Google 渠道一致的自拼接版本。构建说明和完整渠道清单见 `app/src/google_ad_tv_desktop/README.md`。同后台渠道的当前交付版本继续以 `google_ad_tv_desktop_v260904_1` 的版本配置为准；此次没有打包或修改版本号。
