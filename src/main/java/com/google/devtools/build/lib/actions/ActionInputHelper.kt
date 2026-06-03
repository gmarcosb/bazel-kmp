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

import com.google.devtools.build.lib.vfs.Path

/** Helper utility to create ActionInput instances.  */
object ActionInputHelper {
    /**
     * Creates an ActionInput with just the given relative path and no digest.
     * 
     * @param path the relative path of the input.
     * @return a ActionInput.
     */
    fun fromPath(path: String): ActionInput {
        return object : BasicActionInput() {
            override fun getExecPathString(): String {
                return path
            }

            override fun getExecPath(): PathFragment {
                return PathFragment.create(path)
            }
        }
    }

    /**
     * Creates an ActionInput with just the given relative path and no digest.
     * 
     * @param path the relative path of the input.
     * @return a ActionInput.
     */
    fun fromPath(path: PathFragment): ActionInput {
        return object : BasicActionInput() {
            override fun getExecPathString(): String {
                return path.getPathString()
            }

            override fun getExecPath(): PathFragment {
                return path
            }
        }
    }

    fun toExecPaths(artifacts: Iterable<out ActionInput?>): Iterable<String?> {
        return com.google.common.collect.Iterables.transform(
            artifacts,
            { obj: ActionInput? -> obj.getExecPathString() })
    }

    /** Returns the [Path] for an [ActionInput].  */
    fun toInputPath(input: ActionInput?, execRoot: Path?): Path? {
        com.google.common.base.Preconditions.checkNotNull<ActionInput?>(input, "input")
        com.google.common.base.Preconditions.checkNotNull<Any?>(execRoot, "execRoot")

        return if (input is Artifact)
            input.getPath()
        else
            execRoot.getRelative(input.getExecPath())
    }

    /**
     * Most ActionInputs are created and never used again. On the off chance that one is, however, we
     * implement equality via path comparison. Since file caches are keyed by ActionInput, equality
     * checking does come up.
     */
    abstract class BasicActionInput : ActionInput {
        // TODO(lberki): Plumb this flag from InputTree.build() somehow.
        override fun isSymlink(): Boolean {
            return false
        }

        override fun isDirectory(): Boolean {
            return false
        }

        override fun hashCode(): Int {
            return getExecPathString().hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is BasicActionInput) {
                return false
            }
            return getExecPathString() == other.getExecPathString()
        }

        override fun toString(): String {
            return "BasicActionInput: " + getExecPathString()
        }
    }
}
