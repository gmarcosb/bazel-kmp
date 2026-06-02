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
package com.google.devtools.build.lib.profiler

/** An object that can record time statistics about an object.  */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface StatRecorder {
    /** Add a new time statistic for the object `obj`.  */
    fun addStat(duration: Int, obj: Any?)

    /** True if it has not recorded any statistic  */
    fun isEmpty(): Boolean
}
