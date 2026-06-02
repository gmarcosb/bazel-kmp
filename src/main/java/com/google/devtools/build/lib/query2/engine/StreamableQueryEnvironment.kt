// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine

import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryTaskFuture

/**
 * The environment of a Blaze query which supports predefined streaming operations.
 * 
 * @param <T> the node type of the dependency graph
</T> */
interface StreamableQueryEnvironment<T> : QueryEnvironment<T?> {
    fun getAllRdepsBoundedParallel(
        expression: QueryExpression?,
        depth: Int,
        context: QueryExpressionContext<T?>?,
        callback: Callback<T?>?
    ): QueryTaskFuture<Void?>?

    fun getAllRdepsUnboundedParallel(
        expression: QueryExpression?, context: QueryExpressionContext<T?>?, callback: Callback<T?>?
    ): QueryTaskFuture<Void?>?

    fun getRdepsBoundedParallel(
        expression: QueryExpression?,
        depth: Int,
        universe: QueryExpression?,
        context: QueryExpressionContext<T?>?,
        callback: Callback<T?>?
    ): QueryTaskFuture<Void?>?

    fun getRdepsUnboundedParallel(
        expression: QueryExpression?,
        universe: QueryExpression?,
        context: QueryExpressionContext<T?>?,
        callback: Callback<T?>?
    ): QueryTaskFuture<Void?>?

    fun getDepsUnboundedParallel(
        expression: QueryExpression?,
        context: QueryExpressionContext<T?>?,
        callback: Callback<T?>?,
        caller: QueryExpression?
    ): QueryTaskFuture<Void?>?

    // TODO(bazel-team): Make this parallel.
    fun getDepsBounded(
        expression: QueryExpression?,
        context: QueryExpressionContext<T?>?,
        callback: Callback<T?>?,
        depth: Int,
        caller: QueryExpression?
    ): QueryTaskFuture<Void?>?

    fun somePath(
        fromExpression: QueryExpression?,
        toExpression: QueryExpression?,
        context: QueryExpressionContext<T?>?,
        callback: Callback<T?>?,
        caller: QueryExpression?
    ): QueryTaskFuture<Void?>?

    fun allPaths(
        fromExpression: QueryExpression?,
        toExpression: QueryExpression?,
        context: QueryExpressionContext<T?>?,
        callback: Callback<T?>?,
        caller: QueryExpression?
    ): QueryTaskFuture<Void?>?
}
