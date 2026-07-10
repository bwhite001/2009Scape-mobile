package net.kdt.pojavlaunch.progresskeeper;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ProgressKeeperTest {
    @Test public void selfRemovingListenerDuringUpdateDoesNotThrowCME() {
        final int[] observedCount = {-1};
        TaskCountListener selfRemoving = new TaskCountListener() {
            @Override
            public void onUpdateTaskCount(int taskCount) {
                observedCount[0] = taskCount;
                ProgressKeeper.removeTaskCountListener(this);
            }
        };
        ProgressKeeper.addTaskCountListener(selfRemoving, false);
        // Starting a task drives updateTaskCount() -> iterates sTaskCountListeners,
        // during which selfRemoving removes itself. Must not throw CME.
        ProgressKeeper.submitProgress("test-record-010", 0, 0);
        assertEquals(1, observedCount[0]);
        // Clean up: end the task so state doesn't leak into other tests in the same JVM.
        ProgressKeeper.submitProgress("test-record-010", -1, -1);
    }
}
