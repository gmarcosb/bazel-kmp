// Copyright 2023 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.AttributeValuesAdapter.STRING_ESCAPE_SEQUENCE

@RunWith(JUnit4::class)
class AttributeValuesAdapterTest : FoundationTestCase() {
    @org.junit.Test
    @Throws(IOException::class)
    fun testAttributeValuesAdapter() {
        val dict: net.starlark.java.eval.Dict.Builder<String?, Any?> =
            net.starlark.java.eval.Dict.Builder<String?, Any?>()
        val l1: Label? = Label.parseCanonicalUnchecked("@//foo:bar")
        val l2: Label? = Label.parseCanonicalUnchecked("@//foo:tar")
        dict.put("Integer", StarlarkInt.of(56))
        dict.put("Boolean", false)
        dict.put("String", "Hello String")
        dict.put("StringWithAngleBrackets", "<Hello>")
        dict.put(
            "LabelLikeString",
            StarlarkList.of<String?>(Mutability.IMMUTABLE, "@//foo:bar", ":baz", "@@//baz:quz")
        )
        dict.put(
            "StringsWithEscapeSequence",
            StarlarkList.of<String?>(
                Mutability.IMMUTABLE,
                "@@//foo:bar" + STRING_ESCAPE_SEQUENCE,
                STRING_ESCAPE_SEQUENCE + "@@//foo:bar",
                STRING_ESCAPE_SEQUENCE + "@@//foo:bar" + STRING_ESCAPE_SEQUENCE,
                ((STRING_ESCAPE_SEQUENCE
                        + STRING_ESCAPE_SEQUENCE
                        ).toString() + "@@//foo:bar"
                        + STRING_ESCAPE_SEQUENCE
                        + STRING_ESCAPE_SEQUENCE)
            )
        )
        dict.put("Label", l1)
        dict.put(
            "ListOfInts", StarlarkList.of<StarlarkInt?>(Mutability.IMMUTABLE, StarlarkInt.of(1), StarlarkInt.of(2))
        )
        dict.put("ListOfLabels", StarlarkList.of<Any?>(Mutability.IMMUTABLE, l1, l2))
        dict.put("ListOfStrings", StarlarkList.of<String?>(Mutability.IMMUTABLE, "Hello", "There!"))
        val dictLabelString: net.starlark.java.eval.Dict.Builder<Label?, String?> =
            net.starlark.java.eval.Dict.Builder<Label?, String?>()
        dictLabelString.put(l1, "Label#1")
        dictLabelString.put(l2, "Label#2")
        dict.put("DictOfLabel-String", dictLabelString.buildImmutable())

        val builtDict: Dict<String?, Any?> = dict.buildImmutable()
        val attrAdapter: AttributeValuesAdapter = AttributeValuesAdapter()
        val jsonString: String
        java.io.StringWriter().use { stringWriter ->
            attrAdapter.write(JsonWriter(stringWriter), AttributeValues.create(builtDict))
            jsonString = stringWriter.toString()
        }
        val attributeValues: AttributeValues
        java.io.StringReader(jsonString).use { stringReader ->
            attributeValues = attrAdapter.read(JsonReader(stringReader))
        }
        // Verify that the JSON string does not contain any escaped angle brackets.
        Truth.assertThat(jsonString).doesNotContain("\\u003c")
        // Verify that the String "Hello String" is preserved as is, without any additional escaping.
        Truth.assertThat(jsonString).contains(":\"Hello String\"")
        Truth.assertThat(attributeValues.attributes() as MutableMap<*, *>?).containsExactlyEntriesIn(builtDict)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTuple() {
        val dict: net.starlark.java.eval.Dict.Builder<String?, Any?> =
            net.starlark.java.eval.Dict.Builder<String?, Any?>()
        dict.put("Tuple", Tuple.of("bzl", "mod"))

        val builtDict: Dict<String?, Any?> = dict.buildImmutable()
        val attrAdapter: AttributeValuesAdapter = AttributeValuesAdapter()
        val jsonString: String
        java.io.StringWriter().use { stringWriter ->
            attrAdapter.write(JsonWriter(stringWriter), AttributeValues.create(builtDict))
            jsonString = stringWriter.toString()
        }
        val attributeValues: AttributeValues
        java.io.StringReader(jsonString).use { stringReader ->
            attributeValues = attrAdapter.read(JsonReader(stringReader))
        }
        Truth.assertThat(attributeValues.attributes() as MutableMap<*, *>?)
            .containsExactly("Tuple", StarlarkList.of<String?>(Mutability.IMMUTABLE, "bzl", "mod"))
    }
}
