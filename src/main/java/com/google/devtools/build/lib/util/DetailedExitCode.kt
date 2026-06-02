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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.server.FailureDetails

/** An [ExitCode] that has a [FailureDetail] unless it's [ExitCode.SUCCESS].  */
class DetailedExitCode private constructor(exitCode: ExitCode, failureDetail: FailureDetail?) {
    private val exitCode: ExitCode
    private val failureDetail: FailureDetail?

    init {
        this.exitCode = exitCode
        this.failureDetail = failureDetail
    }

    fun getExitCode(): ExitCode {
        return exitCode
    }

    fun getFailureDetail(): FailureDetail? {
        return failureDetail
    }

    val isSuccess: Boolean
        get() = exitCode == ExitCode.Companion.SUCCESS

    override fun hashCode(): Int {
        return java.util.Objects.hash(exitCode, failureDetail)
    }

    override fun equals(obj: Any?): Boolean {
        if (obj === this) {
            return true
        }
        if (obj !is DetailedExitCode) {
            return false
        }
        val that = obj
        return this.exitCode == that.exitCode
                && this.failureDetail == that.failureDetail
    }

    override fun toString(): String {
        return java.lang.String.format(
            "DetailedExitCode{exitCode=%s, failureDetail=%s}", exitCode, failureDetail
        )
    }

    /**
     * A comparator to determine the reporting priority of [DetailedExitCode].
     * 
     * 
     * Priority: infrastructure exit codes > non-infrastructure exit codes > null exit codes, with
     * exit codes that contain failure details taking priority within each class.
     */
    class DetailedExitCodeComparator private constructor() : java.util.Comparator<DetailedExitCode?> {
        override fun compare(c1: DetailedExitCode?, c2: DetailedExitCode?): Int {
            // returns POSITIVE result when the priority of c1 is HIGHER than the priority of c2
            return getPriority(c1) - getPriority(c2)
        }

        companion object {
            val INSTANCE: DetailedExitCodeComparator = DetailedExitCodeComparator()

            fun chooseMoreImportantWithFirstIfTie(
                first: DetailedExitCode?, second: DetailedExitCode?
            ): DetailedExitCode? {
                return if (INSTANCE.compare(first, second) >= 0) first else second
            }

            private fun getPriority(code: DetailedExitCode?): Int {
                if (code == null) {
                    return 0
                } else {
                    val codeClass = if (code.getExitCode().isInfrastructureFailure()) 4 else 2
                    return codeClass + (if (code.getFailureDetail() != null) 1 else 0)
                }
            }
        }
    }

    companion object {
        /** Returns the registered [ExitCode] associated with a [FailureDetail] message.  */
        fun getExitCode(failureDetail: FailureDetail): ExitCode {
            // TODO(mschaller): Consider specializing for unregistered exit codes here, if absolutely
            //  necessary.
            val numericExitCode: Int = Companion.getNumericExitCode(failureDetail)
            return com.google.common.base.Preconditions.checkNotNull<ExitCode>(
                ExitCode.Companion.forCode(numericExitCode), "No ExitCode for numericExitCode %s", numericExitCode
            )
        }

        /** Returns a [DetailedExitCode] specifying success (i.e. exit code 0).  */
        @kotlin.jvm.JvmStatic
        fun success(): DetailedExitCode {
            return DetailedExitCode(ExitCode.Companion.SUCCESS, null)
        }

        /**
         * Returns a [DetailedExitCode] combining the provided [FailureDetail] and [ ].
         * 
         * 
         * This method exists in order to allow for the introduction of new [ ][.of]
         */
        fun of(exitCode: ExitCode?, failureDetail: FailureDetail?): DetailedExitCode {
            return DetailedExitCode(
                com.google.common.base.Preconditions.checkNotNull<ExitCode?>(exitCode),
                com.google.common.base.Preconditions.checkNotNull<FailureDetail?>(failureDetail)
            )
        }

        /**
         * Returns a [DetailedExitCode] whose [ExitCode] is chosen referencing [ ]'s metadata.
         */
        fun of(failureDetail: FailureDetail): DetailedExitCode {
            return DetailedExitCode(
                getExitCode(failureDetail),
                com.google.common.base.Preconditions.checkNotNull<FailureDetail?>(failureDetail)
            )
        }

        /** Returns the numeric exit code associated with a [FailureDetail] message.  */
        fun getNumericExitCode(failureDetail: FailureDetail): Int {
            val categoryMsg: MessageOrBuilder = getCategorySubmessage(failureDetail)
            val subcategoryDescriptor: EnumValueDescriptor =
                getSubcategoryDescriptor(failureDetail, categoryMsg)
            return Companion.getNumericExitCode(subcategoryDescriptor)
        }

        /**
         * Returns the numeric exit code associated with a [FailureDetail] submessage's subcategory
         * enum value.
         */
        fun getNumericExitCode(subcategoryDescriptor: EnumValueDescriptor): Int {
            checkArgument(
                subcategoryDescriptor.getOptions().hasExtension(FailureDetails.metadata),
                "Enum value %s has no FailureDetails.metadata",
                subcategoryDescriptor
            )
            return subcategoryDescriptor.getOptions().getExtension(FailureDetails.metadata).getExitCode()
        }

        /**
         * Returns the category submessage, i.e. the message in [FailureDetail]'s oneof. Throws if
         * none of those fields are set.
         */
        private fun getCategorySubmessage(failureDetail: FailureDetail): MessageOrBuilder {
            var categoryMsg: MessageOrBuilder? = null
            for (entry in failureDetail.getAllFields().entrySet()) {
                val fieldDescriptor: FieldDescriptor = entry.getKey()
                if (isCategoryField(fieldDescriptor)) {
                    categoryMsg = entry.getValue() as MessageOrBuilder?
                    break
                }
            }
            return com.google.common.base.Preconditions.checkNotNull<MessageOrBuilder>(
                categoryMsg, "FailureDetail missing category submessage: %s", failureDetail
            )
        }

        /**
         * Returns whether the [FieldDescriptor] describes a field in [FailureDetail]'s oneof.
         * 
         * 
         * Uses the field number criteria described in failure_details.proto.
         */
        private fun isCategoryField(fieldDescriptor: FieldDescriptor): Boolean {
            val fieldNum: Int = fieldDescriptor.getNumber()
            return 100 < fieldNum && fieldNum <= 10000
        }

        /**
         * Returns the enum value descriptor for the enum field with field number 1 in the [ ]'s category submessage.
         */
        private fun getSubcategoryDescriptor(
            failureDetail: FailureDetail?, categoryMsg: MessageOrBuilder
        ): EnumValueDescriptor {
            val fieldNumberOne: FieldDescriptor? = categoryMsg.getDescriptorForType().findFieldByNumber(1)
            com.google.common.base.Preconditions.checkNotNull<Any?>(
                fieldNumberOne, "FailureDetail category submessage has no field #1: %s", failureDetail
            )
            val fieldNumberOneVal: Any? = categoryMsg.getField(fieldNumberOne)
            com.google.common.base.Preconditions.checkArgument(
                fieldNumberOneVal is EnumValueDescriptor,
                "FailureDetail category submessage has non-enum field #1: %s",
                failureDetail
            )
            return fieldNumberOneVal as EnumValueDescriptor
        }
    }
}
