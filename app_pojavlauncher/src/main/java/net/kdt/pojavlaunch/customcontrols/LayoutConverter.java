package net.kdt.pojavlaunch.customcontrols;

import com.google.gson.JsonSyntaxException;

import net.kdt.pojavlaunch.utils.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lwjgl.glfw.CallbackBridge;

import java.io.IOException;
import java.util.ArrayList;

public class LayoutConverter {
    public static CustomControls loadAndConvertIfNecessary(String jsonPath) throws IOException, JsonSyntaxException {

        String jsonLayoutData = Tools.read(jsonPath);
        try {
            JSONObject layoutJobj = new JSONObject(jsonLayoutData);

            if(!layoutJobj.has("version")) { //v1 layout
                CustomControls layout = LayoutConverter.convertV1Layout(layoutJobj);
                layout.save(jsonPath);
                return layout;
            }else if (layoutJobj.getInt("version") == 2) {
                CustomControls layout = LayoutConverter.convertV2Layout(layoutJobj);
                layout.save(jsonPath);
                return layout;
            }else if (layoutJobj.getInt("version") == 3 || layoutJobj.getInt("version") == 4) {
                return Tools.GLOBAL_GSON.fromJson(jsonLayoutData, CustomControls.class);
            }else{
                return null;
            }
        }catch (JSONException e) {
            throw new JsonSyntaxException("Failed to load",e);
        }
    }
    /**
     * Pure ratio-to-dynamic-expression conversion extracted from the v1/v2
     * migration ratio math so it can be characterized in a plain JVM unit test
     * without loading Tools / org.json / ControlData / any Android class.
     *
     * Behaviour-identical to the previous inline
     * {@code double ratio = coordinate/screenDimension; field = ratio + " * ${...}"}
     * code: {@code ratio + " * " + screenVariable} yields the exact same String
     * as the old {@code ratio + " * ${screen_width}"} concatenation.
     */
    static String toDynamicRatioExpression(double coordinate, int screenDimension, String screenVariable) {
        double ratio = coordinate / screenDimension;
        return ratio + " * " + screenVariable;
    }
    public static CustomControls convertV2Layout(JSONObject oldLayoutJson) throws JSONException {
        return convertV2Layout(oldLayoutJson, CallbackBridge.physicalWidth, CallbackBridge.physicalHeight);
    }
    public static CustomControls convertV2Layout(JSONObject oldLayoutJson, int width, int height) throws JSONException {
        CustomControls layout = Tools.GLOBAL_GSON.fromJson(oldLayoutJson.toString(), CustomControls.class);
        JSONArray layoutMainArray = oldLayoutJson.getJSONArray("mControlDataList");
        layout.mControlDataList = new ArrayList<>(layoutMainArray.length());
        for(int i = 0; i < layoutMainArray.length(); i++) {
            JSONObject button = layoutMainArray.getJSONObject(i);
            ControlData n_button = Tools.GLOBAL_GSON.fromJson(button.toString(), ControlData.class);
            if(!Tools.isValidString(n_button.dynamicX) && button.has("x")) {
                n_button.dynamicX = toDynamicRatioExpression(button.getDouble("x"), width, "${screen_width}");
            }
            if(!Tools.isValidString(n_button.dynamicY) && button.has("y")) {
                n_button.dynamicY = toDynamicRatioExpression(button.getDouble("y"), height, "${screen_height}");
            }
            layout.mControlDataList.add(n_button);
        }
        JSONArray layoutDrawerArray = oldLayoutJson.getJSONArray("mDrawerDataList");
        layout.mDrawerDataList = new ArrayList<>();
        for(int i = 0; i < layoutDrawerArray.length(); i++) {
            JSONObject button = layoutDrawerArray.getJSONObject(i);
            JSONObject buttonProperties = button.getJSONObject("properties");
            ControlDrawerData n_button = Tools.GLOBAL_GSON.fromJson(button.toString(), ControlDrawerData.class);
            if(!Tools.isValidString(n_button.properties.dynamicX) && buttonProperties.has("x")) {
                n_button.properties.dynamicX = toDynamicRatioExpression(buttonProperties.getDouble("x"), width, "${screen_width}");
            }
            if(!Tools.isValidString(n_button.properties.dynamicY) && buttonProperties.has("y")) {
                n_button.properties.dynamicY = toDynamicRatioExpression(buttonProperties.getDouble("y"), height, "${screen_height}");
            }
            layout.mDrawerDataList.add(n_button);
        }
        layout.version = 3;
        return layout;
    }
    public static CustomControls convertV1Layout(JSONObject oldLayoutJson) throws JSONException {
        return convertV1Layout(oldLayoutJson, CallbackBridge.physicalWidth, CallbackBridge.physicalHeight);
    }
    public static CustomControls convertV1Layout(JSONObject oldLayoutJson, int width, int height) throws JSONException {
        CustomControls empty = new CustomControls();
        JSONArray layoutMainArray = oldLayoutJson.getJSONArray("mControlDataList");
        for(int i = 0; i < layoutMainArray.length(); i++) {
            JSONObject button = layoutMainArray.getJSONObject(i);
            ControlData n_button = new ControlData();
            int[] keycodes = new int[] {LwjglGlfwKeycode.GLFW_KEY_UNKNOWN,
                    LwjglGlfwKeycode.GLFW_KEY_UNKNOWN,
                    LwjglGlfwKeycode.GLFW_KEY_UNKNOWN,
                    LwjglGlfwKeycode.GLFW_KEY_UNKNOWN};
            n_button.isDynamicBtn = button.getBoolean("isDynamicBtn");
            n_button.dynamicX = button.getString("dynamicX");
            n_button.dynamicY = button.getString("dynamicY");
            if(!Tools.isValidString(n_button.dynamicX) && button.has("x")) {
                n_button.dynamicX = toDynamicRatioExpression(button.getDouble("x"), width, "${screen_width}");
            }
            if(!Tools.isValidString(n_button.dynamicY) && button.has("y")) {
                n_button.dynamicY = toDynamicRatioExpression(button.getDouble("y"), height, "${screen_height}");
            }
            n_button.name = button.getString("name");
            n_button.opacity = ((float)((button.getInt("transparency")-100)*-1))/100f;
            n_button.passThruEnabled = button.getBoolean("passThruEnabled");
            n_button.isToggle = button.getBoolean("isToggle");
            n_button.setHeight(button.getInt("height"));
            n_button.setWidth(button.getInt("width"));
            n_button.bgColor = 0x4d000000;
            n_button.strokeWidth = 0;
            if(button.getBoolean("isRound")) { n_button.cornerRadius =  35f; }
            int next_idx = 0;
            if(button.getBoolean("holdShift")) { keycodes[next_idx] = LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT; next_idx++; }
            if(button.getBoolean("holdCtrl")) { keycodes[next_idx] = LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL; next_idx++; }
            if(button.getBoolean("holdAlt")) { keycodes[next_idx] = LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT; next_idx++; }
            keycodes[next_idx] = button.getInt("keycode");
            n_button.keycodes = keycodes;
            empty.mControlDataList.add(n_button);
        }
        empty.scaledAt = (float)oldLayoutJson.getDouble("scaledAt");
        empty.version = 3;
        return empty;
    }
}
