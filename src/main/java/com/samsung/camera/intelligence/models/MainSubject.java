package com.samsung.camera.intelligence.models;

import java.util.HashMap;
import java.util.Map;

/**
 * Main subject identification categories.
 * Matches the Python MainSubject enum in models/enums.py.
 */
public enum MainSubject {
    HUMAN_SINGLE("human_single"),
    HUMAN_GROUP("human_group"),
    HUMAN_FACE("human_face"),
    HUMAN_FULL_BODY("human_full_body"),
    ANIMAL_PET("animal_pet"),
    ANIMAL_WILDLIFE("animal_wildlife"),
    ANIMAL_BIRD("animal_bird"),
    FOOD_DISH("food_dish"),
    FOOD_INGREDIENT("food_ingredient"),
    FOOD_DRINK("food_drink"),
    LANDSCAPE_NATURE("landscape_nature"),
    LANDSCAPE_URBAN("landscape_urban"),
    ARCHITECTURE_EXTERIOR("architecture_exterior"),
    ARCHITECTURE_INTERIOR("architecture_interior"),
    OBJECT_PRODUCT("object_product"),
    OBJECT_VEHICLE("object_vehicle"),
    OBJECT_DOCUMENT("object_document"),
    PLANT_FLOWER("plant_flower"),
    PLANT_TREE("plant_tree"),
    SKY_DAY("sky_day"),
    SKY_NIGHT("sky_night"),
    WATER_BODY("water_body"),
    NONE("none");

    private final String value;
    private static final Map<String, MainSubject> VALUE_MAP = new HashMap<>();

    static {
        for (MainSubject ms : values()) {
            VALUE_MAP.put(ms.value, ms);
        }

        // Compatibility aliases used by guidance FrameAnalyzer subject labels
        VALUE_MAP.put("object", OBJECT_PRODUCT);
        VALUE_MAP.put("building", ARCHITECTURE_EXTERIOR);
        VALUE_MAP.put("landscape_subject", LANDSCAPE_NATURE);
        VALUE_MAP.put("vehicle_subject", OBJECT_VEHICLE);
        VALUE_MAP.put("text_document", OBJECT_DOCUMENT);
        VALUE_MAP.put("plant", PLANT_FLOWER);
    }

    MainSubject(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MainSubject fromValue(String value) {
        MainSubject result = VALUE_MAP.get(value);
        return result != null ? result : NONE;
    }
}
