// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.vfs.PathFragment

@RunWith(JUnit4::class)
class PathPrettyPrinterTest {
    @get:org.junit.Test
    val prettyPath_pathUnderSymlinkTarget_returnsPathUnderConvenienceLink: Unit
        get() {
            val underTest: PathPrettyPrinter =
                PathPrettyPrinter( /* workspaceRelativeWorkingDirectory= */
                    PathFragment.EMPTY_FRAGMENT,  /* symlinkPrefix= */
                    "ignored",
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        PathFragment.create("not-blaze-out"),
                        PathFragment.create("/output/execroot/not-stuff"),
                        PathFragment.create("blaze-out"),
                        PathFragment.create("/output/execroot/stuff")
                    )
                )

            val path: PathFragment? = PathFragment.create("/output/execroot/stuff/really")
            assertThat(underTest.getPrettyPath(path)).isEqualTo(PathFragment.create("blaze-out/really"))
        }

    @get:org.junit.Test
    val prettyPath_workingDirectoryUnderWorkspace_returnsUpLevelReference: Unit
        get() {
            val underTest: PathPrettyPrinter =
                PathPrettyPrinter(
                    PathFragment.create("relative/working/directory"),  /* symlinkPrefix= */
                    "ignored",
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        PathFragment.create("not-blaze-out"),
                        PathFragment.create("/output/execroot/not-stuff"),
                        PathFragment.create("blaze-out"),
                        PathFragment.create("/output/execroot/stuff")
                    )
                )

            val path: PathFragment? = PathFragment.create("/output/execroot/stuff/really")
            assertThat(underTest.getPrettyPath(path))
                .isEqualTo(PathFragment.create("../../../blaze-out/really"))
        }

    @get:org.junit.Test
    val prettyPath_pathNotUnderSymlinkTarget_returnsOriginalPath: Unit
        get() {
            val underTest: PathPrettyPrinter =
                PathPrettyPrinter( /* workspaceRelativeWorkingDirectory= */
                    PathFragment.EMPTY_FRAGMENT,  /* symlinkPrefix= */
                    "ignored",
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        PathFragment.create("blaze-out"), PathFragment.create("/output/execroot/stuff")
                    )
                )

            val path: PathFragment? = PathFragment.create("/output/execroot/not-stuff/really")
            assertThat(underTest.getPrettyPath(path)).isEqualTo(path)
        }

    @get:org.junit.Test
    val prettyPath_noCreateSymlinksPrefix_returnsOriginalPath: Unit
        get() {
            val underTest: PathPrettyPrinter =
                PathPrettyPrinter( /* workspaceRelativeWorkingDirectory= */
                    PathFragment.EMPTY_FRAGMENT,  /* symlinkPrefix= */
                    "/",
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        PathFragment.create("blaze-out"), PathFragment.create("/output/execroot/stuff")
                    )
                )

            val path: PathFragment? = PathFragment.create("/output/execroot/stuff/really")
            assertThat(underTest.getPrettyPath(path)).isEqualTo(path)
        }
}
