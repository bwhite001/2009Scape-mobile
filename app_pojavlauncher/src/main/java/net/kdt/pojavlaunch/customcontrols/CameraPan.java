package net.kdt.pojavlaunch.customcontrols;

/** Pure decision logic for one-finger camera panning. No Android/AWT dependencies,
 *  so it is unit-testable. Callers translate the returned codes into real key events. */
public final class CameraPan {
    public static final int NONE = 0;
    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int UP = 3;
    public static final int DOWN = 4;

    private CameraPan() {}

    /** @return int[]{horizontal, vertical} where each is NONE / LEFT|RIGHT / UP|DOWN. */
    public static int[] directions(float dx, float dy, float thresholdX, float thresholdY, boolean invertY) {
        int horizontal = NONE;
        if (dx > thresholdX) horizontal = RIGHT;
        else if (dx < -thresholdX) horizontal = LEFT;

        int vertical = NONE;
        if (dy > thresholdY) vertical = invertY ? DOWN : UP;
        else if (dy < -thresholdY) vertical = invertY ? UP : DOWN;

        return new int[]{horizontal, vertical};
    }
}
