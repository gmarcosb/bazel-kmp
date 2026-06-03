// Copyright 2018 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionContext

/** Unit tests for [ModuleActionContextRegistry].  */
@RunWith(JUnit4::class)
class ModuleActionContextRegistryTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegistration() {
        val context = AC2()
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder().register(IT1::class.java, context).build()
        assertThat(contextRegistry.getContext(IT1::class.java)).isEqualTo(context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleRegistration() {
        val context = AC2()
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder()
                .register(IT1::class.java, context)
                .register(IT1::class.java, context)
                .build()
        assertThat(contextRegistry.getContext(IT1::class.java)).isEqualTo(context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLastRegisteredHasPriority() {
        val context1 = AC2()
        val context2 = AC2()
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder()
                .register(IT1::class.java, context1)
                .register(IT1::class.java, context2)
                .build()
        assertThat(contextRegistry.getContext(IT1::class.java)).isEqualTo(context2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelfIdentifyingType() {
        val context = AC1()
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder().register(AC1::class.java, context).build()
        assertThat(contextRegistry.getContext(AC1::class.java)).isEqualTo(context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIdentifierFilter() {
        val general = AC2()
        val specific = AC2()
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder()
                .register(IT1::class.java, general)
                .register(IT1::class.java, specific, "specific", "foo")
                .register(IT1::class.java, general)
                .restrictTo(IT1::class.java, "specific")
                .build()
        assertThat(contextRegistry.getContext(IT1::class.java)).isEqualTo(specific)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLastRegisteredHasPriorityWithIdentifier() {
        val context1 = AC2()
        val context2 = AC2()
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder()
                .register(IT1::class.java, context1, "foo")
                .register(IT1::class.java, context2, "foo")
                .restrictTo(IT1::class.java, "foo")
                .build()
        assertThat(contextRegistry.getContext(IT1::class.java)).isEqualTo(context2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUsedNotification() {
        val context = RecordingContext()
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder()
                .register(RecordingContext::class.java, context)
                .register(RecordingContext::class.java, context)
                .build()

        contextRegistry.notifyUsed()

        Truth.assertThat(context.usedCalls).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyRestriction() {
        val general = AC2()
        val specific = AC2()
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder()
                .register(IT1::class.java, general)
                .register(IT1::class.java, specific, "specific", "foo")
                .register(IT1::class.java, general)
                .restrictTo(IT1::class.java, "specific")
                .restrictTo(IT1::class.java, "")
                .build()
        assertThat(contextRegistry.getContext(IT1::class.java)).isEqualTo(general)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoMatch() {
        val contextRegistry: ModuleActionContextRegistry =
            ModuleActionContextRegistry.builder().register(AC1::class.java, AC1()).build()

        assertThat(contextRegistry.getContext(IT1::class.java)).isNull()
    }

    @org.junit.Test
    fun testUnfulfilledRestriction() {
        val context1 = AC2()
        val context2 = AC2()
        val builder: ModuleActionContextRegistry.Builder =
            ModuleActionContextRegistry.builder()
                .register(IT1::class.java, context1, "foo")
                .register(IT1::class.java, context2, "baz", "boz")
                .restrictTo(IT1::class.java, "bar")

        val exception: AbruptExitException? =
            org.junit.Assert.assertThrows<T?>(AbruptExitException::class.java, builder::build)
        assertThat(exception).hasMessageThat().containsMatch("IT1.*bar.*[foo, baz, boz]")
    }

    private class AC1 : ActionContext

    private interface IT1 : ActionContext

    private class AC2 : IT1

    private class RecordingContext : ActionContext {
        private var usedCalls = 0

        public override fun usedContext(actionContextRegistry: ActionContext.ActionContextRegistry?) {
            usedCalls++
        }
    }
}
