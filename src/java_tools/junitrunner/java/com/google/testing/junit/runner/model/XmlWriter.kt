// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.model

import com.google.testing.junit.runner.util.XmlEscapers
import java.io.OutputStream
import java.io.StringWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Writer for XML documents. We do not use third-party code, because all
 * java_test rules have the test runner in their run-time classpath.
 */
class XmlWriter private constructor(private val writer: Writer) {
    private var started = false
    private var inElement = false
    private val elementStack: MutableList<String?> = ArrayList<String?>()

    /**
     * Creates an XML writer that writes to the given `OutputStream`.
     * 
     * @param outputStream stream to write to
     */
    constructor(outputStream: OutputStream) : this(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))

    /**
     * Starts the XML document.
     * 
     * @throws IOException if the underlying writer throws an exception
     */
    @Throws(IOException::class)
    fun startDocument() {
        check(!started) { "already started" }

        started = true
        val out = writer
        out.write("<?xml version='1.0' encoding='UTF-8'?>")
    }

    /**
     * Completes the XML document and closes the underlying writer.
     * 
     * @throws IOException if the underlying writer throws an exception
     */
    @Throws(IOException::class)
    fun close() {
        while (!elementStack.isEmpty()) {
            endElement()
        }
        writer.append(EOL)
        writer.close()
    }

    @Throws(IOException::class)
    private fun closeElement() {
        if (inElement) {
            writer.append('>')
            inElement = false
        }
    }

    private fun indentation(): String {
        val stackSize = elementStack.size
        val ident = StringBuilder(2 * stackSize)
        for (i in 0..<stackSize) {
            ident.append("  ")
        }
        return ident.toString()
    }

    /**
     * Starts an XML element. The element is left open until either
     * [.endElement] or [.close] are called. This method may be
     * called multiple times before calling [.endElement]; the writer
     * keeps a stack of currently open elements.
     * 
     * @param elementName name of the element (must be XML safe or escaped)
     * @throws IOException if the underlying writer throws an exception
     */
    @Throws(IOException::class)
    fun startElement(elementName: String?) {
        check(started)
        closeElement()
        inElement = true
        writer.append(EOL + indentation() + "<" + elementName)
        elementStack.add(elementName)
    }

    /**
     * Ends the current XML element.
     * 
     * @throws IOException if the underlying writer throws an exception
     */
    @Throws(IOException::class)
    fun endElement() {
        val elementName = elementStack.removeAt(elementStack.size - 1)
        if (inElement) {
            writer.write(" />")
            inElement = false
        } else {
            /*
       * We'd like to add a newline and indentation here, but that makes them part of the element
       * content, and that might be significant in test outputs, especially those that contain
       * actual and expected values.
       */
            writer.write("</")
            writer.write(elementName)
            writer.write('>'.code)
        }
    }

    /**
     * Writes an attribute with the given integer value to the currently open XML
     * element.
     * 
     * @param name attribute name
     * @param value attribute value
     * @throws IOException
     */
    @Throws(IOException::class)
    fun writeAttribute(name: String?, value: Int) {
        writeAttributeWithoutEscaping(name, value.toString())
    }

    /**
     * Writes an attribute with the given double value to the currently open XML
     * element.
     * 
     * @param name attribute name
     * @param value attribute value (must be XML safe or escaped)
     * @throws IOException
     */
    @Throws(IOException::class)
    fun writeAttribute(name: String?, value: Double) {
        writeAttributeWithoutEscaping(name, value.toString())
    }

    /**
     * Writes an attribute to the currently open XML element.
     * 
     * @param name attribute name (must be XML safe or escaped)
     * @param value attribute value (will be escaped by this method)
     * @throws IOException
     */
    @Throws(IOException::class)
    fun writeAttribute(name: String?, value: String?) {
        var value = value
        if (value != null) {
            value = XmlEscapers.xmlAttributeEscaper().escape(value)
        }
        writeAttributeWithoutEscaping(name, value)
    }

    @Throws(IOException::class)
    private fun writeAttributeWithoutEscaping(name: String?, value: String?) {
        writer.write(" " + name + "='")
        if (value != null) {
            writer.write(value)
        }
        writer.write("'")
    }

    /**
     * Writes the given characters as the content of the element. Closes the
     * element if it is currently open.
     * 
     * @param text String to append to the current content of the element
     * @throws IOException
     */
    @Throws(IOException::class)
    fun writeCharacters(text: String?) {
        closeElement()
        if (text == null || text.isEmpty()) {
            return
        }
        writer.write(XmlEscapers.xmlContentEscaper().escape(text))
    }

    /**
     * Gets the writer that this object uses for writing.
     * 
     * VisibleForTesting
     */
    fun getUnderlyingWriter(): Writer {
        return writer
    }

    companion object {
        // VisibleForTesting
        val EOL: String? = System.getProperty("line.separator", "\n")

        /**
         * Creates an XML writer for testing purposes. Note that if you decide to
         * serialize the `StringWriter` (to disk or network) encode it in `UTF-8`.
         * 
         * VisibleForTesting
         * 
         * @param writer
         */
        fun createForTesting(writer: StringWriter): XmlWriter {
            return XmlWriter(writer)
        }
    }
}
