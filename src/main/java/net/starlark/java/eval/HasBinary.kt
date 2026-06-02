// Copyright 2020 The Bazel Authors. All rights reserved.
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

/**
 * A Starlark value that supports binary operators such as `x+y`.
 * 
 * 
 * During evaluation of a Starlark binary operation, if none of the built-in cases match, then
 * the left operand is queried; if it implements HasBinary, its [.binaryOp] method is called.
 * If the left operand does not implement HasBinary, or declines to implement the particular
 * operation by returning null, then the right operand is queried for HasBinary and its [ ][.binaryOp] method is called. If neither operand defines the operator, evaluation fails.
 * 
 * 
 * Subclasses should strive for appropriate symmetries in their implementations, such as `x * y == y * x`.
 */
// TODO(adonovan): rename BinaryOperand?
interface HasBinary : net.starlark.java.eval.StarlarkValue {
    /**
     * Returns `this op that`, if thisLeft, or `that op this` otherwise. May return null
     * to indicate that the operation is not supported, or may throw a specific exception.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun binaryOp(op: net.starlark.java.syntax.TokenKind?, that: Any?, thisLeft: Boolean): Any?
}
