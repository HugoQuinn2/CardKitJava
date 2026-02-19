package io.serialize;

import com.idear.devices.card.cardkit.core.io.serialize.JsonDynamicKey;
import com.idear.devices.card.cardkit.core.io.serialize.JsonSerializable;
import lombok.Data;
import org.junit.jupiter.api.Test;

public class SerializeTest {

    @Data
    public static class Profile
            implements JsonSerializable {
        private final String name;
    }

    @Data
    public static class UserTest <T>
            implements JsonSerializable {
        private final String name;
        private final long id;

        @JsonDynamicKey
        private final T data;
    }

    @Test
    public void toJson() {
        System.out.println(new UserTest<Profile>("hugo", 1, new Profile("GENERAL")).toJson());
    }

}
