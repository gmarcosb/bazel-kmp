// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler.memory

import com.google.common.base.Joiner
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.packages.RuleClass
import net.starlark.java.syntax.FileOptions
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.SyntaxError
import net.starlark.java.syntax.TokenKind
import org.junit.After
import org.junit.Test
import kotlin.collections.ArrayList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/** Tests for [AllocationTracker].  */
@RunWith(JUnit4::class)
class AllocationTrackerTest {
    // These tests are quite artificial as they call sampleAllocation explicitly.
    // In reality, a call could occur after any 'new' operation.
    private var tracker: AllocationTracker? = null
    private val live = ArrayList<Any?>()

    // A Starlark value whose plus operator "x + 123" simulates allocation of 123 bytes.
    // (We trigger allocation with an operator not a function call so as not to change the stack.)
    private inner class SamplerValue : HasBinary {
        @Throws(EvalException::class)
        public override fun binaryOp(op: TokenKind?, that: Any?, thisLeft: Boolean): Any? {
            if (op == TokenKind.PLUS && thisLeft && that is StarlarkInt) {
                val size: Int = that.toIntUnchecked() // test values are small
                val obj = Any()
                live.add(obj) // ensure that obj outlives the test assertions
                tracker.sampleAllocation(1, "", obj, size)
                return Starlark.NONE
            }
            return null
        }
    }

    @Before
    fun setup() {
        CurrentRuleTracker.setEnabled(true)
        tracker = AllocationTracker(1, 0)
        Debug.setThreadHook(tracker)
    }

    @After
    fun tearDown() {
        Debug.setThreadHook(null)
        CurrentRuleTracker.setEnabled(false)
    }

    @Test
    @Throws(Exception::class)
    fun testMemoryProfileDuringExecution() {
        // The nop() calls force the frame PC location to be updated.
        // It is not updated for a + operation on the assumption that
        // the stack is unobservable to an implementation of the +
        // operator... but the AllocationTracker sneaks a peek at it
        // using thread-local storage.
        // TODO(b/149023294): update this when we use a compiled representation.
        exec(
            "def nop(): pass",
            "def g():",
            "  nop(); sample + 12",  // sample[0]: 12 bytes
            "def f():",
            "  g()",
            "  nop(); sample + 73",  // sample[1]: 73 bytes
            "f()"
        )

        val rules: MutableMap<String?, RuleBytes?> = HashMap<String?, RuleBytes?>()
        val aspects: MutableMap<String?, RuleBytes?> = HashMap<String?, RuleBytes?>()
        tracker.getRuleMemoryConsumption(rules, aspects)
        Truth.assertThat(rules).isEmpty()
        Truth.assertThat(aspects).isEmpty()

        val profile: Profile = tracker.buildMemoryProfile()
        assertThat(profile.getSampleList()).hasSize(2)
        val lines: MutableSet<String?> = HashSet<String?>()
        for (s in profile.getSampleList()) {
            lines.add(sampleToCallstack(profile, s))
        }
        Truth.assertThat(lines).contains("a.star:f:6, a.star:<toplevel>:7")
        Truth.assertThat(lines).contains("a.star:g:3, a.star:f:5, a.star:<toplevel>:7")
    }

    @Test
    @Throws(Exception::class)
    fun testConfiguredTargetsMemoryAllocation() {
        CurrentRuleTracker.beginConfiguredTarget(myRuleClass())
        val ruleAllocation0 = Any()
        val ruleAllocation1 = Any()
        tracker.sampleAllocation(1, "", ruleAllocation0, 10)
        tracker.sampleAllocation(1, "", ruleAllocation1, 20)
        CurrentRuleTracker.endConfiguredTarget()

        CurrentRuleTracker.beginConfiguredAspect({ "aspect" })
        val aspectAllocation = Any()
        tracker.sampleAllocation(1, "", aspectAllocation, 12)
        CurrentRuleTracker.endConfiguredAspect()

        val rules: MutableMap<String?, RuleBytes?> = HashMap<String?, RuleBytes?>()
        val aspects: MutableMap<String?, RuleBytes?> = HashMap<String?, RuleBytes?>()
        tracker.getRuleMemoryConsumption(rules, aspects)
        Truth.assertThat(rules).containsExactly("myrule", RuleBytes("myrule").addBytes(30L))
        Truth.assertThat(aspects).containsExactly("aspect", RuleBytes("aspect").addBytes(12L))

        val profile: Profile = tracker.buildMemoryProfile()
        assertThat(profile.getSampleList()).isEmpty() // no callstacks
    }

    @Test
    @Throws(Exception::class)
    fun testLoadingPhaseRuleAllocations() {
        exec(
            "def g():",  //
            "  myrule()",
            "def f():",
            "  g()",
            "f()"
        )
        val rules: MutableMap<String?, RuleBytes?> = HashMap<String?, RuleBytes?>()
        val aspects: MutableMap<String?, RuleBytes?> = HashMap<String?, RuleBytes?>()
        tracker.getRuleMemoryConsumption(rules, aspects)
        Truth.assertThat(rules).containsExactly("myrule", RuleBytes("myrule").addBytes(128L))
    }

    @Throws(SyntaxError.Exception::class, EvalException::class, InterruptedException::class)
    private fun exec(vararg lines: String?) {
        val input: ParserInput? = ParserInput.fromString(Joiner.on("\n").join(lines), "a.star")
        val module: Module? =
            Module.withPredeclared(
                StarlarkSemantics.DEFAULT,
                ImmutableMap.of<K?, V?>(
                    "sample", SamplerValue(),
                    "myrule", MyRuleFunction()
                )
            )
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(input, FileOptions.DEFAULT, module, thread)
        }
    }

    // A fake Bazel rule. The allocation tracker reports retained memory broken down by rule class.
    private inner class MyRuleFunction : RuleFunction, StarlarkCallable {
        public override fun call(thread: StarlarkThread?, args: Tuple?, kwargs: Dict<String?, Any?>?): Any {
            val obj = Any()
            live.add(obj) // ensure that obj outlives the test assertions
            tracker.sampleAllocation(1, "", obj, 128)
            return Starlark.NONE
        }

        val name: String
            get() = "myrule"

        val ruleClass: RuleClass
            get() = myRuleClass()
    }

    companion object {
        private fun myRuleClass(): RuleClass {
            val myrule: RuleClass = Mockito.mock<RuleClass>(RuleClass::class.java)
            Mockito.`when`<T?>(myrule.getName()).thenReturn("myrule")
            Mockito.`when`<T?>(myrule.getKey()).thenReturn("myrule")
            return myrule
        }

        /** Formats a call stack as a comma-separated list of file:function:line elements.  */
        private fun sampleToCallstack(profile: Profile, sample: Sample): String {
            val buf = StringBuilder()
            for (locationId in sample.getLocationIdList()) {
                val location: com.google.perftools.profiles.ProfileProto.Location =
                    profile.getLocation(locationId.toInt() - 1)
                assertThat(location.getLineList()).hasSize(1)
                val functionId: Long = location.getLine(0).getFunctionId()
                val line: Long = location.getLine(0).getLine()
                val function: Function = profile.getFunction(functionId.toInt() - 1)
                val fileId: Long = function.getFilename()
                val methodId: Long = function.getName()
                val file: String? = profile.getStringTable(fileId.toInt())
                val method: String? = profile.getStringTable(methodId.toInt())
                if (buf.length > 0) {
                    buf.append(", ")
                }
                buf.append(String.format("%s:%s:%d", file, method, line))
            }
            return buf.toString()
        }
    }
}
