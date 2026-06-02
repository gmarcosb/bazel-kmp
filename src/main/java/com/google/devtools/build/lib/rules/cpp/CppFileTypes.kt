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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/** C++-related file type definitions.  */
object CppFileTypes {
    // .cu and .cl are CUDA and OpenCL source extensions, respectively. They are expected to only be
    // supported with clang. Bazel is not officially supporting these targets, and the extensions are
    // listed only as long as they work with the existing C++ actions.
    // FileType is extended to use case-sensitive comparison also on Windows
    @kotlin.jvm.JvmField
    val CPP_SOURCE: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            val extensions: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>(".cc", ".cpp", ".cxx", ".c++", ".C", ".cu", ".cl")

            override fun apply(path: String): Boolean {
                for (ext in extensions) {
                    if (path.endsWith(ext)) {
                        return true
                    }
                }
                return false
            }

            override fun getExtensions(): com.google.common.collect.ImmutableList<String?> {
                return extensions
            }
        }

    // FileType is extended to use case-sensitive comparison also on Windows
    @kotlin.jvm.JvmField
    val C_SOURCE: com.google.devtools.build.lib.util.FileType = object : com.google.devtools.build.lib.util.FileType() {
        val ext: String = ".c"

        override fun apply(path: String): Boolean {
            return path.endsWith(ext)
        }

        val extensions: com.google.common.collect.ImmutableList<String?>
            get() = com.google.common.collect.ImmutableList.of<String?>(ext)
    }

    val OBJC_SOURCE: com.google.devtools.build.lib.util.FileType = com.google.devtools.build.lib.util.FileType.of(".m")
    val OBJCPP_SOURCE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".mm")
    val CLIF_INPUT_PROTO: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".ipb")
    val CLIF_OUTPUT_PROTO: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".opb")
    val BC_SOURCE: com.google.devtools.build.lib.util.FileType = com.google.devtools.build.lib.util.FileType.of(".bc")

    @kotlin.jvm.JvmField
    val ALL_C_CLASS_SOURCE: FileTypeSet? = FileTypeSet.of(
        CPP_SOURCE,
        C_SOURCE,
        OBJCPP_SOURCE,
        OBJC_SOURCE,
        CLIF_INPUT_PROTO
    )

    // Filetypes that generate LLVM bitcode when -flto is specified.
    val LTO_SOURCE: FileTypeSet? = FileTypeSet.of(CPP_SOURCE, C_SOURCE)

    @kotlin.jvm.JvmField
    val CPP_HEADER: com.google.devtools.build.lib.util.FileType = com.google.devtools.build.lib.util.FileType.of(
        ".h", ".hh", ".hpp", ".ipp", ".hxx", ".h++", ".inc", ".inl", ".tlh", ".tli", ".H",
        ".tcc"
    )
    val PCH: com.google.devtools.build.lib.util.FileType = com.google.devtools.build.lib.util.FileType.of(".pch")
    val OBJC_HEADER: FileTypeSet? = FileTypeSet.of(CPP_HEADER, PCH)

    val CPP_TEXTUAL_INCLUDE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".inc")

    val PIC_PREPROCESSED_C: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".pic.i")
    val PREPROCESSED_C: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            val ext: String = ".i"

            override fun apply(path: String?): Boolean {
                return com.google.devtools.build.lib.util.FileType.hasExtension(
                    path,
                    ext
                ) && !PIC_PREPROCESSED_C.matches(path)
            }

            val extensions: com.google.common.collect.ImmutableList<String?>
                get() = com.google.common.collect.ImmutableList.of<String?>(ext)
        }
    val PIC_PREPROCESSED_CPP: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".pic.ii")
    val PREPROCESSED_CPP: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            val ext: String = ".ii"

            override fun apply(path: String?): Boolean {
                return com.google.devtools.build.lib.util.FileType.hasExtension(
                    path,
                    ext
                ) && !PIC_PREPROCESSED_CPP.matches(path)
            }

            val extensions: com.google.common.collect.ImmutableList<String?>
                get() = com.google.common.collect.ImmutableList.of<String?>(ext)
        }

    // FileType is extended to use case-sensitive comparison also on Windows
    @kotlin.jvm.JvmField
    val ASSEMBLER_WITH_C_PREPROCESSOR: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            val ext: String = ".S"

            override fun apply(path: String): Boolean {
                return path.endsWith(ext)
            }

            val extensions: com.google.common.collect.ImmutableList<String?>
                get() = com.google.common.collect.ImmutableList.of<String?>(ext)
        }

    // FileType is extended to use case-sensitive comparison also on Windows
    @kotlin.jvm.JvmField
    val PIC_ASSEMBLER: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            val ext: String = ".pic.s"

            override fun apply(path: String): Boolean {
                return com.google.devtools.build.lib.util.FileType.hasExtension(path, ext) && path.endsWith(".s")
            }

            val extensions: com.google.common.collect.ImmutableList<String?>
                get() = com.google.common.collect.ImmutableList.of<String?>(ext)
        }

    // FileType is extended to use case-sensitive comparison also on Windows
    @kotlin.jvm.JvmField
    val ASSEMBLER: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            val ext: String = ".s"

            override fun apply(path: String): Boolean {
                return (path.endsWith(ext) && !PIC_ASSEMBLER.matches(path)) || com.google.devtools.build.lib.util.FileType.hasExtension(
                    path,
                    ".asm"
                )
            }

            val extensions: com.google.common.collect.ImmutableList<String?>
                get() = com.google.common.collect.ImmutableList.of<String?>(ext, ".asm")
        }

    @kotlin.jvm.JvmField
    val PIC_ARCHIVE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".pic.a")
    @kotlin.jvm.JvmField
    val ARCHIVE: com.google.devtools.build.lib.util.FileType = object : com.google.devtools.build.lib.util.FileType() {
        val extensions: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(".a", ".lib")

        override fun apply(path: String?): Boolean {
            if (PIC_ARCHIVE.matches(path)
                || ALWAYS_LINK_LIBRARY.matches(path)
                || com.google.devtools.build.lib.util.FileType.hasExtension(path, ".if.lib")
            ) {
                return false
            }
            return com.google.devtools.build.lib.util.FileType.hasAnyExtension(path, extensions)
        }

        override fun getExtensions(): com.google.common.collect.ImmutableList<String?> {
            return extensions
        }
    }

    val ALWAYS_LINK_PIC_LIBRARY: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".pic.lo")
    @kotlin.jvm.JvmField
    val ALWAYS_LINK_LIBRARY: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            val ext: String = ".lo"

            override fun apply(path: String?): Boolean {
                return (com.google.devtools.build.lib.util.FileType.hasExtension(
                    path,
                    ext
                ) && !ALWAYS_LINK_PIC_LIBRARY.matches(path))
                        || com.google.devtools.build.lib.util.FileType.hasExtension(path, ".lo.lib")
            }

            val extensions: com.google.common.collect.ImmutableList<String?>
                get() = com.google.common.collect.ImmutableList.of<String?>(ext, ".lo.lib")
        }

    @kotlin.jvm.JvmField
    val PIC_OBJECT_FILE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".pic.o")
    @kotlin.jvm.JvmField
    val OBJECT_FILE: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            val ext: String = ".o"

            override fun apply(path: String?): Boolean {
                return (com.google.devtools.build.lib.util.FileType.hasExtension(path, ext) && !PIC_OBJECT_FILE.matches(
                    path
                ))
                        || com.google.devtools.build.lib.util.FileType.hasExtension(path, ".obj")
            }

            val extensions: com.google.common.collect.ImmutableList<String?>
                get() = com.google.common.collect.ImmutableList.of<String?>(ext, ".obj")
        }

    // Static library artifact created by rustc, can be used as a regular archive.
    @kotlin.jvm.JvmField
    val RUST_RLIB: com.google.devtools.build.lib.util.FileType = com.google.devtools.build.lib.util.FileType.of(".rlib")

    // Minimized bitcode file emitted by the ThinLTO compile step and used just for LTO indexing.
    val LTO_INDEXING_OBJECT_FILE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".indexing.o")

    // Imports file emitted by the ThinLTO indexing step and used for LTO backend action.
    val LTO_IMPORTS_FILE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".imports")

    // Indexing analysis result file emitted by the ThinLTO indexing step and used for LTO backend
    // action.
    val LTO_INDEXING_ANALYSIS_FILE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".thinlto.bc")

    // TODO(bazel-team): File types should not be read from this hard-coded list but should come from
    // the toolchain instead. See https://github.com/bazelbuild/bazel/issues/17117
    @kotlin.jvm.JvmField
    val SHARED_LIBRARY: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".so", ".dylib", ".dll", ".pyd", ".wasm", ".tgt", ".vpi")

    // Unix shared libraries can be passed to linker, but not .dll on Windows
    val UNIX_SHARED_LIBRARY: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".so", ".dylib")
    val INTERFACE_SHARED_LIBRARY: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".ifso", ".tbd", ".lib", ".dll.a")
    val LINKER_SCRIPT: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".ld", ".lds", ".ldscript")

    // Windows DEF file: https://msdn.microsoft.com/en-us/library/28d6s79h.aspx
    val WINDOWS_DEF_FILE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".def")

    // Matches shared libraries with version names in the extension, i.e.
    // libmylib.so.2 or libmylib.so.2.10 or libmylib.so.1a_b35.
    private val VERSIONED_SHARED_LIBRARY_PATTERN: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("^.+\\.((so)|(dylib))(\\.\\d\\w*)+$")
    @kotlin.jvm.JvmField
    val VERSIONED_SHARED_LIBRARY: com.google.devtools.build.lib.util.FileType =
        object : com.google.devtools.build.lib.util.FileType() {
            override fun apply(path: String): Boolean {
                // Because regex matching can be slow, we first do a quick check for ".so." and ".dylib."
                // substring before risking the full-on regex match. This should eliminate the performance
                // hit on practically every non-qualifying file type.
                if (!path.contains(".so.") && !path.contains(".dylib.")) {
                    return false
                }
                return VERSIONED_SHARED_LIBRARY_PATTERN.matcher(path).matches()
            }
        }

    val COVERAGE_NOTES: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".gcno")
    val GCC_AUTO_PROFILE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".afdo")
    val XBINARY_PROFILE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".xfdo")
    val LLVM_PROFILE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".profdata")
    val LLVM_PROFILE_RAW: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".profraw")
    val LLVM_PROFILE_ZIP: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".zip")

    @kotlin.jvm.JvmField
    val CPP_MODULE_MAP: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".cppmap")
    @kotlin.jvm.JvmField
    val CPP_MODULE: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of(".pcm", ".gcm", ".ifc")
    val OBJC_MODULE_MAP: com.google.devtools.build.lib.util.FileType =
        com.google.devtools.build.lib.util.FileType.of("module.modulemap")

    /** Predicate that matches all artifacts that can be used in an objc Clang module map.  */
    val MODULE_MAP_HEADER: com.google.common.base.Predicate<Artifact?> =
        com.google.common.base.Predicate { artifact: Artifact? ->
            if (artifact.isTreeArtifact()) {
                // Tree artifact is basically a directory, which does not have any information about
                // the contained files and their extensions. Here we assume the passed in tree artifact
                // contains proper header files with .h extension.
                return@Predicate true
            } else {
                // The current clang (clang-600.0.57) on Darwin doesn't support 'textual', so we can't
                // have '.inc' files in the module map (since they're implictly textual).
                // TODO(bazel-team): Use HEADERS file type once clang-700 is the base clang we support.
                return@Predicate com.google.devtools.build.lib.util.FileType.hasExtension(artifact.getFilename(), ".h")
            }
        }

    fun headerDiscoveryRequired(source: Artifact): Boolean {
        val fileName: String? = source.getFilename()
        return !ASSEMBLER.matches(fileName) && !PIC_ASSEMBLER.matches(fileName) && !CPP_MODULE.matches(fileName)
    }
}
