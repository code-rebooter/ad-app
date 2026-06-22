# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# Viewbinding 如果用的反射就会有混淆问题，需要加入混淆
-keep class * implements androidx.viewbinding.ViewBinding {
    *;
}
-keep class com.smart.android.ad_app.bean.** { *; }

#Tcl.aar的混淆配置，mofeng_ad.aar也是这个混淆配置
-keep class com.tcl.ff.component.vastad.**{*;}
#xstream
-keep class com.thoughtworks.xstream.**{*;}

# TCL 2.8.02 视频广告 SDK 官方规则（按 AAR 自带 proguard.txt 合并）
-keep class com.tcl.ff.component.overseabasebusiness.bean.** { *; }
-keep class com.tcl.ff.component.overseabasebusiness.ConfigInitObserver { *; }
-keep interface com.tcl.ff.component.overseabasebusiness.ConfigInitObserver$Observer { *; }
-keep class com.tcl.ff.component.overseabasebusiness.AdConfigFetcher { public *; }
-keep class com.tcl.ff.component.overseabasebusiness.bi.** { public *; }
-keep class com.tcl.ff.component.overseabasebusiness.constant.** { public *; }
-keep class com.tcl.ff.component.overseabasebusiness.requestparams.** { public *; protected *; }
-keep class com.tcl.ff.component.overseabasebusiness.adutils.** { public *; }
-keep class com.tcl.ff.component.overseabasebusiness.TripartiteAttributes { *; }
-keep class com.tcl.ff.component.overseabase.base.constant.** { *; }
-keep class com.tcl.ff.component.overseabase.base.device.** { public *; }
-keep class com.tcl.ff.component.overseabase.base.thread.** { public *; }
-keep class com.tcl.ff.component.overseabase.base.util.** { public *; }
-keep class com.tcl.ff.component.overseabase.debugutils.** { public *; }
-keep class com.tcl.ff.component.overseabase.database.** { *; }
-keep class * extends com.tcl.ff.component.overseabase.database.crud.LitePalSupport { *; }
-keep class com.tcl.ff.component.overseahttp.http.convert.** { *; }
-keep class com.tcl.ff.component.overseahttp.http.base.** { *; }
-keep class com.tcl.ff.component.overseahttp.http.sign.** { public *; }
-keep class com.tcl.ff.component.overseahttp.http.BaseRequest { public *; }
-keep class com.tcl.ff.component.overseahttp.http.HttpLogInterceptor { public *; }
-keep class com.tcl.ff.component.overseahttp.http.HttpLogInterceptor$HttpLogger { public *; }
-keep class com.tcl.ff.component.overseahttp.http.HttpRequester { public *; }
-keep class com.tcl.ff.component.oversea.** { *; }
-keep class com.tcl.ff.component.adsdkbi.plugin.bean.** { *; }
-keep class com.tcl.ff.component.adsdkbi.bean.* { *; }
-keep class com.tcl.ff.component.adsdkbi.ReportHelper { *; }
-keep class com.tcl.ff.component.adsdkbi.ReportHelper$ReportBuilder { *; }
-keep class com.tcl.ff.component.adsdkbi.DataReport { public *; }
-keep class com.tcl.ff.component.adsdkbi.DataReport$Builder { public *; }
-keep class com.tcl.ff.component.adsdkbi.common.BaseConfig { *; }
-keep class com.tcl.ff.component.adsdkbi.constant.* { *; }
-keep class com.tcl.ff.component.adsdkbi.utils.AppUtil { public getIV(); }
-keep class com.tcl.ff.component.media.processor.ImaInitiator { *; }
-keep class com.tcl.ff.component.media.processor.address.data.** { *; }
-keep class com.tcl.ff.component.media.processor.api.** { *; }
-keep class com.tcl.ff.component.media.processor.factory.AdFetcherFactory { public *; }
-keep class com.tcl.ff.component.media.processor.params.* { public *; }
-keep class com.tcl.ff.component.media.processor.imp.EmptyAdsFetcher { public *; }
-keep class com.tcl.ff.component.media.ima.BaseImaInitiator { public *; }
-keep class com.tcl.ff.component.media.ima.ImaSettings* { public *; }
-keep class com.tcl.ff.component.media.ima.api.* { public *; protected *; }
-keep interface com.tcl.ff.component.media.ima.bi.AdsVastBiTrackerListener { *; }
-keep class com.tcl.ff.component.media.ima.constant.* { public *; }
-keep class com.tcl.ff.component.media.ima.flat.* { public *; }
-keep class com.tcl.ff.component.media.ima.platform.* { public *; protected *; }
-keep class com.tcl.ff.component.media.ima.circuitbreaker.AdsCircuitBreaker { public *; }
-keep class com.tcl.ff.component.media.ima.TclImaInitiator { public *; }
-keep class com.tcl.ff.component.media.ima.tcl.DevelopedVastAdsLoader { public *; }
-keep class com.tcl.ff.component.media.ima.tcl.convert.* { public *; }
-keep class com.tcl.ff.component.media.ima.tcl.data.* { *; }
-keep class com.tcl.ff.component.media.ima.tcl.xmlmodels.* { *; }
-keep class com.tcl.ff.component.oversea.uniplayer.config.* { *; }
-keep class com.tcl.ff.component.oversea.uniplayer.factory.* { *; }
-keep class com.tcl.ff.component.oversea.uniplayer.init.* { *; }
-keep class com.tcl.ff.component.oversea.uniplayer.listener.* { *; }
-keep class com.tcl.ff.component.oversea.uniplayer.player.IPlayer { *; }
-keep class com.tcl.ff.component.oversea.uniplayer.player.SysVideoPlayerImpl { public *; }
-keep class com.tcl.ff.component.oversea.uniplayer.player.UniVideoPlayerImpl { public *; }
-keep class com.tcl.ff.component.oversea.uniplayer.player.IjkVideoPlayerImpl { public *; }
-keepclassmembers enum com.tcl.uniplayer.** { *; }
-keepclassmembers enum com.tcl.ff.component.uniplayer.** { *; }
-keep class * extends com.tcl.tuniplayer_base.DefaultPlayerDelegate { *; }
-keep class * extends com.tcl.ff.component.uniplayer.player.StreamMediaPlayer { *; }
-keep class com.tcl.tuniplayer_base.DefaultPlayerDelegate
-keep class * extends com.tcl.ff.component.uniplayer.AbstractConfigFactory { *; }
-keep class * extends com.tcl.ff.component.uniplayer.AbstractPlayerFactory { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep class com.tcl.tuniplayer_ijk.IjkPlayerDelegate { *; }
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep public class * extends android.content.ContentProvider
-keep class io.github.xstream.mxparser* { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Haier LSAP SDK
-keep class com.smart.android.ad_app.HaierLsapAdManager { *; }
-keep class com.spctv.** { *; }
-keep class com.itv.component.unified.** { *; }


#Sad1.0.9的混淆
-keep class com.seraphic.ad.** { *; }
-keep interface com.seraphic.ad.** { *; }
-keepclassmembers class com.seraphic.ad.** { *; }



#zyvideo_ad.aar的混淆配置
#okhttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.squareup.okhttp.** { *; }
-keep class okhttp3.** { *; }
-dontwarn com.squareup.okhttp.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.checkerframework.**
-dontwarn kotlin.annotations.jvm.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn com.tcl.ff.component.media.ima.GPalInitiator
-dontwarn com.tcl.ff.component.media.ima.GoogleImaInitiator
-dontwarn com.tcl.ff.component.media.ima.google.GoogleVastAdsLoader
-dontwarn com.tcl.ff.component.media.ima.googlepal.GPalVastAdsLoader
-dontwarn com.tcl.ff.component.uniplayer.data.TclAdInfo
-dontwarn com.tcl.uniplayer.cache.Proxy
-dontwarn com.tcl.uniplayer.cache.cache.CacheConfig$Builder
-dontwarn com.google.android.exoplayer2.core.R$string
-dontwarn com.google.android.exoplayer2.database.DatabaseIOException
-dontwarn com.google.android.exoplayer2.database.DatabaseProvider
-dontwarn com.google.android.exoplayer2.database.VersionTable
-dontwarn jcifs.CIFSContext
-dontwarn jcifs.Configuration
-dontwarn jcifs.config.PropertyConfiguration
-dontwarn jcifs.context.BaseContext
-dontwarn jcifs.smb.SmbFile
-dontwarn jcifs.smb.SmbFileInputStream
-dontwarn org.apache.commons.net.ftp.FTPClient
-dontwarn org.apache.commons.net.ftp.FTPFile
#Rxjava + Retrofit
-keep class io.reactivex.** { *; }
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions


-dontwarn com.barchart.udt.TypeUDT
-dontwarn com.barchart.udt.nio.RendezvousChannelUDT
-dontwarn com.barchart.udt.nio.SocketChannelUDT
-dontwarn com.google.ads.interactivemedia.v3.api.Ad
-dontwarn com.google.ads.interactivemedia.v3.api.AdError$AdErrorCode
-dontwarn com.google.ads.interactivemedia.v3.api.AdError
-dontwarn com.google.ads.interactivemedia.v3.api.AdErrorEvent$AdErrorListener
-dontwarn com.google.ads.interactivemedia.v3.api.AdErrorEvent
-dontwarn com.google.ads.interactivemedia.v3.api.AdEvent$AdEventListener
-dontwarn com.google.ads.interactivemedia.v3.api.AdEvent$AdEventType
-dontwarn com.google.ads.interactivemedia.v3.api.AdEvent
-dontwarn com.google.ads.interactivemedia.v3.api.ImaSdkFactory
-dontwarn com.google.ads.interactivemedia.v3.api.ImaSdkSettings
-dontwarn com.google.ads.interactivemedia.v3.impl.data.zzc
-dontwarn com.jcraft.jzlib.Deflater
-dontwarn com.jcraft.jzlib.Inflater
-dontwarn com.jcraft.jzlib.JZlib$WrapperType
-dontwarn com.jcraft.jzlib.JZlib
-dontwarn io.reactivex.Observable
-dontwarn io.reactivex.Observer
-dontwarn io.reactivex.Scheduler
-dontwarn io.reactivex.android.schedulers.AndroidSchedulers
-dontwarn io.reactivex.disposables.Disposable
-dontwarn io.reactivex.schedulers.Schedulers
-dontwarn org.jboss.marshalling.Marshaller
-dontwarn org.jboss.marshalling.Unmarshaller
-dontwarn retrofit2.CallAdapter$Factory
-dontwarn retrofit2.Converter$Factory
-dontwarn retrofit2.Retrofit$Builder
-dontwarn retrofit2.Retrofit
-dontwarn retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
-dontwarn retrofit2.converter.gson.GsonConverterFactory
-dontwarn retrofit2.http.Field
-dontwarn retrofit2.http.FormUrlEncoded
-dontwarn retrofit2.http.Headers
-dontwarn retrofit2.http.POST
-dontwarn com.bea.xml.stream.MXParserFactory
-dontwarn com.bea.xml.stream.XMLOutputFactoryBase
-dontwarn com.ctc.wstx.stax.WstxInputFactory
-dontwarn com.ctc.wstx.stax.WstxOutputFactory
-dontwarn java.awt.Color
-dontwarn java.awt.Font
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.PropertyEditor
-dontwarn javax.activation.ActivationDataFlavor
-dontwarn javax.swing.plaf.FontUIResource
-dontwarn javax.xml.bind.DatatypeConverter
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLOutputFactory
-dontwarn javax.xml.stream.XMLStreamException
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn javax.xml.stream.XMLStreamWriter
-dontwarn net.sf.cglib.proxy.Callback
-dontwarn net.sf.cglib.proxy.CallbackFilter
-dontwarn net.sf.cglib.proxy.Enhancer
-dontwarn net.sf.cglib.proxy.Factory
-dontwarn net.sf.cglib.proxy.NoOp
-dontwarn net.sf.cglib.proxy.Proxy
-dontwarn nu.xom.Attribute
-dontwarn nu.xom.Builder
-dontwarn nu.xom.Document
-dontwarn nu.xom.Element
-dontwarn nu.xom.Elements
-dontwarn nu.xom.Node
-dontwarn nu.xom.ParentNode
-dontwarn nu.xom.ParsingException
-dontwarn nu.xom.Text
-dontwarn nu.xom.ValidityException
-dontwarn org.codehaus.jettison.AbstractXMLStreamWriter
-dontwarn org.codehaus.jettison.mapped.Configuration
-dontwarn org.codehaus.jettison.mapped.MappedNamespaceConvention
-dontwarn org.codehaus.jettison.mapped.MappedXMLInputFactory
-dontwarn org.codehaus.jettison.mapped.MappedXMLOutputFactory
-dontwarn org.dom4j.Attribute
-dontwarn org.dom4j.Branch
-dontwarn org.dom4j.Document
-dontwarn org.dom4j.DocumentException
-dontwarn org.dom4j.DocumentFactory
-dontwarn org.dom4j.Element
-dontwarn org.dom4j.io.OutputFormat
-dontwarn org.dom4j.io.SAXReader
-dontwarn org.dom4j.io.XMLWriter
-dontwarn org.dom4j.tree.DefaultElement
-dontwarn org.jdom.Attribute
-dontwarn org.jdom.Content
-dontwarn org.jdom.DefaultJDOMFactory
-dontwarn org.jdom.Document
-dontwarn org.jdom.Element
-dontwarn org.jdom.JDOMException
-dontwarn org.jdom.JDOMFactory
-dontwarn org.jdom.Text
-dontwarn org.jdom.input.SAXBuilder
-dontwarn org.jdom2.Attribute
-dontwarn org.jdom2.Content
-dontwarn org.jdom2.DefaultJDOMFactory
-dontwarn org.jdom2.Document
-dontwarn org.jdom2.Element
-dontwarn org.jdom2.JDOMException
-dontwarn org.jdom2.JDOMFactory
-dontwarn org.jdom2.Text
-dontwarn org.jdom2.input.SAXBuilder
-dontwarn org.joda.time.DateTime
-dontwarn org.joda.time.DateTimeZone
-dontwarn org.joda.time.format.DateTimeFormatter
-dontwarn org.joda.time.format.ISODateTimeFormat
-dontwarn org.kxml2.io.KXmlParser
-dontwarn org.xmlpull.mxp1.MXParser


-keep class com.speed.bean.** { *; }
-keep class com.speed.ad.bean.** { *; }
-keep class com.speed.net.ApiResponse { *; }
-keep class com.smart.android.ad_app.sdk.AdData { *; }
# Keep hq008 authorize response fields stable for Gson reflection in release builds.
-keepclassmembers class com.smart.android.ad_app.Hq008AuthorizeResponseData {
    <fields>;
}
