// Copyright 2018 The Bazel Authors. All rights reserved.
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

/**
 * A container wrapping a value of one of two types. An `Either<A, B>` instance either wraps
 * an instance of `A` or a instance of `B`.
 * 
 * 
 * Just as with [Pair], this class is immutable, supports nullable values, and is
 * completely devoid of Bazel-business-logic-specific semantics. Avoid using it in public APIs.
 * 
 * 
 * This class is a simple implementation of a general purpose "sum" type. In type theory, sum
 * types are the duals of product types -- the corresponding observation here is that [Either]
 * is the dual of [Pair].
 */
abstract class Either<A, B>  // Disallow subclasses outside of this file.
private constructor() {
    /**
     * Consumes the value injected into this [Either]. A left injection is consumed using
     * `leftConsumer` and a right injection is consumed using `rightConsumer`.
     */
    abstract fun consume(
        leftConsumer: java.util.function.Consumer<A?>?,
        rightConsumer: java.util.function.Consumer<B?>?
    )

    /**
     * Maps the value injected into this [Either]. A left injection is mapped using
     * `leftFunction` and a right injection is mapped using `rightFunction`.
     */
    abstract fun <C> map(
        leftFunction: java.util.function.Function<A?, C?>?,
        rightFunction: java.util.function.Function<B?, C?>?
    ): C?

    abstract override fun hashCode(): Int

    abstract override fun equals(other: Any?): Boolean

    abstract override fun toString(): String

    private class LeftEither<A, B>(private val a: A?) : Either<A?, B?>() {
        override fun consume(
            leftConsumer: java.util.function.Consumer<A?>,
            rightConsumer: java.util.function.Consumer<B?>?
        ) {
            leftConsumer.accept(a)
        }

        override fun <C> map(
            leftFunction: java.util.function.Function<A?, C?>,
            rightFunction: java.util.function.Function<B?, C?>?
        ): C? {
            return leftFunction.apply(a)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hashCode(a)
        }

        override fun equals(other: Any?): Boolean {
            if (other !is LeftEither<*, *>) {
                return false
            }
            return this.a == other.a
        }

        override fun toString(): String {
            return "left injection of " + a
        }
    }

    private class RightEither<A, B>(private val b: B?) : Either<A?, B?>() {
        override fun consume(
            leftConsumer: java.util.function.Consumer<A?>?,
            rightConsumer: java.util.function.Consumer<B?>
        ) {
            rightConsumer.accept(b)
        }

        override fun <C> map(
            leftFunction: java.util.function.Function<A?, C?>?,
            rightFunction: java.util.function.Function<B?, C?>
        ): C? {
            return rightFunction.apply(b)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hashCode(b)
        }

        override fun equals(other: Any?): Boolean {
            if (other !is RightEither<*, *>) {
                return false
            }
            return this.b == other.b
        }

        override fun toString(): String {
            return "right injection of " + b
        }
    }

    companion object {
        /** Constructs a [Either] representing the left injection of `a`.  */
        fun <A, B> ofLeft(a: A?): Either<A?, B?> {
            return LeftEither<A?, B?>(a)
        }

        /** Constructs a [Either] representing the right injection of `b`.  */
        fun <A, B> ofRight(b: B?): Either<A?, B?> {
            return RightEither<A?, B?>(b)
        }
    }
}
