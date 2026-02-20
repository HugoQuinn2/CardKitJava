package com.idear.devices.card.cardkit.core.io.serialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;

public interface JsonSerializable {

    ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    default String toJson() {
        try {
            String className = this.getClass().getSimpleName();
            String rootKey = Character.toLowerCase(className.charAt(0)) + className.substring(1);

            ObjectNode node = MAPPER.valueToTree(this);

            for (Field field : this.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(JsonDynamicKey.class)) {
                    field.setAccessible(true);
                    Object dynamicValue = field.get(this);

                    if (dynamicValue != null) {
                        String dynamicKey = dynamicValue.getClass().getSimpleName();
                        dynamicKey = Character.toLowerCase(dynamicKey.charAt(0)) + dynamicKey.substring(1);

                        ObjectNode dataWrapper = MAPPER.createObjectNode();
                        dataWrapper.set(dynamicKey, MAPPER.valueToTree(dynamicValue));

                        node.set(field.getName(), dataWrapper);
                    }
                }
            }

            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.set(rootKey, node);

            return MAPPER.writeValueAsString(wrapper);
        } catch (Exception e) {
            throw new RuntimeException("error to build JSON: " + e.getMessage(), e);
        }
    }

}