package com.nemoclaw.chat

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract upload multi-file (test JVM, nessuna dipendenza Android).
 *
 * Sorgente mappata: ChatStream.kt:1272-1277
 * ```
 * val refs = attachments.map { uploadAttachmentForTool(...) }
 * val uploadedIndexes = refs.indices.filter { refs[it].error.isNullOrBlank() }.toSet()
 * val uploaded = refs.filterIndexed { index, _ -> index in uploadedIndexes }
 * val remaining = attachments.filterIndexed { index, _ -> index !in uploadedIndexes }
 * val errors = refs.mapNotNull { ref -> ref.error?.let { "${ref.filename}: $it" } }
 * if (uploaded.isEmpty()) return AttachmentPreparation(prompt, attachments, 0, errors)
 * ```
 * Invariante: un file fallito non elimina quelli validi; l'upload parziale procede
 * con i soli file riusciti e gli errori restano attribuiti per filename.
 */
class AttachmentPartialUploadContractTest {

    private data class FakeAttachment(val filename: String)

    private data class FakeUploadRef(val filename: String, val error: String?)

    private data class Partition(
        val uploaded: List<FakeUploadRef>,
        val remaining: List<FakeAttachment>,
        val errors: List<String>
    )

    /** Copia fedele della partizione di `buildPromptWithAttachmentToolRefs`. */
    private fun partitionUploads(
        attachments: List<FakeAttachment>,
        refs: List<FakeUploadRef>
    ): Partition {
        require(attachments.size == refs.size) { "attachments e refs devono essere allineati per indice" }
        val uploadedIndexes = refs.indices.filter { refs[it].error.isNullOrBlank() }.toSet()
        val uploaded = refs.filterIndexed { index, _ -> index in uploadedIndexes }
        val remaining = attachments.filterIndexed { index, _ -> index !in uploadedIndexes }
        val errors = refs.mapNotNull { ref -> ref.error?.let { "${ref.filename}: $it" } }
        return Partition(uploaded, remaining, errors)
    }

    @Test
    fun singleFailureKeepsValidUploadsAndProceedsPartial() {
        val attachments = listOf(
            FakeAttachment("a.jpg"),
            FakeAttachment("b.pdf"),
            FakeAttachment("c.png")
        )
        val refs = listOf(
            FakeUploadRef("a.jpg", null),
            FakeUploadRef("b.pdf", "HTTP 500: gateway"),
            FakeUploadRef("c.png", null)
        )

        val result = partitionUploads(attachments, refs)

        assertEquals(listOf("a.jpg", "c.png"), result.uploaded.map { it.filename })
        assertEquals(listOf("b.pdf"), result.remaining.map { it.filename })
        assertEquals(listOf("b.pdf: HTTP 500: gateway"), result.errors)
        // L'upload parziale procede: 2 file validi pronti per il prompt tool.
        assertEquals(2, result.uploaded.size)
    }

    @Test
    fun allFailuresKeepOriginalAttachmentsForInlineFallback() {
        val attachments = listOf(FakeAttachment("a.jpg"), FakeAttachment("b.pdf"))
        val refs = listOf(
            FakeUploadRef("a.jpg", "gateway non raggiungibile"),
            FakeUploadRef("b.pdf", "HTTP 401: rifiutata")
        )

        val result = partitionUploads(attachments, refs)

        assertTrue(result.uploaded.isEmpty())
        // Nessun upload riuscito: il chiamante riusa gli allegati originali inline.
        assertEquals(attachments, result.remaining)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors[0].startsWith("a.jpg: "))
        assertTrue(result.errors[1].startsWith("b.pdf: "))
    }

    @Test
    fun allSucceedClearsRemaining() {
        val attachments = listOf(FakeAttachment("a.jpg"), FakeAttachment("b.wav"))
        val refs = listOf(FakeUploadRef("a.jpg", null), FakeUploadRef("b.wav", null))

        val result = partitionUploads(attachments, refs)

        assertEquals(2, result.uploaded.size)
        assertTrue(result.remaining.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun emptyInputReturnsEmptyWithoutErrors() {
        val result = partitionUploads(emptyList(), emptyList())

        assertTrue(result.uploaded.isEmpty())
        assertTrue(result.remaining.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun failedFileDoesNotDeleteValidFilesOnDisk() {
        val directory = Files.createTempDirectory("hermes-attachment-test").toFile()
        try {
            val valid = Files.write(directory.toPath().resolve("ok.jpg"), byteArrayOf(1, 2, 3)).toFile()
            val failed = Files.write(directory.toPath().resolve("ko.pdf"), byteArrayOf(4, 5)).toFile()

            // Simula: "ko.pdf" fallisce in upload, "ok.jpg" riesce. Il cleanup del
            // fallito non deve toccare il file valido.
            val result = partitionUploads(
                listOf(FakeAttachment("ok.jpg"), FakeAttachment("ko.pdf")),
                listOf(FakeUploadRef("ok.jpg", null), FakeUploadRef("ko.pdf", "HTTP 500"))
            )
            failed.delete()

            assertEquals(listOf("ok.jpg"), result.uploaded.map { it.filename })
            assertTrue(valid.isFile)
            assertEquals(3L, valid.length())
        } finally {
            directory.deleteRecursively()
        }
    }
}
