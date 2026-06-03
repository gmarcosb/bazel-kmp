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
package net.starlark.java.eval

import net.starlark.java.eval.Mutability.Freezable

/** Tests for [Mutability].  */
@RunWith(JUnit4::class)
class MutabilityTest {
    /** A trivial Freezable that can do nothing but freeze.  */
    private class DummyFreezable(mutability: Mutability?) : Freezable {
        private val mutability: Mutability?

        init {
            this.mutability = mutability
        }

        public override fun mutability(): Mutability? {
            return mutability
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun freeze() {
        val mutability: Mutability = Mutability.create("test")
        val dummy = DummyFreezable(mutability)

        Starlark.checkMutable(dummy)
        mutability.freeze()
        assertCheckMutableFailsBecauseFrozen(dummy)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryWithResources() {
        val dummy: DummyFreezable?
        Mutability.create("test").use { mutability ->
            dummy = DummyFreezable(mutability)
            Starlark.checkMutable(dummy)
        }
        assertCheckMutableFailsBecauseFrozen(dummy)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initiallyMutable() {
        val mutability: Mutability? = Mutability.create("test")
        val dummy = DummyFreezable(mutability)

        Starlark.checkMutable(dummy)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun temporarilyImmutableDuringIteration() {
        val mutability: Mutability? = Mutability.create("test")
        val x = DummyFreezable(mutability)
        x.updateIteratorCount(+1)
        var ex: EvalException? = org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { Starlark.checkMutable(x) })
        assertThat(ex)
            .hasMessageThat()
            .contains("DummyFreezable value is temporarily immutable due to active for-loop iteration")

        x.updateIteratorCount(+1)
        x.updateIteratorCount(-1) // net +1 => still immutable
        ex = org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { Starlark.checkMutable(x) })
        assertThat(ex)
            .hasMessageThat()
            .contains("DummyFreezable value is temporarily immutable due to active for-loop iteration")

        x.updateIteratorCount(-1) // net 0 => mutable
        Starlark.checkMutable(x) // ok

        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { x.updateIteratorCount(-1) }) // underflow
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addIteratorAndThenFreeze() {
        val mutability: Mutability = Mutability.create("test")
        val dummy = DummyFreezable(mutability)
        dummy.updateIteratorCount(+1)
        mutability.freeze()
        // Should fail with frozen error, not temporarily immutable error.
        assertCheckMutableFailsBecauseFrozen(dummy)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkUnsafeShallowFreezePrecondition_FailsWhenAlreadyFrozen() {
        val mutability: Mutability? = Mutability.create("test").freeze()
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                Freezable.checkUnsafeShallowFreezePrecondition(
                    DummyFreezable(
                        mutability
                    )
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkUnsafeShallowFreezePrecondition_FailsWhenDisallowed() {
        val mutability: Mutability? = Mutability.create("test")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                Freezable.checkUnsafeShallowFreezePrecondition(
                    DummyFreezable(
                        mutability
                    )
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkUnsafeShallowFreezePrecondition_SucceedsWhenAllowed() {
        val mutability: Mutability? = Mutability.createAllowingShallowFreeze("test")
        Freezable.checkUnsafeShallowFreezePrecondition(DummyFreezable(mutability))
    }

    companion object {
        private fun assertCheckMutableFailsBecauseFrozen(x: DummyFreezable?) {
            val ex: EvalException? = org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { Starlark.checkMutable(x) })
            assertThat(ex).hasMessageThat().contains("trying to mutate a frozen DummyFreezable value")
        }
    }
}
