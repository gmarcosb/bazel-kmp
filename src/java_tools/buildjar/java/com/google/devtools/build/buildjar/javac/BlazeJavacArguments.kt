// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.buildjar.javac

import com.google.auto.value.AutoValue
import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin
import com.google.protobuf.ByteString
import java.nio.file.Path
import java.util.OptionalInt

/**
 * Arguments to a single compilation performed by [BlazeJavacMain].
 * 
 * 
 * This includes a subset of arguments to [javax.tools.JavaCompiler.getTask] and [ ][javax.tools.StandardFileManager.setLocation] for a single compilation, with sensible defaults and
 * a builder.
 */
@AutoValue
abstract class BlazeJavacArguments {
    /** The sources to compile.  */
    abstract fun sourceFiles(): com.google.common.collect.ImmutableList<Path?>?

    /** Javac options, not including location settings.  */
    abstract fun javacOptions(): com.google.common.collect.ImmutableList<String?>?

    /** Blaze-specific Javac options.  */
    abstract fun blazeJavacOptions(): com.google.common.collect.ImmutableList<String?>?

    /** The compilation classpath.  */
    abstract fun classPath(): com.google.common.collect.ImmutableList<Path?>?

    /** The compilation bootclasspath.  */
    abstract fun bootClassPath(): com.google.common.collect.ImmutableList<Path?>?

    abstract fun system(): Path?

    /** The compilation source path.  */
    abstract fun sourcePath(): com.google.common.collect.ImmutableList<Path?>?

    /** The classpath to load processors from.  */
    abstract fun processorPath(): com.google.common.collect.ImmutableList<Path?>?

    /** The compiler plugins.  */
    abstract fun plugins(): com.google.common.collect.ImmutableList<BlazeJavaCompilerPlugin?>?

    /** The class output directory (-d).  */
    abstract fun classOutput(): Path?

    /** The native header output directory (-h).  */
    abstract fun nativeHeaderOutput(): Path?

    /** The generated source output directory (-s).  */
    abstract fun sourceOutput(): Path?

    /** Stop compiling after the first diagnostic that could cause transitive classpath fallback.  */
    abstract fun failFast(): Boolean

    /** The Inputs' path and digest received from a WorkRequest  */
    abstract fun inputsAndDigest(): com.google.common.collect.ImmutableMap<String?, ByteString?>?

    abstract fun requestId(): OptionalInt?

    /**
     * The working directory for the compilation relative to which paths should be emitted in
     * diagnostics.
     */
    abstract fun workDir(): Path?

    /** [BlazeJavacArguments]Builder.  */
    @AutoValue.Builder
    interface Builder {
        fun classPath(classPath: com.google.common.collect.ImmutableList<Path?>?): Builder?

        fun classOutput(classOutput: Path?): Builder?

        fun nativeHeaderOutput(nativeHeaderOutput: Path?): Builder?

        fun bootClassPath(bootClassPath: com.google.common.collect.ImmutableList<Path?>?): Builder?

        fun system(system: Path?): Builder?

        fun javacOptions(javacOptions: com.google.common.collect.ImmutableList<String?>?): Builder?

        fun blazeJavacOptions(javacOptions: com.google.common.collect.ImmutableList<String?>?): Builder?

        fun sourcePath(sourcePath: com.google.common.collect.ImmutableList<Path?>?): Builder?

        fun sourceFiles(sourceFiles: com.google.common.collect.ImmutableList<Path?>?): Builder?

        fun sourceOutput(sourceOutput: Path?): Builder?

        fun processorPath(processorPath: com.google.common.collect.ImmutableList<Path?>?): Builder?

        fun plugins(plugins: com.google.common.collect.ImmutableList<BlazeJavaCompilerPlugin?>?): Builder?

        fun failFast(failFast: Boolean): Builder?

        fun inputsAndDigest(inputsAndDigest: com.google.common.collect.ImmutableMap<String?, ByteString?>?): Builder?

        fun requestId(requestId: OptionalInt?): Builder?

        fun workDir(workDir: Path?): Builder?

        fun build(): BlazeJavacArguments?
    }

    companion object {
        fun builder(): Builder {
            return Builder()
                .classPath(com.google.common.collect.ImmutableList.of<E?>())
                .bootClassPath(com.google.common.collect.ImmutableList.of<E?>())
                .javacOptions(com.google.common.collect.ImmutableList.of<E?>())
                .blazeJavacOptions(com.google.common.collect.ImmutableList.of<E?>())
                .sourceFiles(com.google.common.collect.ImmutableList.of<E?>())
                .sourcePath(com.google.common.collect.ImmutableList.of<E?>())
                .sourceOutput(null)
                .processorPath(com.google.common.collect.ImmutableList.of<E?>())
                .plugins(com.google.common.collect.ImmutableList.of<E?>())
                .failFast(false)
                .inputsAndDigest(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .requestId(OptionalInt.empty())
                .workDir(Path.of(""))
        }
    }
}
