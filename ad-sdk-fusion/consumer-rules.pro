-keepattributes *Annotation*

# Silent consent flow reflects SDK 4.0.0 internal form fields.
-keep class com.google.android.gms.internal.consent_sdk.** { *; }

# TCL 2.8.02 loads its own factories/configuration and serialization models reflectively.
-keep class com.tcl.** { *; }

# The patched TCL vendor AAR calls this shared identity bridge directly.
-keep class com.smart.android.adsdk.internal.TclIdentityBridge { public static *; }

# Optional TCL Google/PAL adapters and cache/protocol extensions are not bundled.
# TCL detects adapters/cache by class presence; this module uses its native HTTP VAST path.
-dontwarn com.tcl.ff.component.media.ima.GPalInitiator
-dontwarn com.tcl.ff.component.media.ima.GoogleImaInitiator
-dontwarn com.tcl.ff.component.media.ima.google.GoogleVastAdsLoader
-dontwarn com.tcl.ff.component.media.ima.googlepal.GPalVastAdsLoader
-dontwarn com.tcl.ff.component.uniplayer.data.TclAdInfo
-dontwarn com.tcl.uniplayer.cache.Proxy
-dontwarn com.tcl.uniplayer.cache.cache.CacheConfig$Builder
-dontwarn jcifs.CIFSContext
-dontwarn jcifs.Configuration
-dontwarn jcifs.config.PropertyConfiguration
-dontwarn jcifs.context.BaseContext
-dontwarn jcifs.smb.SmbFile
-dontwarn jcifs.smb.SmbFileInputStream
-dontwarn org.apache.commons.net.ftp.FTPClient
-dontwarn org.apache.commons.net.ftp.FTPFile
-dontwarn com.tcl.uniplayer.cache.preload.Call
-dontwarn com.tcl.uniplayer.cache.preload.Callback
-dontwarn com.tcl.uniplayer.cache.preload.PreloadTask
-dontwarn com.tcl.uniplayer.cache.preload.PreloadTask$Builder

# XStream includes optional desktop XML/object drivers. TCL uses its Android XML parser.
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
