// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId

/** Reports the progress of the evaluation of a module extension.  */
class ModuleExtensionEvaluationProgress private constructor(
    extensionId: ModuleExtensionId?,
    finished: Boolean,
    message: String?
) : com.google.devtools.build.lib.events.ExtendedEventHandler.FetchProgress {
    private val extensionId: ModuleExtensionId?
    private val finished: Boolean
    private val message: String?

    init {
        this.extensionId = extensionId
        this.finished = finished
        this.message = message
    }

    override fun getResourceIdentifier(): String {
        return moduleExtensionEvaluationContextString(extensionId)
    }

    override fun getProgress(): String? {
        return message
    }

    override fun isFinished(): Boolean {
        return finished
    }

    companion object {
        /** Returns the unique identifying string for a module extension evaluation event.  */
        fun moduleExtensionEvaluationContextString(extensionId: ModuleExtensionId?): String {
            return "module extension " + extensionId
        }

        fun ongoing(
            extensionId: ModuleExtensionId?, message: String?
        ): ModuleExtensionEvaluationProgress {
            return ModuleExtensionEvaluationProgress(extensionId,  /* finished= */false, message)
        }

        fun finished(extensionId: ModuleExtensionId?): ModuleExtensionEvaluationProgress {
            return ModuleExtensionEvaluationProgress(extensionId,  /* finished= */true, "finished.")
        }
    }
}
