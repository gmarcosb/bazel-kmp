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
package com.google.devtools.build.lib.query2.engine

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface

/** Exception indicating a failure in Blaze query, aquery, or cquery.  */
class QueryException : Exception, QueryExceptionMarkerInterface {
    /**
     * Returns the subexpression for which evaluation failed, or null if
     * the failure occurred during lexing/parsing.
     */
    val failedExpression: QueryExpression?
    private val failureDetail: FailureDetail?

    constructor(e: QueryException, toplevel: QueryExpression?) : super(describeFailedQuery(e, toplevel), e) {
        this.failedExpression = null
        this.failureDetail = e.getFailureDetail()
    }

    constructor(
        expression: QueryExpression?,
        message: String?,
        cause: Throwable?,
        failureDetail: FailureDetail?
    ) : super(message, cause) {
        this.failedExpression = expression
        this.failureDetail = Preconditions.checkNotNull<FailureDetail?>(failureDetail)
    }

    constructor(expression: QueryExpression?, message: String?, failureDetail: FailureDetail?) : super(message) {
        this.failedExpression = expression
        this.failureDetail = Preconditions.checkNotNull<FailureDetail?>(failureDetail)
    }

    constructor(expression: QueryExpression?, message: String?, queryCode: Query.Code?) : this(
        expression,
        message,
        FailureDetail.newBuilder()
            .setMessage(message)
            .setQuery(Query.newBuilder().setCode(queryCode).build())
            .build()
    )

    constructor(expression: QueryExpression?, message: String?, actionQueryCode: ActionQuery.Code?) : this(
        expression,
        message,
        FailureDetail.newBuilder()
            .setMessage(message)
            .setActionQuery(ActionQuery.newBuilder().setCode(actionQueryCode).build())
            .build()
    )

    constructor(expression: QueryExpression?, message: String?, configurableQueryCode: ConfigurableQuery.Code?) : this(
        expression,
        message,
        FailureDetail.newBuilder()
            .setMessage(message)
            .setConfigurableQuery(
                ConfigurableQuery.newBuilder().setCode(configurableQueryCode).build()
            )
            .build()
    )

    constructor(message: String?, cause: Throwable?, failureDetail: FailureDetail?) : super(message, cause) {
        this.failedExpression = null
        this.failureDetail = Preconditions.checkNotNull<FailureDetail?>(failureDetail)
    }

    constructor(message: String?, failureDetail: FailureDetail?) : super(message) {
        this.failedExpression = null
        this.failureDetail = Preconditions.checkNotNull<FailureDetail?>(failureDetail)
    }

    constructor(message: String?, queryCode: Query.Code?) : this(null, message, queryCode)

    constructor(message: String?, actionQueryCode: ActionQuery.Code?) : this(null, message, actionQueryCode)

    constructor(message: String?, configurableQueryCode: ConfigurableQuery.Code?) : this(
        null,
        message,
        configurableQueryCode
    )

    /** Returns a [FailureDetail] with a corresponding code of the query error.  */
    fun getFailureDetail(): FailureDetail? {
        return failureDetail
    }

    companion object {
        /** Returns a better error message for the query.  */
        fun describeFailedQuery(e: QueryException, toplevel: QueryExpression?): String {
            val badQuery = e.failedExpression
            if (badQuery == null) {
                return "Evaluation failed: " + e.message
            }
            return if (badQuery === toplevel)
                "Evaluation of query \"" + toplevel.toTrunctatedString() + "\" failed: " + e.message
            else
                ("Evaluation of subquery \""
                        + badQuery.toTrunctatedString()
                        + "\" failed (did you want to use --keep_going?): "
                        + e.message)
        }
    }
}
