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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [LabelRenderer].  */
@RunWith(JUnit4::class)
class LabelRendererTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultRenderer() {
        val mainRepoLabel: Label? = Label.parseCanonicalUnchecked("//foo:bar")
        val depRepoLabel: Label? = Label.parseCanonicalUnchecked("@dep//foo:baz")

        assertThat(LabelRenderer.DEFAULT.render(mainRepoLabel)).isEqualTo("//foo:bar")
        assertThat(LabelRenderer.DEFAULT.reprWithoutLabelConstructor(mainRepoLabel))
            .isEqualTo("\"//foo:bar\"")
        assertThat(LabelRenderer.DEFAULT.repr(mainRepoLabel)).isEqualTo("Label(\"//foo:bar\")")

        assertThat(LabelRenderer.DEFAULT.render(depRepoLabel)).isEqualTo("@@dep//foo:baz")
        assertThat(LabelRenderer.DEFAULT.reprWithoutLabelConstructor(depRepoLabel))
            .isEqualTo("\"@@dep//foo:baz\"")
        assertThat(LabelRenderer.DEFAULT.repr(depRepoLabel)).isEqualTo("Label(\"@@dep//foo:baz\")")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mainRepoLabel_withoutMainRepoName() {
        val label: Label = Label.parseCanonicalUnchecked("//foo:bar")
        val shorthandLabel: Label = Label.parseCanonicalUnchecked("//foo")
        val dict: Any? = Dict.immutableCopyOf(com.google.common.collect.ImmutableMap.of<K?, V?>(label, shorthandLabel))

        val repositoryMapping: RepositoryMapping? = RepositoryMapping.EMPTY
        val labelRenderer: LabelRenderer = LabelRenderer(repositoryMapping, java.util.Optional.empty<T?>())

        assertThat(labelRenderer.render(label)).isEqualTo("//foo:bar")
        assertThat(labelRenderer.render(shorthandLabel)).isEqualTo("//foo")

        assertThat(labelRenderer.reprWithoutLabelConstructor(dict))
            .isEqualTo("{\"//foo:bar\": \"//foo\"}")
        assertThat(labelRenderer.repr(dict)).isEqualTo("{Label(\"//foo:bar\"): Label(\"//foo:foo\")}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mainRepoLabel_withMainRepoName() {
        val label: Label? = Label.parseCanonicalUnchecked("//foo:bar")
        val shorthandLabel: Label? = Label.parseCanonicalUnchecked("//foo")
        val ultraShorthandLabel: Label? = Label.parseCanonicalUnchecked("//:my_main")
        val list: Any? =
            StarlarkList.immutableCopyOf(
                com.google.common.collect.ImmutableList.of<E?>(
                    label,
                    shorthandLabel,
                    ultraShorthandLabel
                )
            )

        val repositoryMapping: RepositoryMapping? = RepositoryMapping.EMPTY
        val labelRenderer: LabelRenderer = LabelRenderer(repositoryMapping, java.util.Optional.of<T?>("my_main"))

        assertThat(labelRenderer.render(label)).isEqualTo("@my_main//foo:bar")
        assertThat(labelRenderer.render(shorthandLabel)).isEqualTo("@my_main//foo")
        assertThat(labelRenderer.render(ultraShorthandLabel)).isEqualTo("@my_main")

        assertThat(labelRenderer.reprWithoutLabelConstructor(list))
            .isEqualTo("[\"@my_main//foo:bar\", \"@my_main//foo\", \"@my_main\"]")
        assertThat(labelRenderer.repr(list))
            .isEqualTo(
                "[Label(\"@my_main//foo:bar\"), Label(\"@my_main//foo:foo\"),"
                        + " Label(\"@my_main//:my_main\")]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remappedRepoLabel() {
        val label: Label? = Label.parseCanonicalUnchecked("@canonical//foo:bar")
        val shorthandLabel: Label? = Label.parseCanonicalUnchecked("@canonical//foo")
        val list: Any? =
            StarlarkList.immutableCopyOf(com.google.common.collect.ImmutableList.of<E?>(label, shorthandLabel))

        val repositoryMapping: RepositoryMapping? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("local", RepositoryName.create("canonical")),
                RepositoryName.MAIN
            )
        val labelRenderer: LabelRenderer = LabelRenderer(repositoryMapping, java.util.Optional.of<T?>("my_main"))

        assertThat(labelRenderer.render(label)).isEqualTo("@local//foo:bar")
        assertThat(labelRenderer.render(shorthandLabel)).isEqualTo("@local//foo")

        assertThat(labelRenderer.reprWithoutLabelConstructor(list))
            .isEqualTo("[\"@local//foo:bar\", \"@local//foo\"]")
        assertThat(labelRenderer.repr(list))
            .isEqualTo("[Label(\"@local//foo:bar\"), Label(\"@local//foo:foo\")]")
    }
}
