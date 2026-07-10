package net.kdt.pojavlaunch.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class JSONUtilsTest {
    @Test public void singleKeySubstitution() {
        Map<String, String> map = new HashMap<>();
        map.put("name", "world");
        assertEquals("hello world", JSONUtils.insertSingleJSONValue("hello ${name}", map));
    }

    @Test public void multipleKeysSubstitutedInOneString() {
        Map<String, String> map = new HashMap<>();
        map.put("a", "1");
        map.put("b", "2");
        assertEquals("1-2", JSONUtils.insertSingleJSONValue("${a}-${b}", map));
    }

    @Test public void missingKeyLeftUntouched() {
        Map<String, String> map = new HashMap<>();
        map.put("name", "world");
        assertEquals("hello ${missingKey}", JSONUtils.insertSingleJSONValue("hello ${missingKey}", map));
    }

    @Test public void nullValueSubstitutedWithEmptyString() {
        Map<String, String> map = new HashMap<>();
        map.put("name", null);
        assertEquals("hello ", JSONUtils.insertSingleJSONValue("hello ${name}", map));
    }

    @Test public void noPlaceholdersReturnedUnchanged() {
        Map<String, String> map = new HashMap<>();
        map.put("name", "world");
        assertEquals("hello world, no placeholders here", JSONUtils.insertSingleJSONValue("hello world, no placeholders here", map));
    }

    @Test public void listAppliesSubstitutionAcrossAllElements() {
        Map<String, String> map = new HashMap<>();
        map.put("name", "world");
        map.put("num", "42");
        String[] input = {"hello ${name}", "count=${num}", "no placeholder"};
        String[] expected = {"hello world", "count=42", "no placeholder"};
        assertArrayEquals(expected, JSONUtils.insertJSONValueList(input, map));
    }
}
