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
package com.google.devtools.build.lib.query2

/**
 * A [ParallelVisitor] whose visitations occur on [SkyKey]s and those keys map directly
 * to output keys.
 */
abstract class AbstractSkyKeyParallelVisitor<T>
protected constructor(
    visitationUniquifier: Uniquifier<SkyKey?>,
    callback: com.google.devtools.build.lib.query2.engine.Callback<T?>?,
    visitBatchSize: Int,
    processResultsBatchSize: Int,
    visitTaskStatusCallback: VisitTaskStatusCallback?
) : ParallelQueryVisitor<SkyKey?, SkyKey?, T?>(
    callback,
    visitBatchSize,
    processResultsBatchSize,
    visitTaskStatusCallback
) {
    private val uniquifier: Uniquifier<SkyKey?>

    init {
        this.uniquifier = visitationUniquifier
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected override fun noteAndReturnUniqueVisitationKeys(
        prospectiveVisitationKeys: Iterable<SkyKey?>?
    ): Iterable<SkyKey?>? {
        return uniquifier.unique(prospectiveVisitationKeys)
    }
}
