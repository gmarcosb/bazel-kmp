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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.util.OptionsTestCase
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A test for [CoreOptions].  */
@RunWith(JUnit4::class)
class CoreOptionsTest : OptionsTestCase<CoreOptions>() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatures_orderingOfPositiveFeatures() {
        val one: CoreOptions = createWithPrefix(FEATURES_PREFIX, "foo", "bar")
        val two: CoreOptions = createWithPrefix(FEATURES_PREFIX, "bar", "foo")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatures_duplicateFeatures() {
        val one: CoreOptions = createWithPrefix(FEATURES_PREFIX, "foo", "bar")
        val two: CoreOptions = createWithPrefix(FEATURES_PREFIX, "bar", "foo", "bar")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatures_disablingWins() {
        val one: CoreOptions = createWithPrefix(FEATURES_PREFIX, "foo", "-foo", "bar")
        val two: CoreOptions = createWithPrefix(FEATURES_PREFIX, "-foo", "bar")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefines_duplicateKey() {
        // Last one should win
        val one: CoreOptions = createWithPrefix(DEFINE_PREFIX, "a=1", "a=2")
        val two: CoreOptions = createWithPrefix(DEFINE_PREFIX, "a=2")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefines_orderOfKeys() {
        val one: CoreOptions = createWithPrefix(DEFINE_PREFIX, "a=1", "c=3", "b=2")
        val two: CoreOptions = createWithPrefix(DEFINE_PREFIX, "b=2", "a=1", "c=3")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testflagAlias_duplicateKey() {
        // Last one should win
        val one: CoreOptions = createWithPrefix(FLAG_ALIAS_PREFIX, "a=//one", "a=//two")
        val two: CoreOptions = createWithPrefix(FLAG_ALIAS_PREFIX, "a=//two")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagAlias_orderOfKeys() {
        val one: CoreOptions = createWithPrefix(FLAG_ALIAS_PREFIX, "a=//one", "c=//three", "b=//two")
        val two: CoreOptions = createWithPrefix(FLAG_ALIAS_PREFIX, "b=//two", "a=//one", "c=//three")
        assertSame(one, two)
    }

    override fun getOptionsClass(): java.lang.Class<CoreOptions?> {
        return CoreOptions::class.java
    }

    companion object {
        private const val FEATURES_PREFIX = "--features="
        private const val DEFINE_PREFIX = "--define="
        private const val FLAG_ALIAS_PREFIX = "--flag_alias="
    }
}
