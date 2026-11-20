package com.kineai.local

import com.kineai.local.code.DiffKind
import com.kineai.local.code.Edits
import com.kineai.local.code.LineDiff
import com.kineai.local.data.ChatMessage
import com.kineai.local.data.Conversation
import com.kineai.local.data.ConversationStore
import com.kineai.local.data.Paths
import com.kineai.local.models.DeviceBudget
import com.kineai.local.models.EngineCaps
import com.kineai.local.models.Fit
import com.kineai.local.models.classify
import com.kineai.local.models.kvBytes
import com.kineai.local.models.quantFromFileName
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private val caps = EngineCaps(
        setOf("llama", "qwen2", "qwen3"),
        mapOf("Q4K" to "neon", "Q6K" to "neon", "Q8_0" to "neon", "Q8" to "neon", "Q4_0" to "neon", "Q5K" to "integer", "IQ4XS" to "reference", "F16" to "float"),
        "none", "no gpu", JSONObject(),
    )
    private val gb = 1L shl 30
    private val phone = DeviceBudget(12 * gb, 8 * gb, 100 * gb)

    @Test fun `edit blocks are parsed and plain code is not an edit`() {
        val segs = Edits.parse("Sure.\n```edit src/a.py\nprint(1)\n```\nand\n```kotlin\nval x = 1\n```\n")
        val edits = segs.mapNotNull { it.edit }
        assertEquals(1, edits.size)
        assertEquals("src/a.py", edits[0].path)
        assertEquals("print(1)\n", edits[0].newContent)
        assertEquals(1, segs.count { it.isCode && it.edit == null && it.language == "kotlin" })
    }

    @Test fun `an unfinished edit block is never offered for approval`() {
        val segs = Edits.parse("```edit a.txt\nhalf a fi")
        assertTrue(segs.none { it.edit != null })
    }

    @Test fun `paths that escape the project are refused`() {
        val root = tmp.newFolder("proj")
        assertNull(Edits.resolveSafe(root, "../x"))
        assertNull(Edits.resolveSafe(root, "/etc/passwd"))
        assertNull(Edits.resolveSafe(root, "a/../../x"))
        assertNotNull(Edits.resolveSafe(root, "src/ok.kt"))
    }

    @Test fun `line diff marks added and removed lines`() {
        val d = LineDiff.diff(listOf("a", "b", "c"), listOf("a", "B", "c", "d"))!!
        assertEquals(listOf(DiffKind.SAME, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.SAME, DiffKind.ADDED), d.map { it.kind })
    }

    @Test fun `quantization is read from file names`() {
        assertEquals("Q4_K_M", quantFromFileName("qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"))
        assertEquals("Q8_0", quantFromFileName("SmolLM2-360M-Instruct-Q8_0.gguf"))
        assertEquals("IQ4_XS", quantFromFileName("model-IQ4_XS.gguf"))
        assertEquals("F16", quantFromFileName("model-f16.gguf"))
    }

    @Test fun `unknown architecture is unsupported and split files are unsupported`() {
        val a = classify("mamba3", "Q4_K_M", 2 * gb, 4096, null, null, false, false, caps, phone)
        assertEquals(Fit.UNSUPPORTED, a.fit)
        val b = classify("llama", "Q4_K_M", 2 * gb, 4096, null, null, true, false, caps, phone)
        assertEquals(Fit.UNSUPPORTED, b.fit)
    }

    @Test fun `small compatible model is compatible and reference-path quant is limited`() {
        assertEquals(Fit.COMPATIBLE, classify("qwen2", "Q4_K_M", 1 * gb, 4096, 28, 256, false, false, caps, phone).fit)
        assertEquals(Fit.LIMITED, classify("qwen2", "IQ4_XS", 1 * gb, 4096, 28, 256, false, false, caps, phone).fit)
    }

    @Test fun `not enough storage or memory is unsupported and kv uses f32`() {
        assertEquals(Fit.UNSUPPORTED, classify("llama", "Q4_K_M", 2 * gb, 4096, null, null, false, false, caps, phone.copy(freeStorageBytes = 1 * gb)).fit)
        assertEquals(Fit.UNSUPPORTED, classify("llama", "Q8_0", 11 * gb, 4096, null, null, false, false, caps, phone).fit)
        // 2 (K,V) * 28 layers * 4096 ctx * 256 kv dim * 4 bytes
        assertEquals(2L * 28 * 4096 * 256 * 4, kvBytes(4096, 28, 256, gb))
    }

    @Test fun `conversations persist and survive a backup round trip`() {
        val paths = Paths(tmp.newFolder("files"), tmp.newFolder("cache"))
        val store = ConversationStore(paths)
        val c = Conversation(title = "t", messages = listOf(ChatMessage("user", "hi"), ChatMessage("assistant", "partial", status = "interrupted")))
        store.save(c)
        assertEquals("interrupted", store.load(c.id)!!.messages.last().status)
        val zip = ByteArrayOutputStream().also { assertEquals(1, store.exportZip(it)) }.toByteArray()
        store.delete(c.id)
        assertNull(store.load(c.id))
        assertEquals(1, store.importZip(ByteArrayInputStream(zip)))
        assertEquals("partial", store.load(c.id)!!.messages.last().content)
        assertFalse(java.io.File(paths.models, "x").exists())
    }

    @Test fun `deleting the models directory does not touch conversations`() {
        val paths = Paths(tmp.newFolder("f2"), tmp.newFolder("c2"))
        val store = ConversationStore(paths)
        store.save(Conversation(title = "keep"))
        paths.models.deleteRecursively()
        assertEquals(1, store.list().size)
    }
}
