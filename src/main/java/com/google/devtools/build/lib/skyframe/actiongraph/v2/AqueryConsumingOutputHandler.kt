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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import java.util.concurrent.Callable

/** AqueryOutputHandler that receives and consumes tasks via a work queue.  */
interface AqueryConsumingOutputHandler : AqueryOutputHandler {
    fun startConsumer(): Callable<Void?>?

    /**
     * Stops the consumer thread.
     * 
     * @param discardRemainingTasks true in case an error occurred with the producer
     */
    @Throws(InterruptedException::class)
    fun stopConsumer(discardRemainingTasks: Boolean)
}
