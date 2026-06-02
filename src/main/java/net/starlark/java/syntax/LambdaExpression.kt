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
package net.starlark.java.syntax

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList

/** A LambdaExpression (`lambda params: body`) denotes an anonymous function.  */
class LambdaExpression internal constructor(
    locs: FileLocations?,
    lambdaOffset: Int,
    parameters: ImmutableList<Parameter?>?,
    body: Expression?
) : Expression(locs, Kind.LAMBDA) {
    private val lambdaOffset: Int // offset of 'lambda' token
    @kotlin.jvm.JvmField
    private val parameters: ImmutableList<Parameter?>
    @kotlin.jvm.JvmField
    private val body: Expression

    // set by resolver
    @kotlin.jvm.JvmField
    private var resolved: Resolver.Function? = null

    init {
        this.lambdaOffset = lambdaOffset
        this.parameters = Preconditions.checkNotNull<ImmutableList<Parameter?>>(parameters)
        this.body = Preconditions.checkNotNull<Expression>(body)
    }

    fun getParameters(): ImmutableList<Parameter?> {
        return parameters
    }

    fun getBody(): Expression {
        return body
    }

    /** Returns information about the resolved function. Set by the resolver.  */
    fun getResolvedFunction(): Resolver.Function? {
        return resolved
    }

    fun setResolvedFunction(resolved: Resolver.Function?) {
        this.resolved = resolved
    }

    override fun getStartOffset(): Int {
        return lambdaOffset
    }

    override fun getEndOffset(): Int {
        return body.getEndOffset()
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
