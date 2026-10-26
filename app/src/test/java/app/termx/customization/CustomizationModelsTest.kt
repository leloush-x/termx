package app.termx.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

class CustomizationModelsTest {
    @Test fun themeRequiresExactlySixteenValidAnsiColors() {
        val colors = (0..15).joinToString(",") { "\"#112233\"" }
        val json = """{"schema":1,"name":"Night","mode":"dark","colors":{"background":"#000000","foreground":"#ffffff","cursor":"#ffffff","selection":"#334455","bold":"#ffffff"},"ansi":[$colors],"background":{"image":null,"blur":0,"dim":0.4}}"""
        assertEquals(16, TermXTheme.parse(json).ansi.size)
        val invalid = json.replace("\"#112233\",", "\"bad\",")
        assertFieldError("colors.ansi[0]") { TermXTheme.parse(invalid) }
    }

    @Test fun themeErrorNamesInvalidField() {
        val ansi = (0..15).joinToString(",") { "\"#112233\"" }
        val json = """{"schema":1,"name":"Night","mode":"dark","colors":{"background":"black","foreground":"#ffffff","cursor":"#ffffff","selection":"#334455","bold":"#ffffff"},"ansi":[$ansi],"background":{"image":null,"blur":0,"dim":0}}"""
        assertFieldError("colors.background") { TermXTheme.parse(json) }
    }

    @Test fun keyLayoutRequiresOneActionAndNamesTheKey() {
        val invalid = """{"schema":1,"layers":{"base":[[{"label":"CTRL","send":"ESC","modifier":"ctrl"}]],"more":[]}}"""
        assertFieldError("layers.base[0][0]") { KeyLayoutValidator.validate(invalid) }
    }

    @Test fun keyLayoutRejectsUnknownControlSequence() {
        val invalid = """{"schema":1,"layers":{"base":[[{"label":"bad","send":"\u001b[99z"}]],"more":[]}}"""
        assertFieldError("layers.base[0][0].send") { KeyLayoutValidator.validate(invalid) }
    }

    @Test fun architectureLayoutWithRowsIsAcceptedAndUsable() {
        val valid = """{"schema":1,"layers":{"base":[[{"label":"ESC","send":"\u001b"},{"label":"CTRL","modifier":"ctrl","sticky":true}],[{"label":"-","send":"-","long":["_","="]}]],"more":[[{"label":"Home","send":"\u001b[H"}]]}}"""
        val keys = KeyLayoutValidator.baseKeys(valid)
        assertEquals(listOf("ESC", "CTRL", "-"), keys.map { it.label })
        assertEquals("_", keys.last().long.first())
    }

    @Test fun keymapNamesUnknownActionsAndKeys() {
        val invalid = """{"schema":1,"bindings":{"palette.open":["CTRL","HYPER"]}}"""
        assertFieldError("bindings.palette.open[1]") { KeymapValidator.validate(invalid) }
    }

    @Test fun secretFilteringRemovesNestedFields() {
        val export = SettingsExport.settingsJson(mapOf(
            "theme" to "dark", "token" to "abc",
            "key_layout_json" to """{"keys":[{"api_key":"secret","label":"ESC"}]}""",
        )).getJSONObject("settings")
        val layout = org.json.JSONObject(export.getString("key_layout_json"))
        assertEquals("dark", export.getString("theme"))
        assertTrue(!export.has("token"))
        assertTrue(!layout.getJSONArray("keys").getJSONObject(0).has("api_key"))
        assertEquals("ESC", layout.getJSONArray("keys").getJSONObject(0).getString("label"))
    }

    private fun assertFieldError(field: String, block: () -> Unit) {
        try { block(); fail("Expected validation failure") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(field)) }
    }
}
