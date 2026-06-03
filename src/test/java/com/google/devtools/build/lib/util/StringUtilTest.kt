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
package com.google.devtools.build.lib.util

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A test for [StringUtil].  */
@RunWith(JUnit4::class)
class StringUtilTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJoinEnglishList() {
        Truth.assertThat(com.google.devtools.build.lib.util.StringUtil.joinEnglishList(com.google.common.collect.ImmutableList.of<Any?>()))
            .isEqualTo("nothing")
        Truth.assertThat(com.google.devtools.build.lib.util.StringUtil.joinEnglishList(mutableListOf<String?>("one")))
            .isEqualTo("one")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishList(
                mutableListOf<String?>(
                    "one",
                    "two"
                )
            )
        ).isEqualTo("one or two")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishList(
                mutableListOf<String?>(
                    "one",
                    "two"
                ), "and"
            )
        ).isEqualTo("one and two")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishList(
                mutableListOf<String?>(
                    "one",
                    "two",
                    "three"
                )
            )
        )
            .isEqualTo("one, two, or three")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishList(
                mutableListOf<String?>(
                    "one",
                    "two",
                    "three"
                ), "and"
            )
        )
            .isEqualTo("one, two, and three")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishList(
                mutableListOf<String?>(
                    "one",
                    "two",
                    "three"
                ), "or even", "\"", true
            )
        )
            .isEqualTo("\"one\", \"two\", or even \"three\"")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishList(
                mutableListOf<String?>(
                    "one",
                    "two",
                    "three"
                ), "then", "'", false
            )
        )
            .isEqualTo("'one', 'two' then 'three'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJoinEnglishListSingleQuoted() {
        Truth.assertThat(com.google.devtools.build.lib.util.StringUtil.joinEnglishListSingleQuoted(com.google.common.collect.ImmutableList.of<Any?>()))
            .isEqualTo("nothing")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishListSingleQuoted(
                mutableListOf<String?>(
                    "one"
                )
            )
        ).isEqualTo("'one'")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishListSingleQuoted(
                mutableListOf<String?>(
                    "one",
                    "two"
                )
            )
        )
            .isEqualTo("'one' or 'two'")
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.joinEnglishListSingleQuoted(
                mutableListOf<String?>(
                    "one",
                    "two",
                    "three"
                )
            )
        )
            .isEqualTo("'one', 'two', or 'three'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun listItemsWithLimit() {
        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.listItemsWithLimit(
                java.lang.StringBuilder("begin/"), 3, com.google.common.collect.ImmutableList.of<String?>("a", "b", "c")
            )
                .append("/end")
                .toString()
        )
            .isEqualTo("begin/a, b, c/end")

        Truth.assertThat(
            com.google.devtools.build.lib.util.StringUtil.listItemsWithLimit(
                java.lang.StringBuilder("begin/"),
                3,
                com.google.common.collect.ImmutableList.of<String?>("a", "b", "c", "d", "e")
            )
                .append("/end")
                .toString()
        )
            .isEqualTo("begin/a, b, c ...(omitting 2 more item(s))/end")
    }
}
