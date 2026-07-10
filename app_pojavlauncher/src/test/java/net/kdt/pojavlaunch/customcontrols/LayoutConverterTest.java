package net.kdt.pojavlaunch.customcontrols;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Characterization tests for the pure ratio-math seam extracted from
 * {@link LayoutConverter}'s v1/v2 control-layout migration.
 *
 * These deliberately exercise ONLY {@code LayoutConverter.toDynamicRatioExpression}
 * (primitives in, String out). The full {@code convertV1Layout}/{@code convertV2Layout}
 * methods cannot be unit-tested in this repo's JVM harness because they touch
 * {@code Tools} (static {@code Looper}-dependent init) and {@code org.json.JSONObject}
 * accessors, both of which throw "not mocked" without Robolectric — see
 * .superpowers/sdd/task-019-report.md. This seam is the extractable, pure part.
 *
 * Values are captured-then-locked (characterization), not hand-derived: the
 * asserted strings are the actual current output of the production formula.
 */
public class LayoutConverterTest {

    @Test
    public void exactHalfWidthProducesHalfRatioExpression() {
        assertEquals("0.5 * ${screen_width}",
                LayoutConverter.toDynamicRatioExpression(960, 1920, "${screen_width}"));
    }

    @Test
    public void exactHalfHeightProducesHalfRatioExpression() {
        assertEquals("0.5 * ${screen_height}",
                LayoutConverter.toDynamicRatioExpression(540, 1080, "${screen_height}"));
    }

    @Test
    public void zeroCoordinateProducesZeroRatio() {
        assertEquals("0.0 * ${screen_width}",
                LayoutConverter.toDynamicRatioExpression(0, 1920, "${screen_width}"));
    }

    @Test
    public void nonTerminatingRatioKeepsFullDoubleToString() {
        assertEquals("0.3333333333333333 * ${screen_width}",
                LayoutConverter.toDynamicRatioExpression(640, 1920, "${screen_width}"));
    }

    @Test
    public void coordinateEqualToDimensionProducesOneRatio() {
        assertEquals("1.0 * ${screen_height}",
                LayoutConverter.toDynamicRatioExpression(1080, 1080, "${screen_height}"));
    }

    @Test
    public void coordinateGreaterThanDimensionProducesRatioAboveOne() {
        assertEquals("1.25 * ${screen_width}",
                LayoutConverter.toDynamicRatioExpression(2400, 1920, "${screen_width}"));
    }
}
