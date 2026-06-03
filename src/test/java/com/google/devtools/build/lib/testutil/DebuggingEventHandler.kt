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
package com.google.devtools.build.lib.testutil

import net.starlark.java.syntax.SyntaxError.Exception.getMessage
import java.io.PrintStream

/**
 * Prints all errors and warnings to [System.out].
 */
class DebuggingEventHandler : com.google.devtools.build.lib.events.EventHandler {
    private val out: PrintStream

    init {
        this.out = java.lang.System.out
    }

    override fun handle(e: com.google.devtools.build.lib.events.Event) {
        if (e.getLocation() != null) {
            out.println(e.getKind().toString() + " " + e.getLocation() + ": " + e.getMessage())
        } else {
            out.println(e.getKind().toString() + " " + e.getMessage())
        }
    }
}
