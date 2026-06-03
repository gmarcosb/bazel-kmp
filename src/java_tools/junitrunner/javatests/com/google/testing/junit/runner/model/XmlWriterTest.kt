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

import com.google.common.truth.Truth
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.model
import com.google.testing.junit.runner.model.XmlWriter.close
import com.google.testing.junit.runner.model.XmlWriter.endElement
import com.google.testing.junit.runner.model.XmlWriter.startDocument
import com.google.testing.junit.runner.model.XmlWriter.startElement
import com.google.testing.junit.runner.model.XmlWriter.writeAttribute
import com.google.testing.junit.runner.model.XmlWriter.writeCharacters
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.UnsupportedEncodingException

/**
 * Tests for [XmlWriter]
 */
@RunWith(JUnit4::class)
class XmlWriterTest {
    private val outputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

    @org.junit.Rule
    val expectedException: org.junit.rules.ExpectedException = org.junit.rules.ExpectedException.none()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun encodingShouldBeUtf8() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("String")
        val utf8String = "z\u0080\u0800\u010000" // 1+2+3+4 bytes
        xmlWriter.writeCharacters(utf8String)
        xmlWriter.close()

        // Note: assertHasContents() reads the bytes of the outputStream as a UTF-8 string
        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>", "<String>" + utf8String + "</String>"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun header() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.close()

        assertHasContents("<?xml version='1.0' encoding='UTF-8'?>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyDocument() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("DocumentName")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<DocumentName />"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyDocumentWithOneAttribute() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("Properties")
        xmlWriter.writeAttribute("name", "value")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<Properties name='value' />"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyDocumentWithTwoAttributes() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("TestSuite")
        xmlWriter.writeAttribute("count", 7)
        xmlWriter.writeAttribute("size", "large")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<TestSuite count='7' size='large' />"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyDocumentWithThreeAttributes() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("TestSuite")
        xmlWriter.writeAttribute("count", 7)
        xmlWriter.writeAttribute("size", "large")
        xmlWriter.writeAttribute("time", 1.0)
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<TestSuite count='7' size='large' time='1.0' />"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun documentWithOneEmptyElement() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("Root")
        xmlWriter.writeAttribute("childCount", 1)
        xmlWriter.startElement("Child")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>", "<Root childCount='1'>", "  <Child /></Root>"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun documentWithOneEmptyElementWithAttribute() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("Root")
        xmlWriter.startElement("Child")
        xmlWriter.writeAttribute("name", "value")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>", "<Root>", "  <Child name='value' /></Root>"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun documentWithOneElementWithCharactersNoEscaping() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("Root")
        xmlWriter.startElement("Child")
        xmlWriter.writeCharacters("some text\nmore text")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<Root>",
            "  <Child>some text",
            "more text</Child></Root>"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun documentWithOneElementWithCharactersNeedingEscaping() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("Root")
        xmlWriter.startElement("Child")
        xmlWriter.writeCharacters("foo]]>bar")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>", "<Root>", "  <Child>foo]]&gt;bar</Child></Root>"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun documentWithOneElementChild() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("Root")
        xmlWriter.startElement("Child")
        xmlWriter.writeAttribute("name", "value")
        xmlWriter.startElement("Grandchild")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<Root>",
            "  <Child name='value'>",
            "    <Grandchild /></Child></Root>"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun documentWithTwoElements() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("Parent")
        xmlWriter.startElement("Child")
        xmlWriter.writeAttribute("name", "Deanna")
        xmlWriter.endElement()
        xmlWriter.startElement("Child")
        xmlWriter.writeAttribute("name", "Kyle")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<Parent>",
            "  <Child name='Deanna' />",
            "  <Child name='Kyle' /></Parent>"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attributeValuesEscaped() {
        val xmlWriter: com.google.testing.junit.runner.model.XmlWriter =
            com.google.testing.junit.runner.model.XmlWriter(outputStream)
        xmlWriter.startDocument()
        xmlWriter.startElement("Expression")
        xmlWriter.writeAttribute("name", "a > b")
        xmlWriter.close()

        assertHasContents(
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<Expression name='a &gt; b' />"
        )
    }

    @Throws(UnsupportedEncodingException::class)
    private fun assertHasContents(vararg contents: String?) {
        val expected: Array<Any?> = contents

        Truth.assertThat(outputStream.toString("UTF-8").trim { it <= ' ' })
            .isEqualTo(LINE_JOINER.join(expected).trim { it <= ' ' })
    }

    companion object {
        private val LINE_JOINER: com.google.common.base.Joiner =
            com.google.common.base.Joiner.on(com.google.testing.junit.runner.model.XmlWriter.EOL)
    }
}
