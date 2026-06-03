// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.docgen

import com.google.testing.junit.runner.model.XmlWriter.close
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.exception.MethodInvocationException
import org.apache.velocity.exception.ParseErrorException
import org.apache.velocity.exception.ResourceNotFoundException
import java.io.IOException

/**
 * Class that represents a page to be generated using the [TemplateEngine].
 */
internal class Page(engine: VelocityEngine, template: String?) {
    private val engine: VelocityEngine
    private val context: VelocityContext
    private val template: String?

    /**
     * Creates a new Page instance using the reference to the VelocityEngine and the .vm
     * template file path.
     */
    init {
        this.engine = engine
        this.template = template
        this.context = VelocityContext()
    }

    /**
     * Sets a Velocity variable in the template with the given value.
     */
    fun add(`var`: String?, value: Any?) {
        context.put(`var`, value)
    }

    /**
     * Renders the template and writes the output to the given file.
     * 
     * Strips all trailing whitespace before writing to file.
     */
    @Throws(IOException::class)
    fun write(outputFile: java.io.File) {
        val stringWriter: java.io.StringWriter = java.io.StringWriter()
        try {
            engine.mergeTemplate(template, "UTF-8", context, stringWriter)
        } catch (e: ResourceNotFoundException) {
            throw IOException(e)
        } catch (e: ParseErrorException) {
            throw IOException(e)
        } catch (e: MethodInvocationException) {
            throw IOException(e)
        }
        stringWriter.close()

        val lines: Array<String> =
            stringWriter.toString().split(java.lang.System.getProperty("line.separator").toRegex())
                .dropLastWhile { it.isEmpty() }.toTypedArray()
        java.nio.file.Files.newBufferedWriter(outputFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)
            .use { fileWriter ->
                for (line in lines) {
                    // Strip trailing whitespace then append newline before writing to file.
                    fileWriter.write(line.replaceFirst("\\s+$".toRegex(), "") + "\n")
                }
            }
    }
}
