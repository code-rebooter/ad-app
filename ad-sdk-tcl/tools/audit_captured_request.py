#!/usr/bin/env python3
"""Inventory the original WATERFALL request without replaying advertising traffic."""
import argparse
import json
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

HOST_FIELDS = {
    "appCat", "appDomain", "area", "channelName", "contentEpisode", "contentGenre", "contentKeywords",
    "contentLanguage", "contentLength", "contentLivestream", "contentNetwork", "contentProducerName",
    "contentRating", "contentSeries", "contentSeason", "contentCat", "contentTitle", "contentId", "coppa",
    "dnum", "device", "deviceLanguage", "gdprConsent", "gdpr", "ProductionQuality", "podSequence",
    "schain", "TUID", "dnt", "mediaCp", "gdprSource",
}
MAPPING = {
    "device_model": ("deviceModel", "设备型号"), "rating": ("contentRating", "后台改写为空值以外的节目分级"),
    "skip": ("skip", "后台将数字 0 变为空字符串"), "gdpr_consent": ("gdprConsent", "同为空；仅凭本次样本不能证明非空转换"),
    "ua": ("userAgent", "设备 HTTP User-Agent"), "content_title": ("contentTitle", "后台将 App Content 改为 Suga Babies"),
    "coppa": ("coppa", "后台将空字符串变为 0"), "ic": (None, "后台补入 IAB1；与 contentCat 的映射未证实"),
    "cb": ("cb", "沿用本次请求的毫秒时间戳"), "channel_name": ("channelName", "后台将 TCL_AISHANG 改为 Suga Babies"),
    "content_id": ("contentId", "后台补入节目 ID"), "ip": (None, "后台提供的出口 IP；不是设备局域网 IP"),
    "h": ("playerHeight", "加载阶段为 0"), "network_name": (None, "后台提供节目网络名；与 contentNetwork 的映射未证实"),
    "pod_max_dur": ("podDuration", "广告组时长上限，秒"), "dnt": ("dnt", "后台将数字 0 变为空字符串"),
    "TDID": (None, "后台保留空值；不能据此将 TUID 改名为 TDID"), "url": ("appDomain", "本次与宿主传入域信息相同"),
    "gdpr": ("gdpr", "后台将空字符串变为 0；客户端应传实际授权状态"), "app_name": ("appName", "本次原厂固定 MovieArk"),
    "device_make": ("deviceMake", "设备厂商"), "max_dur": ("maxAdDuration", "单条广告时长上限，秒"),
    "content_genre": ("contentGenre", "后台补入 Drama,Thriller,Crime"), "app_bundle": ("appBundle", "本次原厂外部接入固定 com.tcl.movieark"),
    "w": ("playerWidth", "加载阶段为 0"), "app_store_url": ("appStoreUrl", "商店 URL 作为一个查询值进行编码"),
    "us_privacy": (None, "后台补入 1---；POST 中没有同名字段"), "min_dur": ("minAdDuration", "单条广告时长下限，秒"),
    "placement": ("placement", "本次为 1"), "ifa_type": ("IFAType", "本次为 aaid"), "did": ("did", "设备广告标识"),
    "vt": (None, "后台节目标题别名，与 content_title 相同"), "cid": (None, "后台节目 ID 别名，与 content_id 相同"),
}


def source(key):
    if key in HOST_FIELDS:
        return "宿主 RequestParams；原厂默认空字符串或数字 0"
    if key in {"deviceMake", "deviceModel", "appStoreUrl"}:
        return "SDK 默认值；宿主非空值可覆盖"
    if key in {"application", "appPackage", "appVersion", "appKey", "asfm", "DevicePartner"}:
        return "应用/平台登记身份，BasicParameters"
    if key in {"minAdDuration", "maxAdDuration", "podDuration"}:
        return "getALLConfig 返回的广告时长配置"
    if key in {"cb", "timestamp"}:
        return "每次请求生成的毫秒时间戳"
    if key in {"userAgent", "did", "IFAType"}:
        return "SDK DeviceInfoGeneratorImpl 采集"
    if key in {"appName", "appBundle"}:
        return "原厂固定身份（appBundle 在内部接入分支另有处理）"
    return "当前非直播 WATERFALL 配置/常量"


def displayed(key, value):
    if key in {"appKey", "asfm", "did", "ip"}:
        return "<已脱敏>"
    return json.dumps(value, ensure_ascii=False).replace("|", "\\|")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, default=Path("artifacts/tcl-vast-inspection-20261008/tcl-http-capture.jsonl"))
    parser.add_argument("--output", type=Path, default=Path("artifacts/tcl-request-alignment-20261009"))
    args = parser.parse_args()
    records = [json.loads(line) for line in args.capture.read_text().splitlines() if line.strip()]
    index, record = next((i, r) for i, r in enumerate(records) if urlsplit(r["requestUrl"]).path.endswith("/getWaterfall/ad"))
    body = json.loads(record["requestBody"])
    tag = json.loads(record["responseBody"])["data"]["adsUrl"]
    actual = next(r["requestUrl"] for r in records[index + 1:] if urlsplit(r["requestUrl"]).path == urlsplit(tag).path)
    returned_pairs = parse_qsl(urlsplit(tag).query, keep_blank_values=True)
    actual_pairs = parse_qsl(urlsplit(actual).query, keep_blank_values=True)
    assert [(k, v) for k, v in actual_pairs if k != "requestId"] == returned_pairs
    assert len([k for k, _ in actual_pairs if k == "requestId"]) == 1
    assert set(MAPPING) == {k for k, _ in returned_pairs}
    post_fields = [{"name": k, "type": "number" if isinstance(v, (int, float)) else "string",
                    "capturedValue": "<redacted>" if k in {"appKey", "asfm", "did"} else v, "source": source(k)}
                   for k, v in body.items()]
    query_fields = [{"name": k, "postField": MAPPING[k][0], "capturedValue": "<redacted>" if k in {"did", "ip"} else v,
                     "note": MAPPING[k][1]} for k, v in returned_pairs]
    result = {"capture": str(args.capture), "postFieldCount": len(body), "returnedQueryCount": len(returned_pairs),
              "actualQueryCount": len(actual_pairs), "onlyClientAddition": "requestId", "postFields": post_fields,
              "queryFields": query_fields}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "request-field-audit.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    lines = ["# TCL WATERFALL 请求参数核验", "", "2026-10-09。依据 X88 上 TCL 2.8.02 的实际 HTTP 记录和原厂请求类字节码。",
             "", f"POST 请求实际包含 **{len(body)} 个字段**；后台返回的 adsUrl 含 **{len(returned_pairs)} 个查询参数**；SDK 仅补充 requestId，实际请求含 **{len(actual_pairs)} 个参数**。保留空值后统计，不把嵌套编码的商店 URL 拆开。",
             "", "单次抓包可以证明实际发送字段，不能证明每个字段均为服务端必填，也不能证明所有国家、授权状态及配置使用相同值。",
             "", "## 当前 Google 渠道的接入范围", "", "Google 渠道保留现有 POST /api/v2/ad/google-gam/resolve，但需扩充请求上下文和服务端标签生成逻辑。两字段取链接不能证明 TCL 参数对齐。后端改造协议见 ad-sdk-tcl/RESOLVE_TCL_CONTRACT.md；下表是 TCL 原始输入/输出证据，不同上游采用自己的查询参数名。", "", "## POST 字段（完整清单）", "", "| 字段 | JSON 类型 | 抓包值 | 值来源 |", "| --- | --- | --- | --- |"]
    lines.extend(f"| {f['name']} | {f['type']} | `{displayed(f['name'], body[f['name']])}` | {f['source']} |" for f in post_fields)
    lines += ["", "## 后台返回 URL（完整清单）", "", "| 查询参数 | 对应输入/候选输入 | 抓包值 | 核验结论 |", "| --- | --- | --- | --- |"]
    lines.extend(f"| {k} | {MAPPING[k][0] or '后台提供，未证明客户端映射'} | `{displayed(k, v)}` | {MAPPING[k][1]} |" for k, v in returned_pairs)
    lines += ["| requestId | SDK 每次生成 | UUID | 原厂 VastRequest.getVastUrl 替换或追加；不在该 POST 请求体内 |",
              "", "## 可选 TCL 兼容适配器的接口约定", "", "1. 接收上述 58 字段的 JSON，保持大小写、数字类型及空字符串。DevicePartner、IFAType、ProductionQuality、TUID 不改名。",
              "2. 返回 code=100000、data.adsUrl；该 URL 包含平台所需全部查询值，客户端保留后台生成和改写后的值，仅替换或追加 requestId。",
              "3. 实际应用、登记身份、AAID、授权状态、节目元数据和时长配置由对应数据源提供；不能把 X88 的一次请求值用于其他设备或每次请求。",
              "4. area=US 是本次宿主 Locale 值，而后台配置 countryCode=hk、实际出口 IP 属于香港，三者不可混为一个字段。",
              "5. 5/60/120 秒来自本次 getALLConfig 配置；新适配器允许显式配置这些值，尚未自动实现原厂 getALLConfig 调度。",
              "", "## 已实现与边界", "", "新模块 RequestParams 补齐原厂 34 个宿主字段；WaterfallRequest 生成 58 字段请求；WaterfallAdTagProvider 异步 POST 到宿主配置的接口，解析 adsUrl 并补 requestId。停止或释放 Controller 时取消配置请求。",
              "", "Builder(context) 读取真实宿主包名、版本、应用名、签名 MD5、设备厂商/型号及 User-Agent；appBundle 默认宿主包名，商店地址和平台登记项由宿主填写。AAID 与类型由宿主提供，未提供时明确为空。原厂固定 MovieArk/com.tcl.movieark 和已抓到的项目身份只作为核验依据，需对接该登记身份时通过配置显式传入。",
              "", "本次没有请求真实广告平台。此可选适配器请求宿主传入的接口地址，尚未接入真实后台；不内置原厂地址、HTTP 鉴权或签名拦截器。尚未覆盖原厂配置缓存/调度、CMP 自动取值及真机全流程；不能据此宣称整个 TCL SDK 已完成 1:1 验证。",
              "", "原始证据：../tcl-vast-inspection-20261008/tcl-http-capture.jsonl（第 3、4 条）；本目录 original-request-bytecode.txt、original-waterfall-bytecode.txt、original-vast-request-bytecode.txt。",
              ""]
    (args.output / "report.md").write_text("\n".join(lines))
    print(json.dumps({k: result[k] for k in ("postFieldCount", "returnedQueryCount", "actualQueryCount", "onlyClientAddition")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
