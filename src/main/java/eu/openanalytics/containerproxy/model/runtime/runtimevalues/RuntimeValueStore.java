/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2021 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.containerproxy.model.runtime.runtimevalues;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import eu.openanalytics.containerproxy.model.runtime.Container;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class RuntimeValueStore {

    @JsonIgnore
    protected abstract Map<RuntimeValueKey<?>, RuntimeValue> getRuntimeValues();

    @JsonProperty("runtimeValues")
    public Map<String, String> getRuntimeValuesJson() {
        // only output key<->value in JSON
        Map<String, String> result = new HashMap<>();
        for (RuntimeValue value : getRuntimeValues().values()) {
            if (value.getKey().getIncludeInApi()) {
                result.put(value.getKey().getKeyAsEnvVar(), value.getValue());
            }
        }
        return result;
    }

//    TODO
//    @JsonProperty("runtimeValues")
//    public void setRuntimeValuesJson(Map<String, String> runtimeValues) {
//        for (Map.Entry<String, String> runtimeValue : runtimeValues.entrySet()) {
//            RuntimeValueKey<?> key = RuntimeValueKeyRegistry.getRuntimeValue(runtimeValue.getKey());
//            RuntimeValue value = new RuntimeValue(key, runtimeValue.getValue());
//            this.runtimeValues.put(key, value);
//        }
//    }

    /**
     * Used in SpEL of application.yml
     */
    public String getRuntimeValue(String keyAsEnvVar) {
        Objects.requireNonNull(keyAsEnvVar, "key may not be null");
        return getRuntimeValue(RuntimeValueKeyRegistry.getRuntimeValue(keyAsEnvVar));
    }

    public Object getRuntimeObject(String keyAsEnvVar) {
        Objects.requireNonNull(keyAsEnvVar, "key may not be null");
        return getRuntimeObject(RuntimeValueKeyRegistry.getRuntimeValue(keyAsEnvVar));
    }

    public <T> T getRuntimeObject(RuntimeValueKey<T> key) {
        Objects.requireNonNull(key, "key may not be null");
        RuntimeValue runtimeValue = getRuntimeValues().get(key);
        Objects.requireNonNull(runtimeValue, "did not found a value for key " + key.getKeyAsEnvVar());
        return runtimeValue.getObject();
    }

    public <T> T getRuntimeObjectOrNull(RuntimeValueKey<T> key) {
        Objects.requireNonNull(key, "key may not be null");
        RuntimeValue runtimeValue = getRuntimeValues().get(key);
        if (runtimeValue == null) {
            return null;
        }
        return runtimeValue.getObject();
    }

    public <T> String getRuntimeValue(RuntimeValueKey<T> key) {
        Objects.requireNonNull(key, "key may not be null");
        RuntimeValue runtimeValue = getRuntimeValues().get(key);
        Objects.requireNonNull(runtimeValue, "did not found a value for key " + key.getKeyAsEnvVar());
        return runtimeValue.getValue();
    }

}
