// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.buildjar.javac.plugins.processing

import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin
import com.sun.tools.javac.comp.Env
import java.nio.file.Path

/** A plugin that records information about sources generated during annotation processing.  */
class AnnotationProcessingPlugin(private val processingModule: AnnotationProcessingModule) : BlazeJavaCompilerPlugin() {
    private val toplevels: HashSet<JCCompilationUnit?> = HashSet<JCCompilationUnit?>()

    public override fun postAttribute(env: Env<AttrContext?>) {
        if (toplevels.add(env.toplevel)) {
            recordInfo(env.toplevel)
        }
    }

    private fun recordInfo(toplevel: JCCompilationUnit) {
        val builder: CompilationUnit.Builder = CompilationUnit.newBuilder()

        if (toplevel.sourcefile != null) {
            // FileObject#getName() returns the original exec root-relative path of
            // the source file, which is want we want.
            // Paths.get(sourcefile.toUri()) would absolutize the path.
            val path: Path = Paths.get(toplevel.sourcefile.getName())
            builder.setPath(processingModule.stripSourceRoot(path).toString())
            builder.setGeneratedByAnnotationProcessor(processingModule.isGenerated(path))
        }

        if (toplevel.getPackageName() != null) {
            builder.setPkg(toplevel.getPackageName().toString())
        }

        for (decl in toplevel.defs) {
            if (decl is JCClassDecl) {
                builder.addTopLevel((decl as JCClassDecl).getSimpleName().toString())
            }
        }

        processingModule.recordUnit(builder.build())
    }
}
