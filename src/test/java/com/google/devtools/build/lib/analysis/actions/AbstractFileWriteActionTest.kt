// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Unit tests for [AbstractFileWriteAction].  */
@RunWith(TestParameterInjector::class)
class AbstractFileWriteActionTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executeAction_successfulWrite_callsAfterWrite() {
        val writer: DeterministicWriter = DeterministicWriter { ignored -> }
        val action: AbstractFileWriteAction =
            Mockito.spy<TestFileWriteAction>(TestFileWriteAction(writer,  /*executable=*/false,  /*isRemotable=*/false))
        val fileWriteContext: FileWriteActionContext =
            Mockito.mock<FileWriteActionContext>(FileWriteActionContext::class.java)
        val actionExecutionContext: ActionExecutionContext =
            createMockActionExecutionContext(fileWriteContext)
        val success: SpawnResult =
            Builder().setRunnerName("test").setStatus(Status.SUCCESS).build()
        Mockito.`when`<T?>(
            fileWriteContext.writeOutputToFile(
                action,
                actionExecutionContext,
                writer,  /* makeExecutable= */
                false,  /* isRemotable= */
                false
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(success))

        val result: ActionResult = action.execute(actionExecutionContext)

        assertThat(result.spawnResults()).containsExactly(success)
        Mockito.verify<Any?>(action).afterWrite(actionExecutionContext)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executeAction_failedWrite_doesNotCallAfterWrite() {
        val writer: DeterministicWriter = DeterministicWriter { ignored -> }
        val action: AbstractFileWriteAction =
            Mockito.spy<TestFileWriteAction>(TestFileWriteAction(writer,  /*executable=*/false,  /*isRemotable=*/false))
        val fileWriteContext: FileWriteActionContext =
            Mockito.mock<FileWriteActionContext>(FileWriteActionContext::class.java)
        val actionExecutionContext: ActionExecutionContext =
            createMockActionExecutionContext(fileWriteContext)
        val failure: ExecException =
            EnvironmentalExecException(
                FailureDetail.newBuilder()
                    .setExecution(Execution.newBuilder().setCode(Code.FILE_WRITE_IO_EXCEPTION).build())
                    .build()
            )
        Mockito.`when`<T?>(
            fileWriteContext.writeOutputToFile(
                action,
                actionExecutionContext,
                writer,  /* makeExecutable= */
                false,  /* isRemotable= */
                false
            )
        )
            .thenThrow(failure)

        val exception: ActionExecutionException? =
            org.junit.Assert.assertThrows<T?>(
                ActionExecutionException::class.java,
                org.junit.function.ThrowingRunnable { action.execute(actionExecutionContext) })

        assertThat(exception).hasCauseThat().isSameInstanceAs(failure)
        Mockito.verify<Any?>(action, Mockito.never()).afterWrite(actionExecutionContext)
    }

    fun createMockActionExecutionContext(fileWriteContext: FileWriteActionContext?): ActionExecutionContext {
        val actionExecutionContext: ActionExecutionContext =
            Mockito.mock<ActionExecutionContext>(ActionExecutionContext::class.java)
        Mockito.`when`<T?>(actionExecutionContext.getContext(FileWriteActionContext::class.java))
            .thenReturn(fileWriteContext)
        return actionExecutionContext
    }

    private class TestFileWriteAction(
        deterministicWriter: DeterministicWriter?,
        executable: Boolean,
        isRemotable: Boolean
    ) : AbstractFileWriteAction(
        ActionsTestUtil.Companion.NULL_ACTION_OWNER,  /* inputs= */
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* output= */
        ActionsTestUtil.Companion.DUMMY_ARTIFACT
    ) {
        private val deterministicWriter: DeterministicWriter?
        private val isRemotable: Boolean
        private val makeExecutable: Boolean

        init {
            this.deterministicWriter = deterministicWriter
            this.makeExecutable = executable
            this.isRemotable = isRemotable
        }

        public override fun makeExecutable(): Boolean {
            return makeExecutable
        }

        public override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter? {
            return deterministicWriter
        }

        public override fun isRemotable(): Boolean {
            return isRemotable
        }

        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint?
        ) {
            throw java.lang.UnsupportedOperationException()
        }
    }
}
