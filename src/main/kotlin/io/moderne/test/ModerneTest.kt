package io.moderne.test

import io.moderne.serialization.TreeSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.SourceFile
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException

interface ModerneTest {
    val recipe: Recipe?
        get() = null

    val executionContext: ExecutionContext
        get() = InMemoryExecutionContext { t: Throwable -> fail<Any>("Failed to run parse sources or recipe", t) }

    @Suppress("UNCHECKED_CAST")
    fun assertChanged(
        recipe: Recipe = this.recipe!!,
        moderneAstLink: String,
        moderneApiBearerToken: String = apiTokenFromUserHome(),
        after: String,
        afterConditions: (SourceFile) -> Unit = { }
    ) {
        val treeSerializer = TreeSerializer()
        val httpClient = OkHttpClient.Builder().build()
        try {
            val request = Request.Builder()
                .url(moderneAstLink)
                .header("Authorization", moderneApiBearerToken)
                .build()

            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Unexpected status $response" }
                val source = treeSerializer.read("test", response.peekBody(Long.MAX_VALUE).byteStream())

                var results = recipe.run(source, executionContext)
                results = results.filter { it.before == source }

                if (results.isEmpty()) {
                    fail<Any>("The recipe must make changes")
                }

                val result = results.first()

                assertThat(result).`as`("The recipe must make changes").isNotNull
                assertThat(result!!.after).isNotNull
                assertThat(result.after!!.printAll())
                    .isEqualTo(after.trimIndentPreserveCRLF())
                afterConditions(result.after!!)
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun assertUnchanged(
        recipe: Recipe = this.recipe!!,
        moderneAstLink: String,
        moderneApiBearerToken: String = apiTokenFromUserHome()
    ) {
        val treeSerializer = TreeSerializer()
        val httpClient = OkHttpClient.Builder().build()
        try {
            val request = Request.Builder()
                .url(moderneAstLink)
                .header("Authorization", moderneApiBearerToken)
                .build()

            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Unexpected status $response" }
                val source = treeSerializer.read("test", response.peekBody(Long.MAX_VALUE).byteStream())
                val results = recipe.run(source, executionContext)
                results.forEach { result ->
                    if (result.diff().isEmpty()) {
                        fail<Any>("An empty diff was generated. The recipe incorrectly changed a reference without changing its contents.")
                    }
                }

                for (result in results) {
                    assertThat(result.after?.printAll())
                        .`as`("The recipe must not make changes")
                        .isEqualTo(result.before?.printAll())
                }
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun apiTokenFromUserHome(): String {
        val tokenFile = File(System.getProperty("user.home") + "/.moderne/token.txt")
        if (!tokenFile.exists()) {
            throw IllegalStateException("No token file was not found at ~/.moderne/token.txt")
        }
        val token = tokenFile.readText().trim()
        return if (token.startsWith("Bearer ")) token else "Bearer $token"
    }

    private fun String.trimIndentPreserveCRLF() = replace('\r', '⏎').trimIndent().replace('⏎', '\r')
}
