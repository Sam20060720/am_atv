package dev.amatv.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StorefrontLocalizationTest {
    @Test fun returnsOnlyStorefrontSupportedTagsAndKeepsChineseScript() {
        val supported = listOf("en-US", "zh-Hans-CN", "zh-Hant-TW")
        assertEquals("zh-Hant-TW", selectCatalogLanguage("zh-TW", supported, "en-US"))
        assertEquals("zh-Hans-CN", selectCatalogLanguage("zh-CN", supported, "en-US"))
        assertEquals("en-US", selectCatalogLanguage("en-GB", supported, "zh-Hans-CN"))
        assertEquals("en-US", selectCatalogLanguage("zh-Hant", listOf("en-US", "zh-Hans-CN"), "en-US"))
        assertEquals("en-US", selectCatalogLanguage("fr-FR", supported, "en-US"))
        assertNull(selectCatalogLanguage("en", emptyList(), "en-US"))
    }

    @Test fun preservesEncodedContinuationAndReplacesOldLanguage() {
        assertEquals("/v1/catalog/tw/songs?cursor=a%2Fb%26c&offset=25&l=zh-Hant-TW",
            withCatalogLanguage("/v1/catalog/tw/songs?cursor=a%2Fb%26c&l=en-US&offset=25", "zh-Hant-TW"))
    }

    @Test fun parsesStorefrontLanguageContractWithoutInventingSupport() {
        val metadata = parseStorefrontLocalization(JSONObject("""{"data":[{"attributes":{
          "supportedLanguageTags":["en-US","zh-Hans-CN"],"defaultLanguageTag":"en-US"}}]}"""))!!
        assertEquals(listOf("en-US", "zh-Hans-CN"), metadata.supported)
        assertEquals("en-US", metadata.default)
        assertNull(parseStorefrontLocalization(JSONObject("""{"data":[{"id":"cn"}]}""")))
    }
}
