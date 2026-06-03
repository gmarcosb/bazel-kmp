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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.ExecuteRequest

/** A fake implementation of the [ExecutionImplBase].  */
class FakeExecutionService : ExecutionImplBase() {
    private val executeOperationProvider = OperationProvider()
    private val waitExecutionOperationProvider = OperationProvider()
    var execTimes: Int = 0
        private set
    var waitTimes: Int = 0
        private set

    internal class OperationProvider {
        // Map from the request to the list of operations to be returned for each instance of
        // that request, with Supplier used for either throwing an exception or returning an Operation.
        private val operationProvider: MutableMap<String?, Deque<com.google.common.collect.ImmutableList<java.util.function.Supplier<Operation?>?>>?> =
            HashMap<String?, Deque<com.google.common.collect.ImmutableList<java.util.function.Supplier<Operation?>?>>?>()

        fun append(
            name: String?,
            suppliers: com.google.common.collect.ImmutableList<java.util.function.Supplier<Operation?>?>
        ) {
            operationProvider
                .computeIfAbsent(name) { key: String? -> ArrayDeque<com.google.common.collect.ImmutableList<java.util.function.Supplier<Operation?>?>?>() }
                .add(com.google.common.collect.ImmutableList.copyOf<java.util.function.Supplier<Operation?>?>(suppliers))
        }

        fun hasNext(name: String?): Boolean {
            val q: Deque<com.google.common.collect.ImmutableList<java.util.function.Supplier<Operation?>?>?>? =
                operationProvider.get(name)
            return q != null && !q.isEmpty()
        }

        fun next(name: String?): com.google.common.collect.ImmutableList<java.util.function.Supplier<Operation?>> {
            return operationProvider.get(name).removeFirst()
        }
    }

    fun whenExecute(request: ExecuteRequest): OnetimeOperationSupplierBuilder {
        return OnetimeOperationSupplierBuilder(executeOperationProvider, request)
    }

    fun whenWaitExecution(request: ExecuteRequest): OnetimeOperationSupplierBuilder {
        return OnetimeOperationSupplierBuilder(waitExecutionOperationProvider, request)
    }

    internal class OnetimeOperationSupplierBuilder(private val provider: OperationProvider, request: ExecuteRequest) {
        private val request: ExecuteRequest
        private val operations: MutableList<java.util.function.Supplier<Operation?>?> =
            java.util.ArrayList<java.util.function.Supplier<Operation?>?>()

        init {
            this.request = request
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun thenAck(): OnetimeOperationSupplierBuilder {
            val operation: Operation = ackOperation(request)
            operations.add(java.util.function.Supplier { operation })
            return this
        }

        fun thenDone() {
            val operation: Operation? =
                Operation.newBuilder().setName(getResourceName(request)).setDone(true).build()
            operations.add(java.util.function.Supplier { operation })
            finish()
        }

        fun thenDone(response: ExecuteResponse?) {
            val operation: Operation = doneOperation(request, response)
            operations.add(java.util.function.Supplier { operation })
            finish()
        }

        fun thenError(code: Code) {
            // From REAPI Spec:
            // > Errors discovered during creation of the `Operation` will be reported
            // > as gRPC Status errors, while errors that occurred while running the
            // > action will be reported in the `status` field of the `ExecuteResponse`. The
            // > server MUST NOT set the `error` field of the `Operation` proto.
            val operation: Operation =
                doneOperation(
                    request,
                    ExecuteResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(code.getNumber()))
                        .build()
                )
            operations.add(java.util.function.Supplier { operation })
            finish()
        }

        fun thenError(e: java.lang.RuntimeException) {
            operations.add(
                java.util.function.Supplier {
                    throw e
                })
            finish()
        }

        fun finish() {
            val name = getResourceName(request)
            provider.append(
                name,
                com.google.common.collect.ImmutableList.copyOf<java.util.function.Supplier<Operation?>?>(operations)
            )
        }
    }

    public override fun execute(request: ExecuteRequest, responseObserver: StreamObserver<Operation?>) {
        execTimes += 1
        serve(responseObserver, getResourceName(request), executeOperationProvider)
    }

    public override fun waitExecution(
        request: WaitExecutionRequest, responseObserver: StreamObserver<Operation?>
    ) {
        waitTimes += 1
        serve(responseObserver, request.getName(), waitExecutionOperationProvider)
    }

    companion object {
        fun ackOperation(request: ExecuteRequest): Operation {
            return Operation.newBuilder().setName(getResourceName(request)).setDone(false).build()
        }

        fun doneOperation(request: ExecuteRequest, response: ExecuteResponse?): Operation {
            return Operation.newBuilder()
                .setName(getResourceName(request))
                .setDone(true)
                .setResponse(Any.pack(response))
                .build()
        }

        fun getResourceName(request: ExecuteRequest): String? {
            return java.lang.String.format("operations/%s", request.getActionDigest().getHash())
        }

        private fun serve(
            responseObserver: StreamObserver<Operation?>, name: String?, provider: OperationProvider
        ) {
            if (provider.hasNext(name)) {
                var thrown = false
                val suppliers: com.google.common.collect.ImmutableList<java.util.function.Supplier<Operation?>> =
                    provider.next(name)
                for (supplier in suppliers) {
                    try {
                        responseObserver.onNext(supplier.get())
                    } catch (e: java.lang.Exception) {
                        thrown = true
                        responseObserver.onError(e)
                    }
                }
                if (!thrown) {
                    responseObserver.onCompleted()
                }
            } else {
                responseObserver.onError(io.grpc.Status.UNIMPLEMENTED.asRuntimeException())
            }
        }
    }
}
