package net.kdt.pojavlaunch.customcontrols;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class CameraPanTest {
    @Test public void belowThresholdIsNone() {
        int[] d = CameraPan.directions(3f, 3f, 8f, 8f, false);
        assertEquals(CameraPan.NONE, d[0]);
        assertEquals(CameraPan.NONE, d[1]);
    }
    @Test public void horizontalMapsLeftRight() {
        assertEquals(CameraPan.RIGHT, CameraPan.directions(10f, 0f, 8f, 8f, false)[0]);
        assertEquals(CameraPan.LEFT,  CameraPan.directions(-10f, 0f, 8f, 8f, false)[0]);
    }
    @Test public void verticalMapsUpDown() {
        assertEquals(CameraPan.UP,   CameraPan.directions(0f, 10f, 8f, 8f, false)[1]);
        assertEquals(CameraPan.DOWN, CameraPan.directions(0f, -10f, 8f, 8f, false)[1]);
    }
    @Test public void invertYSwapsVertical() {
        assertEquals(CameraPan.DOWN, CameraPan.directions(0f, 10f, 8f, 8f, true)[1]);
        assertEquals(CameraPan.UP,   CameraPan.directions(0f, -10f, 8f, 8f, true)[1]);
    }
    @Test public void separateThresholdsPerAxis() {
        // dy=6 is below a vertical threshold of 12 but dx=6 is above a horizontal threshold of 4
        int[] d = CameraPan.directions(6f, 6f, 4f, 12f, false);
        assertEquals(CameraPan.RIGHT, d[0]);
        assertEquals(CameraPan.NONE, d[1]);
    }
}
