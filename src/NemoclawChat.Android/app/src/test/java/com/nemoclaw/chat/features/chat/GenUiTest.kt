package com.nemoclaw.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenUiTest {
    private fun block(id: String, spec: String, state: String = "{}", fallback: String = "riepilogo"): JSONObject? {
        return GenUiValidator.validateBlock(id, 1, fallback, state, spec)
    }

    @Test
    fun `parametric ui validates`() {
        val spec = """
        {"kind":"column","id":"root","children":[
          {"kind":"slider","id":"s","label":"A","bind":"amp","min":0.1,"max":2.0,"step":0.1},
          {"kind":"metric","id":"m","label":"P","formula":"1 / freq"},
          {"kind":"chart","id":"c","chart_type":"line","series":[{"name":"w","points":[{"x":0,"y_formula":"amp * sin(x)"},{"x":1,"y_formula":"amp * sin(x)"}]}]}
        ]}
        """.trimIndent()
        val validated = block("wave", spec, """{"amp":1.0,"freq":2.0}""")
        assertNotNull(validated)
    }

    @Test
    fun `tutorial with steps and agent action validates`() {
        val spec = """
        {"kind":"column","id":"root","children":[
          {"kind":"text","id":"t","text":"Passo {{step + 1}} di 2."},
          {"kind":"steps","id":"st","bind":"step","items":[{"title":"A","text":"uno"},{"title":"B","text":"due"}]},
          {"kind":"checkbox","id":"k","label":"Fatto","bind":"done"},
          {"kind":"progress","id":"p","formula":"(step + 1) / 2"},
          {"kind":"button","id":"b","label":"Spiega","action":{"kind":"agent","action_id":"explain_step","state_keys":["step"]}}
        ]}
        """.trimIndent()
        assertNotNull(block("tut", spec, """{"step":0,"done":false}"""))
    }

    @Test
    fun `invalid blocks rejected`() {
        val cases = listOf(
            // id mancante/illegale
            Triple("", """{"kind":"text","id":"a","text":"x"}""", "{}"),
            Triple("bad id!", """{"kind":"text","id":"a","text":"x"}""", "{}"),
            // kind sconosciuto
            Triple("k", """{"kind":"megalodonte","id":"a"}""", "{}"),
            // id duplicati
            Triple("d", """{"kind":"column","id":"root","children":[{"kind":"text","id":"x","text":"a"},{"kind":"text","id":"x","text":"b"}]}""", "{}"),
            // formula rotta e funzioni vietate
            Triple("f", """{"kind":"metric","id":"m","label":"M","formula":"a + * 2"}""", """{"a":1}"""),
            Triple("f2", """{"kind":"metric","id":"m","label":"M","formula":"eval(x)"}""", "{}"),
            // action agent senza action_id
            Triple("a", """{"kind":"button","id":"b","label":"V","action":{"kind":"agent"}}""", "{}"),
            // variant alert sconosciuta
            Triple("av", """{"kind":"alert","id":"a","variant":"hyper","text":"x"}""", "{}"),
            // fallback mancante
            Triple("fb", """{"kind":"text","id":"a","text":"x"}""", "{}")
        )
        for ((id, spec, state) in cases) {
            val fallback = if (id == "fb") "" else "riepilogo"
            assertNull("doveva fallire: $id/$spec", GenUiValidator.validateBlock(id, 1, fallback, state, spec))
        }
    }

    @Test
    fun `evil blocks rejected`() {
        val urlEvil = """{"kind":"column","id":"root","children":[{"kind":"image","id":"i","src":"javascript:alert(1)"}]}"""
        assertNull(GenUiValidator.validateBlock("e1", 1, "x", "{}", urlEvil))
        val fileEvil = """{"kind":"column","id":"root","children":[{"kind":"image","id":"i","src":"file:///etc/passwd"}]}"""
        assertNull(GenUiValidator.validateBlock("e2", 1, "x", "{}", fileEvil))
        val handlerEvil = """{"kind":"column","id":"root","onClick":"steal()","children":[]}"""
        assertNull(GenUiValidator.validateBlock("e3", 1, "x", "{}", handlerEvil))
        // URL buoni passano
        val good = """{"kind":"column","id":"root","children":[{"kind":"image","id":"i","src":"/v1/media/x.png"},{"kind":"image","id":"j","src":"https://example.com/a.png"}]}"""
        assertNotNull(GenUiValidator.validateBlock("g", 1, "x", "{}", good))
    }

    @Test
    fun `formula evaluates over state`() {
        val state = mapOf("amp" to GenUiValue.Num(2.0), "freq" to GenUiValue.Num(4.0), "c1" to GenUiValue.Bool(true), "c2" to GenUiValue.Bool(false))
        assertEquals(0.25, GenUiFormula.evalNumber("1 / freq", state), 1e-9)
        assertEquals(2.0, GenUiFormula.evalNumber("amp * sin(0) + amp", state), 1e-9)
        assertEquals(1.0, GenUiFormula.evalNumber("c1 + c2", state), 1e-9)
        assertEquals(3.0, GenUiFormula.evalNumber("clamp(amp + 5, 0, 3)", state), 1e-9)
        assertEquals(10.0, GenUiFormula.evalNumber("if(c1, 10, 20)", state), 1e-9)
        assertEquals("Passo 2 di 4.", renderGenUiText("Passo {{1 + 1}} di 4.", state))
        assertEquals("Completati 1 di 3.", renderGenUiText("Completati {{c1 + c2}} di 3.", state))
    }

    @Test
    fun `conditions evaluate`() {
        val state = mapOf("metric" to GenUiValue.Str("prefill"), "n" to GenUiValue.Num(3.0))
        assertTrue(evalGenUiCondition(JSONObject("""{"key":"metric","op":"eq","value":"prefill"}"""), state))
        assertTrue(evalGenUiCondition(JSONObject("""{"key":"n","op":"gte","value":3}"""), state))
        assertTrue(!evalGenUiCondition(JSONObject("""{"key":"n","op":"gt","value":3}"""), state))
        assertTrue(evalGenUiCondition(null, state))
    }

    @Test
    fun `agent event format`() {
        val payload = JSONObject().put("name", "clip2").put("n", 2)
        val event = buildGenUiEvent("ui-5", "render", payload)
        assertTrue(event.startsWith("[genui ui-5 render "))
        assertTrue(event.endsWith("]"))
        assertTrue(event.contains("\"name\":\"clip2\""))
    }

    @Test
    fun `initial state parses scalars only`() {
        val seed = GenUiValidator.initialState("""{"a":1.5,"b":"x","c":true}""")
        assertEquals(GenUiValue.Num(1.5), seed["a"])
        assertEquals(GenUiValue.Str("x"), seed["b"])
        assertEquals(GenUiValue.Bool(true), seed["c"])
    }

    @Test
    fun `full envelope extracts and same id update replaces`() {
        val v1 = com.nemoclaw.chat.extractVisualBlocks(
            """{"visual_blocks_version":1,"visual_blocks":[
              {"type":"genui","id":"ui-9","title":"Prima","genui_version":1,"fallback_text":"riassunto",
               "state":{"n":1},"spec":{"kind":"text","id":"t","text":"uno"}},
              {"type":"chart","id":"ch-1","chart_type":"bar","summary":"s","series":[{"name":"a","points":[{"x":"k","y":2}]}]}
            ]}""".trimIndent()
        )
        assertEquals(2, v1.size)
        assertEquals("genui", v1[0].type)
        val merged = com.nemoclaw.chat.dedupeVisualBlocks(
            v1,
            com.nemoclaw.chat.extractVisualBlocks(
                """{"visual_blocks_version":1,"visual_blocks":[
                  {"type":"genui","id":"ui-9","title":"Seconda","genui_version":1,"fallback_text":"riassunto",
                   "state":{"n":2},"spec":{"kind":"text","id":"t","text":"due"}}
                ]}""".trimIndent()
            )
        )
        assertEquals(2, merged.size)
        assertEquals("Seconda", merged.first { it.id == "ui-9" }.title)
    }

    @Test
    fun `series resolve formulas with x`() {
        val node = JSONObject(
            """{"kind":"chart","id":"c","chart_type":"line","series":[
              {"name":"w","points":[{"x":0,"y_formula":"amp * x"},{"x":2,"y_formula":"amp * x"},{"x":"k","y":5}]}
            ]}""".trimIndent()
        )
        val resolved = resolveGenUiSeries(node, mapOf("amp" to GenUiValue.Num(3.0)))
        assertEquals(1, resolved.size)
        assertEquals(0.0, resolved[0].points[0].y, 1e-9)
        assertEquals(6.0, resolved[0].points[1].y, 1e-9)
        assertEquals(5.0, resolved[0].points[2].y, 1e-9)
        assertEquals("k", resolved[0].points[2].xText)
    }

    @Test
    fun `local actions mutate state`() {        val ui = GenUiUiState("test-key", mapOf("n" to GenUiValue.Num(1.0), "b" to GenUiValue.Bool(false)))
        ui.applyLocal(JSONObject("""{"kind":"local","op":"step","key":"n","delta":2}"""))
        assertEquals(GenUiValue.Num(3.0), ui.snapshot()["n"])
        ui.applyLocal(JSONObject("""{"kind":"local","op":"toggle","key":"b"}"""))
        assertEquals(GenUiValue.Bool(true), ui.snapshot()["b"])
        ui.applyLocal(JSONObject("""{"kind":"local","op":"set","key":"n","value":9}"""))
        assertEquals(GenUiValue.Num(9.0), ui.snapshot()["n"])
        ui.applyLocal(JSONObject("""{"kind":"local","op":"reset"}"""))
        assertEquals(GenUiValue.Num(1.0), ui.snapshot()["n"])
        assertEquals(GenUiValue.Bool(false), ui.snapshot()["b"])
    }

    @Test
    fun `markdown marker becomes inline ref`() {
        val blocks = com.nemoclaw.chat.parseMarkdownBlocks("Ecco la simulazione:\n[genui:wave-1]\nEd ecco i dati.")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is com.nemoclaw.chat.MdBlock.GenUiRef)
        assertEquals("wave-1", (blocks[1] as com.nemoclaw.chat.MdBlock.GenUiRef).uiId)
    }

    @Test
    fun `markdown marker inside fence stays literal`() {
        val blocks = com.nemoclaw.chat.parseMarkdownBlocks("Esempio:\n```\n[genui:wave-1]\n```\nFine.")
        assertTrue(blocks.none { it is com.nemoclaw.chat.MdBlock.GenUiRef })
        val code = blocks.filterIsInstance<com.nemoclaw.chat.MdBlock.CodeBlock>().firstOrNull()
        assertNotNull(code)
        assertTrue(code!!.code.contains("[genui:wave-1]"))
    }

    @Test
    fun `markdown midline marker stays text`() {
        val blocks = com.nemoclaw.chat.parseMarkdownBlocks("vedi [genui:wave-1] qui")
        assertTrue(blocks.none { it is com.nemoclaw.chat.MdBlock.GenUiRef })
    }
}
