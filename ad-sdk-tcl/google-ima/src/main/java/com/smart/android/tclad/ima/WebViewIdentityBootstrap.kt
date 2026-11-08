package com.smart.android.tclad.ima

internal object WebViewIdentityBootstrap {
    private const val MARKER = "data-hq008-firmware-identity"

    fun script(
        effectiveUa: String,
        effectiveModel: String,
        effectiveRelease: String,
        sdkInt: Int
    ): String {
        val canonicalPlatformVersion =
            WebViewClientHintsNormalizer.canonicalPlatformVersion(effectiveRelease, sdkInt).orEmpty()
        val comparableRelease = if (effectiveRelease == "12L") "12" else effectiveRelease
        return """
            (function() {
              'use strict';
              var root = typeof globalThis === 'object' ? globalThis : window;
              if (root.__hq008FirmwareIdentityV1 === true) return;
              try {
                Object.defineProperty(root, '__hq008FirmwareIdentityV1', {
                  value: true,
                  configurable: false,
                  enumerable: false,
                  writable: false
                });
              } catch (_) {
                root.__hq008FirmwareIdentityV1 = true;
              }

              var fixedUa = ${quote(effectiveUa)};
              var fixedModel = ${quote(effectiveModel)};
              var fixedPlatformVersion = ${quote(canonicalPlatformVersion)};
              var comparableRelease = ${quote(comparableRelease)};

              function defineGetter(target, name, getter) {
                if (!target) return false;
                try {
                  Object.defineProperty(target, name, {
                    get: getter,
                    configurable: true,
                    enumerable: true
                  });
                  return true;
                } catch (_) {
                  return false;
                }
              }

              function defineMethod(target, name, method) {
                if (!target) return false;
                try {
                  Object.defineProperty(target, name, {
                    value: method,
                    configurable: true,
                    enumerable: false,
                    writable: true
                  });
                  return true;
                } catch (_) {
                  return false;
                }
              }

              function withoutZeroPadding(value) {
                var normalized = String(value);
                while (normalized.length > 2 && normalized.slice(-2) === '.0') {
                  normalized = normalized.slice(0, -2);
                }
                return normalized;
              }

              function copyAndCorrect(values, uaData) {
                if (!values || typeof values !== 'object') return values;
                var corrected = {};
                Object.keys(values).forEach(function(key) { corrected[key] = values[key]; });
                if (typeof corrected.model === 'string') corrected.model = fixedModel;
                var platform = typeof corrected.platform === 'string'
                  ? corrected.platform
                  : (uaData && uaData.platform);
                if (platform === 'Android' && typeof corrected.platformVersion === 'string' &&
                    corrected.platformVersion.length > 0 && fixedPlatformVersion.length > 0 &&
                    withoutZeroPadding(corrected.platformVersion) !==
                      withoutZeroPadding(comparableRelease)) {
                  corrected.platformVersion = fixedPlatformVersion;
                }
                return corrected;
              }

              if (typeof navigator !== 'object' || navigator === null) return;
              if (!defineGetter(navigator, 'userAgent', function() { return fixedUa; })) {
                defineGetter(Object.getPrototypeOf(navigator), 'userAgent', function() {
                  return fixedUa;
                });
              }

              var uaData;
              try {
                uaData = navigator.userAgentData;
              } catch (_) {
                return;
              }
              if (!uaData) return;

              var uaDataPrototype = Object.getPrototypeOf(uaData);
              var originalHighEntropy = uaData.getHighEntropyValues;
              if (typeof originalHighEntropy === 'function') {
                var correctedHighEntropy = function(hints) {
                  return Promise.resolve(originalHighEntropy.call(this, hints)).then(function(values) {
                    return copyAndCorrect(values, uaData);
                  });
                };
                if (!defineMethod(uaData, 'getHighEntropyValues', correctedHighEntropy)) {
                  defineMethod(uaDataPrototype, 'getHighEntropyValues', correctedHighEntropy);
                }
              }

              var originalToJson = uaData.toJSON;
              if (typeof originalToJson === 'function') {
                var correctedToJson = function() {
                  return copyAndCorrect(originalToJson.call(this), uaData);
                };
                if (!defineMethod(uaData, 'toJSON', correctedToJson)) {
                  defineMethod(uaDataPrototype, 'toJSON', correctedToJson);
                }
              }
            })();
        """.trimIndent()
    }

    fun prependToHtml(html: String, script: String): String {
        if (html.contains(MARKER)) return html
        val bootstrap = "<script $MARKER=\"1\">$script</script>"
        val doctype = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (doctype == null) bootstrap + html else {
            html.substring(0, doctype.range.last + 1) + bootstrap +
                html.substring(doctype.range.last + 1)
        }
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
