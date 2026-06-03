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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.concurrent.BlazeInterners

/**
 * An object that encapsulates how a params file should be constructed: what is the filetype, what
 * charset to use and what prefix (typically "@") to use.
 */
@javax.annotation.concurrent.Immutable
class ParamFileInfo private constructor(builder: Builder) {
    private val fileType: ParameterFileType
    private val flagFormatString: String
    private val always: Boolean
    private val flagsOnly: Boolean

    init {
        this.fileType = com.google.common.base.Preconditions.checkNotNull<ParameterFileType>(builder.fileType)
        this.flagFormatString = com.google.common.base.Preconditions.checkNotNull<String>(builder.flagFormatString)
        this.always = builder.always
        this.flagsOnly = builder.flagsOnly
    }

    /** Returns the file type.  */
    fun getFileType(): ParameterFileType {
        return fileType
    }

    /** Returns the format string for the params filename on the command line (typically "@%s").  */
    fun getFlagFormatString(): String {
        return flagFormatString
    }

    /** Returns true if a params file should always be used.  */
    fun always(): Boolean {
        return always
    }

    /**
     * If true, only the flags will be spilled to the file, leaving positional args on the command
     * line.
     */
    fun flagsOnly(): Boolean {
        return flagsOnly
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(flagFormatString, fileType, always)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is ParamFileInfo) {
            return false
        }
        return fileType == obj.fileType
                && flagFormatString == obj.flagFormatString
                && always == obj.always && flagsOnly == obj.flagsOnly
    }

    /** Builder for a ParamFileInfo.  */
    class Builder private constructor(fileType: ParameterFileType?) {
        private val fileType: ParameterFileType?
        private var flagFormatString: String? = "@%s"
        private var always = false
        private var flagsOnly = false

        init {
            this.fileType = fileType
        }

        /**
         * Sets a format string to use for the flag that is passed to original command.
         * 
         * 
         * The format string must have a single "%s" that will be replaced by the execution path to
         * the param file.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFlagFormatString(flagFormatString: String?): Builder {
            this.flagFormatString = flagFormatString
            return this
        }

        /** Set whether the parameter file is always used, regardless of parameter file length.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUseAlways(always: Boolean): Builder {
            this.always = always
            return this
        }

        /**
         * If true, only the flags will be spilled to the file, leaving positional args on the command
         * line. (Default is false.)
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFlagsOnly(flagsOnly: Boolean): Builder {
            this.flagsOnly = flagsOnly
            return this
        }

        fun build(): ParamFileInfo {
            return paramFileInfoInterner.intern(ParamFileInfo(this))
        }
    }

    companion object {
        private val paramFileInfoInterner: com.google.common.collect.Interner<ParamFileInfo> =
            BlazeInterners.newWeakInterner()

        fun builder(parameterFileType: ParameterFileType?): Builder {
            return com.google.devtools.build.lib.actions.ParamFileInfo.Builder(parameterFileType)
        }
    }
}
